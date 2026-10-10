package io.jenkins.plugins.batchcontrol.store;

import com.thoughtworks.xstream.core.util.HierarchicalStreams;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.io.WriterWrapper;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.ParameterValue;
import hudson.util.XStream2;
import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.ActivationState;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.model.RunRequestValues;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * File-based {@link Store} rooted at {@code $JENKINS_HOME/batch-control/}.
 *
 * <ul>
 *   <li>Entities with state (requests, grants, incidents) are XStream XML files, rewritten
 *       atomically (temp file, then {@code ATOMIC_MOVE}).</li>
 *   <li>Append-only records (runs, changes) are monthly JSONL files, written with
 *       append + flush; Instants are stored as epoch milliseconds.</li>
 *   <li>Writes are serialized <em>per target file</em> (a fixed set of lock stripes): an
 *       append to this month's bucket never waits for anything but another single write that
 *       happens to share its stripe, and no operation holds a lock across more than one file
 *       write or deletion. In particular retention, which deletes old months and closed
 *       entities file by file, cannot stall the queue gate or build completion.</li>
 *   <li>Page loads read JSONL buckets newest first and stop at a record cap; the month
 *       summary counters and the entity index are derived in-memory caches, never files.</li>
 *   <li>File names derived from identifiers are validated through {@link PathCodec}; month and
 *       id names use ASCII digits whatever the default locale.</li>
 * </ul>
 */
@Restricted(NoExternalUse.class)
public final class FileStore implements Store {

    private static final Logger LOGGER = Logger.getLogger(FileStore.class.getName());

    private static final FileStore INSTANCE = new FileStore();

    private static final Comparator<Instant> INSTANT_NULLS_FIRST = Comparator.nullsFirst(Comparator.naturalOrder());
    /*
     * Requests are listed oldest first by their stored creation time, the id only breaking ties:
     * ids are UUIDs (D-68) and carry no order.
     */
    private static final Comparator<RunRequest> OLDEST_FIRST_RUNREQUEST =
            Comparator.comparing(RunRequest::getCreatedAt, INSTANT_NULLS_FIRST)
                    .thenComparing(RunRequest::getId);
    private static final Comparator<GrantRequest> OLDEST_FIRST_GRANTREQUEST =
            Comparator.comparing(GrantRequest::getCreatedAt, INSTANT_NULLS_FIRST)
                    .thenComparing(GrantRequest::getId);
    private static final Comparator<ActivationRequest> OLDEST_FIRST_ACTIVATIONREQUEST =
            Comparator.comparing(ActivationRequest::getCreatedAt, INSTANT_NULLS_FIRST)
                    .thenComparing(ActivationRequest::getId);

    /** Number of write-lock stripes; a power of two. */
    private static final int LOCK_STRIPES = 64;

    /** Bound on the month-summary cache entries (one per month file ever summarised). */
    private static final int MAX_STATS_ENTRIES = 256;

    /**
     * Bytes of out-of-period lines one page query may skip before it reports truncation
     * (security-10 S-03): about a month at the SPEC section 6 volume.
     */
    private static final long MAX_SKIPPED_BYTES = 128L * 1024 * 1024;

    /** Tolerance for appends that land slightly out of time order (concurrent writers). */
    private static final long APPEND_ORDER_SLACK_MILLIS = 60_000L;

    /** Chunk size of the incremental month-summary reader. */
    private static final int STATS_CHUNK = 64 * 1024;

    private final ReentrantLock[] writeLocks = new ReentrantLock[LOCK_STRIPES];
    private final XStream2 xstream = new XStream2();

    /** Guards the lazy (re)build of {@link #index}. */
    private final Object indexMonitor = new Object();
    private volatile EntityIndex index;
    private volatile WeakReference<Jenkins> indexFor = new WeakReference<>(null);

    /** Month file to its incremental summary; guarded by itself. */
    private final Map<Path, StatsEntry> statsCache = new HashMap<>();

    /** How often an open request that stays unreadable is warned about again ({@link #skipUnreadableOpen}). */
    private static final Duration UNREADABLE_WARNING_INTERVAL = Duration.ofHours(1);

    /** Open requests left out of a listing as unreadable ("kind id") to when that was last warned about. */
    private final Map<String, Instant> unreadableOpenWarnedAt = new ConcurrentHashMap<>();

    private FileStore() {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            writeLocks[i] = new ReentrantLock();
        }
    }

    /**
     * The file store instance. Callers go through {@link Store#get()} (D-44); only that method
     * and the tests of this class name {@code FileStore} directly.
     */
    public static FileStore get() {
        return INSTANCE;
    }

    /** The write lock of one target file (per-file, never store-wide). */
    private ReentrantLock lockFor(Path file) {
        return writeLocks[file.toAbsolutePath().normalize().hashCode() & (LOCK_STRIPES - 1)];
    }

    /** Resolved on every call: the Jenkins home changes between test sessions in one JVM. */
    private Path root() {
        return Jenkins.get().getRootDir().toPath().resolve("batch-control");
    }

    private Path runRequestDir() {
        return root().resolve("requests").resolve("run");
    }

    private Path grantRequestDir() {
        return root().resolve("requests").resolve("grant");
    }

    private Path grantDir() {
        return root().resolve("grants");
    }

    private Path activationDir() {
        return root().resolve("activations");
    }

    private Path activationRequestDir() {
        return root().resolve("activation-requests");
    }

    /** The upgrade seeding marker; a dot file, so the {@code *.xml} listing never sees it. */
    private static final String ACTIVATION_SCHEMA_MARKER = ".schema";

    private Path snapshotDir() {
        return root().resolve("snapshots");
    }

    private Path runsDir() {
        return root().resolve("runs");
    }

    private Path changesDir() {
        return root().resolve("changes");
    }

    private Path diffDir() {
        return changesDir().resolve("diff");
    }

    private Path incidentDir() {
        return root().resolve("incidents");
    }

    private Path incidentIndexDir() {
        return incidentDir().resolve("index");
    }

    /** {@code YYYY-MM.jsonl} in ASCII digits whatever the default locale. */
    static String monthFileName(YearMonth month) {
        return String.format(Locale.ROOT, "%04d-%02d.jsonl", month.getYear(), month.getMonthValue());
    }

    /** Month bucketing follows the {@link BatchClock} zone, like every other time judgment. */
    private static YearMonth monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(BatchClock.clock().getZone()));
    }

    // ---------------------------------------------------------------- run requests

    /** Suffix of a run request's typed values file, {@code <id>.values.xml} (D-74). */
    private static final String VALUES_SUFFIX = ".values.xml";

    /** The id suffix that would name a values file instead of a request file. */
    private static final String VALUES_ID_SUFFIX = ".values";

    /**
     * The request file of {@code id}, or {@code null} when {@code id} is not a store identifier
     * (S-39-01, {@link PathCodec#isId}): such an id names no request. The identifier shape already
     * excludes {@code .}; the values suffix is refused in any letter case as well (defence in depth,
     * case-insensitive file systems), so no id can name the values file.
     */
    @CheckForNull
    private Path runRequestFile(String id) {
        Objects.requireNonNull(id, "id");
        if (!PathCodec.isId(id) || endsWithIgnoreCase(id, VALUES_ID_SUFFIX)) {
            return null;
        }
        return PathCodec.resolveId(runRequestDir(), id, ".xml");
    }

    /** The values file of {@code id}, or {@code null} when {@code id} is not a store identifier (S-39-01). */
    @CheckForNull
    private Path runRequestValuesFile(String id) {
        Objects.requireNonNull(id, "id");
        return runRequestFile(id) == null ? null : PathCodec.resolveId(runRequestDir(), id, VALUES_SUFFIX);
    }

    private static boolean endsWithIgnoreCase(String text, String suffix) {
        return text.length() >= suffix.length()
                && text.regionMatches(true, text.length() - suffix.length(), suffix, 0, suffix.length());
    }

    @Override
    public void saveRunRequest(RunRequest request) {
        Objects.requireNonNull(request, "request");
        if (runRequestFile(request.getId()) == null) {
            throw new IllegalArgumentException("Invalid run request id: " + request.getId());
        }
        saveXmlEntity(runRequestDir(), request.getId(), request, "run request");
        index().put(request);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The request file is written first: should the process stop between the two writes, what
     * remains is a request whose values are missing, which cannot be approved (fail closed), and
     * never a values file without its request.
     */
    @Override
    public void saveNewRunRequest(RunRequest request, List<ParameterValue> values) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(values, "values");
        String id = request.getId();
        Path requestFile = runRequestFile(id);
        if (requestFile == null) {
            throw new IllegalArgumentException("Invalid run request id: " + id);
        }
        saveXmlEntity(runRequestDir(), id, request, "run request");
        if (!values.isEmpty()) {
            try {
                saveXmlFile(runRequestDir(), id + VALUES_SUFFIX, id, new RunRequestValues(id, values),
                        "parameter values of run request " + id);
            } catch (StoreWriteException e) {
                try {
                    deleteFile(requestFile, "run request " + id);
                } catch (UncheckedIOException deletion) {
                    e.addSuppressed(deletion);
                }
                throw e;
            }
        }
        index().put(request);
    }

    @Override
    public RunRequest loadRunRequest(String id) {
        Path file = runRequestFile(id);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            RunRequest request = readRunRequest(file);
            // The file found must be this request's own: on a case-insensitive file system another
            // spelling of the id reaches the same file, and that spelling names no request.
            return request != null && id.equals(request.getId()) ? request : null;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load run request " + id, e);
        }
    }

    @Override
    @CheckForNull
    public List<ParameterValue> loadRunRequestValues(String id) {
        Path file = runRequestValuesFile(id);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        RunRequestValues read;
        try {
            read = readXml(file, RunRequestValues.class, "parameter values file");
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load the parameter values of run request " + id, e);
        }
        if (read == null || !id.equals(read.requestId())) {
            throw new UncheckedIOException(new IOException("The parameter values file of run request " + id
                    + " does not hold the values of that request"));
        }
        return read.values();
    }

    @Override
    public void deleteRunRequestValues(String id) {
        Path file = runRequestValuesFile(id);
        if (file != null) {
            deleteFile(file, "parameter values of run request " + id);
        }
    }

    @Override
    public List<RunRequest> listRunRequests() {
        List<RunRequest> all = new ArrayList<>();
        for (Path file : listXmlFiles(runRequestDir(), "run request")) {
            Path name = file.getFileName();
            if (name == null || endsWithIgnoreCase(name.toString(), VALUES_SUFFIX)) {
                continue; // typed values, read only through loadRunRequestValues (D-74)
            }
            try {
                RunRequest request = readRunRequest(file);
                if (request != null) {
                    all.add(request);
                }
            } catch (NoSuchFileException e) {
                // Deleted between listing and reading; skip.
            } catch (IOException | RuntimeException e) {
                // As listXmlEntities: one file that cannot be read must not break every listing,
                // badge and the request index.
                warnSkipped("run request", file, e);
            }
        }
        all.sort(OLDEST_FIRST_RUNREQUEST);
        return all;
    }

    /**
     * The run request in {@code file}, or {@code null} (logged) when the file holds something else
     * (S-39-01): its root element is checked before anything else of it is read, so no other
     * object, a values file in particular, is ever materialised here.
     */
    @CheckForNull
    private RunRequest readRunRequest(Path file) throws IOException {
        return readXml(file, RunRequest.class, "run request");
    }

    @Override
    public List<RunRequest> listOpenRunRequests(Consumer<String> unreadable) {
        Objects.requireNonNull(unreadable, "unreadable");
        EntityIndex idx = index();
        List<String> ids = new ArrayList<>();
        for (EntityIndex.RunEntry entry : idx.runRequests.values()) {
            if (entry.summary().isOpen()) {
                ids.add(entry.summary().id());
            }
        }
        List<RunRequest> open = new ArrayList<>(ids.size());
        for (String id : ids) {
            RunRequest request;
            try {
                request = loadRunRequest(id);
            } catch (RuntimeException e) {
                // Its index entry stays, so the next listing reads it again.
                skipUnreadableOpen("run request", id, e);
                unreadable.accept(id);
                continue;
            }
            readableAgain("run request", id, request != null);
            if (request == null) {
                idx.runRequests.remove(id);
                continue;
            }
            if (RequestSummary.of(request).isOpen()) {
                open.add(request);
            } else {
                // Closed behind a stale entry; closed statuses are terminal, so this is safe.
                idx.put(request);
            }
        }
        open.sort(OLDEST_FIRST_RUNREQUEST);
        return open;
    }

    @Override
    public boolean hasRunRequestBy(String userId) {
        if (userId == null) {
            return false;
        }
        for (EntityIndex.RunEntry entry : index().runRequests.values()) {
            if (Approvers.sameUser(userId, entry.summary().requester())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<RequestSummary> listRunRequestSummaries() {
        List<RequestSummary> summaries = new ArrayList<>();
        for (EntityIndex.RunEntry entry : index().runRequests.values()) {
            summaries.add(entry.summary());
        }
        summaries.sort(Comparator.comparing(RequestSummary::createdAt, INSTANT_NULLS_FIRST)
                .thenComparing(RequestSummary::id));
        return summaries;
    }

    // ---------------------------------------------------------------- grant requests, grants

    @Override
    public void saveGrantRequest(GrantRequest request) {
        Objects.requireNonNull(request, "request");
        saveXmlEntity(grantRequestDir(), request.getId(), request, "grant request");
        index().put(request);
    }

    @Override
    public GrantRequest loadGrantRequest(String id) {
        return loadXmlEntity(grantRequestDir(), id, GrantRequest.class, GrantRequest::getId, "grant request");
    }

    @Override
    public List<GrantRequest> listGrantRequests() {
        List<GrantRequest> all = listXmlEntities(grantRequestDir(), GrantRequest.class, "grant request");
        all.sort(OLDEST_FIRST_GRANTREQUEST);
        return all;
    }

    @Override
    public List<GrantRequest> listOpenGrantRequests() {
        EntityIndex idx = index();
        List<String> ids = new ArrayList<>();
        for (EntityIndex.GrantRequestEntry entry : idx.grantRequests.values()) {
            if (EntityIndex.isOpen(entry)) {
                ids.add(entry.id());
            }
        }
        List<GrantRequest> open = new ArrayList<>(ids.size());
        for (String id : ids) {
            GrantRequest request;
            try {
                request = loadGrantRequest(id);
            } catch (RuntimeException e) {
                // Its index entry stays, so the next listing reads it again.
                skipUnreadableOpen("grant request", id, e);
                continue;
            }
            readableAgain("grant request", id, request != null);
            if (request == null) {
                idx.grantRequests.remove(id);
                continue;
            }
            if (request.getStatus() == RequestStatus.PENDING) {
                open.add(request);
            } else {
                idx.put(request);
            }
        }
        open.sort(OLDEST_FIRST_GRANTREQUEST);
        return open;
    }

    @Override
    public void saveGrant(Grant grant) {
        Objects.requireNonNull(grant, "grant");
        saveXmlEntity(grantDir(), grant.getId(), grant, "grant");
        index().put(grant);
    }

    @Override
    public Grant loadGrant(String id) {
        return loadXmlEntity(grantDir(), id, Grant.class, Grant::getId, "grant");
    }

    @Override
    public List<Grant> listGrants() {
        return listXmlEntities(grantDir(), Grant.class, "grant");
    }

    // ---------------------------------------------------------------- activation (D-39)

    @Override
    public void saveActivationRequest(ActivationRequest request) {
        Objects.requireNonNull(request, "request");
        saveXmlEntity(activationRequestDir(), request.getId(), request, "activation request");
        index().put(request);
    }

    @Override
    public ActivationRequest loadActivationRequest(String id) {
        return loadXmlEntity(activationRequestDir(), id, ActivationRequest.class, ActivationRequest::getId,
                "activation request");
    }

    @Override
    public List<ActivationRequest> listActivationRequests() {
        List<ActivationRequest> all = listXmlEntities(activationRequestDir(), ActivationRequest.class, "activation request");
        all.sort(OLDEST_FIRST_ACTIVATIONREQUEST);
        return all;
    }

    @Override
    public boolean hasActivationRequestBy(String userId) {
        if (userId == null) {
            return false;
        }
        for (EntityIndex.GrantRequestEntry entry : index().activationRequests.values()) {
            if (Approvers.sameUser(userId, entry.requester())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<ActivationRequest> listOpenActivationRequests(Consumer<String> unreadable) {
        Objects.requireNonNull(unreadable, "unreadable");
        EntityIndex idx = index();
        List<String> ids = new ArrayList<>();
        for (EntityIndex.GrantRequestEntry entry : idx.activationRequests.values()) {
            if (EntityIndex.isOpen(entry)) {
                ids.add(entry.id());
            }
        }
        List<ActivationRequest> open = new ArrayList<>(ids.size());
        for (String id : ids) {
            ActivationRequest request;
            try {
                request = loadActivationRequest(id);
            } catch (RuntimeException e) {
                // Its index entry stays, so the next listing reads it again.
                skipUnreadableOpen("activation request", id, e);
                unreadable.accept(id);
                continue;
            }
            readableAgain("activation request", id, request != null);
            if (request == null) {
                idx.activationRequests.remove(id);
                continue;
            }
            if (request.getStatus() == RequestStatus.PENDING) {
                open.add(request);
            } else {
                idx.put(request);
            }
        }
        open.sort(OLDEST_FIRST_ACTIVATIONREQUEST);
        return open;
    }

    @Override
    public void saveActivationState(ActivationState state) {
        Objects.requireNonNull(state, "state");
        String fileName = PathCodec.encode(state.getJobFullName()) + ".xml";
        saveXmlFile(activationDir(), fileName, "activation", state,
                "activation state of " + state.getJobFullName());
    }

    @Override
    public ActivationState loadActivationState(String jobFullName) {
        Objects.requireNonNull(jobFullName, "jobFullName");
        Path file = PathCodec.resolveUnder(activationDir(), PathCodec.encode(jobFullName) + ".xml");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ActivationState state = (ActivationState) xstream.fromXML(reader);
            // The name inside the file must match the name the file stands for; anything else is
            // not this job's state (a hand-copied file), and a job without a state is not activated.
            return state != null && jobFullName.equals(state.getJobFullName()) ? state : null;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load the activation state of " + jobFullName, e);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Unreadable activation state of " + jobFullName
                    + "; treating the job as not activated", e);
            return null;
        }
    }

    @Override
    public List<ActivationState> listActivationStates() {
        return listXmlEntities(activationDir(), ActivationState.class, "activation state");
    }

    @Override
    public boolean deleteActivationState(String jobFullName) {
        Objects.requireNonNull(jobFullName, "jobFullName");
        return deleteFile(PathCodec.resolveUnder(activationDir(), PathCodec.encode(jobFullName) + ".xml"),
                "activation state of " + jobFullName);
    }

    @Override
    public boolean isActivationSchemaMarked() {
        return Files.isRegularFile(PathCodec.resolveUnder(activationDir(), ACTIVATION_SCHEMA_MARKER));
    }

    @Override
    public void markActivationSchema() {
        writeTextAtomically(activationDir(), ACTIVATION_SCHEMA_MARKER,
                "1" + System.lineSeparator(), "activation schema marker");
    }

    // ---------------------------------------------------------------- config snapshots

    @Override
    public void saveConfigSnapshot(String jobFullName, String configXml) {
        Objects.requireNonNull(jobFullName, "jobFullName");
        Objects.requireNonNull(configXml, "configXml");
        writeTextAtomically(snapshotDir(), PathCodec.encode(jobFullName) + ".xml", configXml,
                "config snapshot of " + jobFullName);
    }

    @Override
    public String loadConfigSnapshot(String jobFullName) {
        Objects.requireNonNull(jobFullName, "jobFullName");
        Path file = PathCodec.resolveUnder(snapshotDir(), PathCodec.encode(jobFullName) + ".xml");
        if (!Files.isRegularFile(file) && Files.exists(file)) {
            // T-GAP-385: something other than a file in its place is not "no snapshot".
            throw new UncheckedIOException(new IOException("The config snapshot " + file + " is not a regular file"));
        }
        return readTextOrNull(file);
    }

    @Override
    public boolean hasConfigSnapshot(String jobFullName) {
        Objects.requireNonNull(jobFullName, "jobFullName");
        Path file = PathCodec.resolveUnder(snapshotDir(), PathCodec.encode(jobFullName) + ".xml");
        return Files.exists(file, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public void deleteConfigSnapshot(String jobFullName) {
        Objects.requireNonNull(jobFullName, "jobFullName");
        deleteFile(PathCodec.resolveUnder(snapshotDir(), PathCodec.encode(jobFullName) + ".xml"),
                "config snapshot of " + jobFullName);
    }

    // ---------------------------------------------------------------- run and change records

    @Override
    public void appendRunRecord(RunRecord record) {
        Objects.requireNonNull(record, "record");
        JSONObject json = runRecordToJson(record);
        if (record.getAppendedAt() == null) {
            // D-81: every new line carries appendedAt; a caller that did not set it gets the plugin
            // clock's instant of this append.
            json.element("appendedAt", BatchClock.now().toEpochMilli());
        }
        appendLine(runsDir(), monthOf(record.getStartedAt()), json);
    }

    @Override
    public List<RunRecord> listRunRecords(YearMonth month) {
        return parseLines(runsDir(), month, FileStore::runRecordFromJson);
    }

    @Override
    public RecordPage<RunRecord> pageRunRecords(Collection<YearMonth> months, Period period,
                                                Predicate<? super RunRecord> filter,
                                                int offset, int limit, int maxScanned) {
        Comparator<RunRecord> newestFirst = Comparator.comparing(RunRecord::getStartedAt)
                .thenComparing(RunRecord::getRunId).reversed();
        // D-81: a line's own appendedAt says when it was appended. A run is appended when it is
        // finalized, never before start + duration, so the later of the two is used (a clock set
        // back between start and append cannot make the scan stop early). A line without it,
        // written before D-81, falls back to the start + duration estimate, which a finalization
        // that lagged (a slow listener or publisher) makes too early.
        ToLongFunction<JsonLineScanner> appendedAt = line -> {
            long started = line.optLong(K_STARTED);
            long duration = line.optLong(K_DURATION);
            long estimate = started == Long.MIN_VALUE || duration == Long.MIN_VALUE
                    ? Long.MAX_VALUE : started + duration;
            long appended = optAppendedAt(line);
            return appended == Long.MIN_VALUE ? estimate : Math.max(appended, estimate);
        };
        return page(runsDir(), months, period, K_STARTED, appendedAt, FileStore::runRecordFromScanner,
                FileStore::runRecordFromJson, RunRecord::getStartedAt, filter, newestFirst,
                offset, limit, maxScanned);
    }

    @Override
    public RunMonthStats runMonthStats(YearMonth month) {
        Path file = PathCodec.resolveUnder(runsDir(), monthFileName(month));
        synchronized (statsCache) {
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (NoSuchFileException e) {
                statsCache.remove(file);
                return RunMonthStats.EMPTY;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + file, e);
            }
            Object identity = attrs.fileKey() != null ? attrs.fileKey() : attrs.creationTime();
            StatsEntry entry = statsCache.get(file);
            if (entry == null || !Objects.equals(entry.identity, identity) || attrs.size() < entry.offset) {
                entry = new StatsEntry(identity);
            }
            if (attrs.size() > entry.offset) {
                readAppendedStats(file, entry);
            }
            if (statsCache.size() >= MAX_STATS_ENTRIES && !statsCache.containsKey(file)) {
                statsCache.clear();
            }
            statsCache.put(file, entry);
            return entry.stats;
        }
    }

    /** Folds the complete lines appended since {@code entry.offset} into the counters. */
    private static void readAppendedStats(Path file, StatsEntry entry) {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(entry.offset);
            ByteBuffer buffer = ByteBuffer.allocate(STATS_CHUNK);
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            long consumed = entry.offset;
            long position = entry.offset;
            RunMonthStats stats = entry.stats;
            boolean oversized = false;
            int read;
            while ((read = channel.read(buffer)) > 0) {
                byte[] bytes = buffer.array();
                for (int i = 0; i < read; i++) {
                    position++;
                    if (bytes[i] != '\n') {
                        if (line.size() < ReverseLineReader.MAX_LINE_BYTES) {
                            line.write(bytes[i]);
                        } else {
                            oversized = true; // S-04: the rest of this line is dropped
                        }
                        continue;
                    }
                    boolean skip = oversized;
                    oversized = false;
                    String text = skip ? "" : line.toString(StandardCharsets.UTF_8).trim();
                    line.reset();
                    consumed = position;
                    if (skip) {
                        LOGGER.warning(() -> "Month summary skipped a line longer than "
                                + ReverseLineReader.MAX_LINE_BYTES + " bytes in " + file);
                        continue;
                    }
                    if (text.isEmpty()) {
                        continue;
                    }
                    try {
                        stats = stats.plus(optString(JSONObject.fromObject(text), "result"));
                    } catch (RuntimeException e) {
                        LOGGER.log(Level.WARNING, "Skipping unparseable line of {0} in the month summary: {1}",
                                new Object[] {file, e.getClass().getName()});
                    }
                }
                buffer.clear();
            }
            // A trailing line without its separator is still being written; it is read next time.
            entry.offset = consumed;
            entry.stats = stats;
        } catch (NoSuchFileException e) {
            entry.offset = 0;
            entry.stats = RunMonthStats.EMPTY;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    /** Incremental month summary: the counters of the bytes before {@link #offset}. */
    private static final class StatsEntry {
        private final Object identity;
        private long offset;
        private RunMonthStats stats = RunMonthStats.EMPTY;

        StatsEntry(Object identity) {
            this.identity = identity;
        }
    }

    @Override
    public void appendChangeRecord(ChangeRecord record) {
        Objects.requireNonNull(record, "record");
        // The diff text goes to changes/diff/<id>.patch (ARCHITECTURE section 5); the JSONL
        // line stays diff-free. The patch is written first so a reader that sees the line
        // always finds the patch.
        String diff = record.getDiff();
        if (diff != null) {
            writeTextAtomically(diffDir(), record.getId() + ".patch", diff,
                    "diff patch of change record " + record.getId());
        }
        appendLine(changesDir(), monthOf(record.getAt()), changeRecordToJson(record));
    }

    @Override
    public List<ChangeRecord> listChangeRecords(YearMonth month) {
        List<ChangeRecord> records = parseLines(changesDir(), month, FileStore::changeRecordFromJson);
        records.forEach(this::attachDiff);
        return records;
    }

    @Override
    public RecordPage<ChangeRecord> pageChangeRecords(Collection<YearMonth> months,
                                                      Predicate<? super ChangeRecord> filter,
                                                      int offset, int limit, int maxScanned) {
        return pageChangeRecords(months, Period.ALL, filter, offset, limit, maxScanned);
    }

    @Override
    public RecordPage<ChangeRecord> pageChangeRecords(Collection<YearMonth> months, Period period,
                                                      Predicate<? super ChangeRecord> filter,
                                                      int offset, int limit, int maxScanned) {
        Comparator<ChangeRecord> newestFirst = Comparator.comparing(ChangeRecord::getAt)
                .thenComparing(ChangeRecord::getId).reversed();
        RecordPage<ChangeRecord> page = page(changesDir(), months, period, K_AT, line -> line.optLong(K_AT),
                FileStore::changeRecordFromScanner, FileStore::changeRecordFromJson, ChangeRecord::getAt,
                filter, newestFirst, offset, limit, maxScanned);
        // Patches only for the rendered rows (the filter never looks at the diff text).
        page.getItems().forEach(this::attachDiff);
        return page;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The bounded page query with no record cap and no page limit: only the period bounds it, so
     * it reads every line since {@code since} (and the lines inside the append-order slack before
     * it), keeping only the matching records, which are few. It is truncated only when the bytes of
     * skipped out-of-period lines exceed the page query's byte budget. No diff is attached.
     *
     * <p>D-75 (2): it is truncated too when a line it read could not be parsed (a torn or damaged
     * line) or was too long to read: such a line may be a {@code GRANT_REVOKE} record of one of the
     * windows, so their ends cannot be ruled out, and the caller ends them (fail-closed). Its position
     * tells that it was appended within the part of the log read back. Logged once per read. A
     * complete line of another record type, also one this version does not know, is not such a
     * record and does not count.
     *
     * <p>T-SEC-109: reading also stops at the first record before {@code since} that
     * {@code boundary} accepts, inside the append-order slack too, so damage behind such a record
     * is not read again.
     */
    @Override
    public RecordPage<ChangeRecord> grantRevokeRecordsSince(Instant since, Set<String> grantIds,
                                                            Predicate<? super ChangeRecord> boundary) {
        Objects.requireNonNull(since, "since");
        Objects.requireNonNull(boundary, "boundary");
        Set<String> ids = Set.copyOf(grantIds);
        Comparator<ChangeRecord> newestFirst = Comparator.comparing(ChangeRecord::getAt)
                .thenComparing(ChangeRecord::getId).reversed();
        RecordPage<ChangeRecord> page = page(changesDir(), listMonthsSince(changesDir(), monthOf(since).minusMonths(1)),
                new Period(since, null), K_AT, line -> line.optLong(K_AT), FileStore::changeRecordFromScanner,
                FileStore::changeRecordOrOtherType, ChangeRecord::getAt,
                r -> r.getType() == ChangeType.GRANT_REVOKE && r.getGrantId() != null && ids.contains(r.getGrantId()),
                newestFirst, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, boundary);
        int unread = page.getUnreadable() + page.getOversized();
        if (unread == 0 || page.isTruncated()) {
            return page;
        }
        LOGGER.warning(() -> unread + " change record line(s) appended since " + since + " could not be read (torn,"
                + " damaged or too long); any of them may end a permission window, so the read of the window ends"
                + " is incomplete");
        return page.asTruncated();
    }

    /**
     * {@link #changeRecordFromJson}, except that a complete line whose {@code type} names another
     * record type than {@code GRANT_REVOKE} (also one this version does not know) gives {@code null}
     * instead of failing: it cannot be a window's end ({@link #grantRevokeRecordsSince}).
     */
    @CheckForNull
    private static ChangeRecord changeRecordOrOtherType(JSONObject json) {
        try {
            return changeRecordFromJson(json);
        } catch (RuntimeException e) {
            Object type = json.opt("type");
            if (type instanceof String name && !ChangeType.GRANT_REVOKE.name().equals(name)) {
                return null;
            }
            throw e;
        }
    }

    /** The months of the bucket files in {@code dir} from {@code first} on. */
    private static List<YearMonth> listMonthsSince(Path dir, YearMonth first) {
        List<YearMonth> months = new ArrayList<>();
        for (Path file : listMonthFiles(dir)) {
            Path name = file.getFileName();
            YearMonth month = name == null ? null : parseMonthFileName(name.toString());
            if (month != null && !month.isBefore(first)) {
                months.add(month);
            }
        }
        return months;
    }

    private void attachDiff(ChangeRecord record) {
        record.setDiff(readTextOrNull(PathCodec.resolveUnder(diffDir(), record.getId() + ".patch")));
    }

    // ---------------------------------------------------------------- incidents

    @Override
    public void createIncident(Incident incident) {
        Objects.requireNonNull(incident, "incident");
        // XML first, then the index line: a reader that finds the line always finds the XML.
        saveXmlEntity(incidentDir(), incident.getId(), incident, "incident");
        appendLine(incidentIndexDir(), monthOf(incident.getCreatedAt()), incidentIndexToJson(incident));
    }

    @Override
    public void saveIncident(Incident incident) {
        Objects.requireNonNull(incident, "incident");
        saveXmlEntity(incidentDir(), incident.getId(), incident, "incident");
    }

    @Override
    public Incident loadIncident(String id) {
        return loadXmlEntity(incidentDir(), id, Incident.class, Incident::getId, "incident");
    }

    @Override
    public List<Incident> listIncidents(YearMonth month) {
        List<Incident> incidents = new ArrayList<>();
        for (String id : parseLines(incidentIndexDir(), month, json -> optString(json, "id"))) {
            Incident incident = loadIncident(id);
            if (incident != null) {
                incidents.add(incident);
            }
        }
        return incidents;
    }

    @Override
    public RecordPage<Incident> pageIncidents(Collection<YearMonth> months, Period period,
                                              Predicate<? super IncidentSummary> indexFilter,
                                              Predicate<? super Incident> filter,
                                              int offset, int limit, int maxScanned) {
        Comparator<Incident> newestFirst = Comparator
                .comparing((Incident i) -> i.getCreatedAt() == null ? Instant.EPOCH : i.getCreatedAt())
                .thenComparing(Incident::getId).reversed();
        // The index fields are checked first; the XML is loaded only for lines that pass (S-06).
        Function<IncidentSummary, Incident> load = summary ->
                summary == null || !indexFilter.test(summary) ? null : loadIncident(summary.id());
        Function<JsonLineScanner, Incident> fast = line -> load.apply(incidentSummaryFromScanner(line));
        Function<JSONObject, Incident> full = json -> load.apply(incidentSummaryFromJson(json));
        return page(incidentIndexDir(), months, period, K_CREATED, line -> line.optLong(K_CREATED), fast, full,
                Incident::getCreatedAt, filter, newestFirst, offset, limit, maxScanned);
    }

    private static IncidentSummary incidentSummaryFromScanner(JsonLineScanner line) {
        String id = line.optString(K_ID, false);
        if (id == null) {
            return null;
        }
        long created = line.optLong(K_CREATED);
        return new IncidentSummary(id, line.optString(K_RUN_ID, false), line.optString(K_JOB, true),
                line.optString(K_RESULT, true), created == Long.MIN_VALUE ? null : Instant.ofEpochMilli(created));
    }

    private static IncidentSummary incidentSummaryFromJson(JSONObject json) {
        String id = optString(json, "id");
        if (id == null) {
            return null;
        }
        Instant created = json.has("createdAt") ? Instant.ofEpochMilli(json.getLong("createdAt")) : null;
        return new IncidentSummary(id, optString(json, "runId"), optString(json, "jobFullName"),
                optString(json, "result"), created);
    }

    // ---------------------------------------------------------------- retention (SPEC item 12)

    @Override
    public List<YearMonth> listStoredMonths() {
        TreeSet<YearMonth> months = new TreeSet<>();
        for (Path dir : monthDirs()) {
            for (Path file : listMonthFiles(dir)) {
                Path name = file.getFileName();
                YearMonth month = name == null ? null : parseMonthFileName(name.toString());
                if (month != null) {
                    months.add(month);
                }
            }
        }
        return new ArrayList<>(months);
    }

    private Path[] monthDirs() {
        return new Path[] {runsDir(), changesDir(), incidentIndexDir()};
    }

    private static List<Path> listMonthFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jsonl")) {
            for (Path file : stream) {
                files.add(file);
            }
        } catch (NoSuchFileException e) {
            // Nothing stored there yet.
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list month files in " + dir, e);
        }
        return files;
    }

    /**
     * Deletes one month bucket file by file: every incident XML, patch and bucket file is
     * deleted under its own lock stripe, and the lock is released before the next one, so no
     * writer waits behind more than a single deletion. Incident XMLs and patches go first and
     * the JSONL files last, so an interrupted pass leaves the index in place and the next pass
     * finishes it.
     */
    @Override
    public boolean deleteMonth(YearMonth month) {
        Objects.requireNonNull(month, "month");
        boolean deleted = false;
        try {
            Path indexFile = PathCodec.resolveUnder(incidentIndexDir(), monthFileName(month));
            if (Files.isRegularFile(indexFile)) {
                // Order is irrelevant here; the reverse reader caps line length (S-04).
                JsonLineScanner scanner = new JsonLineScanner();
                try (ReverseLineReader reader = new ReverseLineReader(indexFile)) {
                    while (reader.next()) {
                        String id = scanner.scan(reader.buffer(), reader.offset(), reader.length())
                                ? scanner.optString(K_ID, false) : null;
                        if (id != null) {
                            deleted |= deleteIncidentXml(id);
                        }
                    }
                    if (reader.oversized() > 0) {
                        LOGGER.warning(() -> "Skipped over-long lines in " + indexFile);
                    }
                } catch (NoSuchFileException e) {
                    // Deleted concurrently.
                }
            }
            deleted |= deleteFile(indexFile, "incident index of " + month);
            deleted |= deleteFile(PathCodec.resolveUnder(runsDir(), monthFileName(month)), "runs of " + month);
            // Diff patches are named <id>.patch and change record ids start with yyyyMMdd
            // (Ids#newId), so the month bucket of a patch is recoverable from its file-name prefix.
            String idMonthPrefix = String.format(Locale.ROOT, "%04d%02d", month.getYear(), month.getMonthValue());
            if (Files.isDirectory(diffDir())) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(diffDir(), idMonthPrefix + "*.patch")) {
                    for (Path patch : stream) {
                        deleted |= deleteFile(patch, "diff patch");
                    }
                }
            }
            deleted |= deleteFile(PathCodec.resolveUnder(changesDir(), monthFileName(month)), "changes of " + month);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete month bucket " + month, e);
        } finally {
            synchronized (statsCache) {
                statsCache.remove(PathCodec.resolveUnder(runsDir(), monthFileName(month)));
            }
        }
        return deleted;
    }

    private boolean deleteIncidentXml(String id) {
        try {
            return deleteFile(PathCodec.resolveId(incidentDir(), id, ".xml"), "incident " + id);
        } catch (IllegalArgumentException e) {
            return false; // not a valid id; nothing of ours to delete
        }
    }

    @Override
    public RetentionResult deleteClosedEntitiesBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        EntityIndex idx = index();
        int runRequests = 0;
        for (EntityIndex.RunEntry entry : new ArrayList<>(idx.runRequests.values())) {
            if (entry.summary().isOpen() || !entry.lastActivity().isBefore(cutoff)) {
                continue;
            }
            // Confirm against the file: the index is derived data.
            String id = entry.summary().id();
            RunRequest request = loadRunRequest(id);
            if (request != null) {
                idx.put(request);
                EntityIndex.RunEntry fresh = idx.runRequests.get(id);
                if (fresh == null || fresh.summary().isOpen() || !fresh.lastActivity().isBefore(cutoff)) {
                    continue;
                }
                deleteRunRequestValues(id); // normally gone already, when the request ended (D-74)
                // The id is a store identifier: the request was just loaded under it (S-39-01).
                if (deleteFile(PathCodec.resolveId(runRequestDir(), id, ".xml"), "run request " + id)) {
                    runRequests++;
                }
            }
            idx.runRequests.remove(id);
        }
        List<String> grantIds = new ArrayList<>();
        for (EntityIndex.GrantEntry entry : new ArrayList<>(idx.grants.values())) {
            if (!entry.endedAt().isBefore(cutoff)) {
                continue;
            }
            String id = entry.id();
            Grant grant = loadGrant(id);
            if (grant != null) {
                idx.put(grant);
                EntityIndex.GrantEntry fresh = idx.grants.get(id);
                if (fresh == null || !fresh.endedAt().isBefore(cutoff)) {
                    continue;
                }
                if (!grant.getChangedItems().isEmpty()) {
                    // D-58a (5): the "changed under a grant" state never lapses through retention;
                    // the grant file is kept until every item on it has been reviewed or deleted.
                    continue;
                }
                if (deleteFile(PathCodec.resolveId(grantDir(), id, ".xml"), "grant " + id)) {
                    grantIds.add(id);
                }
            }
            idx.grants.remove(id);
        }
        Set<String> referenced = new HashSet<>();
        for (EntityIndex.GrantEntry entry : idx.grants.values()) {
            referenced.add(entry.requestId());
        }
        int grantRequests = 0;
        for (EntityIndex.GrantRequestEntry entry : new ArrayList<>(idx.grantRequests.values())) {
            if (EntityIndex.isOpen(entry) || !entry.lastActivity().isBefore(cutoff)
                    || referenced.contains(entry.id())) {
                continue;
            }
            String id = entry.id();
            GrantRequest request = loadGrantRequest(id);
            if (request != null) {
                idx.put(request);
                EntityIndex.GrantRequestEntry fresh = idx.grantRequests.get(id);
                if (fresh == null || EntityIndex.isOpen(fresh) || !fresh.lastActivity().isBefore(cutoff)) {
                    continue;
                }
                if (deleteFile(PathCodec.resolveId(grantRequestDir(), id, ".xml"), "grant request " + id)) {
                    grantRequests++;
                }
            }
            idx.grantRequests.remove(id);
        }
        int activationRequests = 0;
        for (EntityIndex.GrantRequestEntry entry : new ArrayList<>(idx.activationRequests.values())) {
            if (EntityIndex.isOpen(entry) || !entry.lastActivity().isBefore(cutoff)) {
                continue;
            }
            String id = entry.id();
            ActivationRequest request = loadActivationRequest(id);
            if (request != null) {
                idx.put(request);
                EntityIndex.GrantRequestEntry fresh = idx.activationRequests.get(id);
                if (fresh == null || EntityIndex.isOpen(fresh) || !fresh.lastActivity().isBefore(cutoff)) {
                    continue;
                }
                if (deleteFile(PathCodec.resolveId(activationRequestDir(), id, ".xml"),
                        "activation request " + id)) {
                    activationRequests++;
                }
            }
            idx.activationRequests.remove(id);
        }
        return new RetentionResult(runRequests, grantRequests, grantIds, activationRequests);
    }

    /**
     * Month bucket names are ASCII ({@code \d} in Java regex is ASCII-only). Names with other
     * digits were only ever written by unreleased builds and are ignored (D-43).
     */
    private static YearMonth parseMonthFileName(String fileName) {
        if (!fileName.endsWith(".jsonl")) {
            return null;
        }
        String base = fileName.substring(0, fileName.length() - ".jsonl".length());
        if (!base.matches("\\d{4}-\\d{2}")) {
            return null;
        }
        try {
            return YearMonth.parse(base);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- entity index

    /**
     * The entity index of the current Jenkins session, built on first use by reading every
     * request and grant XML once (startup recovery triggers that before the queue runs).
     */
    private EntityIndex index() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        Path root = root();
        EntityIndex current = index;
        if (current != null && current.root.equals(root) && indexFor.get() == jenkins) {
            return current;
        }
        synchronized (indexMonitor) {
            current = index;
            if (current != null && current.root.equals(root) && indexFor.get() == jenkins) {
                return current;
            }
            EntityIndex built = new EntityIndex(root);
            for (RunRequest request : listRunRequests()) {
                built.put(request);
            }
            for (GrantRequest request : listGrantRequests()) {
                built.put(request);
            }
            for (Grant grant : listGrants()) {
                built.put(grant);
            }
            for (ActivationRequest request : listActivationRequests()) {
                built.put(request);
            }
            indexFor = new WeakReference<>(jenkins);
            index = built;
            return built;
        }
    }

    /** Builds the entity index now (startup), so no later save pays for it. */
    @Override
    public void warmUp() {
        index();
    }

    // ---------------------------------------------------------------- I/O helpers

    /**
     * Writes one XStream XML entity atomically (temp file, then {@code ATOMIC_MOVE}).
     *
     * @throws IllegalArgumentException if {@code id} is not a store identifier (S-39-01): an entity
     *         is only ever written under a name it can also be loaded by
     */
    private void saveXmlEntity(Path dir, String id, Object entity, String what) {
        if (!PathCodec.isId(id)) {
            throw new IllegalArgumentException("Invalid " + what + " id: " + id);
        }
        saveXmlFile(dir, id + ".xml", id, entity, what + " " + id);
    }

    /**
     * Writes one XStream XML file atomically under {@code dir}. {@code tmpPrefix} names the
     * temporary file; it must stay short (an encoded job name may already use 250 characters).
     *
     * <p>R1-01, the last line of defence: every text and attribute value goes through
     * {@link StorableTextWriter}, so an entity holding a character XML 1.0 cannot store (which
     * XStream would write as a character reference the reader then rejects) is refused with
     * {@link StoreWriteException} and no unreadable file is ever written; the previous file, if
     * any, stays as it was. The services refuse user text and clean system text before this.
     */
    private void saveXmlFile(Path dir, String fileName, String tmpPrefix, Object entity, String what) {
        Path target = PathCodec.resolveUnder(dir, fileName);
        ReentrantLock lock = lockFor(target);
        lock.lock();
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, tmpPrefix, ".tmp");
            boolean moved = false;
            try {
                // #34: the content is forced to disk before the rename (as Files.newBufferedWriter
                // does, an unmappable character is refused rather than replaced).
                try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE);
                     Writer writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel),
                             StandardCharsets.UTF_8.newEncoder()))) {
                    // What xstream.toXML(entity, writer) does, with the check around the writer.
                    HierarchicalStreamWriter xml = new StorableTextWriter(
                            XStream2.getDefaultDriver().createWriter(writer));
                    try {
                        xstream.marshal(entity, xml);
                    } finally {
                        xml.flush();
                    }
                    writer.flush();
                    channel.force(true);
                }
                moveAtomically(tmp, target);
                moved = true;
                DurableFiles.forceDirectory(dir);
            } finally {
                if (!moved) {
                    // S-35-03: the partly written temporary file of a failed save is removed.
                    deleteQuietly(tmp);
                }
            }
        } catch (IOException | RuntimeException e) {
            UnstorableTextException unstorable = unstorableCause(e);
            if (unstorable != null) {
                LOGGER.warning(() -> "Refused to save the " + what + ": " + unstorable.getMessage());
                throw new StoreWriteException("The " + what + " could not be saved: it contains a character"
                        + " that cannot be stored (" + unstorable.getMessage() + "); nothing was stored.", e);
            }
            // S-35-03: XStream refuses some content with a RuntimeException (U+0000, for one), so
            // every failure ends here, as one exception type the web layer shows as a refusal.
            throw new StoreWriteException("The " + what + " could not be saved; nothing was stored.", e);
        } finally {
            lock.unlock();
        }
    }

    /** The {@link UnstorableTextException} in the cause chain of {@code e} (XStream wraps it), or null. */
    @CheckForNull
    private static UnstorableTextException unstorableCause(Throwable e) {
        Set<Throwable> seen = new HashSet<>();
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof UnstorableTextException) {
                return (UnstorableTextException) t;
            }
        }
        return null;
    }

    /**
     * R1-01: a text value or attribute value of an entity holds a character XML 1.0 cannot store
     * ({@link XmlChars}). Its message is the character as {@code U+XXXX}.
     */
    private static final class UnstorableTextException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnstorableTextException(String character) {
            super(character, null, false, false);
        }
    }

    /**
     * R1-01: passes everything to the XML writer of the store's driver, after checking each text
     * and attribute value with {@link XmlChars#firstInvalid}; a value it cannot store aborts the
     * save with {@link UnstorableTextException} before the value is written.
     */
    private static final class StorableTextWriter extends WriterWrapper {

        StorableTextWriter(HierarchicalStreamWriter wrapped) {
            super(wrapped);
        }

        @Override
        public void addAttribute(String name, String value) {
            check(value);
            super.addAttribute(name, value);
        }

        @Override
        public void setValue(String text) {
            check(text);
            super.setValue(text);
        }

        private static void check(String text) {
            int bad = XmlChars.firstInvalid(text);
            if (bad >= 0) {
                throw new UnstorableTextException(XmlChars.describe(text, bad));
            }
        }
    }

    /** Deletes a temporary file of a failed write; a failure to do so is only logged. */
    private static void deleteQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not delete the temporary file " + tmp, e);
        }
    }

    /**
     * The entity {@code id} of {@code type} stored in {@code dir}, or {@code null}: also when
     * {@code id} is not a store identifier (S-39-01, {@link PathCodec#isId}), when the file holds
     * something else, and when the entity in it has another id (another spelling of the id reaching
     * the same file on a case-insensitive file system).
     */
    @CheckForNull
    private <T> T loadXmlEntity(Path dir, String id, Class<T> type, Function<T, String> idOf, String what) {
        Objects.requireNonNull(id, "id");
        if (!PathCodec.isId(id)) {
            return null;
        }
        Path file = PathCodec.resolveId(dir, id, ".xml");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            T entity = readXml(file, type, what);
            return entity != null && id.equals(idOf.apply(entity)) ? entity : null;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + what + " " + id, e);
        }
    }

    /**
     * Reads the XStream XML file {@code file} as a {@code type}, or returns {@code null} (logged) when
     * its root element names another class (S-39-01). The root element is checked before anything
     * else in the file is read, so a file holding another object is never materialised, whatever
     * its size: XStream names the root after the object's class (no store class has an alias, and
     * all of them are final), so anything else is not a {@code type}. The reader is the one
     * {@link XStream2} itself creates for {@code fromXML}.
     */
    @CheckForNull
    private <T> T readXml(Path file, Class<T> type, String what) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            HierarchicalStreamReader xml = XStream2.getDefaultDriver().createReader(reader);
            try {
                String classAttribute = HierarchicalStreams.readClassAttribute(xml, xstream.getMapper());
                String declared = classAttribute != null ? classAttribute : xml.getNodeName();
                if (!xstream.getMapper().serializedClass(type).equals(declared)) {
                    LOGGER.warning(() -> "Not reading " + file + ": it holds a " + declared + ", not a " + what);
                    return null;
                }
                Object read = xstream.unmarshal(xml);
                if (!type.isInstance(read)) {
                    LOGGER.warning(() -> "Not using " + file + ": it does not hold a " + what);
                    return null;
                }
                return type.cast(read);
            } finally {
                xml.close();
            }
        }
    }

    /** The {@code *.xml} files of {@code dir}, sorted by name; empty when it does not exist. */
    private static List<Path> listXmlFiles(Path dir, String what) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.xml")) {
            for (Path file : stream) {
                files.add(file);
            }
        } catch (NoSuchFileException e) {
            return new ArrayList<>();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + what + " files in " + dir, e);
        }
        // Same directory for every entry, so the full path sorts identically to the file name.
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    /**
     * Every {@code type} stored in {@code dir}. A file that cannot be read is skipped with a warning
     * naming it, whether it is corrupt (an XStream conversion error) or cannot be read at all
     * (permission denied, an I/O error): one such file must not break every reader of the
     * directory, which for grants would be every permission check of every user. A skipped grant
     * confers nothing. Only a directory that cannot be listed fails the whole listing.
     */
    private <T> List<T> listXmlEntities(Path dir, Class<T> type, String what) {
        List<T> entities = new ArrayList<>();
        for (Path file : listXmlFiles(dir, what)) {
            try {
                T entity = readXml(file, type, what);
                if (entity != null) {
                    entities.add(entity);
                }
            } catch (NoSuchFileException e) {
                // Deleted between listing and reading; skip.
            } catch (IOException | RuntimeException e) {
                warnSkipped(what, file, e);
            }
        }
        return entities;
    }

    /** Logs that the {@code what} file {@code file} is left out of a listing because it cannot be read. */
    private static void warnSkipped(String what, Path file, Exception e) {
        Path name = file.getFileName();
        String stem = name == null ? "" : name.toString();
        if (endsWithIgnoreCase(stem, ".xml")) {
            stem = stem.substring(0, stem.length() - ".xml".length());
        }
        LOGGER.log(Level.WARNING, "Skipping the " + what + " '" + stem + "' (" + file
                + "): its file cannot be read; it is left out until it can be read and Jenkins is restarted", e);
    }

    /**
     * Logs that the open {@code what} {@code id} is left out of an open-request listing because its file
     * cannot be read. The listings run every minute and on every page that shows a pending count, so
     * one request that stays unreadable is warned about when it is first seen and then once an hour,
     * and logged at FINE in between.
     */
    private void skipUnreadableOpen(String what, String id, RuntimeException e) {
        String key = what + ' ' + id;
        Instant now = BatchClock.now();
        Instant warned = unreadableOpenWarnedAt.get(key);
        if (warned == null || now.isBefore(warned) || !now.isBefore(warned.plus(UNREADABLE_WARNING_INTERVAL))) {
            unreadableOpenWarnedAt.put(key, now);
            LOGGER.log(Level.WARNING, "Skipping the open " + what + " '" + id + "': its file cannot be read. While it"
                    + " cannot be read it is not counted, expired, approved, run or recovered; it is picked up again"
                    + " as soon as it can be read", e);
        } else {
            LOGGER.log(Level.FINE, "Still skipping the open " + what + " '" + id + "': its file cannot be read", e);
        }
    }

    /**
     * Notes that the open {@code what} {@code id}, once skipped as unreadable, was read again
     * ({@code found}) or is gone.
     */
    private void readableAgain(String what, String id, boolean found) {
        if (!unreadableOpenWarnedAt.isEmpty() && unreadableOpenWarnedAt.remove(what + ' ' + id) != null) {
            LOGGER.info(() -> "The open " + what + " '" + id + "' " + (found ? "can be read again" : "is gone"));
        }
    }

    /** Writes a plain-text file atomically (temp file, then {@code ATOMIC_MOVE}). */
    private void writeTextAtomically(Path dir, String fileName, String text, String what) {
        Path target = PathCodec.resolveUnder(dir, fileName);
        ReentrantLock lock = lockFor(target);
        lock.lock();
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "write", ".tmp");
            boolean moved = false;
            try {
                DurableFiles.writeForced(tmp, text.getBytes(StandardCharsets.UTF_8));
                moveAtomically(tmp, target);
                moved = true;
                DurableFiles.forceDirectory(dir);
            } finally {
                if (!moved) {
                    deleteQuietly(tmp);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + what, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Deletes one file under its own lock stripe; {@code true} if it existed. A delete Windows refuses
     * while another handle has the file open is retried for about two seconds ({@link SharingRetry}).
     */
    private boolean deleteFile(Path file, String what) {
        ReentrantLock lock = lockFor(file);
        lock.lock();
        try {
            return SharingRetry.run(file, () -> Files.deleteIfExists(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + what, e);
        } finally {
            lock.unlock();
        }
    }

    private static String readTextOrNull(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    /**
     * Moves the temporary file {@code tmp} over {@code target} (every atomic write commits here).
     * Windows refuses to replace a file while another handle has it open (a page, the queue gate or
     * a listener reading it at that moment, a virus scanner); that refusal is transient, so it is
     * retried for about two seconds ({@link SharingRetry}) before the write fails. A refused attempt
     * changes nothing: {@code tmp} stays in place for the next one, and the target keeps its
     * previous content.
     */
    private static void moveAtomically(Path tmp, Path target) throws IOException {
        SharingRetry.run(target, () -> {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        });
    }

    /**
     * Appends one record line to a month file (every append-only JSONL file goes through here:
     * changes, runs, the incident index). Under the file's lock, the file's last byte is checked
     * first: a file that does not end with a line end (a line torn by a crash or a failed write) is
     * terminated before the record is written, so the record never merges into the torn line and
     * stays readable (T-SEC-108); the torn line stays an unreadable line of its own.
     *
     * <p>The append itself needs only write access (T-GAP-387): the file is opened for writing alone
     * and its last byte is read through a channel of its own. When that byte cannot be read (a file
     * that can be written but not read), the record is written after a line end of its own, as for a
     * torn line, instead of failing: at worst that leaves a blank line, which every reader of these
     * files skips ({@link #parseLines}, the paged reads, the month counters, retention).
     */
    private void appendLine(Path dir, YearMonth month, JSONObject json) {
        Path file = PathCodec.resolveUnder(dir, monthFileName(month));
        String line = json.toString() + System.lineSeparator();
        ReentrantLock lock = lockFor(file);
        lock.lock();
        try {
            Files.createDirectories(dir);
            boolean created = !Files.exists(file);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                long end = channel.size();
                if (end > 0 && !lastLineEnded(file, end)) {
                    line = System.lineSeparator() + line;
                }
                ByteBuffer bytes = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    end += channel.write(bytes, end);
                }
                // #34: the record is on disk before the operation that wrote it reports success.
                channel.force(true);
            }
            if (created) {
                // A new month file's directory entry, once a month.
                DurableFiles.forceDirectory(dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append record to " + file, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Whether the byte before {@code end} in {@code file} is a line feed, read through a read-only
     * channel of its own. {@code false}, with a warning, when it is not (a torn last line) or when it
     * cannot be read: the caller then writes a line end before the record.
     */
    private static boolean lastLineEnded(Path file, long end) {
        try (SeekableByteChannel in = Files.newByteChannel(file, StandardOpenOption.READ)) {
            in.position(end - 1);
            ByteBuffer last = ByteBuffer.allocate(1);
            while (last.hasRemaining()) {
                if (in.read(last) < 0) {
                    break;
                }
            }
            if (!last.hasRemaining() && last.get(0) == '\n') {
                return true;
            }
            LOGGER.warning(() -> "The last line of " + file + " was not terminated (torn); it is ended before"
                    + " the next record is appended and stays an unreadable line of its own");
            return false;
        } catch (IOException e) {
            LOGGER.warning(() -> "The last byte of " + file + " could not be read (" + e.getClass().getName()
                    + "); the record is appended after a line end of its own, which leaves at worst a blank line"
                    + " that readers skip");
            return false;
        }
    }

    /**
     * Parses every non-blank line of a month bucket, streaming the file (no list of raw lines).
     * A line that is not valid JSON or does not map to a record (missing field, unknown enum
     * constant) is skipped with a warning naming the file and line number, so one bad line cannot
     * break the whole month. A line longer than {@link ReverseLineReader#MAX_LINE_BYTES} is never
     * materialised: its excess bytes are dropped as they are read, the line is skipped and the
     * skips are counted and logged once, exactly like the page path (security-11 N-01), so a CSV
     * export and a screen agree on which records exist.
     */
    private <T> List<T> parseLines(Path dir, YearMonth month, Function<JSONObject, T> parser) {
        Path file = PathCodec.resolveUnder(dir, monthFileName(month));
        List<T> result = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        int oversized = 0;
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] chunk = new byte[STATS_CHUNK];
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean discarding = false;
            int lineNumber = 0;
            int read;
            while ((read = in.read(chunk)) > 0) {
                int from = 0;
                for (int i = 0; i < read; i++) {
                    if (chunk[i] != '\n') {
                        continue;
                    }
                    discarding = appendCapped(line, chunk, from, i - from, discarding);
                    from = i + 1;
                    lineNumber++;
                    if (discarding) {
                        oversized++;
                    } else {
                        parseLine(line, lineNumber, file, parser, result);
                    }
                    line.reset();
                    discarding = false;
                }
                discarding = appendCapped(line, chunk, from, read - from, discarding);
            }
            // A last line without a separator is still a record.
            if (discarding) {
                oversized++;
            } else if (line.size() > 0) {
                parseLine(line, lineNumber + 1, file, parser, result);
            }
        } catch (NoSuchFileException e) {
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
        if (oversized > 0) {
            int count = oversized;
            LOGGER.warning(() -> "Skipped " + count + " record line(s) longer than "
                    + ReverseLineReader.MAX_LINE_BYTES + " bytes in " + file);
        }
        return result;
    }

    /**
     * Appends {@code length} bytes to {@code line} unless that would take it past
     * {@link ReverseLineReader#MAX_LINE_BYTES}; returns whether the line is (now) being discarded.
     */
    private static boolean appendCapped(ByteArrayOutputStream line, byte[] bytes, int offset, int length,
                                        boolean discarding) {
        if (discarding) {
            return true;
        }
        if ((long) line.size() + length > ReverseLineReader.MAX_LINE_BYTES) {
            line.reset();
            return true;
        }
        line.write(bytes, offset, length);
        return false;
    }

    private static <T> void parseLine(ByteArrayOutputStream line, int lineNumber, Path file,
                                      Function<JSONObject, T> parser, List<T> result) {
        String text = line.toString(StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return;
        }
        T value;
        try {
            value = parser.apply(JSONObject.fromObject(text.strip()));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Skipping unparseable line {0} of {1}: {2}",
                    new Object[] {lineNumber, file, e.getClass().getName()});
            return;
        }
        if (value != null) {
            result.add(value);
        }
    }

    /**
     * The bounded page query behind the {@code page*} methods. Months are read newest
     * first and every file from its end, so the newest records come first.
     *
     * <ul>
     *   <li>Only records inside {@code period} count toward {@code maxScanned} (security-10 S-03).
     *       Their timestamp ({@code timeKey}) is read from the raw bytes, so a line outside the
     *       period is skipped unparsed; newer lines draw on a separate byte budget
     *       ({@link #MAX_SKIPPED_BYTES}), and reading stops at the first line appended
     *       ({@code appendedAt}) before the period starts, since every earlier line is older.</li>
     *   <li>A line the fast scanner rejects is re-read with json-lib before it is skipped, so the
     *       fast path is never stricter than the reference parser (S-02).</li>
     *   <li>Lines over {@link ReverseLineReader#MAX_LINE_BYTES} are skipped and logged once (S-04).</li>
     *   <li>Only the {@code offset + limit} newest matches are held (a bounded heap), sorted exactly
     *       by {@code newestFirst}.</li>
     * </ul>
     */
    private <T> RecordPage<T> page(Path dir, Collection<YearMonth> months, Period period, byte[] timeKey,
                                   ToLongFunction<JsonLineScanner> appendedAt,
                                   Function<JsonLineScanner, T> fastParser, Function<JSONObject, T> fullParser,
                                   Function<T, Instant> timeOf, Predicate<? super T> filter,
                                   Comparator<T> newestFirst, int offset, int limit, int maxScanned) {
        return page(dir, months, period, timeKey, appendedAt, fastParser, fullParser, timeOf, filter, newestFirst,
                offset, limit, maxScanned, null);
    }

    /**
     * As {@link #page(Path, Collection, Period, byte[], ToLongFunction, Function, Function, Function,
     * Predicate, Comparator, int, int, int)}; in addition, reading stops at the first record before
     * the period that {@code boundary} accepts (a record that tells that no earlier line can matter
     * to the caller), even inside the append-order slack.
     */
    private <T> RecordPage<T> page(Path dir, Collection<YearMonth> months, Period period, byte[] timeKey,
                                   ToLongFunction<JsonLineScanner> appendedAt,
                                   Function<JsonLineScanner, T> fastParser, Function<JSONObject, T> fullParser,
                                   Function<T, Instant> timeOf, Predicate<? super T> filter,
                                   Comparator<T> newestFirst, int offset, int limit, int maxScanned,
                                   @CheckForNull Predicate<? super T> boundary) {
        int from = Math.max(0, offset);
        int size = Math.max(0, limit);
        int cap = Math.max(0, maxScanned);
        int keep = (int) Math.min((long) from + size, cap);
        PriorityQueue<T> newest = new PriorityQueue<>(Math.max(1, Math.min(keep, 1024)), newestFirst.reversed());
        Instant periodStart = period.from();
        long stopBefore = periodStart == null ? Long.MIN_VALUE
                : periodStart.toEpochMilli() - APPEND_ORDER_SLACK_MILLIS;
        int scanned = 0;
        int matched = 0;
        int unreadable = 0;
        int oversized = 0;
        long skippedBytes = 0;
        boolean truncated = false;
        JsonLineScanner scanner = new JsonLineScanner();
        List<YearMonth> ordered = new ArrayList<>(new TreeSet<>(months).descendingSet());
        scan:
        for (YearMonth month : ordered) {
            Path file = PathCodec.resolveUnder(dir, monthFileName(month));
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try (ReverseLineReader reader = new ReverseLineReader(file)) {
                try {
                    while (reader.next()) {
                        if (reader.isBlank()) {
                            continue;
                        }
                        T value = null;
                        boolean parsed = false;
                        if (scanner.scan(reader.buffer(), reader.offset(), reader.length())) {
                            long at = scanner.optLong(timeKey);
                            if (at != Long.MIN_VALUE && (period.isAfter(at) || period.isBefore(at))) {
                                if (period.isBefore(at) && appendedAt.applyAsLong(scanner) < stopBefore) {
                                    break scan; // every earlier line, in this and older months, is older
                                }
                                if (boundary != null && period.isBefore(at)
                                        && isBoundary(scanner, reader, fastParser, fullParser, boundary)) {
                                    break scan; // no earlier line can matter to the caller
                                }
                                skippedBytes += reader.length();
                                if (skippedBytes > MAX_SKIPPED_BYTES) {
                                    truncated = true;
                                    break scan;
                                }
                                continue;
                            }
                            try {
                                value = fastParser.apply(scanner);
                                parsed = true;
                            } catch (RuntimeException e) {
                                // fall through to the reference parser
                            }
                        }
                        if (!parsed) {
                            try {
                                String text = new String(reader.buffer(), reader.offset(), reader.length(),
                                        StandardCharsets.UTF_8);
                                value = fullParser.apply(JSONObject.fromObject(text));
                            } catch (RuntimeException e) {
                                if (unreadable++ == 0) {
                                    LOGGER.log(Level.WARNING, "Skipping unparseable line(s) of {0}: {1}",
                                            new Object[] {file, e.getClass().getName()});
                                }
                                continue;
                            }
                            Instant at = value == null ? null : timeOf.apply(value);
                            if (at != null && (period.isAfter(at.toEpochMilli()) || period.isBefore(at.toEpochMilli()))) {
                                if (boundary != null && period.isBefore(at.toEpochMilli()) && boundary.test(value)) {
                                    break scan; // no earlier line can matter to the caller
                                }
                                continue;
                            }
                        }
                        if (scanned >= cap) {
                            truncated = true;
                            break scan;
                        }
                        scanned++;
                        if (value == null || !filter.test(value)) {
                            continue;
                        }
                        matched++;
                        if (keep > 0) {
                            if (newest.size() < keep) {
                                newest.add(value);
                            } else if (newestFirst.compare(value, newest.peek()) < 0) {
                                newest.poll();
                                newest.add(value);
                            }
                        }
                    }
                } finally {
                    oversized += reader.oversized();
                }
            } catch (NoSuchFileException e) {
                // Deleted by retention between the check and the read.
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + file, e);
            }
        }
        if (oversized > 0) {
            int count = oversized;
            LOGGER.warning(() -> "Skipped " + count + " record line(s) longer than "
                    + ReverseLineReader.MAX_LINE_BYTES + " bytes in " + dir);
        }
        List<T> sorted = new ArrayList<>(newest);
        sorted.sort(newestFirst);
        List<T> items = from >= sorted.size()
                ? new ArrayList<>()
                : new ArrayList<>(sorted.subList(from, Math.min(from + size, sorted.size())));
        return new RecordPage<>(items, from, matched, truncated, oversized, unreadable);
    }

    /** Whether the line just scanned is a record {@code boundary} accepts; a line that cannot be parsed is not. */
    private static <T> boolean isBoundary(JsonLineScanner scanner, ReverseLineReader reader,
                                          Function<JsonLineScanner, T> fastParser, Function<JSONObject, T> fullParser,
                                          Predicate<? super T> boundary) {
        T value;
        try {
            value = fastParser.apply(scanner);
        } catch (RuntimeException e) {
            try {
                value = fullParser.apply(JSONObject.fromObject(
                        new String(reader.buffer(), reader.offset(), reader.length(), StandardCharsets.UTF_8)));
            } catch (RuntimeException again) {
                return false;
            }
        }
        return value != null && boundary.test(value);
    }

    // ---------------------------------------------------------------- JSON codecs

    private static void putIfNotNull(JSONObject json, String key, Object value) {
        if (value != null) {
            json.element(key, value);
        }
    }

    private static String optString(JSONObject json, String key) {
        if (!json.has(key) || JSONNull.getInstance().equals(json.get(key))) {
            return null;
        }
        return json.getString(key);
    }

    private static JSONObject runRecordToJson(RunRecord record) {
        JSONObject json = new JSONObject();
        json.element("runId", record.getRunId());
        json.element("jobFullName", record.getJobFullName());
        json.element("number", record.getNumber());
        json.element("causeType", record.getCauseType().name());
        putIfNotNull(json, "result", record.getResult());
        json.element("startedAt", record.getStartedAt().toEpochMilli());
        json.element("durationMs", record.getDurationMs());
        putIfNotNull(json, "user", record.getUser());
        json.element("parameters", record.getParameters());
        putIfNotNull(json, "abortedBy", record.getAbortedBy());
        putIfNotNull(json, "runRequestId", record.getRunRequestId());
        if (record.getAppendedAt() != null) {
            json.element("appendedAt", record.getAppendedAt().toEpochMilli()); // D-81
        }
        return json;
    }

    private static byte[] key(String name) {
        return name.getBytes(StandardCharsets.US_ASCII);
    }

    private static final byte[] K_ID = key("id");
    private static final byte[] K_RUN_ID = key("runId");
    private static final byte[] K_JOB = key("jobFullName");
    private static final byte[] K_NUMBER = key("number");
    private static final byte[] K_CAUSE = key("causeType");
    private static final byte[] K_RESULT = key("result");
    private static final byte[] K_STARTED = key("startedAt");
    private static final byte[] K_DURATION = key("durationMs");
    private static final byte[] K_USER = key("user");
    private static final byte[] K_PARAMETERS = key("parameters");
    private static final byte[] K_ABORTED_BY = key("abortedBy");
    private static final byte[] K_REQUEST_ID = key("runRequestId");
    private static final byte[] K_APPENDED_AT = key("appendedAt");

    /**
     * D-81: a run line's {@code appendedAt}, or {@link Long#MIN_VALUE} when it has none (a line
     * written before D-81) or it is not a number. Absence is checked first: the scanner reports a
     * missing number by an exception, too costly once per line of a page query.
     */
    private static long optAppendedAt(JsonLineScanner line) {
        return line.find(K_APPENDED_AT) < 0 ? Long.MIN_VALUE : line.optLong(K_APPENDED_AT);
    }
    private static final byte[] K_TYPE = key("type");
    private static final byte[] K_TARGET = key("target");
    private static final byte[] K_AT = key("at");
    private static final byte[] K_GRANT_ID = key("grantId");
    private static final byte[] K_DETAIL = key("detail");
    private static final byte[] K_CREATED = key("createdAt");

    /** The page-query twin of {@link #runRecordFromJson} (same fields, same required ones). */
    private static RunRecord runRecordFromScanner(JsonLineScanner line) {
        RunRecord record = new RunRecord(
                line.requireString(K_RUN_ID, false),
                line.requireString(K_JOB, true),
                Math.toIntExact(line.requireLong(K_NUMBER)),
                CauseType.valueOf(line.requireString(K_CAUSE, true)),
                line.optString(K_RESULT, true),
                Instant.ofEpochMilli(line.requireLong(K_STARTED)),
                line.requireLong(K_DURATION));
        record.setUser(line.optString(K_USER, true));
        Map<String, String> parameters = line.optStringMap(K_PARAMETERS);
        if (parameters != null) {
            record.setParameters(parameters);
        }
        record.setAbortedBy(line.optString(K_ABORTED_BY, true));
        record.setRunRequestId(line.optString(K_REQUEST_ID, false));
        // D-81: optional; absent in lines written before it, and a malformed value is ignored.
        long appendedAt = optAppendedAt(line);
        record.setAppendedAt(appendedAt == Long.MIN_VALUE ? null : Instant.ofEpochMilli(appendedAt));
        return record;
    }

    /** The page-query twin of {@link #changeRecordFromJson}. */
    private static ChangeRecord changeRecordFromScanner(JsonLineScanner line) {
        return ChangeRecord.restore(
                line.requireString(K_ID, false),
                ChangeType.valueOf(line.requireString(K_TYPE, true)),
                line.optString(K_TARGET, true),
                line.optString(K_USER, true),
                Instant.ofEpochMilli(line.requireLong(K_AT)),
                line.optString(K_GRANT_ID, false),
                null,
                line.optString(K_DETAIL, false));
    }

    private static RunRecord runRecordFromJson(JSONObject json) {
        RunRecord record = new RunRecord(
                json.getString("runId"),
                json.getString("jobFullName"),
                json.getInt("number"),
                CauseType.valueOf(json.getString("causeType")),
                optString(json, "result"),
                Instant.ofEpochMilli(json.getLong("startedAt")),
                json.getLong("durationMs"));
        record.setUser(optString(json, "user"));
        if (json.has("parameters")) {
            Map<String, String> parameters = new LinkedHashMap<>();
            JSONObject params = json.getJSONObject("parameters");
            for (Object key : params.keySet()) {
                String name = key.toString();
                parameters.put(name, params.getString(name));
            }
            record.setParameters(parameters);
        }
        record.setAbortedBy(optString(json, "abortedBy"));
        record.setRunRequestId(optString(json, "runRequestId"));
        // D-81: optional, as in runRecordFromScanner; a value that is not a whole number is ignored.
        Object appendedAt = json.opt("appendedAt");
        if (appendedAt instanceof Integer || appendedAt instanceof Long) {
            record.setAppendedAt(Instant.ofEpochMilli(((Number) appendedAt).longValue()));
        }
        return record;
    }

    /** The monthly incident index line: id, runId, jobFullName, result, createdAt. */
    private static JSONObject incidentIndexToJson(Incident incident) {
        JSONObject json = new JSONObject();
        json.element("id", incident.getId());
        json.element("runId", incident.getRunId());
        json.element("jobFullName", incident.getJobFullName());
        json.element("result", incident.getResult());
        json.element("createdAt", incident.getCreatedAt().toEpochMilli());
        return json;
    }

    private static JSONObject changeRecordToJson(ChangeRecord record) {
        JSONObject json = new JSONObject();
        json.element("id", record.getId());
        json.element("type", record.getType().name());
        putIfNotNull(json, "target", record.getTarget());
        putIfNotNull(json, "user", record.getUser());
        json.element("at", record.getAt().toEpochMilli());
        putIfNotNull(json, "grantId", record.getGrantId());
        // The diff text is intentionally NOT inlined: it lives in changes/diff/<id>.patch
        // (see appendChangeRecord).
        putIfNotNull(json, "detail", record.getDetail());
        return json;
    }

    private static ChangeRecord changeRecordFromJson(JSONObject json) {
        return ChangeRecord.restore(
                json.getString("id"),
                ChangeType.valueOf(json.getString("type")),
                optString(json, "target"),
                optString(json, "user"),
                Instant.ofEpochMilli(json.getLong("at")),
                optString(json, "grantId"),
                null,
                optString(json, "detail"));
    }
}
