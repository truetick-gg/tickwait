package gg.truetick.tickwait.measure;

/** What happened between two snapshots (normally one second apart). NaN = not measured. */
public final class SecondSample {
    /** Milliseconds per USER_HZ tick in /proc/&lt;pid&gt;/stat (USER_HZ is 100 on Linux). */
    static final double MS_PER_CLOCK_TICK = 10.0;

    public final long epochMillis;
    public final double wallMs;
    /** Main thread: on a CPU, and runnable but waiting for one (ms). */
    public final double busyMs;
    public final double waitMs;
    public final double throttledMs;
    /** CPU time of the server's other threads (whole process minus the main thread), ms. */
    public final double otherThreadsMs;
    /** CPU time on the server's allowed cores used by anything outside this process, ms. */
    public final double foreignMs;
    public final long stealJiffies;
    public final long totalJiffies;
    public final long ticks;
    /** Paper ticks timed in this sample and their summed wall time (ms). */
    public final long paperTicks;
    public final double tickMs;
    /** Paper ticks with a schedstat split, and the main thread's work and wait inside them (ms). */
    public final long tickSchedTicks;
    public final double tickWorkMs;
    public final double tickWaitMs;
    public final int players;
    /** {cap, self, outside, unattributed} ms, see {@link Attribution}. */
    public final double[] split;

    private SecondSample(long epochMillis, double wallMs, double busyMs, double waitMs, double throttledMs,
            double otherThreadsMs, double foreignMs, long stealJiffies, long totalJiffies, long ticks, long paperTicks, double tickMs,
            long tickSchedTicks, double tickWorkMs, double tickWaitMs, int players, double[] split) {
        this.epochMillis = epochMillis;
        this.wallMs = wallMs;
        this.busyMs = busyMs;
        this.waitMs = waitMs;
        this.throttledMs = throttledMs;
        this.otherThreadsMs = otherThreadsMs;
        this.foreignMs = foreignMs;
        this.stealJiffies = stealJiffies;
        this.totalJiffies = totalJiffies;
        this.ticks = ticks;
        this.paperTicks = paperTicks;
        this.tickMs = tickMs;
        this.tickSchedTicks = tickSchedTicks;
        this.tickWorkMs = tickWorkMs;
        this.tickWaitMs = tickWaitMs;
        this.players = players;
        this.split = split;
    }

    private static double delta(long a, long b, double scale) {
        if (a < 0 || b < 0 || b < a) {
            return Double.NaN; // not reported, or the counter went backwards (reset)
        }
        return (b - a) * scale;
    }

    /**
     * Upper bound on the wall time the cgroup spent throttled in the interval, ms. throttled_usec
     * is summed over every CPU's run queue, so it alone can exceed the interval; each throttled
     * period lasts at most one period length, and nothing lasts longer than the interval itself.
     * NaN when the cgroup doesn't report throttling.
     */
    static double throttledWallMs(Snapshot prev, Snapshot now, double wallMs) {
        double summed = delta(prev.throttledUs, now.throttledUs, 1e-3);
        if (Double.isNaN(summed)) {
            return Double.NaN;
        }
        double bound = Math.max(0, wallMs);
        double periods = delta(prev.throttledPeriods, now.throttledPeriods, 1);
        if (!Double.isNaN(periods) && now.periodUs > 0) {
            bound = Math.min(bound, periods * now.periodUs / 1000.0);
        }
        return Math.min(summed, bound);
    }

    public static SecondSample between(Snapshot prev, Snapshot now) {
        double wallMs = (now.wallNanos - prev.wallNanos) / 1e6;
        double busy = delta(prev.mainRuntimeNs, now.mainRuntimeNs, 1e-6);
        double wait = delta(prev.mainRunDelayNs, now.mainRunDelayNs, 1e-6);
        if (Double.isNaN(busy) || Double.isNaN(wait)) {
            busy = Double.NaN;
            wait = Double.NaN;
        }
        double throttled = throttledWallMs(prev, now, wallMs);
        double processMs = delta(prev.processCpuTicks, now.processCpuTicks, MS_PER_CLOCK_TICK);
        double others = Double.isNaN(processMs) || Double.isNaN(busy) ? Double.NaN : Math.max(0, processMs - busy);
        double coresBusyMs = delta(prev.allowedBusyJiffies, now.allowedBusyJiffies, MS_PER_CLOCK_TICK);
        double foreign = Double.isNaN(coresBusyMs) || Double.isNaN(processMs)
                ? Double.NaN
                : Math.max(0, coresBusyMs - processMs);
        long steal = -1;
        long total = -1;
        if (prev.stealJiffies >= 0 && now.stealJiffies >= prev.stealJiffies
                && prev.totalJiffies >= 0 && now.totalJiffies > prev.totalJiffies) {
            steal = now.stealJiffies - prev.stealJiffies;
            total = now.totalJiffies - prev.totalJiffies;
        }
        long ticks = Math.max(0, now.ticks - prev.ticks);
        double tickMs = Double.NaN;
        long paperTicks = 0;
        if (prev.tickNanos >= 0 && now.tickNanos >= prev.tickNanos && now.paperTicks >= prev.paperTicks) {
            tickMs = (now.tickNanos - prev.tickNanos) / 1e6;
            paperTicks = now.paperTicks - prev.paperTicks;
        }
        double tickWork = Double.NaN;
        double tickWait = Double.NaN;
        long tickSched = 0;
        if (prev.tickWorkNs >= 0 && now.tickWorkNs >= prev.tickWorkNs && prev.tickWaitNs >= 0
                && now.tickWaitNs >= prev.tickWaitNs && now.tickSchedTicks >= prev.tickSchedTicks) {
            tickWork = (now.tickWorkNs - prev.tickWorkNs) / 1e6;
            tickWait = (now.tickWaitNs - prev.tickWaitNs) / 1e6;
            tickSched = now.tickSchedTicks - prev.tickSchedTicks;
        }
        double[] split = Attribution.split(wait, throttled, others, foreign);
        return new SecondSample(now.epochMillis, wallMs, busy, wait, throttled, others, foreign, steal, total, ticks,
                paperTicks, tickMs, tickSched, tickWork, tickWait, now.players, split);
    }
}
