package io.jenkins.plugins.batchcontrol.store;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Retries a replace (the atomic move of a temporary file over its target) or a delete of a store
 * file that Windows refused because another handle has the file open.
 *
 * <p>On Windows a file cannot be replaced or deleted while any handle opened it without
 * {@code FILE_SHARE_DELETE} ({@code java.io.FileInputStream}, {@code FileReader} and
 * {@code RandomAccessFile} open that way; so do virus scanners, indexers and backup agents). On a
 * file system without POSIX rename semantics (a container's mapped volume, for one) even a handle
 * that shares deletion blocks a replace. Every reader of this store opens through
 * {@code java.nio.file}, which shares deletion, and holds the file only for the read, so such a
 * refusal is transient: the operation is tried again after a short pause, for about two seconds in
 * all ({@link #PAUSES_MS}), before the refusal is given up to the caller.
 *
 * <p>Retried: {@link AccessDeniedException} (what the replace of an open file reports) and a plain
 * {@link FileSystemException} (a sharing or lock violation, which a delete of an open file reports;
 * its reason is the system's localised message, so it is recognised by its type, not its text).
 * Never retried: any other subclass (no such file, already exists, atomic move not supported, ...),
 * which answers the same however often it is asked. Only on Windows: on other systems an open
 * handle never blocks a rename or a delete, so a refusal there is a genuine permission or file
 * system error and is reported at once, exactly as before.
 *
 * <p>The pauses are a fixed bounded sequence; no clock is read. An interrupt ends the retries at
 * once (the interrupt is kept) and the last refusal is thrown.
 */
@Restricted(NoExternalUse.class)
final class SharingRetry {

    private static final Logger LOGGER = Logger.getLogger(SharingRetry.class.getName());

    /**
     * The pauses before the second and later attempts, in milliseconds: 2,060 ms in all, so a
     * handle held open for well over a second is outlasted. Short at first, because a reader of
     * this store holds a file for milliseconds.
     */
    private static final long[] PAUSES_MS = {10, 20, 40, 80, 160, 250, 250, 250, 250, 250, 250, 250};

    /** Whether this JVM runs on Windows, the one system where an open handle blocks a replace or delete. */
    private static final boolean WINDOWS = File.separatorChar == '\\';

    /** A file operation that may be refused with an {@link IOException}. */
    @FunctionalInterface
    interface FileOperation<T> {
        T run() throws IOException;
    }

    private SharingRetry() {
    }

    /**
     * Runs {@code operation} on {@code file}; on Windows a transient refusal is retried for about
     * two seconds before it is thrown.
     */
    static <T> T run(Path file, FileOperation<T> operation) throws IOException {
        return run(file, operation, WINDOWS);
    }

    /** {@link #run(Path, FileOperation)} as on Windows ({@code windows}) or on any other system. */
    static <T> T run(Path file, FileOperation<T> operation, boolean windows) throws IOException {
        int attempt = 0;
        while (true) {
            try {
                return operation.run();
            } catch (FileSystemException e) {
                if (!windows || !refusedWhileOpen(e) || attempt >= PAUSES_MS.length) {
                    throw e;
                }
                long pause = PAUSES_MS[attempt++];
                int failed = attempt;
                LOGGER.log(Level.FINE, e, () -> "Attempt " + failed + " on " + file + " was refused ("
                        + e.getClass().getSimpleName() + "), probably while another handle had it open;"
                        + " trying again in " + pause + " ms");
                if (!pause(pause)) {
                    throw e;
                }
            }
        }
    }

    /** Whether {@code e} may be a refusal that lasts only while another handle has the file open. */
    static boolean refusedWhileOpen(FileSystemException e) {
        return e instanceof AccessDeniedException || e.getClass() == FileSystemException.class;
    }

    /** Sleeps {@code millis}; {@code false}, with the interrupt kept, when interrupted. */
    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
