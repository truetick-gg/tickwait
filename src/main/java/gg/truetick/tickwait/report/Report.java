package gg.truetick.tickwait.report;

import gg.truetick.tickwait.sums.Bucket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A report over the stored minutes: totals, a profile by hour of day (UTC) and an hourly timeline. */
public final class Report {
    public static final int SCHEMA = 1;
    public static final int MAX_TIMELINE_HOURS = 168;

    public final Bucket totals;
    /** Minutes in which at least one player was online. */
    public final Bucket withPlayers;
    /** Hour of day (UTC, 0–23) → bucket; only hours that have data. */
    public final List<Bucket> hourOfDay;
    /** Epoch hour → bucket, oldest first, at most {@link #MAX_TIMELINE_HOURS}. */
    public final List<Bucket> timeline;
    public final long firstMinute;
    public final long lastMinute;
    public final int minutes;

    private Report(Bucket totals, Bucket withPlayers, List<Bucket> hourOfDay, List<Bucket> timeline,
            long firstMinute, long lastMinute, int minutes) {
        this.totals = totals;
        this.withPlayers = withPlayers;
        this.hourOfDay = hourOfDay;
        this.timeline = timeline;
        this.firstMinute = firstMinute;
        this.lastMinute = lastMinute;
        this.minutes = minutes;
    }

    public static Report build(List<Bucket> minuteList) {
        // One bucket per minute even if the caller repeated one (a clock that stepped back):
        // the API refuses a report with more minutes than its window spans.
        Map<Long, Bucket> byMinute = new TreeMap<>();
        for (Bucket m : minuteList) {
            byMinute.computeIfAbsent(m.key, Bucket::new).merge(m);
        }
        List<Bucket> minuteBuckets = new ArrayList<>(byMinute.values());
        Bucket totals = new Bucket(0);
        Bucket withPlayers = new Bucket(0);
        Map<Long, Bucket> byHourOfDay = new TreeMap<>();
        Map<Long, Bucket> byHour = new TreeMap<>();
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (Bucket m : minuteBuckets) {
            totals.merge(m);
            if (m.playersMax() > 0) {
                withPlayers.merge(m);
            }
            long epochHour = Math.floorDiv(m.key, 60L);
            long hod = Math.floorMod(epochHour, 24L);
            byHourOfDay.computeIfAbsent(hod, Bucket::new).merge(m);
            byHour.computeIfAbsent(epochHour, Bucket::new).merge(m);
            first = Math.min(first, m.key);
            last = Math.max(last, m.key);
        }
        List<Bucket> timeline = new ArrayList<>(byHour.values());
        if (timeline.size() > MAX_TIMELINE_HOURS) {
            timeline = new ArrayList<>(timeline.subList(timeline.size() - MAX_TIMELINE_HOURS, timeline.size()));
        }
        if (minuteBuckets.isEmpty()) {
            first = 0;
            last = 0;
        }
        return new Report(totals, withPlayers, new ArrayList<>(byHourOfDay.values()),
                Collections.unmodifiableList(timeline), first, last, minuteBuckets.size());
    }

    /**
     * The hour of day with the most waiting per second, among hours with at least {@code
     * minMinutes} measured minutes, or null. Same rule as the report page (web/lib/tickwait.ts).
     */
    public Bucket worstHourOfDay(int minMinutes) {
        Bucket worst = null;
        double worstWait = -1;
        for (Bucket b : hourOfDay) {
            if (b.schedSeconds() < minMinutes * 60L) {
                continue;
            }
            double w = new Derived(b).waitPerSecondMs;
            if (!Double.isNaN(w) && w > worstWait) {
                worstWait = w;
                worst = b;
            }
        }
        return worst;
    }

    /** The JSON uploaded by /tickwait share. Labels are optional (null = not given). */
    public String toJson(String pluginVersion, Environment env, String hostLabel, String planLabel) {
        Json j = new Json().beginObject();
        j.name("schema").value(SCHEMA);
        j.name("tool").beginObject().name("name").value("TickWait").name("version").value(pluginVersion).endObject();
        j.name("labels").beginObject().name("host").value(hostLabel).name("plan").value(planLabel).endObject();
        j.name("env");
        env.write(j);
        j.name("window").beginObject()
                .name("firstMinute").value(firstMinute)
                .name("lastMinute").value(lastMinute)
                .name("minutes").value(minutes)
                .endObject();
        j.name("fields").beginArray();
        for (String f : Bucket.FIELDS) {
            j.value(f);
        }
        j.endArray();
        j.name("totals");
        writeValues(j, totals, false);
        j.name("withPlayers");
        writeValues(j, withPlayers, false);
        j.name("hourOfDay").beginArray();
        for (Bucket b : hourOfDay) {
            writeValues(j, b, true);
        }
        j.endArray();
        j.name("timeline").beginArray();
        for (Bucket b : timeline) {
            writeValues(j, b, true);
        }
        j.endArray();
        return j.endObject().toString();
    }

    private static void writeValues(Json j, Bucket b, boolean withKey) {
        j.beginArray();
        if (withKey) {
            j.value(b.key);
        }
        for (long v : b.values()) {
            j.value(v);
        }
        j.endArray();
    }
}
