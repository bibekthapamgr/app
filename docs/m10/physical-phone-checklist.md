# M10 — Physical phone checklist

**For:** the product owner, with their own Android phone and their own HTTP or SOCKS5 proxy · **Why:** every CI run is on emulators (`docs/m10/compatibility-matrix.md`); what only a real phone shows is checked here · **Records:** fill in §5 and keep it with the M10 report

The M1 feasibility report made one condition of its GO recommendation: the lab checks **E01, E07, E08, E09 and E10** reproduced on at least one physical Android phone with packet capture (`docs/m1/09-feasibility-report.md` §7, condition 1). M4–M9 ran them on emulators only, and the M9 report left the condition open for M10. The V1 quality bars for battery and for throughput on a mid-range phone (`docs/m1/01-scope.md` §7) can only be measured on a phone too. This checklist is how; it needs no root and no developer tools on the phone beyond what is named.

## 1. What you need

| | |
|---|---|
| The phone | Any Android 10 (API 29) or later; the model, Android version and security patch go in §5. A mid-range phone is what the throughput bar is for |
| ProxyFlow | The release candidate signed for testing: `app-release-test-signed.apk`, in the CI artifact `android-reports` of the verified run (`app/build/outputs/test-signed/`) — the release build, signed with a key made for that run alone, since Android installs no unsigned APK. Install it with "Install unknown apps" allowed for your file manager; uninstall it before installing ProxyFlow from Google Play later (another key) |
| Your proxy | The HTTP proxy (with CONNECT) or SOCKS5 proxy you pay for, with its sign-in; both kinds if you have them |
| A laptop | With Wireshark (or tcpdump), able to share its connection as a Wi-Fi hotspot (Windows: Mobile hotspot; macOS: Internet Sharing; Linux: NetworkManager's hotspot) |

The laptop's hotspot is the capture point: everything the phone sends goes through the laptop's hotspot interface, so Wireshark there sees every packet that leaves the phone — what M1's `dev-up` capture saw in the lab. Packet-capture apps on the phone itself (PCAPdroid and the like) are VPN apps too, and Android runs only one VPN at a time, so they can't watch ProxyFlow.

## 2. Setup

1. Turn mobile data **off** on the phone, so all its traffic takes the hotspot. Join the laptop's hotspot.
2. Note the phone's address on the hotspot (Android: Settings → Wi-Fi → the network → IP address) and your proxy's address (if the profile has a name, look it up on the laptop: `nslookup proxy.example.com`).
3. In Wireshark, capture on the hotspot interface with the capture filter `host <phone address>`. Save each test's capture as `E01.pcapng`, `E07.pcapng`, … (File → Save As) before the next one.
4. In ProxyFlow, add your proxy (Profiles → Add proxy), tap Test connection: it must say "Proxy works". Then go back to Home.

The display filter for "anything that did not go to the proxy" is
`!(ip.addr == <proxy address> && tcp.port == <proxy port>) && !arp && !dhcp && !mdns && !(icmpv6.type >= 133 && icmpv6.type <= 137)`
(address resolution, DHCP and neighbour discovery are the Wi-Fi's own). What may still appear is listed with each check: Android's own traffic that runs outside every VPN (its network checks, the time service — `docs/m6/routing-and-limitations.md` §4).

## 3. The checks

Each check: what to do, what passes, what to note. Connect ProxyFlow (Home → Connect) before each, unless it says otherwise.

| Check | Do | Passes when | Note |
|---|---|---|---|
| **E01** a TCP request through the proxy | Open `https://example.com` in Chrome (or any site) | The page loads; the capture shows TCP only to the proxy's address and port while it loads (and, if the profile names the proxy by name, one DNS lookup of that name) | The time Home shows from Connect to Connected |
| **E07** DNS | Open a site you haven't opened today, by name. Then, in a terminal app if you have one (Termux), `nslookup example.org 8.8.8.8` | No DNS query for the site's name in the capture (UDP or TCP port 53, or DNS over TLS port 853 to anything); the site loads, because your proxy looked the name up | Android's Private DNS setting (Settings → Network → Private DNS) as it is |
| **E08** UDP | Open a site that uses QUIC (`https://www.google.com`, YouTube) | It loads — over TCP; the capture shows no UDP from the phone except Android's own (time service, port 123), and no QUIC (UDP 443) | Home's diagnostics while connected: the UDP packets blocked |
| **E09** ping | In Termux: `ping -c 4 8.8.8.8`, then `ping -c 4 -s 2000 8.8.8.8` | No reply to either; no ICMP echo leaves the phone in the capture | — |
| **E10** the proxy dies | Make the proxy unreachable while connected: on the laptop, block the proxy's address (Windows firewall rule; macOS `pfctl`; Linux `sudo iptables -I FORWARD -d <proxy address> -j REJECT`), then open a new site | The new site fails at once (ProxyFlow refuses the connection; Home says it is reconnecting); nothing in the capture goes anywhere but the proxy's address; when you remove the block, ProxyFlow reconnects by itself | Seconds from the block to Home's "Reconnecting" |

Then the checks only a phone can make:

| Check | Do | Passes when | Note |
|---|---|---|---|
| **Battery, idle connected** (bar: < 2 % per hour above the phone's own) | Charge to 100 %, unplug. Screen off for an hour with ProxyFlow **disconnected**; note the battery. Then an hour connected, screen off, no apps open; note it again | The hour connected used under 2 percentage points more than the hour disconnected | Both drops; Settings → Battery's figure for ProxyFlow |
| **Throughput** (bar: ≥ 100 Mbit/s down; CPU < 50 % of one core at 50 Mbit/s) | With a fast network and a proxy close to you: a speed test site in Chrome, disconnected, then connected | Note both. The bar applies where the network and the proxy allow 100 Mbit/s without ProxyFlow; ProxyFlow's result should be close to the first | With a computer and USB debugging: `adb shell top -m 5 -d 2` during the connected test shows ProxyFlow's CPU |
| **Wi-Fi ↔ mobile data** | Mobile data back on. Connected on Wi-Fi, open a long download or video; walk out of Wi-Fi range (or turn Wi-Fi off) | ProxyFlow stays connected or reconnects by itself within a few seconds on mobile data; the video goes on after a pause; Logs shows the network change | Seconds of the pause |
| **Private DNS strict** (`docs/m6/routing-and-limitations.md` §4: not automated in CI) | Android Settings → Network → Private DNS → Private DNS provider hostname `dns.google`; connected, open a site | It loads; the capture shows no connection from the phone to `dns.google` (port 853) — its encrypted DNS goes through the proxy like any TCP | — |
| **A Wi-Fi with a sign-in page** (hotel, airport, café) | Join it, ProxyFlow connected | Home says "This network asks you to sign in"; nothing loads until you sign in (Android's notification); after signing in, ProxyFlow connects by itself if it was reconnecting, or after Connect | Whether Android's sign-in page opened while ProxyFlow was connected |
| **Restart with Always-on VPN** | Android Settings → Network → VPN → ProxyFlow → Always-on VPN on, Block connections without VPN on. Restart the phone, unlock it | ProxyFlow connects by itself after the restart (Home shows Connected without a tap); before it does, nothing loads | Seconds from unlock to Connected |
| **The lock screen** (threat model I-11; M10-17) | Android Settings → Notifications → Notifications on lock screen → show all. Connect ProxyFlow, lock the phone (with a PIN or pattern), pull down the shade | The notification names no profile; tapping its Disconnect, or the Quick Settings tile, asks you to unlock first, and ProxyFlow stays connected until you do | Android version (Android 10 and 11 unlock through a screen of ProxyFlow's that shows nothing) |
| **Your phone's battery saving** | Leave it connected overnight, screen off, on battery | In the morning Home still shows Connected, or says Android stopped ProxyFlow (with the time); Logs agrees | Your phone's brand: some (Samsung, Xiaomi, Huawei and others) stop background apps more than Android does; "Unrestricted" battery use for ProxyFlow (Settings → Apps → ProxyFlow → Battery) is the setting that helps |

## 4. If a check fails

Copy Logs (Logs → Copy) right after, keep the capture, and note what you did. A packet in the capture to anything other than the proxy, while ProxyFlow showed Connected or Reconnecting and was not Android's own traffic listed in §2, is a leak and blocks the release (M10 acceptance: no direct bypass in the supported-TCP test matrix).

## 5. Results

| | |
|---|---|
| Phone (model, Android version, security patch) | |
| ProxyFlow version (About) | |
| Proxy (kind, TLS or not, country) | |
| Date | |

| Check | Result (pass / fail) | Notes, numbers |
|---|---|---|
| E01 | | |
| E07 | | |
| E08 | | |
| E09 | | |
| E10 | | |
| Battery, idle connected | | |
| Throughput | | |
| Wi-Fi ↔ mobile data | | |
| Private DNS strict | | |
| Sign-in page | | |
| Restart with Always-on VPN | | |
| The lock screen | | |
| Battery saving overnight | | |
