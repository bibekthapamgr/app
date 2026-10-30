# M1 feasibility spike

Prototype that proves the proposed networking approach before any production code is written: a TUN interface (standing in for Android's `VpnService`) → a Go engine on gVisor netstack → a **real, controlled proxy** → a destination, with packet captures at every hop. Results and interpretation: `docs/m1/09-feasibility-report.md`.

> This is feasibility code, not the product. M4 re-implements the engine as a library with the API in `docs/m1/06-architecture.md` §5, carrying over the adapters, tests and fuzz targets.

## Layout

```
engine/                Go module proxyflow/spike/engine (Go 1.27.3)
  adapters/            SOCKS5, HTTP CONNECT, TLS-to-proxy, error codes, hostname validation (+ tests, fuzz)
  fakedns/             fake-IP pool (198.18.0.0/15) and DNS responder (+ tests, fuzz)
  engine/              netstack wiring, TCP forwarder (dial-before-accept), UDP/ICMP policy, stats/events (+ hermetic tests)
  cmd/pfspike/         Linux harness binary (attaches to an existing TUN)
harness/               Python lab: namespaces, test PKI, instrumented proxy/destinations, pcap analysis, report
tests/                 pytest end-to-end tests E01–E16
hev/                   test-only patch for the hev-socks5-tunnel comparison on IPv6-disabled kernels
run_spike.sh           build → Go tests (race) → fuzzing → end-to-end → SUMMARY.md
evidence/              one folder per run: run-m1-20261030T003301Z (canonical, from the current code);
                       run-20261029T151316Z, run-verify-20261029T161415Z and
                       run-final-20261029T164118Z (earlier code, kept as history);
                       second-check-ping-probes-20261030 (the second check's ping probes, rerun after the fix)
bin/                   build output (git-ignored)
```

## Requirements

Linux with root (network namespaces, TUN, `iptables`), Go 1.27.3+, Python 3.11+ and:

```bash
sudo apt-get install -y iproute2 iptables tcpdump tshark iperf3 squid dante-server stunnel4 \
  dnsutils iputils-ping curl openssl python3-pip
pip install pytest cryptography
```

## Run

```bash
cd spike
sudo -E FUZZTIME=30s ./run_spike.sh                                    # full run
sudo -E HEV_TAG=2.19.3 FUZZTIME=30s ./run_spike.sh                     # also builds hev-socks5-tunnel for E13/E15
sudo -E HEV_TAG=2.19.3 FUZZTIME=30s GO_TEST_REPEAT=20 ./run_spike.sh   # as the canonical run: engine tests repeated 20× for flake evidence
```

The run writes `evidence/run-<UTC>/` containing `SUMMARY.md`, `summary.json`, `junit.xml`, `go-test.json`, `fuzz.log`, `environment.json` (kernel, Go and tool versions), `binary-sizes.txt`, `go-test-repeat.log` (with `GO_TEST_REPEAT`: the command and how often each engine test passed) and, per test, `verdict.json` with the engine's `engine-events.jsonl`, `engine-stats.json` and `engine-stdout.log`. Tests that look at the wire also keep `pcap/` (up to four capture points; E12–E14 have none), and E01 keeps the app's `client-response.json`.

Credentials for the lab proxies and the test PKI are throwaway values generated per run and used only inside the namespace lab. The engine never writes them to its logs, stats or events (E14). Where they still appear:

- **Packet captures of the plaintext tests** — E02 and E05 (SOCKS5, password in clear) and E03 and E05 (HTTP Basic, Base64). Showing that is what E05 is for.
- **The lab's private keys, key bundles and Squid password file** under `_lab/` — git-ignored and left out of the delivery package.
- **hev's configuration** for E13 holds the Dante password: the real file lives in a private temporary directory and evidence gets a copy with `password: '<redacted>'`. The two oldest runs were redacted after the fact (`'<redacted after the run>'`).

Lab teardown removes the namespaces, the proxies and the harness's `pfdante` system account, also when setup fails half-way.

## What each test proves

| Test | Proves |
|---|---|
| E01 | One TCP request through the TUN, the engine and a controlled SOCKS5 proxy; hostname sent to the proxy; nothing direct |
| E02 | SOCKS5 username/password against Dante; wrong password fails closed |
| E03 | HTTP CONNECT + Basic against Squid; HTTPS end to end; per-destination refusal keeps the tunnel up |
| E04 | TLS to proxy: two valid cases hide secrets on the wire; expired, wrong-host, untrusted and plaintext-port cases fail closed |
| E05 | What plaintext SOCKS5/HTTP expose on the wire |
| E06 | IPv6 end to end (needs an IPv6-enabled kernel) |
| E07 | DNS answered locally from the fake pool; no DNS leaves the phone |
| E08 | Non-DNS UDP rejected inside the tunnel; zero UDP on the uplink |
| E09 | Ping never answered with a fake reply: kernel pings (small and 2,000-byte fragmented) plus raw IPv4 and IPv6 echo requests injected at the TUN — small, 2 KB fragmented, behind an extension header, atomic fragment — get nothing back |
| E10 | Proxy outage → fast refusal, no bypass, automatic recovery |
| E11 | Engine crash vs interface teardown, with and without lockdown emulation |
| E12 | 200 concurrent flows complete and are released; then 200 flows are held open and the engine is stopped under them (stop time, goroutines, apps' connections ended) |
| E13 | Throughput / CPU / memory vs hev-socks5-tunnel |
| E14 | No credentials or payload in engine output |
| E15 | hev-socks5-tunnel baseline in the same harness |
| E16 | Only the proxy's hostname is resolved on the underlying network |

## How Android is emulated

| Android | Lab |
|---|---|
| `VpnService.Builder.establish()` + routes | `tun0` with default route in a policy table |
| `protect(fd)` / `addDisallowedApplication(self)` | socket mark 438 + `ip rule fwmark 438 lookup main` |
| Always-on + "Block connections without VPN" | `iptables -A OUTPUT -o dev-up -m mark ! --mark 438 -j REJECT` (E11-C) |
| Underlying network resolver | `underlay_dns.py` on 10.10.0.1:53 |

Not emulated: Android's foreground-service rules, permission revocation, OEM behaviour, device performance — covered in M5, M8 and M10.
