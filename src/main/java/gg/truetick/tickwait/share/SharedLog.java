package gg.truetick.tickwait.share;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reports this server has shared, with the token that deletes each one. Kept in the plugin
 * folder only — the token is shown nowhere else, so whoever controls the server controls the report.
 */
public final class SharedLog {
    public static final class Entry {
        public final String id;
        public final String url;
        public final String deleteToken;
        public final long epochMillis;
        /** The upload URL it was shared to (null in lines written before it was recorded). */
        public final String apiUrl;

        Entry(String id, String url, String deleteToken, long epochMillis, String apiUrl) {
            this.id = id;
            this.url = url;
            this.deleteToken = deleteToken;
            this.epochMillis = epochMillis;
            this.apiUrl = apiUrl;
        }

        String line() {
            return id + "\t" + url + "\t" + deleteToken + "\t" + epochMillis + (apiUrl == null ? "" : "\t" + apiUrl);
        }
    }

    private final Path file;

    public SharedLog(Path file) {
        this.file = file;
    }

    public synchronized void add(String id, String url, String deleteToken, long epochMillis, String apiUrl)
            throws IOException {
        Files.createDirectories(file.getParent());
        String line = new Entry(clean(id), clean(url), clean(deleteToken), epochMillis, clean(apiUrl)).line();
        Files.write(file, Collections.singletonList(line), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized List<Entry> entries() throws IOException {
        List<Entry> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t");
            if (f.length != 4 && f.length != 5) {
                continue;
            }
            try {
                out.add(new Entry(f[0], f[1], f[2], Long.parseLong(f[3]), f.length == 5 ? f[4] : null));
            } catch (NumberFormatException ignored) {
                // skip a damaged line
            }
        }
        return out;
    }

    public synchronized Entry find(String id) throws IOException {
        for (Entry e : entries()) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        return null;
    }

    public synchronized void remove(String id) throws IOException {
        List<String> keep = new ArrayList<>();
        for (Entry e : entries()) {
            if (!e.id.equals(id)) {
                keep.add(e.line());
            }
        }
        Files.write(file, keep, StandardCharsets.UTF_8);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
