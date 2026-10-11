package gg.truetick.tickwait.report;

import gg.truetick.tickwait.sums.Bucket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Chat lines for /tickwait. Plain text with legacy colour codes (works on every version). */
public final class ChatText {
    static final String G = "§7"; // grey
    static final String W = "§f"; // white
    static final String A = "§a"; // green
    static final String Y = "§e"; // yellow
    static final String D = "§8"; // dark grey
    static final String DOT = G + " · ";

    private ChatText() {
    }

    public static String ms(double v) {
        if (Double.isNaN(v)) {
            return "n/a";
        }
        return v >= 100 ? String.format(Locale.ROOT, "%.0f ms", v) : String.format(Locale.ROOT, "%.1f ms", v);
    }

    public static String pct(double share) {
        if (Double.isNaN(share)) {
            return "n/a";
        }
        double p = share * 100;
        return p > 0 && p < 1 ? "<1%" : String.format(Locale.ROOT, "%.0f%%", p);
    }

    static String num(double v, int decimals) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    /** Lines describing one bucket: the tick split, the main thread's second, where waiting came from. */
    public static List<String> describe(Bucket b, boolean hasSchedstat) {
        Derived d = new Derived(b);
        List<String> out = new ArrayList<>();
        if (!hasSchedstat) {
            out.add(Y + "This host's kernel doesn't expose per-thread scheduler stats,");
            out.add(Y + "so time spent waiting for a CPU can't be measured here.");
            out.add(G + "TPS " + W + num(d.tps, 1) + (Double.isNaN(d.tickMs) ? "" : DOT + "tick " + W + ms(d.tickMs)));
            return out;
        }
        if (!Double.isNaN(d.tickWorkMs)) {
            out.add(G + "Average tick " + W + ms(d.tickMs) + G + ": work " + W + ms(d.tickWorkMs)
                    + DOT + "waiting for a CPU " + A + ms(d.tickWaitMs) + DOT + "other " + W + ms(d.tickOtherMs));
        }
        out.add(G + "Each second the main thread worked " + W + ms(d.busyPerSecondMs) + G + ", waited for a CPU "
                + A + ms(d.waitPerSecondMs) + G + ", was idle or blocked " + W + ms(d.idlePerSecondMs));
        out.add(G + "It waited " + A + pct(d.waitShare) + G + " of the time it wanted a core"
                + DOT + "worst second " + W + ms(d.maxWaitInSecondMs));
        if (b.waitUs() > 0) {
            out.add(G + "  at your plan's CPU limit " + W + pct(d.capShare)
                    + DOT + "behind your server's other threads " + W + pct(d.selfShare)
                    + DOT + "other work on your cores " + W + pct(d.outsideShare)
                    + (d.unattrShare > 0 ? DOT + "not attributable here " + W + pct(d.unattrShare) : ""));
        }
        out.add(G + "TPS " + W + num(d.tps, 1)
                + DOT + "your other threads " + W + num(d.otherThreadsCores, 2) + G + " cores"
                + DOT + "other work on your cores " + W + num(d.foreignCores, 2) + G + " cores"
                + DOT + "VM steal " + W + (Double.isNaN(d.stealPct) ? "n/a" : num(d.stealPct, 1) + "%")
                + DOT + "players " + W + num(d.avgPlayers, 1) + G + " avg");
        return out;
    }

    public static String footer() {
        return D + "TickWait by TrueTick · how to read this: truetick.gg/tickwait";
    }

    public static String duration(long minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        long h = minutes / 60;
        if (h < 48) {
            return h + " h " + (minutes % 60) + " min";
        }
        return (h / 24) + " d " + (h % 24) + " h";
    }
}
