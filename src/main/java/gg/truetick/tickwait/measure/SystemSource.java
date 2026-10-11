package gg.truetick.tickwait.measure;

/** Reads every counter for one {@link Snapshot}. Called once a second from the sampler thread. */
public final class SystemSource {
    private final ProcFs fs;
    private final CgroupCpu cgroup;
    /** /proc/self/task/&lt;tid&gt;/schedstat of the main thread, or null when unavailable. */
    private final String schedstatPath;
    private final TickCounter ticks;
    private final PaperTicks paper;
    /** /proc/stat is a per-container view (LXCFS): other work on our cores can't be seen. */
    private final boolean procStatVirtual;
    private final long[] paperCounters = new long[5];
    private int allowedCpus;
    private java.util.BitSet allowed;
    private int sinceAffinity;

    public SystemSource(ProcFs fs, CgroupCpu cgroup, long mainTid, TickCounter ticks, PaperTicks paper) {
        this.fs = fs;
        this.cgroup = cgroup;
        this.schedstatPath = schedstatPathFor(fs, mainTid);
        this.ticks = ticks;
        this.paper = paper;
        this.procStatVirtual = KernelParsers.procStatVirtualized(fs.readOrNull("/proc/self/mountinfo"));
        this.allowed = readAllowed(fs);
        this.allowedCpus = allowed == null ? 0 : allowed.cardinality();
    }

    public boolean procStatVirtual() {
        return procStatVirtual;
    }

    private static java.util.BitSet readAllowed(ProcFs fs) {
        return KernelParsers.cpuListIds(
                KernelParsers.statusField(fs.readOrNull("/proc/self/status"), "Cpus_allowed_list"));
    }

    /** The main thread's schedstat path when the kernel exposes it, else null. */
    public static String schedstatPathFor(ProcFs fs, long mainTid) {
        String path = mainTid > 0 ? "/proc/self/task/" + mainTid + "/schedstat" : null;
        return path != null && KernelParsers.schedstat(fs.readOrNull(path)) != null ? path : null;
    }

    /**
     * The calling thread's id. Call it on the server's main thread (e.g. in onEnable).
     * Falls back to the task named "Server thread" on kernels without /proc/thread-self.
     */
    public static long currentThreadTid(ProcFs fs) {
        long tid = KernelParsers.tidFromThreadSelf(fs.readLinkOrNull("/proc/thread-self"));
        if (tid > 0) {
            return tid;
        }
        for (String task : fs.list("/proc/self/task")) {
            String comm = fs.readOrNull("/proc/self/task/" + task + "/comm");
            if (comm != null && "Server thread".equals(comm.trim())) {
                try {
                    return Long.parseLong(task);
                } catch (NumberFormatException ignored) {
                    // not a task id
                }
            }
        }
        return -1;
    }

    public static int readAllowedCpus(ProcFs fs) {
        return KernelParsers.cpuListCount(
                KernelParsers.statusField(fs.readOrNull("/proc/self/status"), "Cpus_allowed_list"));
    }

    public String schedstatPath() {
        return schedstatPath;
    }

    public boolean hasSchedstat() {
        return schedstatPath != null;
    }

    public boolean hasSteal() {
        return !procStatVirtual && KernelParsers.procStatCpu(fs.readOrNull("/proc/stat")) != null;
    }

    public CgroupCpu cgroup() {
        return cgroup;
    }

    public int allowedCpus() {
        return allowedCpus;
    }

    /** Cores this server may use: the cgroup limit, capped by the CPUs it may run on. */
    public double limitCores() {
        double limit = cgroup.limitCores();
        if (allowedCpus > 0) {
            limit = Double.isNaN(limit) ? allowedCpus : Math.min(limit, allowedCpus);
        }
        return limit;
    }

    public Snapshot take() {
        if (++sinceAffinity >= 60) {
            sinceAffinity = 0;
            java.util.BitSet ids = readAllowed(fs);
            if (ids != null) {
                allowed = ids;
                allowedCpus = ids.cardinality();
            }
        }
        long runtime = -1;
        long delay = -1;
        if (schedstatPath != null) {
            long[] s = KernelParsers.schedstat(fs.readOrNull(schedstatPath));
            if (s != null) {
                runtime = s[0];
                delay = s[1];
            }
        }
        // The clock is read right after the main thread's counters, so a sampler that gets
        // delayed reading the rest doesn't stretch this second's wall time against them.
        long wallNanos = System.nanoTime();
        long epochMillis = System.currentTimeMillis();
        long[] throttling = cgroup.throttling();
        String procStat = procStatVirtual ? null : fs.readOrNull("/proc/stat");
        long[] stat = KernelParsers.procStatCpu(procStat);
        long allowedBusy = KernelParsers.procStatBusyOn(procStat, allowed);
        paper.read(paperCounters); // {ticks, tickNanos, schedTicks, workNanos, waitNanos}, one snapshot
        return new Snapshot(epochMillis, wallNanos, runtime, delay, throttling[0], throttling[1],
                cgroup.periodMicros(), KernelParsers.procSelfCpuTicks(fs.readOrNull("/proc/self/stat")),
                stat == null ? -1 : stat[0], stat == null ? -1 : stat[1],
                ticks.ticks(), paperCounters[0], paperCounters[1], paperCounters[2], paperCounters[3],
                paperCounters[4], ticks.players(), allowedBusy);
    }
}
