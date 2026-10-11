package gg.truetick.tickwait.measure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Inputs are captures from the DEV box (Ubuntu 24.04, kernel 6.8.0-134-generic, cgroup v2,
 * alpine:3.20 container with --cpus=0.5) taken 2026-10-11, unless a test says otherwise.
 */
class KernelParsersTest {
    static final String CPU_STAT_V2 = "usage_usec 1084518\n"
            + "user_usec 1062750\n"
            + "system_usec 21767\n"
            + "core_sched.force_idle_usec 0\n"
            + "nr_periods 22\n"
            + "nr_throttled 20\n"
            + "throttled_usec 1054318\n"
            + "nr_bursts 0\n"
            + "burst_usec 0\n";
    /** /proc/stat cpu lines from the DEV box (4 vCPUs), 2026-10-11 19:50 UTC. */
    static final String PROC_STAT = "cpu  21753664 89659 5346985 3231427707 187365 0 159184 277082 0 0\n"
            + "cpu0 4929145 21571 1203649 808834658 38713 0 48774 59720 0 0\n"
            + "cpu1 5719285 23514 1410793 807365383 51278 0 36015 76286 0 0\n"
            + "cpu2 5742404 22475 1416839 807138436 49335 0 48274 77977 0 0\n"
            + "cpu3 5362828 22097 1315703 808089228 48037 0 26120 63098 0 0\n"
            + "intr 1 2 3\n";

    @Test
    void schedstatOfABusyLoopUnderAQuota() {
        assertArrayEquals(new long[] {1044272536L, 999333958L}, KernelParsers.schedstat("1044272536 999333958 93\n"));
        assertArrayEquals(new long[] {369851L, 36448L}, KernelParsers.schedstat("369851 36448 1"));
    }

    @Test
    void schedstatOffIsNotMeasured() {
        // a kernel with scheduler statistics turned off prints zeros for every thread
        assertNull(KernelParsers.schedstat("0 0 0\n"));
    }

    @Test
    void lxcfsProcStatIsDetected() {
        // First lines of /proc/self/mountinfo in an alpine container on the DEV box (2026-10-11),
        // overlay options shortened.
        String docker = "741 672 0:58 / / rw,relatime - overlay overlay rw,lowerdir=/x,upperdir=/y,workdir=/z,nouserxattr\n"
                + "743 741 0:86 / /proc rw,nosuid,nodev,noexec,relatime - proc proc rw\n"
                + "747 746 0:27 / /sys/fs/cgroup ro,nosuid,nodev,noexec,relatime - cgroup2 cgroup rw,nsdelegate,memory_recursiveprot\n";
        assertTrue(!KernelParsers.procStatVirtualized(docker));
        // LXCFS bind-mounts its own file over /proc/stat (proc(5) mountinfo layout; no LXCFS host to capture from).
        String lxcfs = docker + "760 743 0:95 /proc/stat /proc/stat rw,nosuid,nodev,relatime - fuse.lxcfs lxcfs rw\n";
        assertTrue(KernelParsers.procStatVirtualized(lxcfs));
    }

    @Test
    void schedstatRejectsGarbage() {
        assertNull(KernelParsers.schedstat(null));
        assertNull(KernelParsers.schedstat(""));
        assertNull(KernelParsers.schedstat("12"));
        assertNull(KernelParsers.schedstat("a b c"));
    }

    @Test
    void cpuStatV2() {
        Map<String, Long> kv = KernelParsers.keyValues(CPU_STAT_V2);
        assertEquals(1054318L, (long) kv.get("throttled_usec"));
        assertEquals(1084518L, (long) kv.get("usage_usec"));
        assertEquals(0L, (long) kv.get("core_sched.force_idle_usec"));
    }

    @Test
    void cpuMax() {
        assertEquals(0.5, KernelParsers.cpuMaxCores("50000 100000\n"), 1e-9);
        assertEquals(0.25, KernelParsers.cpuMaxCores("25000 100000"), 1e-9);
        assertTrue(Double.isNaN(KernelParsers.cpuMaxCores("max 100000")));
        assertTrue(Double.isNaN(KernelParsers.cpuMaxCores(null)));
    }

    @Test
    void cfsQuotaV1() {
        // cgroup v1 cpu.cfs_quota_us / cpu.cfs_period_us — format from the kernel's
        // Documentation/scheduler/sched-bwc.rst; there is no v1 host in our fleet to capture from.
        assertEquals(2.0, KernelParsers.cfsQuotaCores("200000\n", "100000\n"), 1e-9);
        assertTrue(Double.isNaN(KernelParsers.cfsQuotaCores("-1", "100000")));
    }

    @Test
    void procStatStealAndTotal() {
        long[] st = KernelParsers.procStatCpu(PROC_STAT);
        assertEquals(277082L, st[0]);
        assertEquals(21753664L + 89659 + 5346985 + 3231427707L + 187365 + 0 + 159184 + 277082, st[1]);
        assertNull(KernelParsers.procStatCpu("intr 1 2 3"));
    }

    @Test
    void procSelfStatCpuTicks() {
        String pid1 = "1 (cat) R 0 1 1 0 -1 4194560 1230 50 0 0 3 1 0 0 20 0 1 0 815784050 1716224 160 "
                + "18446744073709551615 100432269963264 100432270590612 140720733213280 0 0 0 0 4 0 0 0 0 17 3 0 0 0 0 0 "
                + "100432270735408 100432270749744 100432751099904 140720733220717 140720733220734 140720733220734 "
                + "140720733220847 0";
        assertEquals(4, KernelParsers.procSelfCpuTicks(pid1)); // utime 3 + stime 1
        // A command name with spaces and a ')' must not shift the fields.
        assertEquals(4, KernelParsers.procSelfCpuTicks(pid1.replace("(cat)", "(Server thread) x)")));
        assertEquals(-1, KernelParsers.procSelfCpuTicks("garbage"));
    }

    @Test
    void cpuLists() {
        assertEquals(4, KernelParsers.cpuListCount("0-3"));
        assertEquals(7, KernelParsers.cpuListCount("0-3,8,10-11"));
        assertEquals(1, KernelParsers.cpuListCount("3"));
        assertEquals(0, KernelParsers.cpuListCount("3-1"));
        assertEquals(0, KernelParsers.cpuListCount(""));
    }

    @Test
    void busyJiffiesOfTheAllowedCpusOnly() {
        // pinned to cpu3: user + nice + system + irq + softirq + steal of that line alone
        assertEquals(5362828L + 22097 + 1315703 + 0 + 26120 + 63098,
                KernelParsers.procStatBusyOn(PROC_STAT, KernelParsers.cpuListIds("3")));
        long cpu01 = (4929145L + 21571 + 1203649 + 0 + 48774 + 59720) + (5719285L + 23514 + 1410793 + 0 + 36015 + 76286);
        assertEquals(cpu01, KernelParsers.procStatBusyOn(PROC_STAT, KernelParsers.cpuListIds("0-1")));
        assertEquals(-1, KernelParsers.procStatBusyOn(PROC_STAT, KernelParsers.cpuListIds("9")));
        assertEquals(-1, KernelParsers.procStatBusyOn(null, KernelParsers.cpuListIds("0")));
    }

    @Test
    void cpuListIds() {
        assertEquals("{0, 1, 2, 3}", KernelParsers.cpuListIds("0-3").toString());
        assertEquals("{1, 4, 5}", KernelParsers.cpuListIds("1,4-5").toString());
        assertNull(KernelParsers.cpuListIds("x"));
        assertNull(KernelParsers.cpuListIds(""));
    }

    @Test
    void statusField() {
        String status = "Name:\tcat\nThreads:\t1\nCpus_allowed:\tf\nCpus_allowed_list:\t0-3\n";
        assertEquals("0-3", KernelParsers.statusField(status, "Cpus_allowed_list"));
        assertNull(KernelParsers.statusField(status, "Missing"));
    }

    @Test
    void cpuModel() {
        assertEquals("Intel Core Processor (Haswell, no TSX)",
                KernelParsers.cpuModel("processor\t: 0\nvendor_id\t: GenuineIntel\nmodel name\t: Intel Core Processor (Haswell, no TSX)\n"));
        assertNull(KernelParsers.cpuModel(null));
    }

    @Test
    void threadSelf() {
        assertEquals(12, KernelParsers.tidFromThreadSelf("12/task/12"));
        assertEquals(-1, KernelParsers.tidFromThreadSelf(null));
        assertEquals(-1, KernelParsers.tidFromThreadSelf("self"));
    }

    @Test
    void cgroupPath() {
        assertEquals("/", KernelParsers.cgroupPath("0::/\n", null));
        String v1 = "12:cpuset:/docker/abc\n4:cpu,cpuacct:/docker/abc\n1:name=systemd:/docker/abc\n";
        assertEquals("/docker/abc", KernelParsers.cgroupPath(v1, "cpu"));
        assertNull(KernelParsers.cgroupPath(v1, null));
    }
}
