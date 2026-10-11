package gg.truetick.tickwait.measure;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * Runs once per server tick on the main thread: counts ticks and, every 20 ticks, reads the
 * player count (which must be read on the main thread). Costs a counter increment per tick.
 */
public final class TickCounter implements Runnable {
    private final AtomicLong ticks = new AtomicLong();
    private final IntSupplier onlinePlayers;
    private volatile int players;
    private int sincePlayers;

    public TickCounter(IntSupplier onlinePlayers) {
        this.onlinePlayers = onlinePlayers;
    }

    @Override
    public void run() {
        ticks.incrementAndGet();
        if (++sincePlayers >= 20) {
            sincePlayers = 0;
            players = onlinePlayers.getAsInt();
        }
    }

    public long ticks() {
        return ticks.get();
    }

    public int players() {
        return players;
    }
}
