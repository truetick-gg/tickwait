package gg.truetick.tickwait.measure;

import java.util.Map;

/**
 * The CPU controller of the cgroup this server runs in: how often it hit its CPU limit
 * (throttling) and what that limit is. Works with cgroup v2 and v1, inside a container
 * (the cgroup is mounted at the root) or on a bare host (the path comes from /proc/self/cgroup).
 */
public final class CgroupCpu {
    public enum Version { V2, V1, NONE }

    private static final String[] V1_MOUNTS = {
        "/sys/fs/cgroup/cpu,cpuacct", "/sys/fs/cgroup/cpuacct,cpu", "/sys/fs/cgroup/cpu",
    };

    private final ProcFs fs;
    private final Version version;
    private final String dir;

    private CgroupCpu(ProcFs fs, Version version, String dir) {
        this.fs = fs;
        this.version = version;
        this.dir = dir;
    }

    public static CgroupCpu detect(ProcFs fs) {
        String self = fs.readOrNull("/proc/self/cgroup");
        if (fs.exists("/sys/fs/cgroup/cgroup.controllers")) {
            String path = KernelParsers.cgroupPath(self, null);
            String dir = "/sys/fs/cgroup";
            // Inside a container with its own cgroup namespace the path is "/" and the mount
            // root is our cgroup. On a bare host the root is the whole machine — use our path.
            if (path != null && !"/".equals(path) && fs.exists(dir + path + "/cpu.stat")) {
                dir = dir + path;
            }
            if (fs.exists(dir + "/cpu.stat")) {
                return new CgroupCpu(fs, Version.V2, dir);
            }
        }
        String path = KernelParsers.cgroupPath(self, "cpu");
        for (String mount : V1_MOUNTS) {
            // Docker on v1 bind-mounts the container's own cgroup at the mount root, while
            // /proc/self/cgroup still names the host path — so try the path first, then the root.
            if (path != null && !"/".equals(path) && fs.exists(mount + path + "/cpu.stat")) {
                return new CgroupCpu(fs, Version.V1, mount + path);
            }
            if (fs.exists(mount + "/cpu.stat")) {
                return new CgroupCpu(fs, Version.V1, mount);
            }
        }
        return new CgroupCpu(fs, Version.NONE, null);
    }

    public Version version() {
        return version;
    }

    /** CPU limit in cores (0.5 = half a core), or NaN when there is none or it can't be read. */
    public double limitCores() {
        switch (version) {
            case V2:
                return KernelParsers.cpuMaxCores(fs.readOrNull(dir + "/cpu.max"));
            case V1:
                return KernelParsers.cfsQuotaCores(
                        fs.readOrNull(dir + "/cpu.cfs_quota_us"), fs.readOrNull(dir + "/cpu.cfs_period_us"));
            default:
                return Double.NaN;
        }
    }

    /**
     * Throttling counters: {throttled µs, throttled periods}, each -1 when not reported here.
     * The µs figure is summed over every CPU's run queue, so with no cpuset it can exceed wall
     * time; throttled periods × period length is the wall-time bound.
     */
    public long[] throttling() {
        if (version == Version.NONE) {
            return new long[] {-1, -1};
        }
        Map<String, Long> stat = KernelParsers.keyValues(fs.readOrNull(dir + "/cpu.stat"));
        Long periods = stat.get("nr_throttled");
        long p = periods == null ? -1 : periods;
        if (version == Version.V2) {
            Long v = stat.get("throttled_usec");
            return new long[] {v == null ? -1 : v, p};
        }
        Long ns = stat.get("throttled_time");
        return new long[] {ns == null ? -1 : ns / 1000, p};
    }

    /** Length of one CPU-limit period in µs (cpu.max's second field, cfs_period_us on v1), or -1. */
    public long periodMicros() {
        String text;
        switch (version) {
            case V2: {
                String max = fs.readOrNull(dir + "/cpu.max");
                if (max == null) {
                    return -1;
                }
                String[] f = max.trim().split("\\s+");
                text = f.length == 2 ? f[1] : null;
                break;
            }
            case V1:
                text = fs.readOrNull(dir + "/cpu.cfs_period_us");
                break;
            default:
                return -1;
        }
        if (text == null) {
            return -1;
        }
        try {
            long v = Long.parseLong(text.trim());
            return v > 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
