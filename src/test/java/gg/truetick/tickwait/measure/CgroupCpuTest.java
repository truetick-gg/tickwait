package gg.truetick.tickwait.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CgroupCpuTest {
    @TempDir
    Path root;

    private void put(String abs, String content) throws IOException {
        Path p = root.resolve(abs.substring(1));
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void v2InsideAContainer() throws IOException {
        // Same layout as the DEV capture: own cgroup namespace, cgroup mounted at the root.
        put("/proc/self/cgroup", "0::/\n");
        put("/sys/fs/cgroup/cgroup.controllers", "cpuset cpu io memory hugetlb pids rdma misc\n");
        put("/sys/fs/cgroup/cpu.stat", KernelParsersTest.CPU_STAT_V2);
        put("/sys/fs/cgroup/cpu.max", "50000 100000\n");
        CgroupCpu cg = CgroupCpu.detect(new ProcFs(root));
        assertEquals(CgroupCpu.Version.V2, cg.version());
        assertEquals(0.5, cg.limitCores(), 1e-9);
        assertEquals(1054318L, cg.throttling()[0]);
        assertEquals(20L, cg.throttling()[1]);
        assertEquals(100_000L, cg.periodMicros());
    }

    @Test
    void v2OnABareHostUsesTheProcessPath() throws IOException {
        put("/proc/self/cgroup", "0::/system.slice/minecraft.service\n");
        put("/sys/fs/cgroup/cgroup.controllers", "cpu\n");
        put("/sys/fs/cgroup/cpu.stat", "usage_usec 999999999\n"); // the whole machine
        put("/sys/fs/cgroup/system.slice/minecraft.service/cpu.stat", "usage_usec 10\nthrottled_usec 7\n");
        put("/sys/fs/cgroup/system.slice/minecraft.service/cpu.max", "max 100000\n");
        CgroupCpu cg = CgroupCpu.detect(new ProcFs(root));
        assertEquals(7L, cg.throttling()[0]);
        assertEquals(-1L, cg.throttling()[1]);
        assertEquals(100_000L, cg.periodMicros());
        assertTrue(Double.isNaN(cg.limitCores()));
    }

    @Test
    void v1DockerMountsTheContainerAtTheRoot() throws IOException {
        // /proc/self/cgroup names the host path, which doesn't exist inside the container.
        put("/proc/self/cgroup", "4:cpu,cpuacct:/docker/abc\n");
        put("/sys/fs/cgroup/cpu,cpuacct/cpu.stat", "nr_periods 22\nnr_throttled 20\nthrottled_time 1054318000\n");
        put("/sys/fs/cgroup/cpu,cpuacct/cpu.cfs_quota_us", "150000\n");
        put("/sys/fs/cgroup/cpu,cpuacct/cpu.cfs_period_us", "100000\n");
        CgroupCpu cg = CgroupCpu.detect(new ProcFs(root));
        assertEquals(CgroupCpu.Version.V1, cg.version());
        assertEquals(1054318L, cg.throttling()[0]); // ns → µs
        assertEquals(20L, cg.throttling()[1]);
        assertEquals(1.5, cg.limitCores(), 1e-9);
        assertEquals(100_000L, cg.periodMicros());
    }

    @Test
    void noCgroupMeansNotMeasured() {
        CgroupCpu cg = CgroupCpu.detect(new ProcFs(root));
        assertEquals(CgroupCpu.Version.NONE, cg.version());
        assertEquals(-1L, cg.throttling()[0]);
        assertEquals(-1L, cg.periodMicros());
        assertTrue(Double.isNaN(cg.limitCores()));
    }
}
