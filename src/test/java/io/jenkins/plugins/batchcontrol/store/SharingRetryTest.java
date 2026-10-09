package io.jenkins.plugins.batchcontrol.store;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 4 (the store; ARCHITECTURE section 5, tmp -> rename): the bounded retry the store applies
 * on Windows to a write refused because another handle has the file open. Matrix rows T-04-25 ..
 * T-04-29 (note 293).
 *
 * <p>The contract was frozen from core-dev's report (no SPEC sentence names it; it rests on SPEC 4's
 * store and the Windows CI defect of note 292): {@code SharingRetry.run(file, operation, windows)}
 * retries, when {@code windows} is true, an operation that throws {@link AccessDeniedException} or a
 * {@link FileSystemException} of exactly that class; it pauses 10, 20, 40, 80, 160 ms and then
 * 250 ms seven times (12 pauses, about 2,060 ms, so at most 13 attempts) and then rethrows the last
 * refusal. It does not retry other subclasses of {@code FileSystemException} nor a plain
 * {@link IOException}; when {@code windows} is false it makes exactly one attempt; an interrupt
 * during a pause stops it at once, keeps the interrupt flag and rethrows the last refusal.
 *
 * <p>The operation is a fake that counts its attempts and fails a given number of times with a new
 * exception each time (numbered, so that "the last refusal" can be told from the first). These rows
 * run on every operating system: the {@code windows} flag is passed in. The pauses are real time
 * (the contract names no clock for them); the timing assertions use generous bounds.
 *
 * Written from the contract above, docs/SPEC.md and docs/ARCHITECTURE.md section 5 only (no
 * src/main knowledge).
 */
class SharingRetryTest {

    /** The sum of the twelve pauses of the contract. */
    private static final long PAUSES_MILLIS = 10 + 20 + 40 + 80 + 160 + 7 * 250;

    /** Attempts before giving up: the first one plus one after each of the twelve pauses. */
    private static final int ATTEMPTS = 13;

    @TempDir
    Path dir;

    /**
     * T-04-25: on Windows an operation refused three times with {@code AccessDeniedException} and
     * then successful returns its value after four attempts, with the first three pauses (70 ms)
     * actually taken; one refused twice with a plain {@code FileSystemException} returns after
     * three attempts. Guard: an operation that succeeds at once is run exactly once.
     */
    @Test
    void t_04_25_refusalIsRetriedUntilTheOperationSucceeds() throws Exception {
        FakeOperation accessDenied = new FakeOperation(3, this::accessDenied);
        long start = System.nanoTime();
        String value = SharingRetry.run(file(), accessDenied, true);
        long elapsed = millisSince(start);
        assertEquals("done after 4", value, "the value of the successful attempt is returned");
        assertEquals(4, accessDenied.attempts.get(), "three AccessDeniedException refusals, then success: four attempts");
        assertTrue(elapsed >= 50, "the pauses before the second, third and fourth attempts (10 + 20 + 40 ms) are taken,"
                + " not skipped: " + elapsed + " ms");

        FakeOperation sharing = new FakeOperation(2, this::sharingViolation);
        assertEquals("done after 3", SharingRetry.run(file(), sharing, true), "the value of the successful attempt is returned");
        assertEquals(3, sharing.attempts.get(), "two plain FileSystemException refusals, then success: three attempts");

        // guard: success at once is not repeated
        FakeOperation once = new FakeOperation(0, this::accessDenied);
        assertEquals("done after 1", SharingRetry.run(file(), once, true), "an operation that succeeds at once returns its value");
        assertEquals(1, once.attempts.get(), "an operation that succeeds at once is run exactly once");
    }

    /**
     * T-04-26: on Windows an operation that is always refused is attempted 13 times over about
     * 2,060 ms (12 pauses) and the last refusal is rethrown, for {@code AccessDeniedException} and
     * for a plain {@code FileSystemException}. Guard: an operation refused 12 times succeeds on the
     * 13th attempt (the bound is not one short).
     */
    @Test
    void t_04_26_refusalIsRethrownAfterTheBound() throws Exception {
        assertGivesUp(new FakeOperation(Integer.MAX_VALUE, this::accessDenied), AccessDeniedException.class);
        assertGivesUp(new FakeOperation(Integer.MAX_VALUE, this::sharingViolation), FileSystemException.class);

        // guard: the 13th attempt is still made
        FakeOperation lastChance = new FakeOperation(ATTEMPTS - 1, this::accessDenied);
        assertEquals("done after " + ATTEMPTS, SharingRetry.run(file(), lastChance, true),
                "an operation refused twelve times succeeds on the thirteenth attempt");
        assertEquals(ATTEMPTS, lastChance.attempts.get(), "twelve refusals, then success: thirteen attempts");
    }

    /**
     * T-04-27: on Windows an operation failing with a {@code FileSystemException} subclass other
     * than {@code AccessDeniedException} ({@code NoSuchFileException}, {@code FileAlreadyExistsException},
     * {@code AtomicMoveNotSupportedException}, {@code DirectoryNotEmptyException}) or with a plain
     * {@code IOException} is attempted exactly once and that exception is rethrown. Guard: in the
     * same setting an {@code AccessDeniedException} is retried.
     */
    @Test
    void t_04_27_otherFailuresAreNotRetried() throws Exception {
        List<IntFunction<IOException>> failures = List.of(
                n -> new NoSuchFileException(file().toString(), null, "attempt " + n),
                n -> new FileAlreadyExistsException(file().toString(), null, "attempt " + n),
                n -> new AtomicMoveNotSupportedException(file() + ".tmp", file().toString(), "attempt " + n),
                n -> new DirectoryNotEmptyException(file() + " attempt " + n),
                n -> new IOException("attempt " + n));
        for (IntFunction<IOException> failure : failures) {
            FakeOperation operation = new FakeOperation(Integer.MAX_VALUE, failure);
            IOException thrown = assertThrows(IOException.class, () -> SharingRetry.run(file(), operation, true));
            String type = thrown.getClass().getSimpleName();
            assertSame(operation.last.get(), thrown, type + ": the operation's own exception is rethrown");
            assertEquals(1, operation.attempts.get(), type + " is not a sharing refusal: exactly one attempt");
        }

        // guard: the same setting does retry a sharing refusal
        FakeOperation accessDenied = new FakeOperation(1, this::accessDenied);
        assertEquals("done after 2", SharingRetry.run(file(), accessDenied, true), "guard: an AccessDeniedException is retried");
        assertEquals(2, accessDenied.attempts.get(), "guard: one refusal, then success: two attempts");
    }

    /**
     * T-04-28: on other systems ({@code windows} false) an operation refused with
     * {@code AccessDeniedException} or a plain {@code FileSystemException} is attempted exactly once
     * and the refusal is rethrown; a successful one returns its value after one attempt. Guard: the
     * same refused operation with {@code windows} true is retried.
     */
    @Test
    void t_04_28_otherSystemsMakeExactlyOneAttempt() throws Exception {
        for (IntFunction<IOException> refusal : List.<IntFunction<IOException>>of(this::accessDenied, this::sharingViolation)) {
            FakeOperation operation = new FakeOperation(Integer.MAX_VALUE, refusal);
            IOException thrown = assertThrows(IOException.class, () -> SharingRetry.run(file(), operation, false));
            String type = thrown.getClass().getSimpleName();
            assertSame(operation.last.get(), thrown, type + ": the refusal is rethrown as it is");
            assertEquals(1, operation.attempts.get(), type + " outside Windows: exactly one attempt");
        }
        FakeOperation success = new FakeOperation(0, this::accessDenied);
        assertEquals("done after 1", SharingRetry.run(file(), success, false), "a successful operation returns its value");
        assertEquals(1, success.attempts.get(), "a successful operation is run once");

        // guard: the flag is what decides
        FakeOperation onWindows = new FakeOperation(1, this::accessDenied);
        assertEquals("done after 2", SharingRetry.run(file(), onWindows, true), "guard: on Windows the same refusal is retried");
        assertEquals(2, onWindows.attempts.get(), "guard: one refusal, then success: two attempts");
    }

    /**
     * T-04-29: an interrupt stops the retry at once, keeps the interrupt flag and rethrows the last
     * refusal: (a) with the flag already set, the first pause ends the retry after one attempt;
     * (b) a worker thread retrying an always-refused operation is interrupted after its third
     * attempt and ends within a second (well before the 2,060 ms of pauses), with fewer than 13
     * attempts, its flag set and the refusal of its last attempt. Guard (T-04-26): without an
     * interrupt the same operation runs all 13 attempts.
     */
    @Test
    void t_04_29_interruptStopsTheRetryAndKeepsTheFlag() throws Exception {
        // (a) interrupted before the first pause
        FakeOperation preset = new FakeOperation(Integer.MAX_VALUE, this::accessDenied);
        IOException thrown;
        boolean flagKept;
        Thread.currentThread().interrupt();
        try {
            thrown = assertThrows(IOException.class, () -> SharingRetry.run(file(), preset, true));
        } finally {
            flagKept = Thread.interrupted(); // read and clear, so that nothing after this row is interrupted
        }
        assertTrue(flagKept, "(a) the interrupt flag is kept");
        assertSame(preset.last.get(), thrown, "(a) the last refusal is rethrown");
        assertEquals(1, preset.attempts.get(), "(a) the retry stops at the first pause: one attempt");

        // (b) interrupted while retrying on another thread
        FakeOperation operation = new FakeOperation(Integer.MAX_VALUE, this::accessDenied);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicBoolean workerFlag = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                SharingRetry.run(file(), operation, true);
                outcome.set(new AssertionError("an always refused operation returned"));
            } catch (Throwable t) {
                outcome.set(t);
            } finally {
                workerFlag.set(Thread.currentThread().isInterrupted());
            }
        }, "sharing-retry-worker");
        worker.setDaemon(true);
        worker.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (operation.attempts.get() < 3 && worker.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(1); // polling for the worker's third attempt, not waiting for an expiry
        }
        assertTrue(operation.attempts.get() >= 3 && worker.isAlive(),
                "fixture: the worker is still retrying after its third attempt (" + operation.attempts.get() + " attempts)");
        long interruptedAt = System.nanoTime();
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(10));
        long stoppedAfter = millisSince(interruptedAt);
        assertFalse(worker.isAlive(), "(b) the worker ends after the interrupt");
        assertTrue(stoppedAfter < 1_000, "(b) the retry stops at once, not after the remaining pauses: " + stoppedAfter + " ms");
        assertTrue(operation.attempts.get() < ATTEMPTS, "(b) fewer than 13 attempts: " + operation.attempts.get());
        assertTrue(workerFlag.get(), "(b) the worker's interrupt flag is kept");
        Throwable result = outcome.get();
        assertNotNull(result, "(b) the worker reports an outcome");
        assertTrue(result instanceof AccessDeniedException, "(b) the refusal is rethrown: " + result);
        assertSame(operation.last.get(), result, "(b) the refusal of the last attempt is rethrown");
    }

    // ---------------------------------------------------------------- helpers

    private void assertGivesUp(FakeOperation operation, Class<? extends FileSystemException> type) {
        long start = System.nanoTime();
        IOException thrown = assertThrows(IOException.class, () -> SharingRetry.run(file(), operation, true));
        long elapsed = millisSince(start);
        String name = type.getSimpleName();
        assertEquals(type, thrown.getClass(), name + ": the refusal itself is rethrown");
        assertSame(operation.last.get(), thrown, name + ": the last refusal is rethrown, not the first");
        assertTrue(thrown.getMessage().contains("attempt " + ATTEMPTS), name + ": the thirteenth refusal: " + thrown.getMessage());
        assertEquals(ATTEMPTS, operation.attempts.get(), name + ": the retry gives up after thirteen attempts");
        assertTrue(elapsed >= PAUSES_MILLIS - 260, name + ": the twelve pauses (about " + PAUSES_MILLIS
                + " ms) are taken before giving up: " + elapsed + " ms");
        assertTrue(elapsed < 10_000, name + ": the retry is bounded (about " + PAUSES_MILLIS + " ms): " + elapsed + " ms");
    }

    private Path file() {
        return dir.resolve("requests").resolve("run").resolve("req-1.xml");
    }

    private IOException accessDenied(int attempt) {
        return new AccessDeniedException(file() + ".tmp", file().toString(), "attempt " + attempt);
    }

    /** A plain FileSystemException, as Windows reports a sharing or lock violation (its reason is localised). */
    private IOException sharingViolation(int attempt) {
        return new FileSystemException(file() + ".tmp", file().toString(), "attempt " + attempt);
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** Fails its first {@code failures} attempts with a new, numbered exception, then returns "done after N". */
    private static final class FakeOperation implements SharingRetry.FileOperation<String> {
        private final int failures;
        private final IntFunction<IOException> failure;
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicReference<IOException> last = new AtomicReference<>();

        FakeOperation(int failures, IntFunction<IOException> failure) {
            this.failures = failures;
            this.failure = failure;
        }

        @Override
        public String run() throws IOException {
            int attempt = attempts.incrementAndGet();
            if (attempt <= failures) {
                IOException e = failure.apply(attempt);
                last.set(e);
                throw e;
            }
            return "done after " + attempt;
        }
    }
}
