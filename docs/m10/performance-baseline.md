# M10 — Performance baseline

**Milestone:** M10 · **Verified commit:** `d48f4f8` · **CI:** run 42344467318, attempts 1–3 (the tables: attempt 1) · **Test:** `PerformanceEndToEndTest` · **Report script:** `scripts/m10_performance.py` · **Decisions:** ADR-0015 §2

What M1 set as V1's quality bars (`docs/m1/01-scope.md` §7), measured on each emulator of the CI matrix, each figure beside what the emulator itself allows without ProxyFlow. The bars are a phone's: an emulator on a CI runner shares the runner's CPUs with the lab's proxies and servers, and its network is the emulator's own, so these figures are a baseline that catches regressions, and the bars' own figures come from the owner's phone (`docs/m10/physical-phone-checklist.md`).

## 1. How each figure is measured

| Bar | Target | Measured on each emulator |
|---|---|---|
| Time to connected (warm, good network, proxy RTT < 150 ms) | ≤ 3 s at p90 | From the controller's connect (what a tap does) to the state Connected — the proxy's pre-flight check, the VPN interface, the engine, Android routing apps into the VPN — 20 times per protocol after one to warm up (p50, p90, max, nearest rank). An attempt the emulator's own uplink failed is left out and counted (`uplink_drops`, at most eight) |
| — (latency) | — | A new TCP connection's set-up from the shell through ProxyFlow and the proxy to the lab's destination, 20 per protocol: ProxyFlow answers the SYN once the proxy's tunnel is open (ADR-0002). Beside it, the same client's TCP connection straight to the proxy, before connecting |
| Fail-closed reaction when the proxy dies | New connections refused (RST) in < 1 s; no direct packet in the capture | Connected through the lab's gate in front of Dante; the gate stops listening (a proxy whose port is shut); the next 5 connections to the destination are timed and none may open; the moment the session leaves Connected (it reconnects after a few failures, and then holds new connections by design) is recorded, and the bar is judged on the longest any connection waited while the session was still connected — from when the shell client began it (its own stamp, on Android's clock, M10-92) to its result or to the session leaving, whichever came first; the evidence gives each probe's start from the session leaving (M10-99). Then a proxy that accepts and never answers: refused after the handshake timeout. No direct packet: the M6 evidence's captures of the whole run |
| Throughput through a local test proxy on a mid-range phone | ≥ 100 Mbit/s down, CPU < 50 % of one core at 50 Mbit/s | 16 MiB each way to the lab's bulk server, 3 times, the median, timed from after the request (up) or the first byte (down) — the connection's set-up is reported apart: through ProxyFlow from the shell, and from the same shell client straight through the proxy, which it then signs in to itself (outside the VPN, while ProxyFlow is disconnected: the path's own speed), the two paths alternating. ProxyFlow's CPU (user and system, `/proc/self/stat`) over the transfers through it, per gigabyte the engine carried meanwhile; and measured at 50 Mbit/s: an upload paced at that rate for 10 s, which counts for the bar only if it reached within 5 % of it (downloads' cost at 50 Mbit/s follows from their rate) |
| Engine memory | < 50 MB RSS at 200 concurrent connections | 200 connections held open at once from the shell through SOCKS5, twice: idle, and with 256 KiB in flight on each behind small receive buffers (the bar is judged on this one, in MB of 10^6 bytes), each connection handed data by ProxyFlow before the memory is read (at least 16 KiB, below the engine's buffers; the least is reported). The test counts its own connections — the engine's flows to the bulk server — since other apps' traffic goes through the VPN too. What the Go runtime holds from Android (heap, stacks, its own: the engine's share of the process), and the whole process's resident memory (the app's and the test's as well), disconnected, idle and at its peak |
| Battery when idle-connected | < 2 % per hour above baseline (screen off, no traffic) | A phone's battery only. The emulator gives what drives it: ProxyFlow's CPU over 5 minutes of Android's clock connected with the screen off (checked off), the phone's sleep included, and how much of them it was awake (M10-130); the traffic other apps sent meanwhile, and the engine's goroutines before and after, which must stay bounded |
| Crash-free sessions | ≥ 99.8 % in closed testing | M11's closed testing (Google Play's crash and ANR reports) |

## 2. Results, per Android version

Attempt 1 of the verified run, each level's device job (`M10 performance` notices). Uplink: what carried the phone's traffic to the lab, chosen per image (`docs/m10/compatibility-matrix.md` §1); in this attempt API 34's mobile data came up beside its Wi-Fi, and Android used the Wi-Fi.

| Android (API) | Uplink | Connected p50 / p90, SOCKS5 (ms) | HTTP (ms) | Set-up p50 through ProxyFlow, SOCKS5 / HTTP (ms) | TCP straight to the proxy p50, SOCKS5 / HTTP (ms) | A dead proxy: first refused / worst while connected (ms) | A silent proxy: refused after (ms) |
|---|---|---:|---:|---:|---:|---:|---:|
| 10 (29) | mobile data | 209 / 235 | 187 / 202 | 18 / 3 | 1 / 0 | 2 / 3 | 10006 |
| 11 (30) | mobile data | 104 / 113 | 85 / 101 | 17 / 1 | 0 / 0 | 0 / 1 | 10002 |
| 12 (31) | mobile data | 212 / 224 | 171 / 200 | 22 / 2 | 0 / 0 | 2 / 2 | 10002 |
| 12L (32) | mobile data | 362 / 421 | 279 / 341 | 23 / 4 | 1 / 0 | 4 / 4 | 10004 |
| 13 (33) | Wi-Fi | 656 / 704 | 651 / 671 | 520 / 501 | 1 / 1 | 2 / 2 | 10003 |
| 14 (34) | mobile data and Wi-Fi, both up | 331 / 440 | 324 / 759 | 525 / 502 | 1 / 1 | 3 / 5 | 10003 |
| 15 (35) | Wi-Fi | 467 / 699 | 356 / 678 | 518 / 502 | 1 / 1 | 1 / 2 | 10003 |
| 16 (36) | mobile data | 267 / 331 | 234 / 288 | 23 / 2 | 0 / 0 | 1 / 2 | 10006 |

| Android (API) | Down through ProxyFlow, SOCKS5 / HTTP (Mbit/s) | Down straight through the proxy | Up through ProxyFlow | Up straight through the proxy | CPU at 50 Mbit/s, an upload paced at it, SOCKS5 / HTTP (% of one core) | CPU at 50 Mbit/s from the rates, the costliest (%) |
|---|---:|---:|---:|---:|---:|---:|
| 10 (29) | 160.0 / 165.1 | 294.2 / 321.8 | 97.5 / 102.6 | 123.4 / 125.0 | 65.0 / 65.1 | 55.0 |
| 11 (30) | 601.3 / 545.2 | 758.0 / 713.5 | 547.8 / 636.1 | 749.8 / 754.0 | 12.5 / 11.9 | 7.9 |
| 12 (31) | 287.3 / 310.9 | 359.7 / 469.2 | 284.4 / 273.9 | 150.5 / 170.3 | 22.1 / 21.2 | 19.1 |
| 12L (32) | 282.4 / 287.4 | 400.1 / 350.3 | 278.5 / 300.3 | 131.8 / 104.4 | 24.4 / 24.8 | 15.9 |
| 13 (33) | 123.5 / 118.3 | 131.3 / 132.2 | 69.1 / 71.9 | 79.2 / 76.7 | 20.9 / 21.1 | 19.2 |
| 14 (34) | 69.6 / 68.1 | 66.0 / 61.9 | 40.9 / 41.1 | 44.2 / 43.0 | 10.6 / 10.4 | 40.4 |
| 15 (35) | 143.6 / 134.3 | 127.0 / 132.9 | 72.8 / 76.7 | 79.7 / 83.9 | 14.7 / 13.0 | 20.0 |
| 16 (36) | 332.9 / 288.6 | 428.6 / 398.3 | 329.8 / 303.7 | 384.6 / 384.6 | 20.0 / 20.4 | 14.8 |

| Android (API) | Connections | Idle: Go runtime holds, before → with them (MiB) | Idle: process RSS, disconnected → connected → peak (MiB) | 256 KiB in flight on each: Go runtime holds / process RSS peak (MB) | Idle, screen off: CPU (% of one core) |
|---|---:|---:|---:|---:|---:|
| 10 (29) | 200 / 200 | 7.4 → 29.2 | 191.4 → 169.0 → 169.3 | 64.5 / 190.8 | 0.310 |
| 11 (30) | 200 / 200 | 6.8 → 26.1 | 214.1 → 195.8 → 209.2 | 60.2 / 260.3 | 0.073 |
| 12 (31) | 200 / 200 | 7.1 → 28.6 | 199.4 → 194.4 → 194.6 | 61.3 / 203.8 | 0.110 |
| 12L (32) | 200 / 200 | 10.4 → 28.4 | 200.5 → 204.6 → 205.1 | 61.5 / 214.6 | 0.183 |
| 13 (33) | 200 / 200 | 7.3 → 30.9 | 131.8 → 116.6 → 116.9 | 67.3 / 130.1 | 0.103 |
| 14 (34) | 200 / 200 | 8.0 → 31.0 | 194.2 → 195.8 → 198.6 | 71.0 / 205.3 | 0.093 |
| 15 (35) | 200 / 200 | 7.6 → 29.1 | 215.8 → 217.7 → 218.7 | 63.4 / 264.3 | 0.057 |
| 16 (36) | 200 / 200 | 7.5 → 26.5 | 222.7 → 223.2 → 226.1 | 54.9 / 259.0 | 0.073 |

On API 34 the paced upload could not be held at 50 Mbit/s (42.2 and 42.8 Mbit/s: the emulator's Wi-Fi path), so its CPU column there is the CPU at that rate, left out of the bar, which the last column's figure from the measured rates stands in for (`scripts/m10_performance.py`, M10-55, M10-69).

## 3. What the figures say

- **Connecting** takes 85 to 656 ms at p50 and 101 to 759 ms at p90 on every level — the proxy's pre-flight check, the VPN interface, the engine and Android's routing. On the Wi-Fi levels (API 33 to 35) it is slower and more spread, and the set-up of a new connection through ProxyFlow there (about 500 ms at p50 against 1 to 23 ms on mobile data) shows why: the emulator's virtio Wi-Fi adds about half a second to each new TCP connection to the lab, which the same client's connection straight to the proxy (1 ms) does not carry only because that one is to the runner's own address.
- **Fail-closed** is immediate: with the proxy's port shut, the first new connection is refused in 0 to 4 ms and the worst of the three made while the session was still connected in 1 to 5 ms, each timed from its own stamped start (M10-92, M10-99); none of the 5 opened, and the capture shows no direct packet (the M6 evidence). A proxy that accepts and never answers is failed after the 10 s handshake timeout on every level.
- **Throughput** through ProxyFlow is 160 to 601 Mbit/s down and 98 to 636 up on the mobile-data levels, 68 to 144 down and 41 to 77 up on the Wi-Fi levels; beside it, the same client straight through the proxy gets 294 to 758 down on mobile data and 62 to 133 on Wi-Fi. So ProxyFlow carries 55 to 80 % of what the emulator's path gives on mobile data, and all of it on Wi-Fi, where the path itself is the limit; the Wi-Fi path's speed varies from run to run and level to level (68 Mbit/s on API 34 in this attempt, 118 to 144 on 33 and 35; the other way round in other runs).
- **CPU at 50 Mbit/s** is 8 to 25 % of one core on API 30 to 33, 35 and 36, 40 % from the rates on API 34 (where the paced upload could not reach 50 Mbit/s) — and 65 % on API 29, whose emulator is the slowest image of the matrix on a shared runner (its download straight through the proxy is 294 to 322 Mbit/s against 350 to 758 on the other mobile-data levels). Uploads cost more than downloads (the engine copies each byte from the TUN device into the proxy's socket; downloads go the other way with larger reads).
- **Memory:** the Go runtime holds 6.8 to 10.4 MiB connected and idle, 26 to 31 MiB with 200 idle connections, and 52 to 68 MiB (54.9 to 71.0 MB) with 256 KiB in flight on each, having handed each connection at least 37 to 60 KiB on when read — the buffers that carry throughput (M10-63). The whole process (the app's and the tests' code too) peaks at 117 to 264 MiB RSS.
- **Idle and connected, the screen off,** ProxyFlow uses 0.06 to 0.31 % of one core (2 to 11 s an hour) over 5 minutes of the phone's clock, the phone awake throughout in this attempt; the traffic meanwhile is other apps', and the engine's goroutines stay bounded.

## 4. Against the bars

| Bar | Target | Verified run, every level | Verdict |
|---|---|---|---|
| Time to connected | ≤ 3 s at p90 | 101 to 759 ms (the slower protocol's p90 on each level) | **Within** on all eight |
| Fail-closed reaction when the proxy dies | New connections refused in < 1 s; no direct packet | 1 to 5 ms (the worst while connected); none opened; no direct packet in any capture | **Within** on all eight |
| Throughput down through a local test proxy | ≥ 100 Mbit/s | 160 to 601 Mbit/s on API 29 to 32 and 36; 118 and 134 on API 33 and 35; 68.1 on API 34, where the same client straight through the proxy gets 61.9 to 66.0 | **Within** on seven; **past** on API 34, where the emulator's Wi-Fi path is itself below the bar without ProxyFlow — the bar measures the emulator there, not the app (ADR-0015 §2); the level it falls on varies from run to run (API 33 and 35 in the previous run) |
| CPU at 50 Mbit/s | < 50 % of one core | 8 to 40 % (the costliest of the paced uploads and the rates) on API 30 to 36; 65 % on API 29 | **Within** on seven; **past** on API 29's emulator, the slowest image on a shared runner, where even the path without ProxyFlow carries half of the others' (ADR-0015 §2); a phone's figure is the checklist's |
| Engine memory | < 50 MB at 200 concurrent connections | 54.9 to 71.0 MB held by the Go runtime with 256 KiB in flight on each; 26 to 31 MiB idle | **Past** on all eight, with data in flight; within idle. Accepted for V1 as M10-63: the excess is the connections' buffers (64 KiB TCP, 32 KiB relay, ADR-0001), which carry the throughput above; a smaller buffer would meet the bar at the cost of it, and 200 connections each with 256 KiB in flight is a load no phone's apps hold at once |
| Battery when idle-connected | < 2 % per hour above baseline | A phone's only; the emulator's driver, ProxyFlow's CPU over 5 minutes with the screen off, is 0.06 to 0.31 % of one core | **Not measured** here: `docs/m10/physical-phone-checklist.md` (overnight on the owner's phone) |
| Crash-free sessions | ≥ 99.8 % | — | M11's closed testing |

Across the three attempts the picture holds: connecting (p90 113 to 787 ms) and fail-closed (1 to 13 ms) within on every level each time; the engine's memory with data in flight past on every level each time (54.9 to 71.0 MB); CPU past on API 29 alone each time (65 to 82 %); and throughput down past only on Wi-Fi levels, a different set each attempt (API 34 in attempt 1; 33, 34 and 35 in attempt 2; 33 and 34 in attempt 3), the emulator's Wi-Fi path giving 62 to 133 Mbit/s without ProxyFlow.

Two bars are past on every level or on one, each for a reason the figures beside them show (the emulator's own path, or the buffers that carry the throughput bar), and each is recorded here and in the defect register rather than met by weakening the measurement.
