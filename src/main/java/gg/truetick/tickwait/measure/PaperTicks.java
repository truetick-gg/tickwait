package gg.truetick.tickwait.measure;

import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Times every tick between Paper's ServerTickStartEvent and ServerTickEndEvent and reads the
 * main thread's schedstat at both ends, so a tick splits exactly into CPU work, waiting for a
 * CPU, and the rest (GC pauses, disk, locks, VM steal). The events aren't in the Bukkit API we
 * compile against, so they're found by reflection; on Spigot and old Paper builds they don't exist.
 *
 * <p>The five counters change together under one lock and are read together through
 * {@link #read(long[])}: a sampler that saw a tick's split but not the tick itself would hand the
 * report a bucket with more split ticks than ticks.
 */
public final class PaperTicks implements Listener {
    private static final String START = "com.destroystokyo.paper.event.server.ServerTickStartEvent";
    private static final String END = "com.destroystokyo.paper.event.server.ServerTickEndEvent";
    /** Clock slack between nanoTime and the scheduler clock before a tick's split is dropped. */
    private static final long SLACK_NS = 2_000_000L;
    /** Longer "ticks" are a paused or frozen server, not a tick. */
    static final long MAX_TICK_NS = 600_000_000_000L;

    private final Object lock = new Object();
    // Guarded by lock.
    private long ticks;
    private long tickNanos;
    private long schedTicks;
    private long workNanos;
    private long waitNanos;
    private volatile boolean active;

    // Main thread only.
    private MainThreadStat stat;
    private final long[] cur = new long[2];
    private long startNs = -1;
    private long startRuntime;
    private long startDelay;
    private boolean startRead;

    /**
     * Registers the listeners when the server has Paper's tick events. {@code schedstatPath} is
     * the main thread's schedstat file, or null when the kernel doesn't expose it.
     */
    public boolean register(Plugin plugin, Logger log, String schedstatPath) {
        try {
            Class<? extends Event> start = Class.forName(START).asSubclass(Event.class);
            Class<? extends Event> end = Class.forName(END).asSubclass(Event.class);
            stat = schedstatPath == null ? null : new MainThreadStat(schedstatPath);
            PluginManager pm = Bukkit.getPluginManager();
            pm.registerEvent(start, this, EventPriority.LOWEST, (l, e) -> {
                if (start.isInstance(e)) {
                    onStart();
                }
            }, plugin, false);
            pm.registerEvent(end, this, EventPriority.MONITOR, (l, e) -> {
                if (end.isInstance(e)) {
                    onEnd();
                }
            }, plugin, false);
            active = true;
        } catch (ClassNotFoundException e) {
            active = false;
        } catch (RuntimeException e) {
            log.warning("Paper tick events unavailable: " + e);
            active = false;
        }
        return active;
    }

    void onStart() {
        startRead = stat != null && stat.read(cur);
        if (startRead) {
            startRuntime = cur[0];
            startDelay = cur[1];
        }
        startNs = System.nanoTime();
    }

    void onEnd() {
        long end = System.nanoTime();
        if (startNs < 0) {
            return;
        }
        long wall = end - startNs;
        startNs = -1;
        if (wall < 0 || wall > MAX_TICK_NS) {
            return;
        }
        long work = -1;
        long wait = -1;
        if (startRead && stat.read(cur)) {
            long w = cur[0] - startRuntime;
            long d = cur[1] - startDelay;
            if (w >= 0 && d >= 0 && w + d <= wall + SLACK_NS) {
                work = w;
                wait = d;
            }
        }
        synchronized (lock) {
            if (work >= 0) {
                workNanos += work;
                waitNanos += wait;
                schedTicks++;
            }
            tickNanos += wall;
            ticks++;
        }
    }

    public void close() {
        if (stat != null) {
            stat.close();
        }
    }

    public boolean active() {
        return active;
    }

    /**
     * Copies {ticks, tickNanos, schedTicks, workNanos, waitNanos} as one consistent snapshot.
     * When the events don't exist: {0, -1, 0, -1, -1} ("not measured").
     */
    public void read(long[] out) {
        if (!active) {
            out[0] = 0;
            out[1] = -1;
            out[2] = 0;
            out[3] = -1;
            out[4] = -1;
            return;
        }
        synchronized (lock) {
            out[0] = ticks;
            out[1] = tickNanos;
            out[2] = schedTicks;
            out[3] = workNanos;
            out[4] = waitNanos;
        }
    }
}
