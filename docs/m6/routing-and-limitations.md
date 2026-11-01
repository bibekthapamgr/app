# M6 — Traffic routing, DNS, TLS and limitations

**Milestone:** M6 · **Decisions:** ADR-0003, ADR-0004, ADR-0005, ADR-0011 · **Evidence:** device tests on API 29, 34, 35 and 36 (`RoutingEndToEndTest`, `TlsEndToEndTest`), `scripts/m6_evidence.py`, `docs/m6/packet-capture-report.md`

This page is the single source for what ProxyFlow carries, what it refuses, and what the app, the store listing and support texts may claim. It supersedes the "Device check (M6)" rows of `docs/m1/02-traffic-support-matrix.md`.

ProxyFlow carries **TCP** from apps to the user's HTTP CONNECT or SOCKS5 proxy. It answers **DNS** itself, so that names reach the proxy as names and the proxy resolves them. It refuses **UDP** and **ping**. Nothing is ever sent around the proxy: a failure ends the connection on the phone.

## 1. Traffic matrix (while connected)

"Verified on devices" means an emulator test with real proxies on the CI runner, checked afterwards against the proxies' and destinations' logs and packet captures of the phone's uplink (§6).

### TCP

| Traffic | What ProxyFlow does | What the user notices | Verified on devices |
|---|---|---|---|
| TCP to a name (normal app traffic) | The app's lookup gets a placeholder IPv4 address (198.18.0.0/15); the connection to it goes to the proxy as **name:port** (SOCKS5 ATYP 3, `CONNECT name:port`) and the proxy resolves the name | Works; sites see the proxy's address | M5 tests; `namesAreAnsweredOnThePhoneAndResolvedByTheProxy`; captures show SOCKS5 requests by name, never the destination's address |
| TCP to an IPv4 address | To the proxy as **address:port** | Works | `ipv4AddressesGoThroughTheProxyByAddress` (SOCKS5 and HTTP) |
| TCP to an IPv6 address | To the proxy as **[address]:port** (SOCKS5 ATYP 4, `CONNECT [addr]:port`) | Works if the proxy has IPv6 | `ipv6AddressesGoThroughTheProxy` (SOCKS5 and HTTP) |
| TCP to a name that has only IPv6 addresses | The phone gets an IPv4 placeholder and no IPv6 answer; the proxy resolves the name and connects over IPv6 | Works if the proxy has IPv6 | `ipv6OnlyNamesAreReachedThroughTheProxy` |
| TCP to the local network (printers, NAS, the router's page) | Captured like any address and sent to the proxy, which normally cannot reach the user's LAN | Local devices are not reachable while connected | `lanAddressesGoThroughTheProxyToo`: the emulator's view of the runner (10.0.2.2) answered only through the proxy |
| TCP to a placeholder from an earlier session | Refused (RST), `STALE_DNS_MAPPING` | The app looks the name up again | unit `TestIPLiteralAndStaleMapping` |
| TCP to ProxyFlow's own addresses (other than DNS) | Refused on the phone (RST), counted apart (`local_refused`): the VPN's DNS server 198.18.0.2 and the rest of 198.18.0.0–198.18.0.255; Android's DNS-over-TLS probe to 198.18.0.2:853 is the case that occurs. The TUN's own addresses (198.18.0.1, `fd00:66::1`) are local to the phone: its kernel answers them | Nothing (Android uses plain DNS) | unit `TestTCPToTheEnginesOwnAddressesIsRefusedLocally`; the device runs report the engine's count (§7) |

### DNS

| Traffic | What ProxyFlow does | Verified on devices |
|---|---|---|
| DNS over UDP port 53 to **any** server (the VPN's 198.18.0.2, the network's own resolver, a public one) | Answered on the phone; never sent anywhere | `namesAreAnsweredOnThePhoneAndResolvedByTheProxy` asks 198.18.0.2, 10.0.2.3 and 8.8.8.8 and gets the same placeholder from each; captures and the phone's uplink DNS server show no lookup of a lab name |
| DNS over TCP port 53 to any server | Answered on the phone the same way (ADR-0011); at most 32 such connections at once, apart from DNS over UDP | the same test, over TCP to 198.18.0.2 and 8.8.8.8 |
| `A` | A placeholder IPv4 address, TTL 5 s | the same test |
| `AAAA`, `HTTPS`, `SVCB` | `NOERROR`, no records: apps use the IPv4 answer, the proxy chooses the family | the same test |
| Other types (`TXT`, `MX`, `SRV`, `PTR`…) | `REFUSED` | the same test (`TXT`) |
| A name the proxy cannot resolve | The app's lookup still gets a placeholder; the connection fails at the proxy (SOCKS5 "host unreachable", HTTP 503) and the app gets a RST; nothing is looked up anywhere else | `aNameTheProxyCannotResolveFailsWithoutAFallback`; the proxies' DNS server logged the name, the phone's never did |
| The proxy's own hostname | Looked up by ProxyFlow on the phone's network (the app is outside its VPN): the only lookup outside the proxy. Use the proxy's IP address to avoid it | `theProxysOwnNameIsLookedUpByTheApp`; `aProxyNameThatDoesNotExistNeverConnects` (`PROXY_DNS_FAILED`) |
| DNS over TLS or HTTPS chosen by an app (Chrome secure DNS, DoH libraries) | Ordinary TCP through the proxy (encrypted; ProxyFlow cannot answer it); the app then connects by address, still through the proxy | Follows from the TCP rows; not run separately |
| Android Private DNS "Automatic" (the default) | Android's probe to the VPN's DNS server on port 853 is refused on the phone; Android then uses plain DNS, which ProxyFlow answers | Every device test runs with it (the setting as shipped) and gets plain DNS answered; the evidence reports how many probes the engine refused (§7) |
| Android Private DNS "Private DNS provider hostname" (strict) | Android's DNS-over-TLS connection goes through the proxy like any TCP; apps get real addresses and connect by address, through the proxy | Not automated (§4) |

### UDP, ICMP and other protocols

| Traffic | What ProxyFlow does | What the user notices | Verified on devices |
|---|---|---|---|
| UDP, any port except 53 (QUIC/HTTP-3, video calls, games, STUN, NTP from apps), IPv4 and IPv6 | Refused on the phone with ICMP "port unreachable"; never sent; counted by port | Browsers and most apps fall back to TCP at once; calls and games may fail | `udpIsRefusedOnThePhoneAndNeverSent`: "port unreachable" for IPv4 and IPv6, the lab's UDP sinks received nothing, the captures show no UDP to a lab address (IPv4 or IPv6) |
| Multicast and broadcast discovery (mDNS, SSDP) | UDP, so refused | Local devices are not discovered | Follows from the UDP row (unicast UDP is what the device test sends) |
| ICMP and ICMPv6 echo (ping), any size | Dropped before the network stack, never answered | Ping shows 100 % loss | `pingsAreNeverAnswered`: `ping` and `ping6` got no reply, the engine counted the requests and sent no reply, the captures show no echo request to a lab address |
| Traceroute | No answer (ICMP); the first UDP probe is refused by ProxyFlow itself | Not useful while connected | Follows from the rows above |
| Other IP protocols (GRE, ESP, SCTP…) | Dropped | VPN-in-VPN and similar do not work | unit (M4) |

## 2. Failure behaviour: nothing goes around the proxy

| Situation (while connected) | Result | Verified on devices |
|---|---|---|
| The proxy stops accepting connections | New connections get a RST at once; names are still answered on the phone; traffic flows again as soon as the proxy is back; Home counts the failures | `whenTheProxyStopsAnsweringNothingGoesAroundIt` (the gate refuses, then forwards again): lines to a name, to an IPv4 and to an IPv6 address all fail, and none arrives anywhere |
| The proxy accepts connections and never answers | Each new connection fails after the 10 s handshake timeout; counted as a proxy timeout | `aProxyThatHangsIsReportedAsATimeout` (to a name and to an address) |
| The proxy's certificate changes to one the phone does not trust | Each new connection is refused (`TLS_UNTRUSTED`), nothing is sent unencrypted; Home counts the TLS failures and names the check; recovers when the valid certificate is back | `aCertificateThatChangesWhileConnectedIsRefusedForEachConnection` (to a name and to an address) |
| The proxy cannot resolve or reach one name | That connection fails; others continue; counted as a DNS problem | `aNameTheProxyCannotResolveFailsWithoutAFallback` |
| Switch, restart, engine failure | As in M5: traffic held, never let out | M5 (`VpnEndToEndTest`) |
| Android revokes the VPN (another VPN app starts, or the user withdraws the permission) | The VPN is gone, so apps use the normal network (Android's behaviour); Home and an alert say so. Only Always-on VPN with "Block connections without VPN" keeps them blocked (guided in M8) | M5 `revocationClosesEverythingAndSaysSo` |

At connect time (before the VPN interface exists) every failure — TLS, DNS, timeout, sign-in, refusal — ends the attempt with nothing changed on the phone and a code-specific explanation (board B6b, `docs/m1/04-ux-screens-and-error-states.md` §4).

From M7 on, three proxy-level failures in a row while connected switch Home to an error state (the escalation rule); M6 counts them.

## 3. TLS to the proxy versus HTTPS to sites

Two different kinds of encryption, often confused:

| | TLS to the proxy (a profile setting) | HTTPS to a site (the app's own) |
|---|---|---|
| Between | this phone and the proxy | the app and the site, end to end |
| Hides from the network between phone and proxy | the proxy username and password, the names and addresses of the sites opened, everything the apps send | the content of that site's pages (not its name) |
| Hides from the proxy provider | nothing: the proxy terminates it and sees which sites are opened | the content of pages; the provider sees only the site's name and the amount of data |
| Who checks the certificate | ProxyFlow: the proxy's certificate, with the phone's trusted CAs and the proxy's name; no way to skip the check | the app (browser), with its own rules |
| ProxyFlow's role | performs it | relays the encrypted bytes unchanged; it has no certificate it could present instead and never decrypts them |

| Profile | Proxy password visible on the network | Site names visible on the network | Page content of HTTPS sites | Page content of plain HTTP sites |
|---|---|---|---|---|
| No TLS to proxy | yes | yes | no | yes |
| TLS to proxy | no | no | no | no (to the network); yes to the proxy |

Verified on devices: `httpConnectOverTlsCarriesTraffic`, `socks5OverTlsCarriesTraffic` (TLS to the proxy works, checked by the platform's trust manager with the run's CA); a certificate for another name (`TLS_HOSTNAME`), an expired one (`TLS_EXPIRED`), one from a CA nothing trusts (`TLS_UNTRUSTED`), the app as shipped facing the run's private CA (`TLS_UNTRUSTED`), a proxy without TLS on that port (`TLS_HANDSHAKE`) — never Connected, no interface established, and every connection to a TLS port in the captures opened with a TLS record; `httpsToASiteStaysEndToEnd`: through a plain SOCKS5 proxy and through HTTP over TLS, the shell's TLS client, trusting only the run's CA and checking the name as a browser does, saw the site's own certificate (its SHA-256 matches the one the runner made), and the site saw the name the client sent.

## 4. Limitations

- **Not anonymous.** The proxy provider sees which sites are opened (names or addresses) and the content of anything not encrypted end to end. Apps and sites still recognise the user through accounts, cookies and device identifiers. ProxyFlow has no servers and adds no privacy beyond what the user's proxy gives.
- **Without TLS to the proxy**, anyone on the network between the phone and the proxy sees the proxy password, the sites opened and plain-HTTP content (§3). The app warns on the form (D4).
- **UDP is not carried.** Video and voice calls, many games, and QUIC fail or fall back to TCP. SOCKS5 `UDP ASSOCIATE` is not used (ADR-0004).
- **Ping and traceroute do not work** while connected.
- **Local devices** (printers, casting, NAS, the router) are unreachable while connected: their addresses go to the proxy.
- **IPv6** works only if the proxy supports it. The names ProxyFlow answers get IPv4 placeholders only; an app's own encrypted DNS can give it IPv6 addresses, which then work only if the proxy has IPv6.
- **Not routed by the VPN** (Android): ProxyFlow's own connection to the proxy and its lookup of the proxy's name; Android system traffic that bypasses VPNs (network validation and captive-portal checks, the time service, some carrier services); tethered devices; work profiles, Private space and other users on the phone.
- **Privileged system apps can bypass the VPN.** Android lets them: Google answered Mullvad's report that Android's own connectivity checks leave a VPN, even with "Block connections without VPN", as intended behaviour ([Mullvad, 2022](https://mullvad.net/en/blog/android-leaks-connectivity-check-traffic)). In the CI runs, on the Android 15 image over Wi-Fi, Google Play services opened connections outside the VPN a few seconds into connected periods (port 5228, its push messages, and port 443); the evidence names their owner from the phone's own socket table. There they went to placeholders from ProxyFlow's DNS, so they could not connect; on a phone, such connections could reach Google directly from the phone's own address. Once, on the Android 16 image over mobile data, the emulator's capture showed connections of the same kind (to Google's push servers on ports 5228 and 443, and lookups of their names) that no socket sample named — seen only in the emulator's capture, which the evidence can line up about a second wrong (§7), so they may have fallen just outside a connected period; §7 says how the evidence treats those. ProxyFlow cannot stop any of it.
- **Around connecting and disconnecting, not fully checked.** The evidence leaves out the first and last 2 s of each connected period (§7), and most test periods are shorter than 4 s. Measured afterwards on the verified run's artifacts with the evidence's own owner rules, the phone's own captures show in those moments only expected traffic, Google Play services and Android's network stack, and a few connections with no owner sampled — no ordinary app's traffic. Right after a disconnect, though, apps' DNS queries addressed to the VPN's DNS address 198.18.0.2 go out on the normal network (on Android 15 Gboard's and Private Compute Services', just after Android marked the VPN disconnected): nothing there answers that address, but the names cross the network in clear. Before the VPN is up and after it is taken down, apps use the normal network by design unless Android's "Block connections without VPN" is on (README). M8 examines these moments.
- **Private DNS "strict" and app-chosen encrypted DNS** go through the proxy, but then the proxy sees addresses instead of names (geo-located resolution at the proxy does not apply). Not automated in CI: Android validates a strict Private DNS server against the system CA store, and the lab has no server with a publicly trusted certificate; the behaviour follows from the TCP rows, and the M10 device checklist includes it.
- **Private and user-installed CAs** are not trusted for TLS to the proxy in V1 (ADR-0005, D7).
- **Destinations behind slow proxies**: a SOCKS5 or HTTP proxy that takes more than 10 s to open a connection makes it fail as a proxy timeout, also when the destination is the slow part.

## 5. What the app says

| Where | What it says (English source; `CopyClaimsTest` checks the claims) |
|---|---|
| Home, connected | "Apps' TCP connections go through your proxy"; the routing card (Apps (TCP) via proxy, site names resolved by proxy, UDP & ping blocked); "HTTPS sites stay encrypted end to end: ProxyFlow never decrypts them. TLS to proxy only encrypts the connection between this phone and your proxy." |
| Profile chips | "TLS to proxy" / "No TLS to proxy" (never "encrypted") |
| Add/Edit proxy | "TLS to the proxy" switch with "Encrypts the connection between this phone and your proxy… HTTPS sites stay encrypted end to end either way"; the plaintext warning lists what the network can see and that HTTPS pages stay encrypted end to end |
| Notification | "Apps' TCP goes through your proxy · UDP and ping blocked" |
| About | not anonymous; what goes through the proxy; UDP, ping and local devices; IPv6; what doesn't use the VPN; TLS to the proxy versus HTTPS |

Never: "all traffic", "every app", "full VPN", "anonymous" (except to deny it), "hide your IP/identity", "100 %", "military-grade", "protection", "no logs", "supports games/calls/QUIC/UDP", or plain "encrypted"/"not encrypted" for the TLS setting.

## 6. Diagnostics

While connected, Home shows a routing summary and **Diagnostics since connecting** (counts only, read from the engine once a second while Home is shown):

| Row | Counts | Engine source |
|---|---|---|
| Through your proxy: Apps (TCP) | connections the proxy opened | `tcp_opened` |
| Through your proxy: Site names (DNS) | different names apps looked up during this connection, answered with a placeholder for the proxy to resolve when an app connects (repeats count once) | `dns_names` |
| TLS to proxy | "Not used" without TLS; else failures and the last failed check (certificate not trusted, doesn't match, expired, TLS failed) | `failures[TLS_*]`, `last_proxy_error` |
| DNS | failures: connections to names the proxy couldn't find or reach, failed lookups of the proxy's own name, lookups dropped, placeholders from an earlier session; also shown, never counted as failures: query types ProxyFlow doesn't answer by design (TXT, MX, SRV, PTR, malformed) | `hostname_failures[TARGET_UNREACHABLE]`, `failures[PROXY_DNS_FAILED]`, `dns.exhausted` + `dns_dropped`, `failures[STALE_DNS_MAPPING]`; `dns.refused` + `dns.formerr` |
| Unsupported traffic | UDP packets refused (ports refused most) and pings dropped | `udp_rejected`, `udp_rejected_ports`, `icmp_echo_requests_dropped` |
| Proxy timeouts | connections the proxy didn't open in time | `proxy_timeouts` |
| Last problem | the last failed connection's code and detail | `last_failure` |

The DNS row is a heuristic: a proxy reports a name it cannot resolve the way it reports a host that is down (SOCKS5 "host unreachable", HTTP 502/503/504), so both count; a proxy that reports a failed lookup only as a general failure (SOCKS5 0x01, HTTP 500) shows it as the last problem alone.

Verified on devices: each test above reads the diagnostics through the app's own path (`VpnRuntime.diagnostics`) and checks the row it caused (names, UDP, pings, a DNS failure at the proxy, the proxy's failure, a proxy timeout, a TLS failure with its check).

## 7. How it is verified (CI lab)

`scripts/e2e_env.sh` sets up, on the CI runner the emulator reaches as 10.0.2.2: Dante (SOCKS5) and Squid (HTTP CONNECT) with sign-in, resolving names only with their own DNS server (dnsmasq at 10.99.0.53, logging every query); stunnel TLS fronts with certificates from a CA made for each run (`scripts/e2e_pki.py`: valid, wrong name, expired, other CA); a gate the tests switch between relaying, refusing and hanging; the phone's uplink DNS server (dnsmasq at 10.99.0.54, the emulator's `-dns-server`, logging every name the phone looks up itself); destinations by name, by IPv4 address, by IPv6 address (`fd99::1`), by an IPv6-only name and on the LAN address; an HTTPS site; UDP sinks. `scripts/e2e_lab_check.py` checks all of it from the runner before the emulator starts. The tests send their traffic from the shell user, whom ProxyFlow's VPN covers (`ShellClient`, a Java client started with `app_process`), and log every line they send. `scripts/m6_evidence.py` then checks only what the tests caused (the logs' lengths are recorded after the lab's own checks): every line arrived where it was sent, once, through a proxy; no refused line arrived anywhere; the proxies' DNS server was asked for the lab's names and the phone's uplink DNS server never was; no UDP reached a sink; the HTTPS site saw only the name the client sent; every connection to a TLS proxy port, at the gate and in the captures, opened with a TLS record; no capture shows a packet to or from a destination's address (IPv4 or IPv6, extension headers walked), UDP or an echo request to one, a DNS query for a lab name, or a direct connection to the LAN destination. It also checks the periods ProxyFlow was connected (from the VPN log in logcat: Connected until Disconnect, until Android revokes the VPN — from the test's own log line just before it has Android do so, since apps use the normal network from then on — or until logcat ends; the first and last 2 s of each period are left out for the clocks, which also leaves out the moments right after connecting and right before disconnecting, and a period shorter than 4 s is not checked at all: in the verified run about half the connected time was checked, `docs/m6/M6-acceptance-report.md`). Each capture that covers the uplink is lined up with logcat by the phone's own connections to its proxy right after each attempt begins (logcat and the captures are both UTC); one that cannot be lined up fails the run. With attempts close together this nearest-match alignment can pair an attempt with a neighbouring connection: in the verified run it put the emulator's own capture about a second off on Android 14 and 16, inside the 2 s margin (the phone's own captures share logcat's clock); M8 lines it up by the phone's capture instead. In those periods, every lookup and every new connection, datagram or ping that left the phone on its own network — from any of its addresses, a temporary IPv6 address it took during the tests included — must be accounted for. Expected: to its proxy (and the gate's control port, which the tests use from ProxyFlow's own process), its network's resolver, or Android's own checks of its network and its time (the hosts NetworkMonitor and the time service use, which Android sends outside VPNs). Reported with its owner: what the socket with that connection's own ends (protocol, addresses, ports) belonged to, in the phone's kernel tables sampled about three times a second during the tests, when that owner is one Android lets leave a VPN that allows no bypassing: a system uid (Android's daemons and services), or an app granted `CONNECTIVITY_USE_RESTRICTED_NETWORKS`, `NETWORK_STACK` or `MAINLINE_NETWORK_STACK` (from `dumpsys package`), as Google Play services is (§4). Failing the run: anything owned by the shell (the tests' own traffic), by ProxyFlow, or by any other app, preinstalled or not. What no sample caught (a connection opened and closed between two samples, a datagram from an unconnected socket, a ping) is put down to apps Android lets leave a VPN, and reported, only when Android's own view of ProxyFlow's VPN — logged by the tests after each connection made through their helper, from the VPN network's capabilities read with the shell's permissions — routed every uid but ProxyFlow's into it (and, where Android says, let no app bypass it), and ProxyFlow's own sockets reached nothing but its proxies, the gate's control port and the phone's loopback; otherwise it fails the run. Both of those are checks of their own too: Android's view must leave out ProxyFlow alone (with, since Android 13, its SDK sandbox, uid + 10000, which Android leaves out with the app), let no app bypass the VPN where Android says, and name ProxyFlow's DNS server, and ProxyFlow's sockets (it is outside its VPN) must reach nothing but its proxies, the gate's control port and the phone's loopback. A lookup Android's resolver makes on the phone's network for an app is its own (a system uid); whether the VPN's DNS server is ProxyFlow's is Android's view, and the tests' own lookups of the lab's names never reach the phone's network. The uplink DNS server's log is checked the same way when it can be lined up (through the emulator's capture, or, without one, on its own clock once the app's proxy-name lookups sit within 3 s of its connection attempts: on Android 15 their median gap was 0.9 to 1.7 s, inside the margin); a lookup there is put down to an app Android lets leave a VPN only when a capture shows the same lookup put down so. It reassembles DNS over TCP, counts a phone capture only if its own tcpdump still ran at the end, and reports how many connections to ProxyFlow's own addresses the engine refused (`local_refused`). Its checks have their own tests on synthetic captures and logs (`scripts/tests/test_evidence.py`, run in CI). `scripts/m5_evidence.py` keeps checking the M5 guarantees on the same run. The results per API level are in `docs/m6/packet-capture-report.md`.
