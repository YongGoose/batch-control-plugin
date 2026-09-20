package io.jenkins.plugins.batchcontrol.store;

import hudson.util.XStream2;
import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
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
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
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

    private Path runsDir() {
        return root().resolve("runs");
    }

    private Path changesDir() {
        return root().resolve("changes");
    }

    private static String monthFileName(YearMonth month) {
        return String.format("%04d-%02d.jsonl", month.getYear(), month.getMonthValue());
    }

    private static YearMonth monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(ZoneId.systemDefault()));
    }

    @Override
    public void saveRunRequest(RunRequest request) {
        Objects.requireNonNull(request, "request");
        Path dir = runRequestDir();
        Path target = PathCodec.resolveUnder(dir, request.getId() + ".xml");
        writeLock.lock();
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, request.getId(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                xstream.toXML(request, writer);
            }
            moveAtomically(tmp, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save run request " + request.getId(), e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public RunRequest loadRunRequest(String id) {
        Objects.requireNonNull(id, "id");
        Path file = PathCodec.resolveUnder(runRequestDir(), id + ".xml");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return (RunRequest) xstream.fromXML(reader);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load run request " + id, e);
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
        appendLine(changesDir(), monthOf(record.getAt()), changeRecordToJson(record));
    }

    @Override
    public List<ChangeRecord> listChangeRecords(YearMonth month) {
        List<ChangeRecord> records = new ArrayList<>();
        for (String line : readLines(changesDir(), month)) {
            records.add(changeRecordFromJson(JSONObject.fromObject(line)));
        }
        return records;
    }

    // ---------------------------------------------------------------- I/O helpers

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

    private static JSONObject changeRecordToJson(ChangeRecord record) {
        JSONObject json = new JSONObject();
        json.element("id", record.getId());
        json.element("type", record.getType().name());
        putIfNotNull(json, "target", record.getTarget());
        putIfNotNull(json, "user", record.getUser());
        json.element("at", record.getAt().toEpochMilli());
        putIfNotNull(json, "grantId", record.getGrantId());
        putIfNotNull(json, "diff", record.getDiff());
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
