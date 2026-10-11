package gg.truetick.tickwait.sums;

import gg.truetick.tickwait.measure.SecondSample;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-minute buckets kept in memory and appended to a CSV file, so a report can cover days
 * of play across restarts. Thread-safe: the sampler adds, commands read.
 *
 * <p>Minutes are keyed by wall-clock minute. A clock that steps back would revisit time already
 * recorded: those samples stay out of the minutes until the clock passes the latest time seen, so
 * no minute ever holds more than a minute of samples (a restart within the same minute merges
 * into it). The file is append-only between compactions —
 * at most once a day it is rewritten without the expired minutes — so a week of data doesn't
 * cost a full rewrite every minute.
 */
public final class MinuteStore {
    private final Path file;
    private final int keepDays;
    private final TreeMap<Long, Bucket> minutes = new TreeMap<>();
    private final Deque<SecondSample> recent = new ArrayDeque<>();
    private Bucket current;
    private long lastCompaction;
    /** Latest sample time counted into the minutes, epoch ms; earlier samples are time revisited. */
    private long highWaterMillis = Long.MIN_VALUE;

    public MinuteStore(Path file, int keepDays) {
        this.file = file;
        this.keepDays = Math.max(1, Math.min(7, keepDays));
    }

    /** Loads the file, merging repeated minutes and dropping malformed or expired ones. */
    public synchronized int load(long nowEpochMinute) throws IOException {
        minutes.clear();
        if (Files.exists(file)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (!lines.isEmpty()) {
                String[] header = lines.get(0).trim().split(",");
                long oldest = nowEpochMinute - keepDays * 1440L;
                for (int i = 1; i < lines.size(); i++) {
                    Bucket b = Bucket.fromCsv(lines.get(i), header);
                    if (b != null && b.key > oldest && b.key <= nowEpochMinute) {
                        mergeIn(b);
                    }
                }
            }
        }
        compact(nowEpochMinute);
        // From the start of the last stored minute: a restart within it merges, earlier time doesn't.
        highWaterMillis = minutes.isEmpty() ? Long.MIN_VALUE : minutes.lastKey() * 60_000L;
        return minutes.size();
    }

    private void mergeIn(Bucket b) {
        Bucket existing = minutes.get(b.key);
        if (existing == null) {
            minutes.put(b.key, b);
        } else {
            existing.merge(b);
        }
    }

    private void compact(long nowMinute) throws IOException {
        List<String> out = new ArrayList<>();
        out.add(Bucket.csvHeader());
        for (Bucket b : minutes.values()) {
            out.add(b.toCsv());
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, out, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        lastCompaction = nowMinute;
    }

    /**
     * Adds one sample; when the wall-clock minute changes, the finished minute is persisted. A
     * sample from time already recorded (the clock stepped back) still counts for the last 60
     * seconds but not for the minutes.
     */
    public synchronized void add(SecondSample s) throws IOException {
        recent.addLast(s);
        while (recent.size() > 60) {
            recent.removeFirst();
        }
        if (s.epochMillis <= highWaterMillis) {
            return;
        }
        highWaterMillis = s.epochMillis;
        long minute = Math.floorDiv(s.epochMillis, 60_000L);
        if (current != null && current.key != minute) {
            flushLocked();
        }
        if (current == null) {
            current = new Bucket(minute);
        }
        current.add(s);
    }

    /** Persists the minute in progress (on shutdown). */
    public synchronized void flush() throws IOException {
        flushLocked();
    }

    private void flushLocked() throws IOException {
        Bucket done = current;
        current = null;
        if (done == null || done.seconds() == 0) {
            return;
        }
        Bucket copy = new Bucket(done.key);
        copy.merge(done);
        mergeIn(copy);
        long expired = done.key - keepDays * 1440L;
        minutes.headMap(expired, true).clear();
        if (!Files.exists(file) || done.key - lastCompaction >= 1440) {
            compact(done.key);
        } else {
            Files.write(file, Collections.singletonList(done.toCsv()), StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        }
    }

    /** Copies of the finished minutes plus the one in progress, oldest first, one per minute. */
    public synchronized List<Bucket> minutes() {
        TreeMap<Long, Bucket> out = new TreeMap<>();
        for (Map.Entry<Long, Bucket> e : minutes.entrySet()) {
            Bucket c = new Bucket(e.getKey());
            c.merge(e.getValue());
            out.put(e.getKey(), c);
        }
        if (current != null && current.seconds() > 0) {
            Bucket c = out.get(current.key);
            if (c == null) {
                c = new Bucket(current.key);
                out.put(current.key, c);
            }
            c.merge(current);
        }
        return new ArrayList<>(out.values());
    }

    /** The last (up to) 60 one-second samples. */
    public synchronized List<SecondSample> recentSeconds() {
        return new ArrayList<>(recent);
    }

    public synchronized void clear() throws IOException {
        minutes.clear();
        recent.clear();
        current = null;
        highWaterMillis = Long.MIN_VALUE;
        compact(Math.floorDiv(System.currentTimeMillis(), 60_000L));
    }
}
