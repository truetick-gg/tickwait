package gg.truetick.tickwait.share;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Blocking HTTP calls to the report API. Run off the main thread. */
public final class Uploader {
    public static final class Response {
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private final String userAgent;

    public Uploader(String pluginVersion) {
        this.userAgent = "TickWait/" + pluginVersion + " (+https://truetick.gg/tickwait)";
    }

    public Response post(String url, String json) throws IOException {
        HttpURLConnection c = open(url, "POST");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(body);
        }
        return read(c);
    }

    public Response delete(String url, String deleteToken) throws IOException {
        HttpURLConnection c = open(url, "DELETE");
        c.setRequestProperty("X-Delete-Token", deleteToken);
        return read(c);
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        if (!url.startsWith("https://") && !url.startsWith("http://localhost") && !url.startsWith("http://127.0.0.1")) {
            throw new IOException("refusing a non-HTTPS upload URL: " + url);
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", userAgent);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static Response read(HttpURLConnection c) throws IOException {
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = "";
        if (in != null) {
            try (InputStream s = in) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = s.read(chunk)) > 0 && buf.size() < 64 * 1024) {
                    buf.write(chunk, 0, n);
                }
                body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        c.disconnect();
        return new Response(status, body);
    }
}
