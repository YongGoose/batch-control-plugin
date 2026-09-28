package io.jenkins.plugins.batchcontrol.store;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Reads a UTF-8 text file line by line from its end towards its start (#13). JSONL buckets are
 * appended in time order, so the newest records come first and a page query can stop after a
 * bounded number of lines without reading the rest of the month.
 *
 * <p>Splitting on the byte {@code 0x0A} is safe in UTF-8 (it never occurs inside a multi-byte
 * sequence); a trailing {@code \r} is stripped so files written with Windows line separators read
 * the same. Only one chunk plus the current line is held in memory.
 */
@Restricted(NoExternalUse.class)
final class ReverseLineReader implements Closeable {

    private static final int CHUNK_SIZE = 64 * 1024;

    private final SeekableByteChannel channel;
    private final byte[] chunk = new byte[CHUNK_SIZE];
    /** File offset of {@code chunk[0]}; everything before it is still unread. */
    private long position;
    /** End (exclusive) of the unread part of {@link #chunk}. */
    private int index;
    /** Bytes of the line being assembled that lie after {@link #chunk} in the file. */
    private byte[] carry = new byte[0];
    private boolean done;

    ReverseLineReader(Path file) throws IOException {
        this.channel = Files.newByteChannel(file, StandardOpenOption.READ);
        this.position = channel.size();
    }

    /** The previous line (without its separator), or {@code null} once the start is reached. */
    String readLine() throws IOException {
        while (true) {
            for (int i = index - 1; i >= 0; i--) {
                if (chunk[i] == '\n') {
                    String line = assemble(i + 1, index);
                    index = i;
                    return line;
                }
            }
            if (position == 0) {
                if (done) {
                    return null;
                }
                done = true;
                String line = assemble(0, index);
                index = 0;
                return line;
            }
            carry = concat(Arrays.copyOfRange(chunk, 0, index), carry);
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

    private String assemble(int from, int to) {
        byte[] bytes = concat(Arrays.copyOfRange(chunk, from, to), carry);
        carry = new byte[0];
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        if (tail.length == 0) {
            return head;
        }
        byte[] joined = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, joined, head.length, tail.length);
        return joined;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
