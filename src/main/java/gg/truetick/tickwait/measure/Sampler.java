package gg.truetick.tickwait.measure;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns snapshots taken once a second into {@link SecondSample}s. A gap far from one second
 * (the JVM was paused, the machine suspended) is skipped rather than averaged in.
 */
public final class Sampler {
    static final double MIN_GAP_MS = 500;
    static final double MAX_GAP_MS = 5_000;

    private final Supplier<Snapshot> source;
    private final Consumer<SecondSample> sink;
    private final Logger log;
    private Snapshot prev;
    private boolean warned;

    public Sampler(Supplier<Snapshot> source, Consumer<SecondSample> sink, Logger log) {
        this.source = source;
        this.sink = sink;
        this.log = log;
    }

    /** One step; never throws (an exception would cancel the scheduled task for good). */
    public void step() {
        try {
            Snapshot now = source.get();
            if (prev != null) {
                double gap = (now.wallNanos - prev.wallNanos) / 1e6;
                if (gap >= MIN_GAP_MS && gap <= MAX_GAP_MS) {
                    sink.accept(SecondSample.between(prev, now));
                }
            }
            prev = now;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                log.log(Level.WARNING, "TickWait sampler error (further errors are not logged)", t);
            }
        }
    }
}
