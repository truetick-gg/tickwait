package gg.truetick.tickwait.sums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.truetick.tickwait.measure.SecondSample;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinuteStoreTest {
    @TempDir
    Path dir;

    /** A one-second sample ending at {@code epochSecond}. */
    private static SecondSample at(long epochSecond) {
        return SecondSample.between(BucketTest.at(epochSecond - 1), BucketTest.at(epochSecond));
    }

    @Test
    void finishedMinutesArePersistedAndReloaded() throws IOException {
        Path file = dir.resolve("minutes.csv");
        MinuteStore store = new MinuteStore(file, 7);
        long minute = 29_000_000L;
        for (int s = 0; s < 60; s++) {
            store.add(at(minute * 60 + s));
        }
        store.add(at((minute + 1) * 60)); // the next minute's first second closes the previous one
        assertEquals(2, store.minutes().size()); // finished + in progress
        assertEquals(60, store.minutes().get(0).seconds());

        MinuteStore again = new MinuteStore(file, 7);
        assertEquals(1, again.load(minute + 2));
        assertEquals(minute, again.minutes().get(0).key);
        assertEquals(60 * 124_000L, again.minutes().get(0).waitUs());
    }

    @Test
    void oldMinutesAreDroppedOnLoad() throws IOException {
        Path file = dir.resolve("minutes.csv");
        long now = 29_000_000L;
        Bucket fresh = new Bucket(now - 10);
        fresh.set("seconds", 60);
        Bucket stale = new Bucket(now - 8 * 1440);
        stale.set("seconds", 60);
        Files.write(file, Arrays.asList(Bucket.csvHeader(), stale.toCsv(), "garbage,line", fresh.toCsv()),
                StandardCharsets.UTF_8);
        MinuteStore store = new MinuteStore(file, 7);
        assertEquals(1, store.load(now));
        List<String> rewritten = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, rewritten.size()); // header + the fresh minute
        assertTrue(rewritten.get(1).startsWith(Long.toString(now - 10)));
    }

    @Test
    void aClockStepBackNeverPutsMoreThanAMinuteUnderOneMinute() throws IOException {
        Path file = dir.resolve("minutes.csv");
        MinuteStore store = new MinuteStore(file, 7);
        long minute = 29_000_000L;
        for (int s = 0; s < 5 * 60; s++) {          // five full minutes
            store.add(at(minute * 60 + s));
        }
        for (int s = 0; s < 6 * 60; s++) {          // the clock steps back five minutes, then runs on
            store.add(at(minute * 60 + s));
        }
        store.flush();
        List<Bucket> mins = store.minutes();
        assertEquals(6, mins.size(), "one bucket per minute, however often the clock revisits it");
        for (Bucket b : mins) {
            assertTrue(b.seconds() <= 60, "minute " + b.key + " holds " + b.seconds() + " s");
        }
        assertEquals(60, mins.get(0).seconds());
        assertEquals(minute + 5, mins.get(5).key, "measuring resumes once the clock is past the last minute");
        assertEquals(60, mins.get(5).seconds());
        assertEquals(60, store.recentSeconds().size(), "the last 60 seconds don't care about the clock");
        MinuteStore again = new MinuteStore(file, 7);
        assertEquals(6, again.load(minute + 10));
        for (Bucket b : again.minutes()) {
            assertTrue(b.seconds() <= 60);
        }
    }

    @Test
    void aRestartWithinTheSameMinuteMergesIntoIt() throws IOException {
        Path file = dir.resolve("minutes.csv");
        long minute = 29_000_000L;
        MinuteStore store = new MinuteStore(file, 7);
        for (int s = 0; s < 20; s++) {
            store.add(at(minute * 60 + s));
        }
        store.flush(); // shutdown
        MinuteStore again = new MinuteStore(file, 7);
        again.load(minute);
        for (int s = 40; s < 61; s++) {
            again.add(at(minute * 60 + s));
        }
        again.flush();
        List<Bucket> mins = again.minutes();
        assertEquals(minute, mins.get(0).key);
        assertEquals(40, mins.get(0).seconds());
    }

    @Test
    void returnedBucketsAreCopies() throws IOException {
        MinuteStore store = new MinuteStore(dir.resolve("m2.csv"), 7);
        store.add(at(1_740_000_000L));
        Bucket b = store.minutes().get(0);
        b.set("seconds", 999);
        assertEquals(1, store.minutes().get(0).seconds());
    }

    @Test
    void recentKeepsTheLastMinuteOfSeconds() throws IOException {
        MinuteStore store = new MinuteStore(dir.resolve("m.csv"), 7);
        for (int s = 0; s < 75; s++) {
            store.add(at(1_740_000_000L + s));
        }
        assertEquals(60, store.recentSeconds().size());
    }
}
