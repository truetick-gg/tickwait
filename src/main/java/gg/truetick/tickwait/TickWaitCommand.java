package gg.truetick.tickwait;

import gg.truetick.tickwait.sums.Bucket;
import gg.truetick.tickwait.measure.SecondSample;
import gg.truetick.tickwait.report.ChatText;
import gg.truetick.tickwait.report.Derived;
import gg.truetick.tickwait.report.Environment;
import gg.truetick.tickwait.report.Json;
import gg.truetick.tickwait.report.Report;
import gg.truetick.tickwait.share.SharedLog;
import gg.truetick.tickwait.share.Uploader;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** /tickwait [status|report|share|unshare|shared|host|plan|reset|help]. */
final class TickWaitCommand implements CommandExecutor, TabCompleter {
    static final int MIN_SHARE_MINUTES = 10;
    private static final List<String> SUBS =
            Arrays.asList("status", "report", "share", "export", "unshare", "shared", "host", "plan", "reset", "help");
    private static final String P = "§8[§aTickWait§8] §r";
    private static final String G = "§7";
    private static final String W = "§f";
    private static final String Y = "§e";
    private static final String R = "§c";
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter UTC_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final TickWaitPlugin plugin;
    private final AtomicBoolean busy = new AtomicBoolean();

    TickWaitCommand(TickWaitPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status":
                status(sender);
                return true;
            case "report":
                report(sender);
                return true;
            case "share":
                share(sender, args.length > 1 && "confirm".equalsIgnoreCase(args[1]));
                return true;
            case "export":
                export(sender);
                return true;
            case "unshare":
                unshare(sender, args.length > 1 ? args[1] : null);
                return true;
            case "shared":
                listShared(sender);
                return true;
            case "host":
            case "plan":
                label(sender, sub, Arrays.copyOfRange(args, 1, args.length));
                return true;
            case "reset":
                reset(sender, args.length > 1 && "confirm".equalsIgnoreCase(args[1]));
                return true;
            default:
                help(sender, label);
                return true;
        }
    }

    private void status(CommandSender sender) {
        List<SecondSample> recent = plugin.store().recentSeconds();
        if (recent.isEmpty()) {
            sender.sendMessage(P + G + "Collecting the first samples — try again in a few seconds.");
            return;
        }
        Bucket last = new Bucket(0);
        for (SecondSample s : recent) {
            last.add(s);
        }
        sender.sendMessage(P + W + "Main thread, last " + last.seconds() + " s");
        for (String line : ChatText.describe(last, plugin.source().hasSchedstat())) {
            sender.sendMessage(line);
        }
        long nowMinute = Math.floorDiv(System.currentTimeMillis(), 60_000L);
        Bucket hour = new Bucket(0);
        for (Bucket m : plugin.store().minutes()) {
            if (m.key > nowMinute - 60) {
                hour.merge(m);
            }
        }
        if (hour.schedSeconds() > 0) {
            Derived d = new Derived(hour);
            sender.sendMessage(G + "Last hour: waited for a CPU " + W + ChatText.ms(d.waitPerSecondMs) + G + " a second ("
                    + W + ChatText.pct(d.waitShare) + G + ") · full window: " + W + "/tickwait report");
        }
        sender.sendMessage(ChatText.footer());
    }

    private void report(CommandSender sender) {
        List<Bucket> minutes = plugin.store().minutes();
        if (minutes.isEmpty()) {
            sender.sendMessage(P + G + "Nothing measured yet. Leave the server running; TickWait samples once a second.");
            return;
        }
        Report r = Report.build(minutes);
        sender.sendMessage(P + W + "Report: " + ChatText.duration(r.minutes) + " measured, "
                + UTC_TIME.format(Instant.ofEpochSecond(r.firstMinute * 60)) + " → "
                + UTC_TIME.format(Instant.ofEpochSecond(r.lastMinute * 60 + 59)) + " UTC");
        boolean sched = plugin.source().hasSchedstat();
        for (String line : ChatText.describe(r.totals, sched)) {
            sender.sendMessage(line);
        }
        if (sched && r.withPlayers.seconds() > 0 && r.withPlayers.seconds() < r.totals.seconds()) {
            Derived d = new Derived(r.withPlayers);
            sender.sendMessage(G + "While players were online (" + ChatText.duration(r.withPlayers.seconds() / 60)
                    + "): waited for a CPU " + W + ChatText.ms(d.waitPerSecondMs) + G + " a second (" + W
                    + ChatText.pct(d.waitShare) + G + ")");
        }
        Bucket worst = r.worstHourOfDay(30);
        if (worst != null) {
            Derived d = new Derived(worst);
            sender.sendMessage(G + "Most waiting, of hours with 30+ min measured: around " + W
                    + String.format(Locale.ROOT, "%02d:00 UTC", worst.key) + G + ", " + W
                    + ChatText.ms(d.waitPerSecondMs) + G + " a second");
        }
        Environment env = plugin.environment();
        sender.sendMessage(G + "CPU " + W + (env.cpuModel == null ? "unknown" : env.cpuModel) + G + " · limit "
                + W + (Double.isNaN(env.limitCores) ? "none" : String.format(Locale.ROOT, "%.2f cores", env.limitCores))
                + G + " · cgroup " + W + env.cgroup);
        sender.sendMessage(G + "Host label: " + W + orDash(plugin.label("host")) + G + " · plan: " + W
                + orDash(plugin.label("plan")) + G + " (set with /tickwait host|plan)");
        sender.sendMessage(G + "Get a link to share: " + W + "/tickwait share");
        sender.sendMessage(ChatText.footer());
    }

    private void share(CommandSender sender, boolean confirmed) {
        int minutes = plugin.store().minutes().size();
        if (minutes < MIN_SHARE_MINUTES) {
            sender.sendMessage(P + G + "Only " + minutes + " min measured so far. Share once there are at least "
                    + MIN_SHARE_MINUTES + " (a day of play is better).");
            return;
        }
        if (!confirmed) {
            sender.sendMessage(P + W + "Share uploads this report to truetick.gg and gives you a public link.");
            sender.sendMessage(G + "Uploaded: per-hour CPU, tick and player-count sums; the CPU model, CPU count");
            sender.sendMessage(G + "and limit, cgroup version, OS, kernel, Java and server versions; the host/plan");
            sender.sendMessage(G + "labels you set. Not uploaded: the server's address, player names, the world, chat.");
            sender.sendMessage(G + "Anyone with the link can view it; you can delete it with /tickwait unshare.");
            sender.sendMessage(G + "See the exact upload first: " + W + "/tickwait export");
            sender.sendMessage(G + "Upload now: " + W + "/tickwait share confirm");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            sender.sendMessage(P + G + "An upload is already running.");
            return;
        }
        String json = Report.build(plugin.store().minutes())
                .toJson(plugin.getDescription().getVersion(), plugin.environment(), emptyToNull(plugin.label("host")),
                        emptyToNull(plugin.label("plan")));
        String url = plugin.uploadUrl();
        String version = plugin.getDescription().getVersion();
        sender.sendMessage(P + G + "Uploading…");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> out = new ArrayList<>();
            try {
                Uploader.Response resp = new Uploader(version).post(url, json);
                String id = Json.stringField(resp.body, "id");
                String link = Json.stringField(resp.body, "url");
                String token = Json.stringField(resp.body, "deleteToken");
                if ((resp.status == 200 || resp.status == 201) && id != null && link != null && token != null) {
                    plugin.shared().add(id, link, token, System.currentTimeMillis(), url);
                    out.add(P + W + "Shared: " + link);
                    out.add(G + "Anyone with the link can view it. Remove it: " + W + "/tickwait unshare " + id);
                } else {
                    String err = Json.stringField(resp.body, "error");
                    out.add(P + R + "Upload failed: " + (err != null ? err : "HTTP " + resp.status));
                }
            } catch (IOException | RuntimeException e) {
                out.add(P + R + "Upload failed: " + e.getMessage());
            } finally {
                busy.set(false);
            }
            reply(sender, out);
        });
    }

    /** Writes exactly what /tickwait share would upload to a local file, without sending anything. */
    private void export(CommandSender sender) {
        List<Bucket> minutes = plugin.store().minutes();
        if (minutes.isEmpty()) {
            sender.sendMessage(P + G + "Nothing measured yet.");
            return;
        }
        String json = Report.build(minutes).toJson(plugin.getDescription().getVersion(), plugin.environment(),
                emptyToNull(plugin.label("host")), emptyToNull(plugin.label("plan")));
        String name = "report-" + FILE_TIME.format(Instant.now()) + ".json";
        try {
            java.nio.file.Path file = plugin.getDataFolder().toPath().resolve(name);
            java.nio.file.Files.write(file, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sender.sendMessage(P + G + "Wrote " + W + "plugins/TickWait/" + name + G
                    + " — exactly what /tickwait share would upload. Nothing was sent.");
        } catch (IOException e) {
            sender.sendMessage(P + R + "Couldn't write the file: " + e.getMessage());
        }
    }

    private void unshare(CommandSender sender, String id) {
        if (id == null) {
            sender.sendMessage(P + G + "Usage: /tickwait unshare <id> (see /tickwait shared)");
            return;
        }
        SharedLog.Entry entry;
        try {
            entry = plugin.shared().find(id);
        } catch (IOException e) {
            sender.sendMessage(P + R + "Couldn't read the shared list: " + e.getMessage());
            return;
        }
        if (entry == null) {
            sender.sendMessage(P + G + "No report " + id + " was shared from this server.");
            return;
        }
        // Delete where it was uploaded, even if upload-url has changed since.
        String base = entry.apiUrl != null ? entry.apiUrl : plugin.uploadUrl();
        String version = plugin.getDescription().getVersion();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> out = new ArrayList<>();
            try {
                Uploader.Response resp = new Uploader(version).delete(base + "/" + entry.id, entry.deleteToken);
                if (resp.status == 204 || resp.status == 200) {
                    plugin.shared().remove(entry.id);
                    out.add(P + G + "Deleted report " + W + entry.id + G + ".");
                } else if (resp.status == 404) {
                    plugin.shared().remove(entry.id);
                    out.add(P + G + "Report " + W + entry.id + G + " no longer exists there (already deleted?).");
                } else if (resp.status == 403) {
                    out.add(P + R + "The delete token doesn't match: report " + entry.id + " was NOT deleted.");
                } else {
                    String err = Json.stringField(resp.body, "error");
                    out.add(P + R + "Delete failed: " + (err != null ? err : "HTTP " + resp.status));
                }
            } catch (IOException | RuntimeException e) {
                out.add(P + R + "Delete failed: " + e.getMessage());
            }
            reply(sender, out);
        });
    }

    private void listShared(CommandSender sender) {
        try {
            List<SharedLog.Entry> entries = plugin.shared().entries();
            if (entries.isEmpty()) {
                sender.sendMessage(P + G + "Nothing shared from this server yet.");
                return;
            }
            sender.sendMessage(P + W + "Shared reports:");
            for (SharedLog.Entry e : entries) {
                sender.sendMessage(G + "  " + W + e.id + G + " · " + UTC_TIME.format(Instant.ofEpochMilli(e.epochMillis))
                        + " UTC · " + W + e.url);
            }
        } catch (IOException e) {
            sender.sendMessage(P + R + "Couldn't read the shared list: " + e.getMessage());
        }
    }

    private void label(CommandSender sender, String key, String[] rest) {
        if (rest.length == 0) {
            sender.sendMessage(P + G + capitalize(key) + " label: " + W + orDash(plugin.label(key)) + G
                    + " · set: /tickwait " + key + " <text> · clear: /tickwait " + key + " clear");
            return;
        }
        String value = String.join(" ", rest);
        if ("clear".equalsIgnoreCase(value.trim())) {
            value = "";
        }
        plugin.setLabel(key, value);
        sender.sendMessage(P + G + capitalize(key) + " label: " + W + orDash(plugin.label(key)));
    }

    private void reset(CommandSender sender, boolean confirmed) {
        if (!confirmed) {
            sender.sendMessage(P + Y + "This deletes every measured minute. Run /tickwait reset confirm");
            return;
        }
        try {
            plugin.store().clear();
            sender.sendMessage(P + G + "Cleared. Measuring from now.");
        } catch (IOException e) {
            sender.sendMessage(P + R + "Couldn't clear: " + e.getMessage());
        }
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(P + W + "Commands");
        sender.sendMessage(G + "/" + label + " status " + W + "— the last minute and hour");
        sender.sendMessage(G + "/" + label + " report " + W + "— everything measured (up to 7 days)");
        sender.sendMessage(G + "/" + label + " share " + W + "— upload the report, get a public link");
        sender.sendMessage(G + "/" + label + " export " + W + "— save what share would upload, send nothing");
        sender.sendMessage(G + "/" + label + " shared | unshare <id> " + W + "— your links, delete one");
        sender.sendMessage(G + "/" + label + " host|plan <text> " + W + "— label the report");
        sender.sendMessage(G + "/" + label + " reset confirm " + W + "— delete measured data");
        sender.sendMessage(ChatText.footer());
    }

    private void reply(CommandSender sender, List<String> lines) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String l : lines) {
                sender.sendMessage(l);
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefixed(SUBS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("share".equals(sub) || "reset".equals(sub)) {
                return prefixed(Collections.singletonList("confirm"), args[1]);
            }
            if ("host".equals(sub) || "plan".equals(sub)) {
                return prefixed(Collections.singletonList("clear"), args[1]);
            }
            if ("unshare".equals(sub)) {
                List<String> ids = new ArrayList<>();
                try {
                    for (SharedLog.Entry e : plugin.shared().entries()) {
                        ids.add(e.id);
                    }
                } catch (IOException ignored) {
                    // no suggestions
                }
                return prefixed(ids, args[1]);
            }
        }
        return Collections.emptyList();
    }

    private static List<String> prefixed(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(o);
            }
        }
        return out;
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
