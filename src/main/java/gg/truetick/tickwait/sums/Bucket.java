package gg.truetick.tickwait.sums;

import gg.truetick.tickwait.measure.Attribution;
import gg.truetick.tickwait.measure.SecondSample;
import java.util.HashMap;
import java.util.Map;

/**
 * Sums of one-second samples over a span (a minute, an hour, an hour of day, a whole report).
 * Each metric carries the count of seconds (or ticks) it was actually measured in, so a host
 * that doesn't expose something reads as "not measured", not as zero.
 */
public final class Bucket {
    /** Column order of the on-disk CSV and the uploaded arrays; append only once released. */
    public static final String[] FIELDS = {
        "seconds", "wallUs",
        "schedSeconds", "schedWallUs", "schedTicks", "busyUs", "waitUs",
        "capUs", "selfUs", "outsideUs", "unattrUs", "maxWaitUs",
        "throttledSeconds", "throttledUs",
        "otherSeconds", "otherUs", "foreignSeconds", "foreignUs",
        "stealJ", "totalJ",
        "ticks", "paperTicks", "tickUs", "tickSchedTicks", "tickWorkUs", "tickWaitUs",
        "playersSum", "playersMax",
    };
    private static final int MAX_WAIT = indexOf("maxWaitUs");
    private static final int PLAYERS_MAX = indexOf("playersMax");

    /** Epoch minute, epoch hour or hour of day (0–23), depending on what this bucket groups. */
    public long key;
    /** Field values in {@link #FIELDS} order. */
    private final long[] v = new long[FIELDS.length];

    public Bucket(long key) {
        this.key = key;
    }

    private static int indexOf(String name) {
        for (int i = 0; i < FIELDS.length; i++) {
            if (FIELDS[i].equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException(name);
    }

    /** Value of a field by name (tests and readers). */
    public long get(String name) {
        return v[indexOf(name)];
    }

    public void set(String name, long value) {
        v[indexOf(name)] = value;
    }

    // Named accessors for the fields read most.
    public long seconds() { return v[0]; }
    public long wallUs() { return v[1]; }
    public long schedSeconds() { return v[2]; }
    public long schedWallUs() { return v[3]; }
    public long schedTicks() { return v[4]; }
    public long busyUs() { return v[5]; }
    public long waitUs() { return v[6]; }
    public long capUs() { return v[7]; }
    public long selfUs() { return v[8]; }
    public long outsideUs() { return v[9]; }
    public long unattrUs() { return v[10]; }
    public long maxWaitUs() { return v[11]; }
    public long throttledSeconds() { return v[12]; }
    public long throttledUs() { return v[13]; }
    public long otherSeconds() { return v[14]; }
    public long otherUs() { return v[15]; }
    public long foreignSeconds() { return v[16]; }
    public long foreignUs() { return v[17]; }
    public long stealJ() { return v[18]; }
    public long totalJ() { return v[19]; }
    public long ticks() { return v[20]; }
    public long paperTicks() { return v[21]; }
    public long tickUs() { return v[22]; }
    public long tickSchedTicks() { return v[23]; }
    public long tickWorkUs() { return v[24]; }
    public long tickWaitUs() { return v[25]; }
    public long playersSum() { return v[26]; }
    public long playersMax() { return v[27]; }

    private static long us(double ms) {
        return Math.round(ms * 1000.0);
    }

    public void add(SecondSample s) {
        long wall = us(s.wallMs);
        v[0]++;
        v[1] += wall;
        v[20] += s.ticks;
        if (!Double.isNaN(s.busyMs)) {
            v[2]++;
            v[3] += wall;
            v[4] += s.ticks;
            v[5] += us(s.busyMs);
            long w = us(s.waitMs);
            v[6] += w;
            v[7] += us(s.split[Attribution.CAP]);
            v[8] += us(s.split[Attribution.SELF]);
            v[9] += us(s.split[Attribution.OUTSIDE]);
            v[10] += us(s.split[Attribution.UNATTRIBUTED]);
            v[11] = Math.max(v[11], w);
        }
        if (!Double.isNaN(s.throttledMs)) {
            v[12]++;
            v[13] += us(s.throttledMs);
        }
        if (!Double.isNaN(s.otherThreadsMs)) {
            v[14]++;
            v[15] += us(s.otherThreadsMs);
        }
        if (!Double.isNaN(s.foreignMs)) {
            v[16]++;
            v[17] += us(s.foreignMs);
        }
        if (s.totalJiffies > 0) {
            v[18] += s.stealJiffies;
            v[19] += s.totalJiffies;
        }
        if (!Double.isNaN(s.tickMs)) {
            v[21] += s.paperTicks;
            v[22] += us(s.tickMs);
        }
        if (!Double.isNaN(s.tickWorkMs)) {
            v[23] += s.tickSchedTicks;
            v[24] += us(s.tickWorkMs);
            v[25] += us(s.tickWaitMs);
        }
        v[26] += s.players;
        v[27] = Math.max(v[27], s.players);
    }

    public void merge(Bucket o) {
        for (int i = 0; i < v.length; i++) {
            v[i] = i == MAX_WAIT || i == PLAYERS_MAX ? Math.max(v[i], o.v[i]) : v[i] + o.v[i];
        }
    }

    public long[] values() {
        return v.clone();
    }

    /** CSV line: key first, then {@link #FIELDS} in order. */
    public String toCsv() {
        StringBuilder sb = new StringBuilder().append(key);
        for (long x : v) {
            sb.append(',').append(x);
        }
        return sb.toString();
    }

    public static String csvHeader() {
        return "minute," + String.join(",", FIELDS);
    }

    /**
     * Parses a CSV line using the file's own header, so a file written by another version still
     * loads (unknown columns ignored, missing ones read as 0). Returns null for a malformed line.
     */
    public static Bucket fromCsv(String line, String[] header) {
        String[] cells = line.trim().split(",");
        if (cells.length != header.length || cells.length < 2) {
            return null;
        }
        Map<String, Long> byName = new HashMap<>();
        long key;
        try {
            key = Long.parseLong(cells[0]);
            for (int i = 1; i < cells.length; i++) {
                long x = Long.parseLong(cells[i]);
                if (x < 0) {
                    return null;
                }
                byName.put(header[i].trim(), x);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        Bucket b = new Bucket(key);
        for (int i = 0; i < FIELDS.length; i++) {
            Long x = byName.get(FIELDS[i]);
            b.v[i] = x == null ? 0 : x;
        }
        return b;
    }
}
