package io.jenkins.plugins.batchcontrol.store;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Reads a UTF-8 text file line by line from its end towards its start. JSONL buckets are
 * appended in time order, so the newest records come first and a page query can stop after a
 * bounded number of lines without reading the rest of the month.
 *
 * <p>Allocation does not grow with the file: one fixed read buffer, plus one reusable line buffer
 * that only grows to the longest line that crosses a chunk boundary. A line is exposed as a byte
 * range ({@link #buffer()}, {@link #offset()}, {@link #length()}) valid until the next
 * {@link #next()}; no {@code String} is created for it. Splitting on {@code 0x0A} is safe in UTF-8
 * (it never occurs inside a multi-byte sequence); a trailing {@code \r} is excluded.
 */
@Restricted(NoExternalUse.class)
final class ReverseLineReader implements Closeable {

    private static final int CHUNK_SIZE = 64 * 1024;

    /** Longest line exposed (security-10 S-04); longer lines are skipped and counted. */
    static final int MAX_LINE_BYTES = 1024 * 1024;

    private final SeekableByteChannel channel;
    private final byte[] chunk = new byte[CHUNK_SIZE];
    /** File offset of {@code chunk[0]}; everything before it is still unread. */
    private long position;
    /** End (exclusive) of the unread part of {@link #chunk}. */
    private int index;
    /**
     * Bytes of the line being assembled that lie after {@link #chunk} in the file, stored at the
     * end of {@code carry}: {@code carry[carryStart, carry.length)}.
     */
    private byte[] carry = new byte[256];
    private int carryStart = carry.length;
    private boolean done;
    /** Whether the line being assembled has exceeded {@link #MAX_LINE_BYTES}. */
    private boolean discarding;
    private int oversized;

    private byte[] lineBuffer;
    private int lineOffset;
    private int lineLength;

    ReverseLineReader(Path file) throws IOException {
        this.channel = Files.newByteChannel(file, StandardOpenOption.READ);
        this.position = channel.size();
    }

    /** Moves to the previous line; {@code false} once the start of the file is passed. */
    boolean next() throws IOException {
        while (true) {
            for (int i = index - 1; i >= 0; i--) {
                if (chunk[i] == '\n') {
                    boolean exposed = expose(i + 1, index);
                    index = i;
                    if (exposed) {
                        return true;
                    }
                    i = index;
                }
            }
            if (position == 0) {
                if (done) {
                    return false;
                }
                done = true;
                boolean exposed = expose(0, index);
                index = 0;
                return exposed;
            }
            prependToCarry(0, index);
            int n = (int) Math.min(CHUNK_SIZE, position);
            position -= n;
            channel.position(position);
            ByteBuffer buffer = ByteBuffer.wrap(chunk, 0, n);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("File shrank while being read");
                }
            }
            index = n;
        }
    }

    byte[] buffer() {
        return lineBuffer;
    }

    int offset() {
        return lineOffset;
    }

    int length() {
        return lineLength;
    }

    /** Whether the current line holds only whitespace. */
    boolean isBlank() {
        for (int i = lineOffset; i < lineOffset + lineLength; i++) {
            byte b = lineBuffer[i];
            if (b != ' ' && b != '\t' && b != '\r') {
                return false;
            }
        }
        return true;
    }

    /** Lines longer than {@link #MAX_LINE_BYTES} skipped so far. */
    int oversized() {
        return oversized;
    }

    /** Exposes the line; {@code false} if it was too long and has been skipped instead. */
    private boolean expose(int from, int to) {
        if (carryStart == carry.length && !discarding) {
            if (to - from > MAX_LINE_BYTES) {
                oversized++;
                return false;
            }
            lineBuffer = chunk;
            lineOffset = from;
            lineLength = to - from;
        } else {
            prependToCarry(from, to);
            if (discarding) {
                discarding = false;
                carryStart = carry.length;
                oversized++;
                return false;
            }
            lineBuffer = carry;
            lineOffset = carryStart;
            lineLength = carry.length - carryStart;
            // The next line starts afresh; the bytes stay valid until the next prepend.
            carryStart = carry.length;
        }
        if (lineLength > 0 && lineBuffer[lineOffset + lineLength - 1] == '\r') {
            lineLength--;
        }
        return true;
    }

    private void prependToCarry(int from, int to) {
        int n = to - from;
        if (n == 0 || discarding) {
            return;
        }
        if ((long) carry.length - carryStart + n > MAX_LINE_BYTES) {
            // Drop what was assembled and ignore the rest of this line up to its start.
            discarding = true;
            carryStart = carry.length;
            return;
        }
        if (carryStart < n) {
            int used = carry.length - carryStart;
            byte[] grown = new byte[Math.max(carry.length * 2, used + n)];
            System.arraycopy(carry, carryStart, grown, grown.length - used, used);
            carry = grown;
            carryStart = grown.length - used;
        }
        carryStart -= n;
        System.arraycopy(chunk, from, carry, carryStart, n);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
