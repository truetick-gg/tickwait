package gg.truetick.tickwait.measure;

/**
 * Splits the main thread's CPU wait in one sample into where it came from:
 * <ul>
 *   <li><b>cap</b> — the server's cgroup was throttled: it had used up its CPU limit for the period;</li>
 *   <li><b>self</b> — the server's other threads (GC, chunk workers, plugins) held the cores;</li>
 *   <li><b>outside</b> — other work ran on the cores this server may use: other servers, the host;</li>
 *   <li><b>unattributed</b> — waiting was measured, but the host doesn't expose enough to tell.</li>
 * </ul>
 * Throttled time (an upper bound on the wall time spent at the limit) is taken first; the
 * remaining wait is shared in proportion to who else used the server's allowed cores in that
 * second: its own other threads versus everything outside the process (busy time of those cores
 * from /proc/stat minus this process's CPU time). When throttling can't be read, none of the
 * wait is attributed.
 */
public final class Attribution {
    public static final int CAP = 0;
    public static final int SELF = 1;
    public static final int OUTSIDE = 2;
    public static final int UNATTRIBUTED = 3;

    private Attribution() {
    }

    /**
     * @param waitMs          main thread run-queue delay in the sample
     * @param throttledMs     cgroup throttled time in the sample, NaN if not reported
     * @param otherThreadsMs  CPU time of this process's other threads in the sample, NaN if unknown
     * @param foreignMs       CPU time on the allowed cores used outside this process, NaN if unknown
     * @return {cap, self, outside, unattributed} in ms; all NaN when waitMs is NaN
     */
    public static double[] split(double waitMs, double throttledMs, double otherThreadsMs, double foreignMs) {
        double[] out = new double[4];
        if (Double.isNaN(waitMs)) {
            java.util.Arrays.fill(out, Double.NaN);
            return out;
        }
        double wait = Math.max(0, waitMs);
        if (Double.isNaN(throttledMs)) {
            // Throttling isn't visible here: some of this wait may be the plan's limit, and
            // splitting the rest would hide that — none of it can be attributed.
            out[UNATTRIBUTED] = wait;
            return out;
        }
        double cap = Math.min(wait, Math.max(0, throttledMs));
        double rest = wait - cap;
        out[CAP] = cap;
        if (Double.isNaN(otherThreadsMs) || Double.isNaN(foreignMs)) {
            out[UNATTRIBUTED] = rest;
            return out;
        }
        double others = Math.max(0, otherThreadsMs);
        double foreign = Math.max(0, foreignMs);
        if (others + foreign <= 0) {
            out[UNATTRIBUTED] = rest; // nothing else measurably ran, yet the thread waited
            return out;
        }
        out[SELF] = rest * others / (others + foreign);
        out[OUTSIDE] = rest - out[SELF];
        return out;
    }
}
