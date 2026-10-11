# TickWait

How long does your Minecraft server's main thread wait for a CPU core — and why?

When the machine under your server is busy, the main thread sits in the kernel's run queue
and every tick starts late. Nothing in the server console shows that wait. TickWait reads it
from the kernel once a second and splits it by cause:

- **at the plan's CPU limit** — the server's cgroup was throttled: it had used its CPU
  allowance for the period;
- **behind the server's own threads** — garbage collection, chunk workers and plugin threads
  held the cores;
- **other work on the same cores** — the cores were busy with work that isn't this server's:
  other servers, the host.

On Paper and its forks it also splits every tick into CPU work, waiting for a CPU, and the
rest (GC pauses, disk, locks, and on a VM the moments its host took the core away).

How to read the numbers, with calibration runs: <https://truetick.gg/tickwait>

## Install

1. Drop `TickWait-<version>.jar` into `plugins/` and restart.
2. After a minute, `/tickwait status` shows the last 60 seconds.
3. Leave it running through your busy hours — it keeps up to 7 days of per-minute numbers in
   `plugins/TickWait/minutes.csv`, across restarts.
4. `/tickwait report` sums everything; `/tickwait share` turns it into a public link.

Needs a Linux host and Paper, Spigot, Purpur or another Paper fork, Java 8 or newer. The
per-tick split needs a Paper build with tick events (current Paper and its forks); elsewhere you get the per-second split. Folia isn't supported yet (it has no single main
thread).

Run on (2026-10-11): Paper 1.21.11 and Purpur 1.21.11 on Java 25 (per-second and per-tick
split), Spigot 1.21.11 on Java 25 and Paper 1.8.8 on Java 8 (per-second split).

## Commands

All need `tickwait.admin` (operators by default).

| Command | What it does |
|---|---|
| `/tickwait status` | The last 60 seconds and the last hour |
| `/tickwait report` | Everything measured so far (up to 7 days) and the busiest hour |
| `/tickwait export` | Saves exactly what `share` would upload to `plugins/TickWait`, sends nothing |
| `/tickwait share` | Uploads the report and prints a public link (asks first) |
| `/tickwait shared`, `/tickwait unshare <id>` | Lists your links; deletes one |
| `/tickwait host <text>`, `/tickwait plan <text>` | Optional labels shown on a shared report |
| `/tickwait reset confirm` | Deletes measured data |

## What it reads

| Source | Meaning |
|---|---|
| `/proc/self/task/<main thread>/schedstat` | Time the main thread ran, and time it was runnable but waiting for a CPU |
| cgroup `cpu.stat` | Time the cgroup was throttled at its CPU limit |
| cgroup `cpu.max` / `cpu.cfs_quota_us` | The CPU limit itself |
| `/proc/self/stat` | CPU time of the whole server process |
| `/proc/stat` | Busy time of the CPUs the server may run on, and steal time |
| Bukkit API | Ticks and players online |

Waiting in a second when the cgroup was throttled counts toward the plan's limit, up to how long
it was throttled — an upper bound, since the kernel doesn't say which thread each throttled
moment held back. The rest is shared between the server's other threads and other work on its
cores, in proportion to the CPU time each used in that second. Time that is neither running nor
waiting — GC pauses, disk, locks, interrupts, and on a VM the moments the hypervisor took the
core away (steal) — lands in "idle or blocked" per second and "other" per tick.

Where `/proc/stat` is a per-container view (LXCFS), other work on the server's cores can't be
measured, and waiting the limit doesn't explain is reported as unattributed. Only the server's own
cgroup is read: a CPU limit set on a parent (the container or VM around it, as some LXC and
Proxmox setups do) isn't seen, so that waiting is shared out between the other causes. A counter the host
doesn't expose shows as "—" (or `n/a` in chat), never as 0.

## What `share` uploads

Per-hour sums of the counters above (player counts included), an hour-of-day profile, the CPU
model, CPU count and limit, cgroup version, OS, kernel, Java and server versions, and the
host/plan labels if you set them. Not the server's address, player names, world or chat. `/tickwait export` writes the exact upload to a local file first.
Reports are public to anyone with the link, not indexed by search engines, and deleted with
`/tickwait unshare <id>`.

## Build

```bash
./gradlew build   # built and tested with JDK 21; the jar targets Java 8
```

The jar lands in `build/libs/`. Tests run against kernel files captured from a real Linux
host.

## Source and releases

TickWait is developed in TrueTick's monorepo;
[github.com/truetick-gg/tickwait](https://github.com/truetick-gg/tickwait) mirrors each release,
and its Releases page has the jar. Issues are welcome there — for code changes, please open an
issue first.

## License

MIT. Made by [TrueTick](https://truetick.gg), a Minecraft host — TickWait measures every
host the same way, ours included.
