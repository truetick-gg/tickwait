package gg.truetick.tickwait.sums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.truetick.tickwait.measure.Attribution;
import gg.truetick.tickwait.measure.SecondSample;
import gg.truetick.tickwait.measure.Snapshot;
import gg.truetick.tickwait.report.Derived;
import org.junit.jupiter.api.Test;

class BucketTest {
    /**
     * Cumulative counters at second t of a server that, every second, runs 20 ticks of 40 ms
     * (30 ms work + 6 ms waiting + 4 ms other), waits another 4 ms between ticks, and whose other
     * threads use 100 ms of CPU; its cores are busy 1000 ms in all, so 300 ms is other work.
     * It is never throttled (but throttling is readable). The VM loses 0.25% to steal.
     */
    static Snapshot at(long t) {
        long ms = 1_000_000L;
        return new Snapshot(t * 1000, t * 1_000_000_000L,
                600 * ms * t, (120 + 4) * ms * t, // main thread: work 600, wait 124 (120 in ticks + 4 between)
                0, 0, 100_000,                     // throttled µs, throttled periods, period µs
                (600 + 100) / 10 * t,              // process CPU in 10 ms ticks
                10 * t, 4000 * t,                  // steal, total jiffies
                20 * t, 20 * t, 800 * ms * t,      // ticks, Paper ticks, tick wall time
                20 * t, 600 * ms * t, 120 * ms * t, // split ticks, work and wait inside ticks
                3, 1000 / 10 * t);                 // players; busy jiffies of the allowed cores
    }

    static SecondSample second(long t) {
        return SecondSample.between(at(t), at(t + 1));
    }

    @Test
    void bothViewsAddUp() {
        Bucket b = new Bucket(0);
        for (int i = 0; i < 60; i++) {
            b.add(second(1000 + i));
        }
        Derived d = new Derived(b);
        assertEquals(20.0, d.tps, 1e-6);
        // per second: worked + waited + idle = 1000 ms
        assertEquals(600.0, d.busyPerSecondMs, 1e-6);
        assertEquals(124.0, d.waitPerSecondMs, 1e-6);
        assertEquals(276.0, d.idlePerSecondMs, 1e-6);
        assertEquals(124.0 / 724, d.waitShare, 1e-9);
        // per tick: 40 = 30 work + 6 wait + 4 other
        assertEquals(40.0, d.tickMs, 1e-6);
        assertEquals(30.0, d.tickWorkMs, 1e-6);
        assertEquals(6.0, d.tickWaitMs, 1e-6);
        assertEquals(4.0, d.tickOtherMs, 1e-6);
        assertEquals(4.0, d.betweenTickWaitPerSecondMs, 1e-6);
        // never throttled: the plan's share is a measured 0; the rest is shared 100 : 300
        assertEquals(0.0, d.capShare, 1e-9);
        assertEquals(0.25, d.selfShare, 1e-9);
        assertEquals(0.75, d.outsideShare, 1e-9);
        assertEquals(0.1, d.otherThreadsCores, 1e-9);
        assertEquals(0.3, d.foreignCores, 1e-9);
        assertEquals(0.25, d.stealPct, 1e-9);
        assertEquals(3.0, d.avgPlayers, 1e-9);
    }

    @Test
    void unreadableThrottlingLeavesTheWaitUnattributed() {
        long ms = 1_000_000L;
        Snapshot a = new Snapshot(0, 0, 600 * ms, 124 * ms, -1, -1, -1, 70, 0, 0, 20, 0, -1, 0, -1, -1, 0, 100);
        Snapshot b = new Snapshot(1000, 1_000_000_000L, 1200 * ms, 248 * ms, -1, -1, -1, 140, 0, 0, 40, 0, -1, 0, -1,
                -1, 0, 200);
        Bucket bucket = new Bucket(0);
        bucket.add(SecondSample.between(a, b));
        Derived d = new Derived(bucket);
        assertTrue(Double.isNaN(d.capShare), "plan's share must be unknown, not 0");
        assertEquals(1.0, d.unattrShare, 1e-9);
        assertTrue(Double.isNaN(d.selfShare), "own threads' share must be unknown, not 0");
        assertTrue(Double.isNaN(d.outsideShare), "other work's share must be unknown, not 0");
    }

    @Test
    void unmeasuredOtherWorkLeavesBothSharesUnknown() {
        // Throttling readable, but /proc/stat isn't (LXCFS): the plan's share is measured, the
        // rest of the waiting is unattributed, and own threads vs other work is unknown.
        long ms = 1_000_000L;
        Snapshot a = new Snapshot(0, 0, 600 * ms, 124 * ms, 0, 0, 100_000, 70, 0, 0, 20, 0, -1, 0, -1, -1, 0, -1);
        Snapshot b = new Snapshot(1000, 1_000_000_000L, 1200 * ms, 248 * ms, 0, 0, 100_000, 140, 0, 0, 40, 0, -1, 0,
                -1, -1, 0, -1);
        Bucket bucket = new Bucket(0);
        bucket.add(SecondSample.between(a, b));
        Derived d = new Derived(bucket);
        assertEquals(0.0, d.capShare, 1e-9);
        assertEquals(1.0, d.unattrShare, 1e-9);
        assertTrue(Double.isNaN(d.selfShare));
        assertTrue(Double.isNaN(d.outsideShare));
    }

    @Test
    void throttledTimeIsBoundedByPeriodsAndWall() {
        // throttled_usec summed over 4 CPUs grew by 3 s in one second, in 5 throttled 100 ms
        // periods: at most 500 ms of that second was spent throttled.
        long ms = 1_000_000L;
        Snapshot a = new Snapshot(0, 0, 100 * ms, 100 * ms, 0, 0, 100_000, 10, 0, 0, 0, 0, -1, 0, -1, -1, 0, 0);
        Snapshot b = new Snapshot(1000, 1_000_000_000L, 200 * ms, 900 * ms, 3_000_000, 5, 100_000, 20, 0, 0, 20, 0, -1,
                0, -1, -1, 0, 100);
        SecondSample s = SecondSample.between(a, b);
        assertEquals(500.0, s.throttledMs, 1e-9);
        assertEquals(500.0, s.split[Attribution.CAP], 1e-9);
    }

    @Test
    void unmeasuredIsNotZero() {
        Snapshot a = new Snapshot(0, 0, -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, -1, 0, -1, -1, 0, -1);
        Snapshot b = new Snapshot(1000, 1_000_000_000L, -1, -1, -1, -1, -1, -1, -1, -1, 20, 0, -1, 0, -1, -1, 0, -1);
        Bucket bucket = new Bucket(0);
        bucket.add(SecondSample.between(a, b));
        Derived d = new Derived(bucket);
        assertEquals(20.0, d.tps, 1e-9);
        assertTrue(Double.isNaN(d.waitPerSecondMs));
        assertTrue(Double.isNaN(d.waitShare));
        assertTrue(Double.isNaN(d.capShare));
        assertTrue(Double.isNaN(d.foreignCores));
        assertTrue(Double.isNaN(d.stealPct));
        assertTrue(Double.isNaN(d.tickMs));
        assertTrue(Double.isNaN(d.tickWaitMs));
    }

    @Test
    void counterGoingBackwardsIsNotANegativeWait() {
        Snapshot a = at(10);
        Snapshot reset = new Snapshot(11_000, 11_000_000_000L, 1, 1, 0, 0, 100_000, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0);
        SecondSample s = SecondSample.between(a, reset);
        assertTrue(Double.isNaN(s.waitMs));
        assertTrue(Double.isNaN(s.busyMs));
        assertTrue(Double.isNaN(s.tickWaitMs));
    }

    @Test
    void accessorsMatchFieldNames() {
        Bucket b = new Bucket(0);
        for (int i = 0; i < Bucket.FIELDS.length; i++) {
            b.set(Bucket.FIELDS[i], 1000 + i);
        }
        long[] direct = {
            b.seconds(), b.wallUs(), b.schedSeconds(), b.schedWallUs(), b.schedTicks(), b.busyUs(), b.waitUs(),
            b.capUs(), b.selfUs(), b.outsideUs(), b.unattrUs(), b.maxWaitUs(), b.throttledSeconds(), b.throttledUs(),
            b.otherSeconds(), b.otherUs(), b.foreignSeconds(), b.foreignUs(), b.stealJ(), b.totalJ(), b.ticks(),
            b.paperTicks(), b.tickUs(), b.tickSchedTicks(), b.tickWorkUs(), b.tickWaitUs(), b.playersSum(),
            b.playersMax(),
        };
        assertEquals(Bucket.FIELDS.length, direct.length);
        for (int i = 0; i < direct.length; i++) {
            assertEquals(1000 + i, direct[i], Bucket.FIELDS[i]);
        }
    }

    @Test
    void csvRoundTripAndForeignHeaders() {
        Bucket b = new Bucket(29_000_000L);
        for (int i = 0; i < 5; i++) {
            b.add(second(i + 1));
        }
        String[] header = Bucket.csvHeader().split(",");
        assertEquals(b.toCsv(), Bucket.fromCsv(b.toCsv(), header).toCsv());

        String[] other = {"minute", "seconds", "ticks", "someFutureField"};
        Bucket foreign = Bucket.fromCsv("29000001,60,1200,7", other);
        assertEquals(60, foreign.seconds());
        assertEquals(1200, foreign.ticks());
        assertEquals(0, foreign.waitUs());

        assertNull(Bucket.fromCsv("1,2", header));
        assertNull(Bucket.fromCsv("29000001,-5,1200,0", other));
    }

    @Test
    void mergeKeepsMaxima() {
        Bucket a = new Bucket(1);
        a.set("maxWaitUs", 5);
        a.set("playersMax", 2);
        a.set("seconds", 3);
        Bucket b = new Bucket(2);
        b.set("maxWaitUs", 9);
        b.set("playersMax", 1);
        b.set("seconds", 4);
        a.merge(b);
        assertEquals(9, a.maxWaitUs());
        assertEquals(2, a.playersMax());
        assertEquals(7, a.seconds());
    }
}
