package gg.truetick.tickwait.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AttributionTest {
    private static double sum(double[] s) {
        return s[Attribution.CAP] + s[Attribution.SELF] + s[Attribution.OUTSIDE] + s[Attribution.UNATTRIBUTED];
    }

    @Test
    void anotherContainerOnTheSameCoreIsOutside() {
        // DEV 2026-10-11, two single-threaded busy loops pinned to cpu3 for 5 s: the measured one
        // ran 2502 ms and waited 2503 ms; it has no other threads, the other loop had the rest.
        double[] s = Attribution.split(2503, 0, 0, 2498);
        assertEquals(2503, s[Attribution.OUTSIDE], 1e-9);
        assertEquals(0, s[Attribution.SELF], 1e-9);
        assertEquals(0, s[Attribution.CAP], 1e-9);
    }

    @Test
    void aQuotaIsThePlansCap() {
        // DEV 2026-10-11, --cpus=0.25: run_delay 3749 ms, cgroup throttled_usec delta 3748 ms.
        // Nothing else used the core, so the last millisecond can't be attributed.
        double[] s = Attribution.split(3749, 3748, 0, 0);
        assertEquals(3748, s[Attribution.CAP], 1e-9);
        assertEquals(1, s[Attribution.UNATTRIBUTED], 1e-9);
        assertEquals(3749, sum(s), 1e-9);
    }

    @Test
    void waitIsSharedInProportionToWhoElseRan() {
        // 120 ms of waiting; the server's other threads used 300 ms of its cores, other work 900 ms.
        double[] s = Attribution.split(120, 0, 300, 900);
        assertEquals(30, s[Attribution.SELF], 1e-9);
        assertEquals(90, s[Attribution.OUTSIDE], 1e-9);
    }

    @Test
    void anIdleNeighbourLeavesItToTheServer() {
        // Startup on one core: JIT and world generation used 810 ms, nothing else ran.
        double[] s = Attribution.split(283, 0, 810, 0);
        assertEquals(283, s[Attribution.SELF], 1e-9);
        assertEquals(0, s[Attribution.OUTSIDE], 1e-9);
    }

    @Test
    void throttlingComesFirstAndNeverExceedsTheWait() {
        double[] s = Attribution.split(10, 500, 100, 100);
        assertEquals(10, s[Attribution.CAP], 1e-9);
        assertEquals(0, s[Attribution.SELF] + s[Attribution.OUTSIDE], 1e-9);
    }

    @Test
    void unknownInputsAreUnattributed() {
        assertEquals(30, Attribution.split(30, Double.NaN, 40, Double.NaN)[Attribution.UNATTRIBUTED], 1e-9);
        assertEquals(30, Attribution.split(30, 0, Double.NaN, 5)[Attribution.UNATTRIBUTED], 1e-9);
    }

    @Test
    void unreadableThrottlingAttributesNothing() {
        // others and foreign work were measured, but the plan's limit may have caused any of it
        double[] s = Attribution.split(90, Double.NaN, 300, 900);
        assertEquals(90, s[Attribution.UNATTRIBUTED], 1e-9);
        assertEquals(0, s[Attribution.CAP] + s[Attribution.SELF] + s[Attribution.OUTSIDE], 1e-9);
    }

    @Test
    void notMeasuredStaysNotMeasured() {
        for (double v : Attribution.split(Double.NaN, 0, 1, 2)) {
            assertTrue(Double.isNaN(v));
        }
    }
}
