package io.jenkins.plugins.batchcontrol.store;

import hudson.util.XStream2;
import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.DirectoryStream;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import jenkins.model.Jenkins;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * File-based {@link Store} rooted at {@code $JENKINS_HOME/batch-control/}.
 *
 * <ul>
 *   <li>Entities with state (run requests) are XStream XML files, rewritten atomically
 *       (temp file, then {@code ATOMIC_MOVE}).</li>
 *   <li>Append-only records (runs, changes) are monthly JSONL files, written with
 *       append + flush; Instants are stored as epoch milliseconds.</li>
 *   <li>All writes are serialized by one {@link ReentrantLock}.</li>
 *   <li>File names derived from identifiers are validated through {@link PathCodec}.</li>
 * </ul>
 */
@Restricted(NoExternalUse.class)
public final class FileStore implements Store {

    private static final FileStore INSTANCE = new FileStore();

    private final ReentrantLock writeLock = new ReentrantLock();
    private final XStream2 xstream = new XStream2();

    private FileStore() {
    }

    public static FileStore get() {
        return INSTANCE;
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

    private static String monthFileName(YearMonth month) {
        return String.format("%04d-%02d.jsonl", month.getYear(), month.getMonthValue());
    }

    /** Month bucketing follows the {@link BatchClock} zone, like every other time judgment. */
    private static YearMonth monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(BatchClock.clock().getZone()));
    }

    @Override
    public void saveRunRequest(RunRequest request) {
        Objects.requireNonNull(request, "request");
        saveXmlEntity(runRequestDir(), request.getId(), request, "run request");
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
    public void saveGrantRequest(GrantRequest request) {
        Objects.requireNonNull(request, "request");
        saveXmlEntity(grantRequestDir(), request.getId(), request, "grant request");
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
    public void saveGrant(Grant grant) {
        Objects.requireNonNull(grant, "grant");
        saveXmlEntity(grantDir(), grant.getId(), grant, "grant");
    }

    @Override
    public Grant loadGrant(String id) {
        return loadXmlEntity(grantDir(), id, Grant.class, "grant");
    }

    @Override
    public List<Grant> listGrants() {
        return listXmlEntities(grantDir(), Grant.class, "grant");
    }

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
        Path file = PathCodec.resolveUnder(snapshotDir(), PathCodec.encode(jobFullName) + ".xml");
        writeLock.lock();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete config snapshot of " + jobFullName, e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void appendRunRecord(RunRecord record) {
        Objects.requireNonNull(record, "record");
        appendLine(runsDir(), monthOf(record.getStartedAt()), runRecordToJson(record));
    }

    @Override
    public List<RunRecord> listRunRecords(YearMonth month) {
        List<RunRecord> records = new ArrayList<>();
        for (String line : readLines(runsDir(), month)) {
            records.add(runRecordFromJson(JSONObject.fromObject(line)));
        }
        return records;
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
        List<ChangeRecord> records = new ArrayList<>();
        for (String line : readLines(changesDir(), month)) {
            ChangeRecord record = changeRecordFromJson(JSONObject.fromObject(line));
            if (record.getDiff() == null) {
                record.setDiff(readTextOrNull(
                        PathCodec.resolveUnder(diffDir(), record.getId() + ".patch")));
            }
            records.add(record);
        }
        return records;
    }

    @Override
    public void createIncident(Incident incident) {
        Objects.requireNonNull(incident, "incident");
        writeLock.lock();
        try {
            saveXmlEntity(incidentDir(), incident.getId(), incident, "incident");
            appendLine(incidentIndexDir(), monthOf(incident.getCreatedAt()),
                    incidentIndexToJson(incident));
        } finally {
            writeLock.unlock();
        }
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
        for (String line : readLines(incidentIndexDir(), month)) {
            String id = optString(JSONObject.fromObject(line), "id");
            if (id == null) {
                continue;
            }
            Incident incident = loadIncident(id);
            if (incident != null) {
                incidents.add(incident);
            }
        }
        return incidents;
    }

    // ---------------------------------------------------------------- retention (SPEC item 12)

    @Override
    public List<YearMonth> listStoredMonths() {
        java.util.TreeSet<YearMonth> months = new java.util.TreeSet<>();
        for (Path dir : new Path[] {runsDir(), changesDir(), incidentIndexDir()}) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jsonl")) {
                for (Path file : stream) {
                    Path name = file.getFileName();
                    YearMonth month = name == null ? null : parseMonthFileName(name.toString());
                    if (month != null) {
                        months.add(month);
                    }
                }
            } catch (NoSuchFileException e) {
                // Nothing stored there yet.
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to list month files in " + dir, e);
            }
        }
        return new ArrayList<>(months);
    }

    @Override
    public boolean deleteMonth(YearMonth month) {
        Objects.requireNonNull(month, "month");
        writeLock.lock();
        try {
            boolean deleted = false;
            // Incident XMLs first (found through the index), then the index file itself.
            for (String line : readLines(incidentIndexDir(), month)) {
                String id = optString(JSONObject.fromObject(line), "id");
                if (id != null) {
                    deleted |= Files.deleteIfExists(PathCodec.resolveUnder(incidentDir(), id + ".xml"));
                }
            }
            deleted |= Files.deleteIfExists(
                    PathCodec.resolveUnder(incidentIndexDir(), monthFileName(month)));
            deleted |= Files.deleteIfExists(PathCodec.resolveUnder(runsDir(), monthFileName(month)));
            // Diff patches are named <id>.patch and ids start with yyyyMMdd, so the month
            // bucket of a patch is recoverable from its file-name prefix.
            String idMonthPrefix = String.format("%04d%02d", month.getYear(), month.getMonthValue());
            if (Files.isDirectory(diffDir())) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                        diffDir(), idMonthPrefix + "*.patch")) {
                    for (Path patch : stream) {
                        deleted |= Files.deleteIfExists(patch);
                    }
                }
            }
            deleted |= Files.deleteIfExists(
                    PathCodec.resolveUnder(changesDir(), monthFileName(month)));
            return deleted;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete month bucket " + month, e);
        } finally {
            writeLock.unlock();
        }
    }

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

    // ---------------------------------------------------------------- I/O helpers

    /** Writes one XStream XML entity atomically (temp file, then {@code ATOMIC_MOVE}). */
    private void saveXmlEntity(Path dir, String id, Object entity, String what) {
        Path target = PathCodec.resolveUnder(dir, id + ".xml");
        writeLock.lock();
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
            writeLock.unlock();
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
            }
        }
        return entities;
    }

    /** Writes a plain-text file atomically (temp file, then {@code ATOMIC_MOVE}). */
    private void writeTextAtomically(Path dir, String fileName, String text, String what) {
        Path target = PathCodec.resolveUnder(dir, fileName);
        writeLock.lock();
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "write", ".tmp");
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            moveAtomically(tmp, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + what, e);
        } finally {
            writeLock.unlock();
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
        writeLock.lock();
        try {
            Files.createDirectories(dir);
            // Files.write opens, writes, flushes and closes in one call.
            Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append record to " + file, e);
        } finally {
            writeLock.unlock();
        }
    }

    private List<String> readLines(Path dir, YearMonth month) {
        Path file = PathCodec.resolveUnder(dir, monthFileName(month));
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (NoSuchFileException e) {
            return new ArrayList<>();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
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
