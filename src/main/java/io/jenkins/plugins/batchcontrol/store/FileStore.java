package io.jenkins.plugins.batchcontrol.store;

import hudson.util.XStream2;
import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
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
import java.util.concurrent.locks.ReentrantLock;
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
 *   <li>Writes are serialized <em>per target file</em> (a fixed set of lock stripes, #18): an
 *       append to this month's bucket never waits for anything but another single write that
 *       happens to share its stripe, and no operation holds a lock across more than one file
 *       write or deletion. In particular retention, which deletes old months and closed
 *       entities file by file, cannot stall the queue gate or build completion.</li>
 *   <li>Page loads read JSONL buckets newest first and stop at a record cap (#13); the month
 *       summary counters and the entity index are derived in-memory caches, never files.</li>
 *   <li>File names derived from identifiers are validated through {@link PathCodec}; month and
 *       id names use ASCII digits whatever the default locale (#17).</li>
 * </ul>
 */
@Restricted(NoExternalUse.class)
public final class FileStore implements Store {

    private static final Logger LOGGER = Logger.getLogger(FileStore.class.getName());

    private static final FileStore INSTANCE = new FileStore();

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

    private FileStore() {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            writeLocks[i] = new ReentrantLock();
        }
    }

    public static FileStore get() {
        return INSTANCE;
    }

    /** The write lock of one target file (#18: per-file, never store-wide). */
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

    /** {@code YYYY-MM.jsonl} in ASCII digits whatever the default locale (#17). */
    static String monthFileName(YearMonth month) {
        return String.format(Locale.ROOT, "%04d-%02d.jsonl", month.getYear(), month.getMonthValue());
    }

    /** Month bucketing follows the {@link BatchClock} zone, like every other time judgment. */
    private static YearMonth monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(BatchClock.clock().getZone()));
    }

    // ---------------------------------------------------------------- run requests

    @Override
    public void saveRunRequest(RunRequest request) {
        Objects.requireNonNull(request, "request");
        saveXmlEntity(runRequestDir(), request.getId(), request, "run request");
        index().put(request);
    }

    @Override
    public RunRequest loadRunRequest(String id) {
        return loadXmlEntity(runRequestDir(), id, RunRequest.class, "run request");
    }

    @Override
    public List<RunRequest> listRunRequests() {
        return listXmlEntities(runRequestDir(), RunRequest.class, "run request");
    }

    @Override
    public List<RunRequest> listOpenRunRequests() {
        EntityIndex idx = index();
        List<String> ids = new ArrayList<>();
        for (EntityIndex.RunEntry entry : idx.runRequests.values()) {
            if (entry.summary().isOpen()) {
                ids.add(entry.summary().id());
            }
        }
        ids.sort(Comparator.naturalOrder());
        List<RunRequest> open = new ArrayList<>(ids.size());
        for (String id : ids) {
            RunRequest request = loadRunRequest(id);
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
        return open;
    }

    @Override
    public List<RequestSummary> listRunRequestSummaries() {
        List<RequestSummary> summaries = new ArrayList<>();
        for (EntityIndex.RunEntry entry : index().runRequests.values()) {
            summaries.add(entry.summary());
        }
        summaries.sort(Comparator.comparing(RequestSummary::id));
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
        return loadXmlEntity(grantRequestDir(), id, GrantRequest.class, "grant request");
    }

    @Override
    public List<GrantRequest> listGrantRequests() {
        return listXmlEntities(grantRequestDir(), GrantRequest.class, "grant request");
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
        ids.sort(Comparator.naturalOrder());
        List<GrantRequest> open = new ArrayList<>(ids.size());
        for (String id : ids) {
            GrantRequest request = loadGrantRequest(id);
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
        return loadXmlEntity(grantDir(), id, Grant.class, "grant");
    }

    @Override
    public List<Grant> listGrants() {
        return listXmlEntities(grantDir(), Grant.class, "grant");
    }

    // ---------------------------------------------------------------- config snapshots (#25)

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
        return readTextOrNull(file);
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
        appendLine(runsDir(), monthOf(record.getStartedAt()), runRecordToJson(record));
    }

    @Override
    public List<RunRecord> listRunRecords(YearMonth month) {
        return parseLines(runsDir(), month, FileStore::runRecordFromJson);
    }

    @Override
    public RecordPage<RunRecord> pageRunRecords(Collection<YearMonth> months,
                                                Predicate<? super RunRecord> filter,
                                                int offset, int limit, int maxScanned) {
        return pageRunRecords(months, Period.ALL, filter, offset, limit, maxScanned);
    }

    @Override
    public RecordPage<RunRecord> pageRunRecords(Collection<YearMonth> months, Period period,
                                                Predicate<? super RunRecord> filter,
                                                int offset, int limit, int maxScanned) {
        Comparator<RunRecord> newestFirst = Comparator.comparing(RunRecord::getStartedAt)
                .thenComparing(RunRecord::getRunId).reversed();
        // A run is appended when it completes: start + duration orders the file.
        ToLongFunction<JsonLineScanner> appendedAt = line -> {
            long started = line.optLong(K_STARTED);
            long duration = line.optLong(K_DURATION);
            return started == Long.MIN_VALUE || duration == Long.MIN_VALUE ? Long.MAX_VALUE : started + duration;
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

    private void attachDiff(ChangeRecord record) {
        if (record.getDiff() == null) {
            record.setDiff(readTextOrNull(PathCodec.resolveUnder(diffDir(), record.getId() + ".patch")));
        }
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
        return loadXmlEntity(incidentDir(), id, Incident.class, "incident");
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
    public RecordPage<Incident> pageIncidents(Collection<YearMonth> months,
                                              Predicate<? super Incident> filter,
                                              int offset, int limit, int maxScanned) {
        return pageIncidents(months, Period.ALL, summary -> true, filter, offset, limit, maxScanned);
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
     * Deletes one month bucket file by file (#18): every incident XML, patch and bucket file is
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
            // Diff patches are named <id>.patch and ids start with yyyyMMdd, so the month
            // bucket of a patch is recoverable from its file-name prefix.
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
            return deleteFile(PathCodec.resolveUnder(incidentDir(), id + ".xml"), "incident " + id);
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
                if (deleteFile(PathCodec.resolveUnder(runRequestDir(), id + ".xml"), "run request " + id)) {
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
                if (deleteFile(PathCodec.resolveUnder(grantDir(), id + ".xml"), "grant " + id)) {
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
                if (deleteFile(PathCodec.resolveUnder(grantRequestDir(), id + ".xml"), "grant request " + id)) {
                    grantRequests++;
                }
            }
            idx.grantRequests.remove(id);
        }
        return new RetentionResult(runRequests, grantRequests, grantIds);
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

    // ---------------------------------------------------------------- entity index (#13)

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
            indexFor = new WeakReference<>(jenkins);
            index = built;
            return built;
        }
    }

    /** Builds the entity index now (startup), so no later save pays for it. */
    public void warmUp() {
        index();
    }

    // ---------------------------------------------------------------- I/O helpers

    /** Writes one XStream XML entity atomically (temp file, then {@code ATOMIC_MOVE}). */
    private void saveXmlEntity(Path dir, String id, Object entity, String what) {
        Path target = PathCodec.resolveUnder(dir, id + ".xml");
        ReentrantLock lock = lockFor(target);
        lock.lock();
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, id, ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                xstream.toXML(entity, writer);
            }
            moveAtomically(tmp, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save " + what + " " + id, e);
        } finally {
            lock.unlock();
        }
    }

    private <T> T loadXmlEntity(Path dir, String id, Class<T> type, String what) {
        Objects.requireNonNull(id, "id");
        Path file = PathCodec.resolveUnder(dir, id + ".xml");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return type.cast(xstream.fromXML(reader));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + what + " " + id, e);
        }
    }

    private <T> List<T> listXmlEntities(Path dir, Class<T> type, String what) {
        List<T> entities = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return entities;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.xml")) {
            for (Path file : stream) {
                files.add(file);
            }
        } catch (NoSuchFileException e) {
            return entities;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + what + " files in " + dir, e);
        }
        // Same directory for every entry, so the full path sorts identically to the file name.
        files.sort(Comparator.comparing(Path::toString));
        for (Path file : files) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                entities.add(type.cast(xstream.fromXML(reader)));
            } catch (NoSuchFileException e) {
                // Deleted between listing and reading; skip.
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load " + what + " file " + file, e);
            } catch (RuntimeException e) {
                // One corrupt file (XStream conversion error, wrong type) must not break every
                // reader of the directory -- for grants that would be every permission check.
                LOGGER.log(Level.WARNING, "Skipping unreadable " + what + " file " + file, e);
            }
        }
        return entities;
    }

    /** Writes a plain-text file atomically (temp file, then {@code ATOMIC_MOVE}). */
    private void writeTextAtomically(Path dir, String fileName, String text, String what) {
        Path target = PathCodec.resolveUnder(dir, fileName);
        ReentrantLock lock = lockFor(target);
        lock.lock();
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "write", ".tmp");
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            moveAtomically(tmp, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + what, e);
        } finally {
            lock.unlock();
        }
    }

    /** Deletes one file under its own lock stripe; {@code true} if it existed. */
    private boolean deleteFile(Path file, String what) {
        ReentrantLock lock = lockFor(file);
        lock.lock();
        try {
            return Files.deleteIfExists(file);
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

    private static void moveAtomically(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void appendLine(Path dir, YearMonth month, JSONObject json) {
        Path file = PathCodec.resolveUnder(dir, monthFileName(month));
        String line = json.toString() + System.lineSeparator();
        ReentrantLock lock = lockFor(file);
        lock.lock();
        try {
            Files.createDirectories(dir);
            // Files.write opens, writes, flushes and closes in one call.
            Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append record to " + file, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Parses every non-blank line of a month bucket, streaming the file (no list of raw lines).
     * A line that is not valid JSON or does not map to a record (missing field, unknown enum
     * constant) is skipped with a warning naming the file and line number, so one bad line cannot
     * break the whole month.
     */
    private <T> List<T> parseLines(Path dir, YearMonth month, Function<JSONObject, T> parser) {
        Path file = PathCodec.resolveUnder(dir, monthFileName(month));
        List<T> result = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                T value;
                try {
                    value = parser.apply(JSONObject.fromObject(line));
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Skipping unparseable line {0} of {1}: {2}",
                            new Object[] {lineNumber, file, e.getClass().getName()});
                    continue;
                }
                if (value != null) {
                    result.add(value);
                }
            }
        } catch (NoSuchFileException e) {
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
        return result;
    }

    /**
     * The bounded page query behind the {@code page*} methods (#13). Months are read newest
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
        int from = Math.max(0, offset);
        int size = Math.max(0, limit);
        int cap = Math.max(0, maxScanned);
        int keep = (int) Math.min((long) from + size, cap);
        PriorityQueue<T> newest = new PriorityQueue<>(Math.max(1, Math.min(keep, 1024)), newestFirst.reversed());
        long stopBefore = period.from() == null ? Long.MIN_VALUE
                : period.from().toEpochMilli() - APPEND_ORDER_SLACK_MILLIS;
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
        return new RecordPage<>(items, from, matched, scanned, truncated);
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
    private static final byte[] K_TYPE = key("type");
    private static final byte[] K_TARGET = key("target");
    private static final byte[] K_AT = key("at");
    private static final byte[] K_GRANT_ID = key("grantId");
    private static final byte[] K_DIFF = key("diff");
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
                line.optString(K_DIFF, false),
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
        // (see appendChangeRecord). Reading still accepts a legacy inline "diff" field.
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
                optString(json, "diff"),
                optString(json, "detail"));
    }
}
