package gg.truetick.tickwait.measure;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parsers for the handful of kernel files TickWait reads. All are pure and null-safe. */
public final class KernelParsers {
    private KernelParsers() {
    }

    /**
     * /proc/&lt;pid&gt;/task/&lt;tid&gt;/schedstat: "runtime_ns run_delay_ns timeslices".
     * Returns {runtimeNs, runDelayNs} or null when the line isn't in that shape.
     */
    public static long[] schedstat(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        try {
            long runtime = Long.parseLong(parts[0]);
            long delay = Long.parseLong(parts[1]);
            // "0 0 0" is what a kernel with scheduler statistics turned off prints; a thread
            // that has run at all has a runtime, so zero means "not measured", not "idle".
            if (runtime <= 0 || delay < 0) {
                return null;
            }
            return new long[] {runtime, delay};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Whether /proc/stat is a per-container view (LXCFS mounts its own file over it). Then the
     * busy time of "our" CPUs is this container's own, and other work on those cores can't be
     * seen — it must read as not measured rather than as zero.
     */
    public static boolean procStatVirtualized(String mountinfo) {
        if (mountinfo == null) {
            return false;
        }
        for (String line : mountinfo.split("\n")) {
            // fields: id parent major:minor root mountpoint options ...
            String[] f = line.trim().split("\\s+");
            if (f.length > 4 && "/proc/stat".equals(f[4])) {
                return true;
            }
        }
        return false;
    }

    /** "key value" lines (cgroup cpu.stat, v1 and v2). Unparseable lines are skipped. */
    public static Map<String, Long> keyValues(String text) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            String[] kv = line.trim().split("\\s+");
            if (kv.length != 2) {
                continue;
            }
            try {
                out.put(kv[0], Long.parseLong(kv[1]));
            } catch (NumberFormatException ignored) {
                // not a counter line
            }
        }
        return out;
    }

    /** cgroup v2 cpu.max: "max 100000" (no limit → NaN) or "50000 100000" (0.5 cores). */
    public static double cpuMaxCores(String text) {
        if (text == null) {
            return Double.NaN;
        }
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 2 || "max".equals(parts[0])) {
            return Double.NaN;
        }
        try {
            double quota = Double.parseDouble(parts[0]);
            double period = Double.parseDouble(parts[1]);
            return quota > 0 && period > 0 ? quota / period : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** cgroup v1 cpu.cfs_quota_us / cpu.cfs_period_us; quota -1 means no limit (NaN). */
    public static double cfsQuotaCores(String quotaText, String periodText) {
        if (quotaText == null || periodText == null) {
            return Double.NaN;
        }
        try {
            double quota = Double.parseDouble(quotaText.trim());
            double period = Double.parseDouble(periodText.trim());
            return quota > 0 && period > 0 ? quota / period : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * First line of /proc/stat: "cpu user nice system idle iowait irq softirq steal guest guest_nice".
     * Returns {stealJiffies, totalJiffies} or null. guest/guest_nice are already inside user/nice,
     * so the total sums the first eight fields only.
     */
    public static long[] procStatCpu(String text) {
        if (text == null) {
            return null;
        }
        String first = text.split("\n", 2)[0].trim();
        String[] f = first.split("\\s+");
        if (f.length < 9 || !"cpu".equals(f[0])) {
            return null;
        }
        try {
            long total = 0;
            for (int i = 1; i <= 8; i++) {
                total += Long.parseLong(f[i]);
            }
            return new long[] {Long.parseLong(f[8]), total};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** /proc/&lt;pid&gt;/stat → utime + stime in clock ticks (USER_HZ, 100 on Linux), or -1. */
    public static long procSelfCpuTicks(String text) {
        if (text == null) {
            return -1;
        }
        // The command name (field 2) may contain spaces and parentheses: split after the last ')'.
        int close = text.lastIndexOf(')');
        if (close < 0) {
            return -1;
        }
        String[] f = text.substring(close + 1).trim().split("\\s+");
        // After ')': field 3 (state) is f[0], so utime (14) is f[11] and stime (15) is f[12].
        if (f.length < 13) {
            return -1;
        }
        try {
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** CPU list such as "0-3,8,10-11" → its CPU ids, or null for anything unparseable. */
    public static BitSet cpuListIds(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        BitSet ids = new BitSet();
        for (String part : text.trim().split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                int dash = p.indexOf('-');
                int lo = Integer.parseInt(dash < 0 ? p : p.substring(0, dash));
                int hi = dash < 0 ? lo : Integer.parseInt(p.substring(dash + 1));
                if (lo < 0 || hi < lo || hi > 65_535) {
                    return null;
                }
                ids.set(lo, hi + 1);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return ids.isEmpty() ? null : ids;
    }

    /** CPU list such as "0-3,8,10-11" → 7. Returns 0 for anything unparseable. */
    public static int cpuListCount(String text) {
        BitSet ids = cpuListIds(text);
        return ids == null ? 0 : ids.cardinality();
    }

    /**
     * Busy jiffies (user + nice + system + irq + softirq + steal) summed over the given CPUs from
     * the per-CPU lines of /proc/stat. Steal counts as busy: the hypervisor took that time.
     * Returns -1 when none of the CPUs has a line.
     */
    public static long procStatBusyOn(String text, BitSet cpus) {
        if (text == null || cpus == null) {
            return -1;
        }
        long busy = 0;
        int found = 0;
        for (String line : text.split("\n")) {
            if (line.length() < 4 || !line.startsWith("cpu") || !Character.isDigit(line.charAt(3))) {
                continue;
            }
            String[] f = line.trim().split("\\s+");
            if (f.length < 9) {
                continue;
            }
            try {
                int id = Integer.parseInt(f[0].substring(3));
                if (!cpus.get(id)) {
                    continue;
                }
                // fields: user nice system idle iowait irq softirq steal
                busy += Long.parseLong(f[1]) + Long.parseLong(f[2]) + Long.parseLong(f[3])
                        + Long.parseLong(f[6]) + Long.parseLong(f[7]) + Long.parseLong(f[8]);
                found++;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return found == 0 ? -1 : busy;
    }

    /** Value of "Key:\tvalue" in /proc/self/status, or null. */
    public static String statusField(String status, String key) {
        if (status == null) {
            return null;
        }
        String prefix = key + ":";
        for (String line : status.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /** First "model name" in /proc/cpuinfo (x86), else "Model"/"Hardware" (ARM), or null. */
    public static String cpuModel(String cpuinfo) {
        if (cpuinfo == null) {
            return null;
        }
        String fallback = null;
        for (String line : cpuinfo.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) {
                continue;
            }
            if ("model name".equals(key)) {
                return value;
            }
            if (fallback == null && ("Model".equals(key) || "Hardware".equals(key))) {
                fallback = value;
            }
        }
        return fallback;
    }

    /** readlink /proc/thread-self → "12/task/12" → 12, or -1. */
    public static long tidFromThreadSelf(String link) {
        if (link == null) {
            return -1;
        }
        String s = link.trim();
        int slash = s.lastIndexOf('/');
        try {
            long tid = Long.parseLong(slash >= 0 ? s.substring(slash + 1) : s);
            return tid > 0 ? tid : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * cgroup path of this process for the given hierarchy, from /proc/self/cgroup.
     * v2 is the "0::/path" line (controllers == ""), v1 the line whose controller list
     * contains {@code controller}. Returns null when absent.
     */
    public static String cgroupPath(String procSelfCgroup, String controller) {
        if (procSelfCgroup == null) {
            return null;
        }
        for (String line : procSelfCgroup.split("\n")) {
            String[] f = line.trim().split(":", 3);
            if (f.length != 3) {
                continue;
            }
            if (controller == null) {
                if ("0".equals(f[0]) && f[1].isEmpty()) {
                    return f[2];
                }
            } else {
                for (String c : f[1].split(",")) {
                    if (c.equals(controller)) {
                        return f[2];
                    }
                }
            }
        }
        return null;
    }
}
