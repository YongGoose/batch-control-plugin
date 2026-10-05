package io.jenkins.plugins.batchcontrol;

import hudson.model.User;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import jenkins.security.ApiTokenProperty;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A minimal HTTP/1.1 client on a plain socket, for the two body-cap rows that need what HtmlUnit
 * cannot send (matrix note 265): a request that only declares a length without sending the body
 * (the exact default cap of 100 MB without moving 100 MB), and a body sent with
 * {@code Transfer-Encoding: chunked} (no declared length). The user authenticates with an API token
 * over basic authentication, which Jenkins exempts from the crumb check, so no session or crumb is
 * involved.
 *
 * <p>Written from docs/SPEC.md item 5 and docs/DECISIONS.md D-72a and D-72b (4) only (no src/main
 * knowledge).
 */
final class RawHttpFixtures {

    private RawHttpFixtures() {
        // utility class
    }

    /** A fresh API token of {@code userId} (the user is created if needed). */
    static String apiToken(String userId) throws IOException {
        User user = User.getById(userId, true);
        ApiTokenProperty property = user.getProperty(ApiTokenProperty.class);
        if (property == null) {
            property = new ApiTokenProperty();
            user.addProperty(property);
        }
        String token = property.generateNewToken("d72b-raw-http").plainValue;
        user.save();
        return token;
    }

    /** The {@code Authorization} header value for {@code userId} with {@code token}. */
    static String basic(String userId, String token) {
        return "Basic " + Base64.getEncoder().encodeToString((userId + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    /** One header line. */
    static String[] header(String name, String value) {
        return new String[] {name, value};
    }

    /**
     * POSTs to {@code relative} under {@code jenkins} and returns the status code of the answer.
     * With {@code chunked} the body is sent in chunks without a length; otherwise the caller's
     * headers must carry the {@code Content-Length} (which may declare more than {@code body} holds,
     * to probe a refusal decided from the declared length). Writing stops quietly if the server
     * closes early; the answer's status line is read with a 60-second limit.
     */
    static int post(URL jenkins, String relative, List<String[]> headers, byte[] body, boolean chunked) throws IOException {
        URL target = new URL(jenkins, relative);
        int port = target.getPort() == -1 ? 80 : target.getPort();
        try (Socket socket = new Socket(target.getHost(), port)) {
            socket.setSoTimeout(60_000);
            OutputStream out = socket.getOutputStream();
            StringBuilder head = new StringBuilder();
            head.append("POST ").append(target.getFile()).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(target.getHost()).append(':').append(port).append("\r\n");
            head.append("Connection: close\r\n");
            for (String[] h : headers) {
                head.append(h[0]).append(": ").append(h[1]).append("\r\n");
            }
            if (chunked) {
                head.append("Transfer-Encoding: chunked\r\n");
            }
            head.append("\r\n");
            try {
                out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
                if (chunked) {
                    for (int at = 0; at < body.length; at += 8192) {
                        int n = Math.min(8192, body.length - at);
                        out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        out.write(body, at, n);
                        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    }
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                } else {
                    out.write(body);
                }
                out.flush();
            } catch (IOException closedEarly) {
                // the server may answer and close before it reads the whole body; the status still counts
            }
            return status(socket.getInputStream(), relative);
        }
    }

    private static int status(InputStream in, String relative) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        try {
            int previous = -1;
            for (int b = in.read(); b != -1; b = in.read()) {
                if (previous == '\r' && b == '\n') {
                    break;
                }
                if (previous != -1) {
                    line.write(previous);
                }
                previous = b;
            }
        } catch (SocketTimeoutException timeout) {
            fail("no answer to the POST to " + relative + " within 60 seconds (did the server wait for a body it was never sent?)");
        }
        String text = line.toString(StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("HTTP/1."), "fixture: an HTTP status line was expected from " + relative + ", got '" + text + "'");
        return Integer.parseInt(text.split(" ")[1]);
    }

    /** One part of a {@code multipart/form-data} body: a field ({@code fileName} null) or a file. */
    static Object[] part(String name, String fileName, byte[] content) {
        return new Object[] {name, fileName, content};
    }

    /** A {@code multipart/form-data} body of {@code parts} with {@code boundary}. */
    static byte[] multipart(String boundary, List<Object[]> parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object[] p : parts) {
            StringBuilder h = new StringBuilder("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(p[0]).append('"');
            if (p[1] != null) {
                h.append("; filename=\"").append(p[1]).append("\"\r\nContent-Type: application/octet-stream");
            }
            h.append("\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.UTF_8));
            out.write((byte[]) p[2]);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** A mutable header list. */
    static List<String[]> headers(String[]... lines) {
        List<String[]> out = new ArrayList<>();
        for (String[] l : lines) {
            out.add(l);
        }
        return out;
    }
}
