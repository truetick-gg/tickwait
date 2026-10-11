package gg.truetick.tickwait.measure;

/**
 * Cumulative counters read at one instant. Fields that this host doesn't expose are -1 —
 * "not measured" is never folded into zero.
 */
public final class Snapshot {
    public final long epochMillis;
    public final long wallNanos;
    /** Main thread time on a CPU (ns), from schedstat. */
    public final long mainRuntimeNs;
    /** Main thread time spent runnable but waiting for a CPU (ns), from schedstat. */
    public final long mainRunDelayNs;
    /** cgroup throttled time (µs, summed over CPUs) and throttled periods, and the period length (µs). */
    public final long throttledUs;
    public final long throttledPeriods;
    public final long periodUs;
    /** CPU time of this whole process, all threads (USER_HZ ticks, 10 ms each). */
    public final long processCpuTicks;
    public final long stealJiffies;
    public final long totalJiffies;
    /** Server ticks completed so far (counted by a scheduler task, every server type). */
    public final long ticks;
    /** Ticks timed between Paper's tick events, and their summed wall time (ns, -1 if unavailable). */
    public final long paperTicks;
    public final long tickNanos;
    /** Paper ticks whose schedstat was read at both ends, and the main thread's work/wait inside them (ns). */
    public final long tickSchedTicks;
    public final long tickWorkNs;
    public final long tickWaitNs;
    public final int players;
    /** Busy jiffies of the CPUs this process may run on (any process, incl. steal), -1 if unknown. */
    public final long allowedBusyJiffies;

    public Snapshot(long epochMillis, long wallNanos, long mainRuntimeNs, long mainRunDelayNs, long throttledUs,
            long throttledPeriods, long periodUs, long processCpuTicks, long stealJiffies, long totalJiffies,
            long ticks, long paperTicks, long tickNanos, long tickSchedTicks, long tickWorkNs, long tickWaitNs,
            int players, long allowedBusyJiffies) {
        this.epochMillis = epochMillis;
        this.wallNanos = wallNanos;
        this.mainRuntimeNs = mainRuntimeNs;
        this.mainRunDelayNs = mainRunDelayNs;
        this.throttledUs = throttledUs;
        this.throttledPeriods = throttledPeriods;
        this.periodUs = periodUs;
        this.processCpuTicks = processCpuTicks;
        this.stealJiffies = stealJiffies;
        this.totalJiffies = totalJiffies;
        this.ticks = ticks;
        this.paperTicks = paperTicks;
        this.tickNanos = tickNanos;
        this.tickSchedTicks = tickSchedTicks;
        this.tickWorkNs = tickWorkNs;
        this.tickWaitNs = tickWaitNs;
        this.players = players;
        this.allowedBusyJiffies = allowedBusyJiffies;
    }
}
