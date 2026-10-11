package gg.truetick.tickwait.report;

import gg.truetick.tickwait.sums.Bucket;

/**
 * Readable numbers derived from a {@link Bucket}. Every value is NaN when the inputs it needs
 * weren't measured. The web report computes the same formulas from the uploaded sums.
 *
 * <p>Two views, both exact by construction:
 * <ul>
 *   <li>per second of the main thread: worked + waited for a CPU + idle = 1000 ms
 *       (waiting between ticks delays the next tick; it never shows up in tick time);</li>
 *   <li>per tick (Paper): tick time = work + waiting for a CPU + other (GC pauses, disk, locks).</li>
 * </ul>
 */
public final class Derived {
    public final double tps;
    /** Main thread per second of wall time, ms: on a CPU, waiting for one, neither. */
    public final double busyPerSecondMs;
    public final double waitPerSecondMs;
    public final double idlePerSecondMs;
    /** Share of the time the main thread wanted a CPU that it spent waiting for one, 0–1. */
    public final double waitShare;
    /** Main thread wait per tick across the whole sample (inside and between ticks), ms. */
    public final double waitPerTickMs;
    /** Average tick time between Paper's tick events and its split, ms (Paper only). */
    public final double tickMs;
    public final double tickWorkMs;
    public final double tickWaitMs;
    public final double tickOtherMs;
    /** Waiting that happened between ticks (delaying the next one), ms per second. */
    public final double betweenTickWaitPerSecondMs;
    /** Shares of the wait (0–1): at the plan's CPU limit, own threads, outside the server, unknown. */
    public final double capShare;
    public final double selfShare;
    public final double outsideShare;
    public final double unattrShare;
    /** Cores used by the main thread, by the server's other threads, and by other work on its cores. */
    public final double mainThreadCores;
    public final double otherThreadsCores;
    public final double foreignCores;
    /** CPU time the VM lost to its hypervisor, % of all CPU time on the machine. */
    public final double stealPct;
    public final double avgPlayers;
    /** Longest wait inside a single second, ms. */
    public final double maxWaitInSecondMs;

    public Derived(Bucket b) {
        double secs = b.wallUs() / 1e6;
        tps = secs > 0 ? b.ticks() / secs : Double.NaN;
        boolean sched = b.schedSeconds() > 0 && b.schedWallUs() > 0;
        double schedWallMs = b.schedWallUs() / 1000.0;
        busyPerSecondMs = sched ? b.busyUs() / schedWallMs : Double.NaN;
        waitPerSecondMs = sched ? b.waitUs() / schedWallMs : Double.NaN;
        idlePerSecondMs = sched ? Math.max(0, 1000 - busyPerSecondMs - waitPerSecondMs) : Double.NaN;
        long wanted = b.busyUs() + b.waitUs();
        waitShare = sched && wanted > 0 ? (double) b.waitUs() / wanted : Double.NaN;
        waitPerTickMs = sched && b.schedTicks() > 0 ? b.waitUs() / 1000.0 / b.schedTicks() : Double.NaN;

        tickMs = b.paperTicks() > 0 ? b.tickUs() / 1000.0 / b.paperTicks() : Double.NaN;
        boolean split = b.tickSchedTicks() > 0;
        tickWorkMs = split ? b.tickWorkUs() / 1000.0 / b.tickSchedTicks() : Double.NaN;
        tickWaitMs = split ? b.tickWaitUs() / 1000.0 / b.tickSchedTicks() : Double.NaN;
        tickOtherMs = split && !Double.isNaN(tickMs) ? Math.max(0, tickMs - tickWorkMs - tickWaitMs) : Double.NaN;
        betweenTickWaitPerSecondMs = sched && split && b.tickSchedTicks() == b.paperTicks()
                ? Math.max(0, b.waitUs() - b.tickWaitUs()) / schedWallMs
                : Double.NaN;

        double w = b.waitUs();
        // Own threads vs other work needs throttling (the split starts from what the plan's
        // share leaves), the server's CPU and the cores' busy time. Without any of them those
        // waits sit in "unattributed", and these two shares are unknown, not 0.
        boolean splitKnown = sched && b.throttledSeconds() > 0 && b.otherSeconds() > 0 && b.foreignSeconds() > 0;
        selfShare = !splitKnown ? Double.NaN : w > 0 ? b.selfUs() / w : 0;
        outsideShare = !splitKnown ? Double.NaN : w > 0 ? b.outsideUs() / w : 0;
        unattrShare = !sched ? Double.NaN : w > 0 ? b.unattrUs() / w : 0;
        // Throttling never readable here → the plan's share is unknown, not 0 (those seconds'
        // waits sit in "unattributed").
        capShare = !sched || b.throttledSeconds() == 0 ? Double.NaN : w > 0 ? b.capUs() / w : 0;
        mainThreadCores = sched ? b.busyUs() / (double) b.schedWallUs() : Double.NaN;
        otherThreadsCores = b.otherSeconds() > 0 ? b.otherUs() / (b.otherSeconds() * 1e6) : Double.NaN;
        foreignCores = b.foreignSeconds() > 0 ? b.foreignUs() / (b.foreignSeconds() * 1e6) : Double.NaN;
        stealPct = b.totalJ() > 0 ? 100.0 * b.stealJ() / b.totalJ() : Double.NaN;
        avgPlayers = b.seconds() > 0 ? (double) b.playersSum() / b.seconds() : Double.NaN;
        maxWaitInSecondMs = sched ? b.maxWaitUs() / 1000.0 : Double.NaN;
    }
}
