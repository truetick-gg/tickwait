package gg.truetick.tickwait.measure;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * The main thread's schedstat file, kept open so reading it twice per tick costs a seek and a
 * read rather than an open. Not thread-safe: each reader thread gets its own instance.
 */
public final class MainThreadStat implements AutoCloseable {
    private final String path;
    private final byte[] buf = new byte[128];
    private RandomAccessFile file;

    public MainThreadStat(String path) {
        this.path = path;
    }

    /** {runtimeNs, runDelayNs} into {@code out}; returns false when it can't be read. */
    public boolean read(long[] out) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (file == null) {
                    file = new RandomAccessFile(path, "r");
                }
                file.seek(0);
                int n = file.read(buf);
                if (n > 0 && parse(buf, n, out)) {
                    return true;
                }
            } catch (IOException e) {
                // fall through and reopen once
            }
            close();
        }
        return false;
    }

    /** Parses "runtime run_delay slices" from ASCII bytes without allocating. */
    static boolean parse(byte[] b, int n, long[] out) {
        int field = 0;
        long value = 0;
        boolean inNumber = false;
        for (int i = 0; i < n && field < 2; i++) {
            byte c = b[i];
            if (c >= '0' && c <= '9') {
                value = value * 10 + (c - '0');
                inNumber = true;
            } else if (inNumber) {
                out[field++] = value;
                value = 0;
                inNumber = false;
            } else if (c != ' ' && c != '\t' && c != '\n') {
                return false;
            }
        }
        if (inNumber && field < 2) {
            out[field++] = value;
        }
        return field == 2;
    }

    @Override
    public void close() {
        if (file != null) {
            try {
                file.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            file = null;
        }
    }
}
