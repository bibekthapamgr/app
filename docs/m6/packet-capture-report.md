# M6 — Packet capture report

**Milestone:** M6 · **Decisions:** ADR-0011 · **Verified run:** see §5 (filled from the verified CI run) · **Checks:** `scripts/m6_evidence.py`, `scripts/m5_evidence.py`

This report answers the M6 acceptance criteria that need packets: supported TCP traffic uses the configured proxy, no DNS goes around it, unsupported traffic stays on the phone, TLS to the proxy never falls back to plaintext, and HTTPS to a site stays end to end. It describes where the packets were captured, which controlled servers stood on the other side, what was checked, and what the verified run showed on each API level.

## 1. Capture points

| Capture | Where | Sees | API levels |
|---|---|---|---|
| The emulator's own capture (`-tcpdump`) | the emulator's network stack, outside the phone, on its mobile-data uplink | every frame the phone sends or receives on mobile data, full length | 29, 34, 36 (their uplink is mobile data); on 35 the uplink is Wi-Fi, which this capture does not see |
| tcpdump inside the phone | each uplink interface (`eth0`, `wlan0`, `radio0`…; never the VPN's `tun0`), as root, headers and the first 400 bytes of each packet | the uplink whichever it is, Wi-Fi included | 34, 35, 36 (API 29: adbd does not survive `adb root` and back on that image) |

A capture counts as covering the uplink only if it saw the app's connections to both proxies on the runner, and tcpdump inside the phone only if that interface's own tcpdump was still running after the tests (`guest-capture.txt`). The VPN's own interface is never captured: what matters is what leaves the phone. Each run also records the phone's IPv4 and IPv6 addresses and default routes (`guest-network.txt`): whether a direct IPv6 packet could have left the phone at all.

## 2. Controlled servers

All on the CI runner, which the emulator reaches as `10.0.2.2` (`scripts/e2e_env.sh`, checked from the runner first by `scripts/e2e_lab_check.py`):

| Server | Role |
|---|---|
| Dante (SOCKS5, :1080), Squid (HTTP CONNECT, :3128) | the user's proxy, with sign-in; they resolve names only with their own DNS server |
| stunnel :3129 (HTTP CONNECT over TLS), :1443 (SOCKS5 over TLS) | TLS to the proxy, certificate for `proxy.proxyflow.test` from the run's CA |
| stunnel :3130, :3131, :3132 | a certificate for another name, an expired one, one from a CA nothing trusts |
| gate :1090 | relays to a proxy, refuses, or hangs, as a test sets it while connected |
| dnsmasq 10.99.0.53 | the proxies' DNS server: knows the lab's names (`proxyflow.test`, `secure.proxyflow.test`, `proxyflow6.test` with only an IPv6 address); logs every query |
| dnsmasq 10.99.0.54 | the phone's uplink resolver (`-dns-server`): knows only the proxy's name `proxy.proxyflow.test`; logs every name the phone looks up itself |
| destinations | `proxyflow.test` = 10.99.0.1:8080 (by name), 10.99.0.2:8080 (by address), `[fd99::1]:8080` (IPv6), 10.0.2.2:8082 (the LAN address), `secure.proxyflow.test:8443` (HTTPS, the run's CA), UDP sinks on 10.99.0.1:9999 and `[fd99::1]:9999` |

None of the destinations can be reached from the phone except through a proxy without it showing: their addresses are not on the phone's network (a direct packet would appear in the captures), the phone's resolver does not know their names (a direct lookup would appear in its log and the captures), and the LAN destination listens on an address the phone cannot reach directly.

## 3. What was captured

The traffic of `RoutingEndToEndTest` and `TlsEndToEndTest` (and of the M5 tests on the same run), sent from the shell user, whom ProxyFlow's VPN covers. Each test logs every line it sends ("sent … to host:port") and every line that must get nowhere ("refused … to host:port").

## 4. What was checked

| Acceptance criterion | Check (`m6_evidence.py` unless noted) |
|---|---|
| Supported TCP traffic uses the configured proxy | Every sent line arrived exactly once at the destination it was sent to; each destination's connections equal the proxies' tunnels to it (Squid's and Dante's logs); the captures show the SOCKS5 and HTTP CONNECT requests the phone made, by name for names and by address for addresses, and no packet to or from any destination's address, IPv4 or IPv6 (IPv6 extension headers are walked), nor a direct connection to the LAN destination. M5's checks hold on the same run (`m5_evidence.py`). |
| No silent direct DNS fallback | The proxies' DNS server resolved the lab's names (`proxyflow.test`, `proxyflow6.test`, `missing.proxyflow.test`); the phone's uplink DNS server was never asked for one of them; the captures show no DNS query for them — also during the tests where the proxy refused, hung, or could not resolve the name. While ProxyFlow was connected (the periods from the VPN log in logcat), every lookup on the phone's own network other than the proxy's name and Android's network checks and time must be put down to an app Android lets leave a VPN (next row) — in every capture that covers the uplink, and in the uplink DNS server's log when it can be lined up — or it fails the run. |
| Nothing else leaves around the VPN while connected | Each capture that covers the uplink is lined up with logcat by the phone's own connections to its proxy right after each connection attempt (both UTC; half the gaps must agree within 1.5 s and the offset stay under 2 s); a capture that cannot be lined up fails the run. In the connected periods (from Connected until Disconnect, until the test has Android revoke the VPN, or until logcat ends; 2 s left out at each end), each new connection (a retransmitted SYN counts once), datagram, ping or packet of another protocol from the phone (any of its IPv4 or IPv6 addresses, or another address in their networks) is either expected — to its proxy (and the gate's control port, which the tests use from ProxyFlow's own process), to its network's resolver, or to the addresses Android's network checks and time service were given (HTTP/HTTPS, NTP) — or put down to its owner: the socket with its own ends in the phone's kernel tables (every TCP socket held open and every connected UDP socket, sampled four times a second, with its uid). Owners Android lets leave a VPN (system uids; apps granted `CONNECTIVITY_USE_RESTRICTED_NETWORKS`, `NETWORK_STACK` or `MAINLINE_NETWORK_STACK`) are reported; the shell, ProxyFlow or any other app fails the run. What no sample caught is put down to such apps only when Android's view of the VPN (logged after each connection from `dumpsys connectivity`) routed every uid but ProxyFlow's into it and ProxyFlow's own sockets reached nothing but its proxy; otherwise it fails the run. Each unexplained item is reported with its source port, hop limit and which captures saw it. No lookup a capture cut off. DNS over TCP is reassembled. |
| Android's view of the VPN; ProxyFlow's own sockets | After each connection the tests log what `dumpsys connectivity` says of the VPN: it must route every uid but ProxyFlow's (and other users') into it, let no app bypass it where Android says (Android 13 and later), and use ProxyFlow's DNS server 198.18.0.2. Every socket ProxyFlow's uid held (it is outside its VPN) must go to its proxies or the gate's control port. |
| TLS validation cannot be bypassed | Every connection to a TLS proxy port, in the captures and at the gate, opened with a TLS record: none was retried in plaintext after a refused certificate; the TLS fronts logged the handshakes the app ended with an alert. |
| Unsupported traffic fails closed as documented | The UDP sinks received nothing; the captures show no UDP to a lab address and no echo request (ICMP or ICMPv6) to one. The engine's refusals of TCP to its own addresses (Android's DNS-over-TLS probe) are reported from the test's log line. |
| HTTPS stays end to end | The HTTPS destination saw only TLS sessions for the name the client sent (`secure.proxyflow.test`), and the device's client saw that server's own certificate (checked in the test). |
| No credentials leak | No test password in logcat (`m5_evidence.py`); captures of plaintext proxies hold sign-ins by design (random per run, never published outside the run's artifacts). |

## 5. Results

Filled from the verified CI run (three attempts of one commit on API 29, 34, 35 and 36): see `docs/m6/M6-acceptance-report.md` §3 for the run and the per-level figures.

## 6. Reproducing

On a fresh Ubuntu runner or a disposable VM: the lab script installs packages and changes the host (loopback addresses, a system account, `/etc/hosts`, IPv6 enabled, `/usr/sbin/policy-rc.d` replaced and removed, any running Squid or Dante stopped) and undoes none of it.

```bash
bash scripts/e2e_env.sh                       # the lab (sudo; Ubuntu with IPv6)
adb push /tmp/pf/e2e.properties /data/local/tmp/proxyflow-e2e.properties
adb push /tmp/pf/pki/ca.crt /data/local/tmp/proxyflow-ca.pem
# start the emulator with -dns-server 10.99.0.54 -tcpdump uplink.pcap
./gradlew :app:connectedDebugAndroidTest
python3 scripts/m6_evidence.py --api 34 --pf /tmp/pf --out <dir with logcat.txt> --host-pcap uplink.pcap
python3 -m unittest discover -s scripts/tests   # the evidence checks on synthetic captures
```
