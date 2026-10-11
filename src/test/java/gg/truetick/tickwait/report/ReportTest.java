package gg.truetick.tickwait.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.truetick.tickwait.sums.Bucket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportTest {
    private static Bucket minute(long epochMinute, long waitUs, long players) {
        Bucket b = new Bucket(epochMinute);
        b.set("seconds", 60);
        b.set("wallUs", 60_000_000L);
        b.set("schedSeconds", 60);
        b.set("schedWallUs", 60_000_000L);
        b.set("schedTicks", 1200);
        b.set("ticks", 1200);
        b.set("busyUs", 1200 * 20_000L);
        b.set("waitUs", waitUs);
        b.set("outsideUs", waitUs);
        b.set("playersSum", players * 60);
        b.set("playersMax", players);
        return b;
    }

    @Test
    void groupsByHourOfDayAndTimeline() {
        List<Bucket> mins = new ArrayList<>();
        long base = 29_000_160L; // on an hour boundary: 29_000_160 / 60 = 483_336
        for (int i = 0; i < 180; i++) {
            mins.add(minute(base + i, i < 60 ? 1_000_000L : 6_000_000L, i % 2));
        }
        Report r = Report.build(mins);
        assertEquals(180, r.minutes);
        assertEquals(3, r.timeline.size());
        assertEquals(3, r.hourOfDay.size());
        long firstHod = Math.floorMod(Math.floorDiv(base, 60L), 24L);
        assertEquals(firstHod, r.hourOfDay.get(0).key);
        assertEquals(90 * 60, r.withPlayers.seconds());
        Bucket worst = r.worstHourOfDay(30);
        assertTrue(worst.key != firstHod); // the later hours waited more
        assertNull(r.worstHourOfDay(61)); // no hour has more than 60 measured minutes
    }

    @Test
    void jsonCarriesFieldsAndNullsNotNaN() {
        List<Bucket> mins = new ArrayList<>();
        mins.add(minute(29_000_000L, 2_000_000L, 4));
        Environment env = new Environment("Linux amd64", "6.8.0-134-generic", "21.0.5 (Eclipse Adoptium)", "Paper",
                "git-Paper-1 (MC: 1.21.4)", "1.21.4-R0.1-SNAPSHOT", "AMD \"Ryzen\" 9", "v2", 4, Double.NaN, true, true,
                true, false);
        String json = Report.build(mins).toJson("0.1.0", env, "Some Host", null);
        assertTrue(json.startsWith("{\"schema\":1,"));
        assertTrue(json.contains("\"labels\":{\"host\":\"Some Host\",\"plan\":null}"));
        assertTrue(json.contains("\"limitCores\":null"));
        assertTrue(json.contains("\"cpuModel\":\"AMD \\\"Ryzen\\\" 9\""));
        assertTrue(json.contains("\"fields\":[\"seconds\",\"wallUs\","));
        assertTrue(json.contains("\"hourOfDay\":[["));
        assertFalse(json.contains("NaN"));
    }

    @Test
    void jsonStringFieldReadsOurResponse() {
        String body = "{\"id\":\"k3x9p2\",\"url\":\"https:\\/\\/truetick.gg\\/tickwait\\/r\\/k3x9p2\",\"deleteToken\":\"a\\u0062c\"}";
        assertEquals("k3x9p2", Json.stringField(body, "id"));
        assertEquals("https://truetick.gg/tickwait/r/k3x9p2", Json.stringField(body, "url"));
        assertEquals("abc", Json.stringField(body, "deleteToken"));
        assertNull(Json.stringField(body, "error"));
        assertNull(Json.stringField(null, "id"));
    }

    @Test
    void chatTextNeverPrintsNaN() {
        Bucket empty = new Bucket(0);
        for (boolean sched : new boolean[] {true, false}) {
            for (String line : ChatText.describe(empty, sched)) {
                assertFalse(line.contains("NaN"), line);
            }
        }
    }
}
