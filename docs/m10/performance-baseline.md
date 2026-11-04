# M10 — Performance baseline

**Milestone:** M10 · **Verified commit:** `{SHA}` · **CI:** run {RUN}, attempts 1–3 (the tables: attempt {ATTEMPT}) · **Test:** `PerformanceEndToEndTest` · **Report script:** `scripts/m10_performance.py` · **Decisions:** ADR-0015 §2

What M1 set as V1's quality bars (`docs/m1/01-scope.md` §7), measured on each emulator of the CI matrix, each figure beside what the emulator itself allows without ProxyFlow. The bars are a phone's: an emulator on a CI runner shares the runner's CPUs with the lab's proxies and servers, and its network is the emulator's own, so these figures are a baseline that catches regressions, and the bars' own figures come from the owner's phone (`docs/m10/physical-phone-checklist.md`).

## 1. How each figure is measured

| Bar | Target | Measured on each emulator |
|---|---|---|
| Time to connected (warm, good network, proxy RTT < 150 ms) | ≤ 3 s at p90 | From the controller's connect (what a tap does) to the state Connected — the proxy's pre-flight check, the VPN interface, the engine, Android routing apps into the VPN — 20 times per protocol after one to warm up (p50, p90, max, nearest rank). An attempt the emulator's own uplink failed is left out and counted |
| — (latency) | — | A new TCP connection's set-up from the shell through ProxyFlow and the proxy to the lab's destination, 20 per protocol: ProxyFlow answers the SYN once the proxy's tunnel is open (ADR-0002). Beside it, the same client's TCP connection straight to the proxy, before connecting |
| Fail-closed reaction when the proxy dies | New connections refused (RST) in < 1 s; no direct packet in the capture | Connected through the lab's gate in front of Dante; the gate stops listening (a proxy whose port is shut); the next 5 connections to the destination are timed and none may open. Then a proxy that accepts and never answers: refused after the handshake timeout. No direct packet: the M6 evidence's captures of the whole run |
| Throughput through a local test proxy on a mid-range phone | ≥ 100 Mbit/s down, CPU < 50 % of one core at 50 Mbit/s | 16 MiB each way to the lab's bulk server, 3 times, the median: through ProxyFlow from the shell, and from ProxyFlow's own process straight through the proxy (outside the VPN: the path's own speed). ProxyFlow's CPU (user and system, `/proc/self/stat`) over the transfers through it, as seconds per GB and as the share of one core 50 Mbit/s would take |
| Engine memory | < 50 MB RSS at 200 concurrent connections | 200 connections held open at once from the shell through SOCKS5; what the Go runtime holds from Android (heap, stacks, its own: the engine's share of the process), and the whole process's resident memory (the app's and the test's as well), idle and at its peak |
| Battery when idle-connected | < 2 % per hour above baseline (screen off, no traffic) | A phone's battery only. The emulator gives what drives it: ProxyFlow's CPU over 5 minutes connected with the screen off, the traffic other apps sent meanwhile, and the engine's goroutines before and after |
| Crash-free sessions | ≥ 99.8 % in closed testing | M11's closed testing (Google Play's crash and ANR reports) |

## 2. Results, per Android version

{TABLES}

## 3. What the figures say

{READING}

## 4. Against the bars

{BARS}
