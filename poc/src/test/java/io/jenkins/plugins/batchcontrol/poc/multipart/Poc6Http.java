package io.jenkins.plugins.batchcontrol.poc.multipart;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import net.sf.json.JSONObject;
import org.jvnet.hudson.test.JenkinsRule;

/** Raw HTTP for PoC-6: exact multipart bytes, optional chunking, crumb from the crumb issuer. */
final class Poc6Http {

    final JenkinsRule j;
    final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .cookieHandler(new CookieManager())
            .build();
    String basicUser;
    String crumbField;
    String crumb;

    Poc6Http(JenkinsRule j) {
        this.j = j;
    }

    Poc6Http as(String user) {
        this.basicUser = user;
        return this;
    }

    Poc6Http withCrumb() throws Exception {
        HttpResponse<String> r = client.send(builder("crumbIssuer/api/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JSONObject json = JSONObject.fromObject(r.body());
        crumbField = json.getString("crumbRequestField");
        crumb = json.getString("crumb");
        return this;
    }

    HttpRequest.Builder builder(String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(j.getURL() + path));
        if (basicUser != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (basicUser + ":" + basicUser).getBytes(StandardCharsets.UTF_8)));
        }
        return b;
    }

    enum CrumbIn { QUERY, HEADER, NONE }

    HttpResponse<String> post(String path, String contentType, byte[] body, boolean chunked, CrumbIn crumbIn)
            throws Exception {
        String p = path;
        if (crumbIn == CrumbIn.QUERY) {
            p += (p.contains("?") ? "&" : "?") + crumbField + "=" + crumb;
        }
        HttpRequest.Builder b = builder(p).header("Content-Type", contentType);
        if (crumbIn == CrumbIn.HEADER) {
            b.header(crumbField, crumb);
        }
        b.POST(chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                : HttpRequest.BodyPublishers.ofByteArray(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> submit(String job, Multipart body) throws Exception {
        return post("job/" + job + "/" + PocMultipartAction.URL_NAME + "/submit", body.contentType(), body.build(),
                false, CrumbIn.QUERY);
    }

    /** A multipart/form-data body shaped like a browser's: no charset in the part headers. */
    static final class Multipart {
        final String boundary = "----PoC6Boundary" + Long.toHexString(new Random(6).nextLong());
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Multipart field(String name, String value) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
            out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
            write("\r\n");
            return this;
        }

        Multipart file(String name, String fileName, byte[] content) {
            write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\""
                    + fileName + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
            out.writeBytes(content);
            write("\r\n");
            return this;
        }

        String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        byte[] build() {
            ByteArrayOutputStream copy = new ByteArrayOutputStream();
            copy.writeBytes(out.toByteArray());
            copy.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
            return copy.toByteArray();
        }

        /** The body without its closing delimiter: a truncated upload. */
        byte[] buildTruncated() {
            return out.toByteArray();
        }

        private void write(String s) {
            out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    static byte[] payload(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }
}
