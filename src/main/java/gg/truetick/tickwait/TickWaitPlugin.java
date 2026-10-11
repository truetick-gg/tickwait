package gg.truetick.tickwait;

import gg.truetick.tickwait.sums.MinuteStore;
import gg.truetick.tickwait.measure.CgroupCpu;
import gg.truetick.tickwait.measure.KernelParsers;
import gg.truetick.tickwait.measure.PaperTicks;
import gg.truetick.tickwait.measure.ProcFs;
import gg.truetick.tickwait.measure.Sampler;
import gg.truetick.tickwait.measure.SystemSource;
import gg.truetick.tickwait.measure.TickCounter;
import gg.truetick.tickwait.report.Environment;
import gg.truetick.tickwait.share.SharedLog;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** TickWait: measures how long the server's main thread waits for a CPU core. */
public final class TickWaitPlugin extends JavaPlugin {
    private ProcFs fs;
    private CgroupCpu cgroup;
    private SystemSource source;
    private PaperTicks paper;
    private MinuteStore store;
    private SharedLog shared;
    private ScheduledExecutorService executor;

    @Override
    public void onEnable() {
        if (isFolia()) {
            getLogger().warning("TickWait doesn't support Folia yet (it has no single main thread). Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        fs = ProcFs.system();
        // onEnable runs on the main thread, so /proc/thread-self is the main thread.
        long mainTid = Bukkit.isPrimaryThread() ? SystemSource.currentThreadTid(fs) : -1;

        TickCounter ticks = new TickCounter(() -> Bukkit.getOnlinePlayers().size());
        getServer().getScheduler().runTaskTimer(this, ticks, 1L, 1L);
        cgroup = CgroupCpu.detect(fs);
        paper = new PaperTicks();
        paper.register(this, getLogger(), SystemSource.schedstatPathFor(fs, mainTid));
        source = new SystemSource(fs, cgroup, mainTid, ticks, paper);
        store = new MinuteStore(getDataFolder().toPath().resolve("minutes.csv"), getConfig().getInt("keep-days", 7));
        shared = new SharedLog(getDataFolder().toPath().resolve("shared.tsv"));
        try {
            int loaded = store.load(Math.floorDiv(System.currentTimeMillis(), 60_000L));
            if (loaded > 0) {
                getLogger().info("Loaded " + loaded + " measured minutes.");
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Couldn't read stored minutes; starting fresh", e);
        }

        Sampler sampler = new Sampler(source::take, sample -> {
            try {
                store.add(sample);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, getLogger());
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TickWait sampler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(sampler::step, 0, 1, TimeUnit.SECONDS);

        PluginCommand command = getCommand("tickwait");
        if (command != null) {
            TickWaitCommand handler = new TickWaitCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        double limit = source.limitCores();
        getLogger().info(String.format(Locale.ROOT,
                "Measuring. main-thread wait: %s · cgroup: %s · CPU limit: %s · per-tick split: %s",
                source.hasSchedstat() ? "yes" : "not exposed by this kernel",
                cgroup.version().name().toLowerCase(Locale.ROOT),
                Double.isNaN(limit) ? "unknown" : String.format(Locale.ROOT, "%.2f cores", limit),
                paper.active() ? "yes (Paper tick events)" : "no (needs Paper's tick events)"));
    }

    @Override
    public void onDisable() {
        if (paper != null) {
            paper.close();
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (store != null) {
            try {
                store.flush();
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Couldn't save the last minute", e);
            }
        }
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    MinuteStore store() {
        return store;
    }

    SharedLog shared() {
        return shared;
    }

    SystemSource source() {
        return source;
    }

    boolean paperTicks() {
        return paper != null && paper.active();
    }

    Environment environment() {
        String kernel = fs.readOrNull("/proc/sys/kernel/osrelease");
        return new Environment(
                System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                kernel == null ? null : kernel.trim(),
                System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
                Bukkit.getName(),
                Bukkit.getVersion(),
                Bukkit.getBukkitVersion(),
                KernelParsers.cpuModel(fs.readOrNull("/proc/cpuinfo")),
                cgroup.version() == CgroupCpu.Version.NONE ? "none" : cgroup.version().name().toLowerCase(Locale.ROOT),
                source.allowedCpus(),
                cgroup.limitCores(),
                source.hasSchedstat(),
                source.hasSteal(),
                paperTicks(),
                source.procStatVirtual());
    }

    String label(String key) {
        return Labels.clean(getConfig().getString(key, ""));
    }

    void setLabel(String key, String value) {
        getConfig().set(key, Labels.clean(value));
        saveConfig();
    }

    String uploadUrl() {
        return getConfig().getString("upload-url", "https://api.truetick.gg/v1/public/tickwait/reports");
    }
}
