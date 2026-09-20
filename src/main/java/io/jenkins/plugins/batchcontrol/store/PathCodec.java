package io.jenkins.plugins.batchcontrol.store;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The single place where job full names are turned into store file names and where store file
 * names are validated. No other class may build file names from user-controlled input.
 *
 * <p>Encoding: UTF-8 percent-encoding. Only {@code [A-Za-z0-9_-]} pass through unchanged; every
 * other byte (including {@code / \ . %} and control characters) becomes {@code %XX}. The result is
 * a single printable path segment that round-trips losslessly via {@link #decode}. Names whose
 * encoding exceeds {@value #MAX_ENCODED_LENGTH} characters are shortened deterministically to
 * {@code <prefix>-<sha256 hex of the full name>}, which keeps two long names differing only at the
 * tail on different files (such shortened names no longer decode, but they stay stable).
 */
@Restricted(NoExternalUse.class)
public final class PathCodec {

    /** Leaves room for an extension within the usual 255-char file-name limit. */
    private static final int MAX_ENCODED_LENGTH = 250;
    /** Prefix kept when shortening: 180 + 1 ('-') + 64 (sha-256 hex) = 245 &lt;= 250. */
    private static final int SHORTENED_PREFIX_LENGTH = 180;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private PathCodec() {
    }

    private static boolean isSafe(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_';
    }

    /** Encodes a job full name into a safe, deterministic, collision-free file-name segment. */
    public static String encode(String jobFullName) {
        Objects.requireNonNull(jobFullName, "jobFullName");
        StringBuilder sb = new StringBuilder(jobFullName.length() + 16);
        for (byte b : jobFullName.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isSafe(c)) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >>> 4]).append(HEX[c & 0x0F]);
            }
        }
        String encoded = sb.toString();
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            String prefix = encoded.substring(0, SHORTENED_PREFIX_LENGTH);
            // Never cut through a %XX escape.
            int lastPercent = prefix.lastIndexOf('%');
            if (lastPercent > SHORTENED_PREFIX_LENGTH - 3) {
                prefix = prefix.substring(0, lastPercent);
            }
            encoded = prefix + "-" + sha256Hex(jobFullName);
        }
        return encoded;
    }

    /**
     * Decodes an encoded file-name segment back to the original job full name.
     *
     * @throws IllegalArgumentException if the input contains any character outside the encoding
     *         alphabet (in particular {@code / \ .}, so traversal strings are rejected) or a
     *         malformed {@code %XX} escape.
     */
    public static String decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        ByteArrayOutputStream out = new ByteArrayOutputStream(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%') {
                if (i + 2 >= encoded.length()) {
                    throw new IllegalArgumentException("Truncated percent escape in: " + encoded);
                }
                int hi = Character.digit(encoded.charAt(i + 1), 16);
                int lo = Character.digit(encoded.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("Malformed percent escape in: " + encoded);
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else if (isSafe(c)) {
                out.write(c);
            } else {
                throw new IllegalArgumentException(
                        "Illegal character 0x" + Integer.toHexString(c) + " in encoded name: " + encoded);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * Resolves {@code fileName} under {@code baseDir}, guaranteeing the result cannot escape.
     *
     * @throws IllegalArgumentException if the file name contains a path separator, is {@code .}
     *         or {@code ..}, or the resolved path leaves {@code baseDir}.
     */
    public static Path resolveUnder(Path baseDir, String fileName) {
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(fileName, "fileName");
        if (fileName.isEmpty() || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0
                || fileName.equals(".") || fileName.equals("..")) {
            throw new IllegalArgumentException("Unsafe file name: " + fileName);
        }
        Path base = baseDir.normalize();
        Path resolved = base.resolve(fileName).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new IllegalArgumentException("File name escapes the base directory: " + fileName);
        }
        return resolved;
    }

    private static String sha256Hex(String value) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JCA specification", e);
        }
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(HEX[(b >>> 4) & 0x0F]).append(HEX[b & 0x0F]);
        }
        return sb.toString();
    }
}
