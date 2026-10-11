package gg.truetick.tickwait.report;

/** Facts about the machine and server a report was measured on. Strings may be null. */
public final class Environment {
    public final String os;
    public final String kernel;
    public final String java;
    public final String serverSoftware;
    public final String serverVersion;
    public final String mcVersion;
    public final String cpuModel;
    /** "v2", "v1" or "none". */
    public final String cgroup;
    /** CPUs this process may be scheduled on (affinity list), 0 if unknown. */
    public final int allowedCpus;
    /** The cgroup CPU limit in cores at report time, NaN when there is none. */
    public final double limitCores;
    public final boolean schedstat;
    public final boolean steal;
    public final boolean paperTicks;
    /** /proc/stat is a per-container view (LXCFS): other work on the server's cores is unmeasured. */
    public final boolean procStatVirtual;

    public Environment(String os, String kernel, String java, String serverSoftware, String serverVersion,
            String mcVersion, String cpuModel, String cgroup, int allowedCpus, double limitCores, boolean schedstat,
            boolean steal, boolean paperTicks, boolean procStatVirtual) {
        this.os = os;
        this.kernel = kernel;
        this.java = java;
        this.serverSoftware = serverSoftware;
        this.serverVersion = serverVersion;
        this.mcVersion = mcVersion;
        this.cpuModel = cpuModel;
        this.cgroup = cgroup;
        this.allowedCpus = allowedCpus;
        this.limitCores = limitCores;
        this.schedstat = schedstat;
        this.steal = steal;
        this.paperTicks = paperTicks;
        this.procStatVirtual = procStatVirtual;
    }

    void write(Json j) {
        j.beginObject()
                .name("os").value(os)
                .name("kernel").value(kernel)
                .name("java").value(java)
                .name("serverSoftware").value(serverSoftware)
                .name("serverVersion").value(serverVersion)
                .name("mcVersion").value(mcVersion)
                .name("cpuModel").value(cpuModel)
                .name("cgroup").value(cgroup)
                .name("allowedCpus").value(allowedCpus)
                .name("limitCores").value(limitCores)
                .name("schedstat").value(schedstat)
                .name("steal").value(steal)
                .name("paperTicks").value(paperTicks)
                .name("procStatVirtual").value(procStatVirtual)
                .endObject();
    }
}
