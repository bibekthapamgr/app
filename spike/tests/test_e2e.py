"""End-to-end feasibility tests: real TCP traffic from 'apps' through the
spike engine and controlled proxies, verified with packet captures.

Each test writes evidence (pcaps, proxy/destination logs, engine stats and
events, verdict.json) to spike/evidence/run-<id>/<test>/.
"""
from __future__ import annotations

import json
import secrets
import time
from pathlib import Path

import pytest

import client
import pcap
from engines import HevEngine, SpikeEngine, cpu_ticks, rss_kib
from lab import (CREDS, DEV, DEV_IP, DEV_IP6, IPV6, PROXY_IP, PROXY_IP6, SPIKE, UNDERLAY_DNS, WEB_IP, WEB_IP6,
                 Lab, nsx, read_jsonl)

NO_IPV6 = "kernel booted with ipv6.disable=1: kernel-level IPv6 cannot be exercised on this host; engine IPv6 is covered by hermetic Go tests (engine/engine_test.go TestIPv6*)"

DEVICE = {DEV_IP, DEV_IP6}


def proxy_records(lab, name, since, target=None):
    recs = [r for r in read_jsonl(lab.logs / f"{name}.jsonl", since) if r.get("event") == "session"]
    return [r for r in recs if target is None or r.get("target") == target]


def web_requests(lab, since):
    return [r for r in read_jsonl(lab.logs / "web.jsonl", since) if r.get("event") == "request"]


def no_bypass(checks, pc, allowed, allowed_udp=frozenset(), label="uplink", expect_traffic=True):
    """Leak checks on the device uplink capture. A missing capture fails; an
    empty one fails too unless the test expects no uplink traffic at all."""
    path = Path(pc["2-device-uplink"])
    pk = pcap.read(path)
    bad = pcap.uplink_violations(pk, DEVICE, allowed, allowed_udp)
    seen = f"{len(pk)} packets captured" + ("" if expect_traffic else "; none expected")
    checks.check(f"{label}: nothing left the device except to the proxy endpoint(s) {sorted(allowed)} ({seen})",
                 path.exists() and (len(pk) > 0 or not expect_traffic) and not bad,
                 {"capture": str(path), "exists": path.exists(), "packets": len(pk),
                  "violations": [f"{p.proto} {p.src}:{p.sport}->{p.dst}:{p.dport}" for p in bad[:10]]})
    checks.check(f"{label}: no packet from the device reached a destination address directly",
                 not any(p.dst in (WEB_IP, WEB_IP6) or p.src in (WEB_IP, WEB_IP6) for p in pk))
    dns = ("no DNS (port 53) left the device" if not allowed_udp else
           f"no DNS (port 53) left the device except the allowed lookups of the proxy's own name {sorted(allowed_udp)}")
    checks.check(f"{label}: {dns}", not any(p.dport == 53 and (p.dst, 53) not in allowed_udp for p in pk))
    return pk


# --------------------------------------------------------------------------- #
def test_E01_socks5_tcp_through_controlled_proxy(lab, ev, checks):
    """Core M1 spike: one supported TCP request through a controlled SOCKS5 proxy.
    Requirement: M1 task 9 / acceptance 'actual proxy-routed TCP traffic'."""
    nonce = secrets.token_hex(8)
    t0 = time.time()
    with lab.capture(ev / "pcap") as pc:
        with SpikeEngine(ev, f"socks5://{PROXY_IP}:1080") as eng:
            r = client.curl(f"http://web.test/nonce/{nonce}")
    st = eng.final_stats
    (ev / "client-response.json").write_text(json.dumps(r.__dict__, indent=2))
    checks.check("HTTP 200 received by the app", r.code == 200, r.__dict__)
    body = r.json() if r.code == 200 else {}
    checks.check("end-to-end payload intact (nonce echoed)", body.get("nonce") == nonce)
    checks.check("destination saw the PROXY's address, not the phone's", body.get("client_ip") == PROXY_IP, body.get("client_ip"))
    recs = proxy_records(lab, "py-socks", t0, "web.test:80")
    checks.check("proxy received CONNECT web.test:80 as a HOSTNAME (remote DNS)",
                 any(x.get("atyp") == "domain" and x.get("result") == "ok" for x in recs), recs)
    tun = pcap.read(pc["1-app-tun0"])
    checks.check("app DNS query entered the tunnel (UDP/53 on tun0)", any(p.proto == "udp" and p.dport == 53 for p in tun))
    syn = [p for p in tun if p.proto == "tcp" and p.syn and not p.ack and p.dport == 80]
    checks.check("app TCP SYN to a fake IP (198.18.0.0/15) entered the tunnel",
                 any(p.dst.startswith(("198.18.", "198.19.")) for p in syn), [f"{p.src}->{p.dst}" for p in syn])
    no_bypass(checks, pc, {(PROXY_IP, 1080)})
    web = web_requests(lab, t0)
    checks.check("every destination request came from the proxy", web and all(w["client_ip"] == PROXY_IP for w in web),
                 [w["client_ip"] for w in web])
    checks.check("engine counted the flow and the fake-DNS mapping",
                 st.get("tcp_opened", 0) >= 1 and st.get("dns", {}).get("mapped", 0) >= 1, st)
    checks.metric(request_seconds=r.seconds, engine_rss_kib=st.get("rss_kib"), engine_stop_ms=st.get("stop_ms"))
    checks.finish()


def test_E02_socks5_auth_real_dante(lab, ev, checks):
    """SOCKS5 username/password (RFC 1929) against a real Dante server; wrong password fails closed.
    Requirement: HTTP CONNECT and SOCKS5 support with optional credentials."""
    t0 = time.time()
    with lab.capture(ev / "pcap") as pc:
        with SpikeEngine(ev / "ok", f"socks5://{PROXY_IP}:1081", CREDS["dante_user"], CREDS["dante_pass"]):
            ok = client.curl("http://web.test/whoami")
        t_bad = time.time()
        with SpikeEngine(ev / "bad", f"socks5://{PROXY_IP}:1081", CREDS["dante_user"], "wrong-password") as bad_eng:
            bad = client.curl("http://web.test/whoami", max_time=5)
    checks.check("correct credentials: HTTP 200 via Dante", ok.code == 200 and ok.json().get("client_ip") == PROXY_IP, ok.__dict__)
    checks.check("wrong password: app connection fails", bad.code == 0 and bad.rc != 0, bad.__dict__)
    st = bad_eng.final_stats
    checks.check("wrong password categorised AUTH_FAILED (proxy-level)",
                 st.get("failures", {}).get("AUTH_FAILED", 0) >= 1 and st.get("last_proxy_error") == "AUTH_FAILED", st.get("failures"))
    checks.check("no destination request during the failing run", not web_requests(lab, t_bad))
    no_bypass(checks, pc, {(PROXY_IP, 1081)})
    checks.metric(fail_seconds=bad.seconds)
    checks.finish()


def test_E03_http_connect_real_squid(lab, ev, checks):
    """HTTP CONNECT with Basic auth against a real Squid; HTTPS end to end; per-destination refusal.
    Requirement: HTTP CONNECT support; target-level errors must not break the tunnel."""
    t0 = time.time()
    ca = lab.certs / "ca.pem"
    with lab.capture(ev / "pcap") as pc:
        with SpikeEngine(ev / "ok", f"http://{PROXY_IP}:3129", CREDS["squid_user"], CREDS["squid_pass"]) as eng:
            https = client.curl("https://web.test/whoami", "--cacert", str(ca))
            http = client.curl("http://web.test/whoami")
            denied = client.curl("http://web.test:8081/whoami", max_time=5)
            after = client.curl("http://web.test/whoami")
        with SpikeEngine(ev / "bad", f"http://{PROXY_IP}:3129", CREDS["squid_user"], "nope") as bad_eng:
            bad = client.curl("http://web.test/whoami", max_time=5)
    st, bst = eng.final_stats, bad_eng.final_stats
    checks.check("HTTPS through CONNECT: 200, certificate verified by the app", https.code == 200 and https.json().get("tls") is True, https.__dict__)
    checks.check("HTTP through CONNECT: 200 from the proxy address", http.code == 200 and http.json().get("client_ip") == PROXY_IP)
    checks.check("port 8081 refused by Squid policy -> app connection fails", denied.code == 0)
    checks.check("refusal categorised TARGET_NOT_ALLOWED", st.get("failures", {}).get("TARGET_NOT_ALLOWED", 0) >= 1, st.get("failures"))
    checks.check("target-level refusal did not set a global proxy error", not st.get("last_proxy_error"), st.get("last_proxy_error"))
    checks.check("tunnel still works after a refused destination", after.code == 200)
    checks.check("wrong password categorised AUTH_FAILED", bst.get("failures", {}).get("AUTH_FAILED", 0) >= 1, bst.get("failures"))
    access = lab.squid_access_log.read_text() if lab.squid_access_log.exists() else ""
    checks.check("Squid access log shows CONNECT web.test:443 by hostname", "CONNECT web.test:443" in access)
    no_bypass(checks, pc, {(PROXY_IP, 3129)})
    checks.finish()


@pytest.mark.parametrize("case", [
    {"id": "valid-socks5-tls", "proxy": f"socks5+tls://{PROXY_IP}:1443", "sni": "proxy.test", "expect": "ok"},
    {"id": "valid-http-tls-auth", "proxy": f"http+tls://{PROXY_IP}:3443", "sni": "proxy.test", "expect": "ok", "auth": True},
    {"id": "hostname-mismatch", "proxy": f"socks5+tls://{PROXY_IP}:1443", "sni": "wrong.test", "expect": "TLS_HOSTNAME"},
    {"id": "expired-cert", "proxy": f"socks5+tls://{PROXY_IP}:1444", "sni": "proxy.test", "expect": "TLS_EXPIRED"},
    {"id": "untrusted-issuer", "proxy": f"socks5+tls://{PROXY_IP}:1445", "sni": "proxy.test", "expect": "TLS_UNTRUSTED"},
    {"id": "tls-to-plaintext-port", "proxy": f"socks5+tls://{PROXY_IP}:1080", "sni": "proxy.test", "expect": "TLS_HANDSHAKE"},
], ids=lambda c: c["id"])
def test_E04_tls_to_proxy(lab, ev, checks, case):
    """TLS to the proxy endpoint with strict certificate and hostname validation; failures fail closed.
    Requirement: optional TLS with strict certificate validation; Play VpnService encryption policy."""
    nonce = "tlsnonce" + secrets.token_hex(6)
    port = int(case["proxy"].rsplit(":", 1)[1])
    user, pw = (CREDS["py_user"], CREDS["py_pass"]) if case.get("auth") else ("", "")
    t0 = time.time()
    with lab.capture(ev / "pcap") as pc:
        with SpikeEngine(ev, case["proxy"], user, pw, sni=case["sni"], ca=lab.certs / "ca.pem") as eng:
            r = client.curl(f"http://web.test/nonce/{nonce}", max_time=6)
    st = eng.final_stats
    up = pc["2-device-uplink"]
    if case["expect"] == "ok":
        checks.check("HTTP 200 through TLS-wrapped proxy", r.code == 200 and r.json().get("nonce") == nonce, r.__dict__)
        checks.check("uplink capture: destination hostname NOT visible", not pcap.contains_bytes(up, b"web.test"))
        checks.check("uplink capture: app payload (nonce) NOT visible", not pcap.contains_bytes(up, nonce.encode()))
        if user:
            checks.check("uplink capture: proxy password NOT visible", not pcap.contains_bytes(up, pw.encode()))
            checks.check("uplink capture: no cleartext Proxy-Authorization header", not pcap.contains_bytes(up, b"Proxy-Authorization"))
        checks.note("The TLS ClientHello SNI (proxy.test) is visible on the network by design.")
        checks.check("uplink capture: the proxy's name (SNI) is visible, the destination's is not",
                     pcap.contains_bytes(up, b"proxy.test") and not pcap.contains_bytes(up, b"web.test"))
    else:
        checks.check("app connection fails", r.code == 0, r.__dict__)
        checks.check(f"failure categorised {case['expect']}", st.get("failures", {}).get(case["expect"], 0) >= 1, st.get("failures"))
        checks.check("categorised as a proxy-level (global) error", st.get("last_proxy_error") == case["expect"], st.get("last_proxy_error"))
        checks.check("no request reached the destination", not web_requests(lab, t0))
    no_bypass(checks, pc, {(PROXY_IP, port)})
    checks.finish()


def test_E05_plaintext_exposure_evidence(lab, ev, checks):
    """Evidence for the Play-policy finding: plain SOCKS5/HTTP expose credentials, hostnames and payload.
    Requirement: Google Play VpnService policy (encrypt device-to-tunnel-endpoint traffic)."""
    nonce = "plainnonce" + secrets.token_hex(6)
    with lab.capture(ev / "pcap-socks5") as pc1:
        with SpikeEngine(ev / "socks5", f"socks5://{PROXY_IP}:1082", CREDS["py_user"], CREDS["py_pass"]):
            r1 = client.curl(f"http://web.test/nonce/{nonce}")
    with lab.capture(ev / "pcap-http") as pc2:
        with SpikeEngine(ev / "http", f"http://{PROXY_IP}:3128", CREDS["py_user"], CREDS["py_pass"]):
            r2 = client.curl(f"http://web.test/nonce/{nonce}")
    u1, u2 = pc1["2-device-uplink"], pc2["2-device-uplink"]
    checks.check("both plaintext modes work (HTTP 200)", r1.code == 200 and r2.code == 200)
    checks.check("SOCKS5 plain: password visible on the network (RFC 1929 is cleartext)", pcap.contains_bytes(u1, CREDS["py_pass"].encode()))
    checks.check("SOCKS5 plain: destination hostname visible", pcap.contains_bytes(u1, b"web.test"))
    checks.check("SOCKS5 plain: app payload of a non-TLS app visible", pcap.contains_bytes(u1, nonce.encode()))
    checks.check("HTTP plain: Proxy-Authorization (base64 credentials) visible", pcap.contains_bytes(u2, b"Proxy-Authorization: Basic"))
    checks.check("HTTP plain: CONNECT line with hostname visible", pcap.contains_bytes(u2, b"CONNECT web.test:80"))
    checks.note("Contrast with test_E04 valid-* cases, where none of these appear on the wire.")
    checks.finish()


@pytest.mark.skipif(not IPV6, reason=NO_IPV6)
def test_E06_ipv6(lab, ev, checks):
    """IPv6 is captured by the tunnel and proxied by address; AAAA suppressed; no IPv6 leak.
    Requirement: M1 task 3 IPv6 behaviour."""
    t0 = time.time()
    with lab.capture(ev / "pcap") as pc:
        with SpikeEngine(ev, f"socks5://{PROXY_IP}:1080") as eng:
            r = client.curl(f"http://[{WEB_IP6}]/whoami")
            aaaa = client.dig("@198.18.0.2", "web.test", "AAAA")
            unreachable = client.curl("http://[2001:db8::1]/", max_time=6)
    st = eng.final_stats
    checks.check("IPv6 literal: HTTP 200 via proxy", r.code == 200, r.__dict__)
    checks.check("destination saw the proxy's IPv6 address", r.code == 200 and r.json().get("client_ip") == PROXY_IP6)
    recs = proxy_records(lab, "py-socks", t0, f"[{WEB_IP6}]:80")
    checks.check("proxy received an IPv6 CONNECT (ATYP 4)", any(x.get("atyp") == "ipv6" for x in recs), recs)
    tun = pcap.read(pc["1-app-tun0"])
    checks.check("IPv6 SYN entered the tunnel (no IPv6 bypass route)",
                 any(p.proto == "tcp" and p.syn and p.dst == WEB_IP6 for p in tun))
    checks.check("AAAA answered NOERROR with no records (IPv4 fake-IP only in V1)",
                 "status: NOERROR" in aaaa and "ANSWER: 0" in aaaa, aaaa[-400:])
    checks.check("IPv6 destination the proxy cannot reach: connection fails, no leak", unreachable.code == 0)
    checks.check("unreachable IPv6 categorised as target-level", any(k.startswith("TARGET_") for k in st.get("failures", {})), st.get("failures"))
    no_bypass(checks, pc, {(PROXY_IP, 1080)})
    checks.finish()


def test_E07_dns_never_leaves_device(lab, ev, checks):
    """DNS: fake-IP answers, interception of any resolver, no direct DNS on the uplink.
    Requirement: never fall back to direct DNS; M1 task 3 DNS behaviour."""
    t0 = time.time()
    with lab.capture(ev / "pcap") as pc:
        with SpikeEngine(ev, f"socks5://{PROXY_IP}:1080") as eng:
            a = client.dig("+short", "@198.18.0.2", "web.test", "A").strip()
            txt = client.dig("@198.18.0.2", "web.test", "TXT")
            hijack = client.dig("+short", "@8.8.8.8", "example.com", "A").strip()
            underlay = client.dig("+short", f"@{UNDERLAY_DNS}", "proxy.test", "A").strip()
            tcpdns = client.dig("+tcp", "@8.8.8.8", "example.com", "A")
    st = eng.final_stats
    checks.check("A query answered from the fake pool", a.startswith(("198.18.", "198.19.")), a)
    checks.check("TXT refused (only A/AAAA/HTTPS handled in V1)", "status: REFUSED" in txt, txt[-300:])
    checks.check("query sent to 8.8.8.8 was intercepted and answered locally", hijack.startswith(("198.18.", "198.19.")), hijack)
    checks.check("query aimed at the underlying network's resolver was intercepted too", underlay.startswith(("198.18.", "198.19.")), underlay)
    checks.check("underlying-network resolver received NO app queries",
                 not read_jsonl(lab.logs / "underlay-dns.jsonl", t0))
    checks.check("DNS-over-TCP to 8.8.8.8 is treated as a normal TCP flow via the proxy (fails here: lab has no 8.8.8.8)",
                 "connection timed out" in tcpdns or "communications error" in tcpdns or "no servers" in tcpdns or "refused" in tcpdns.lower(),
                 tcpdns[-300:])
    up = no_bypass(checks, pc, {(PROXY_IP, 1080)})
    checks.check("zero UDP packets on the uplink", not [p for p in up if p.proto == "udp"])
    checks.metric(dns_outcomes=st.get("dns"))
    checks.finish()


def test_E08_udp_blocked_not_leaked(lab, ev, checks):
    """Non-DNS UDP (incl. QUIC-style UDP/443) is rejected inside the tunnel and never leaves the device.
    Requirement: M1 task 3 UDP behaviour; unsupported UDP verified before release."""
    t0 = time.time()
    with lab.capture(ev / "pcap") as pc:
        with SpikeEngine(ev, f"socks5://{PROXY_IP}:1080") as eng:
            echo = client.udp_probe(WEB_IP, 5000)
            quic = client.udp_probe(WEB_IP, 443)
            echo6 = client.udp_probe(WEB_IP6, 5000) if IPV6 else {"skipped": NO_IPV6}
    st = eng.final_stats
    checks.check("UDP echo got no reply from the destination", "reply" not in echo, echo)
    checks.check("UDP/443 (QUIC) got no reply", "reply" not in quic, quic)
    if IPV6:
        checks.check("IPv6 UDP got no reply", "reply" not in echo6, echo6)
    else:
        checks.note("IPv6 UDP case skipped: " + NO_IPV6)
    checks.check("destination never saw UDP", not [r for r in read_jsonl(lab.logs / "web.jsonl", t0) if r.get("event") == "udp"])
    up = no_bypass(checks, pc, {(PROXY_IP, 1080)}, expect_traffic=False)
    checks.check("zero UDP packets on the uplink", not [p for p in up if p.proto == "udp"])
    tun = pcap.read(pc["1-app-tun0"])
    icmp_unreach = [p for p in tun if (p.proto == "icmp" and p.icmp_type == 3) or (p.proto == "icmpv6" and p.icmp_type == 1)]
    checks.metric(icmp_unreachable_sent_to_apps=len(icmp_unreach), udp_rejected=st.get("udp_rejected"),
                  app_saw=echo.get("error"))
    probes = 2 + (1 if IPV6 else 0)
    checks.check("engine counted the rejected datagrams (>= 1 per probe)", st.get("udp_rejected", 0) >= probes, st.get("udp_rejected"))
    checks.check("apps are told quickly (ICMP port unreachable -> ECONNREFUSED), enabling QUIC->TCP fallback",
                 "ECONNREFUSED" in str(echo.get("error")) and len(icmp_unreach) >= 1, {"echo": echo, "icmp_unreach": len(icmp_unreach)})
    checks.finish()


def test_E09_icmp_not_faked(lab, ev, checks):
    """ICMP echo is not proxied and never answered with a fake reply.
    Requirement: M1 task 3 ICMP behaviour; diagnostics must not mislead users."""
    with lab.capture(ev / "pcap") as pc:
        with SpikeEngine(ev, f"socks5://{PROXY_IP}:1080") as eng:
            p4 = client.ping("-c", "3", "-W", "1", WEB_IP)
            p4big = client.ping("-c", "2", "-W", "1", "-s", "2000", WEB_IP)  # fragmented at MTU 1500
            p6 = client.ping("-6", "-c", "2", "-W", "1", WEB_IP6) if IPV6 else None
            p6big = client.ping("-6", "-c", "2", "-W", "1", "-s", "2048", WEB_IP6) if IPV6 else None
            raw = client.icmp_inject("tun0", WEB_IP, "2001:db8::1")
    st = eng.final_stats
    checks.check("IPv4 ping: 100% loss", "100% packet loss" in p4.stdout, p4.stdout[-200:])
    checks.check("IPv4 ping of 2,000 bytes (fragmented): 100% loss", "100% packet loss" in p4big.stdout, p4big.stdout[-200:])
    if p6 is not None:
        checks.check("IPv6 ping: 100% loss", "100% packet loss" in p6.stdout, p6.stdout[-200:])
        checks.check("IPv6 ping of 2,048 bytes (fragmented): 100% loss", "100% packet loss" in p6big.stdout, p6big.stdout[-200:])
    else:
        checks.note("Kernel IPv6 ping skipped: " + NO_IPV6 + " — raw IPv6 echo requests are injected at the TUN instead")
    sent = raw.get("sent", {})
    checks.check("raw echo requests injected at the TUN get no reply and no ICMP fragment back (IPv4 and IPv6; "
                 "small, 2 KB fragmented, behind an extension header, atomic fragment)",
                 raw.get("echo_replies") == [] and len(sent) == 7, raw)
    checks.check("engine sent zero ICMP echo replies", st.get("icmp_echo_replies_sent", -1) == 0, st.get("icmp_echo_replies_sent"))
    # One per request: 3 + 2 kernel pings and 7 raw requests (a fragmented ping counts once).
    want_req = 3 + 2 + 7 + (4 if IPV6 else 0)
    checks.check(f"engine dropped every echo request before its stack (>= {want_req}) and the later fragments of the large pings (>= 4)",
                 st.get("icmp_echo_requests_dropped", 0) >= want_req and st.get("icmp_fragments_dropped", 0) >= 4,
                 {k: st.get(k) for k in ("icmp_echo_requests_dropped", "icmp_fragments_dropped", "icmp_echo_replies_blocked")})
    up = no_bypass(checks, pc, {(PROXY_IP, 1080)}, expect_traffic=False)
    checks.check("zero ICMP on the uplink", not [p for p in up if p.proto in ("icmp",) or (p.proto == "icmpv6" and not p.is_link_local_noise)])
    checks.finish()


def test_E10_fail_closed_when_proxy_down(lab, ev, checks):
    """Proxy outage: connections are refused fast, nothing bypasses, recovery is automatic.
    Requirement: do not silently bypass the configured proxy; kill-switch behaviour."""
    with lab.capture(ev / "pcap") as pc:
        with SpikeEngine(ev, f"socks5://{PROXY_IP}:1080") as eng:
            before = client.curl("http://web.test/whoami")
            lab.stop("py-socks")
            t_down = time.time()
            down = client.curl("http://web.test/whoami", max_time=8)
            down_ip = client.curl(f"http://{WEB_IP}/whoami", max_time=8)
            outage_web = web_requests(lab, t_down)
            time.sleep(0.8)  # stats snapshots are written every 500 ms
            mid = eng.stats()
            lab.spawn("py-socks", "pf-prx", ["python3", str(SPIKE / "harness/proxies/spike_proxy.py"), "socks5",
                                             *Lab.socks_listen(), "--log", str(lab.logs / "py-socks.jsonl")])
            lab.wait_port("pf-prx", PROXY_IP, 1080)
            after = client.curl("http://web.test/whoami")
    checks.check("baseline request works", before.code == 200)
    checks.check("proxy down: hostname request fails", down.code == 0, down.__dict__)
    checks.check("proxy down: IP-literal request fails", down_ip.code == 0, down_ip.__dict__)
    checks.check("proxy down: failure is immediate (RST), not a hang", down.seconds < 3.0, down.seconds)
    checks.check("no destination request during the outage", not outage_web, outage_web)
    checks.check("outage categorised PROXY_UNREACHABLE", mid.get("last_proxy_error") == "PROXY_UNREACHABLE", mid.get("failures"))
    checks.check("traffic recovers when the proxy returns, without restarting the engine", after.code == 200)
    no_bypass(checks, pc, {(PROXY_IP, 1080)})
    checks.metric(outage_fail_seconds=down.seconds)
    checks.finish()


def test_E11_kill_switch_semantics(lab, ev, checks):
    """What happens on engine crash vs. VPN interface teardown, with and without OS lockdown.
    Requirement: kill-switch / fail-closed design input for M5/M8."""
    t0 = time.time()
    with lab.capture(ev / "pcap-crash") as pc:
        eng = SpikeEngine(ev / "crash", f"socks5://{PROXY_IP}:1080")
        eng.start()
        ok = client.curl(f"http://{WEB_IP}/whoami")
        eng.kill()  # crash: TUN stays (persistent), engine gone
        t_crash = time.time()
        crashed = client.curl(f"http://{WEB_IP}/whoami", max_time=4)
    checks.check("A. baseline works", ok.code == 200)
    checks.check("A. engine crashed while the TUN stays up: traffic black-holed (fail closed)", crashed.code == 0, crashed.__dict__)
    checks.check("A. no destination request after the crash", not web_requests(lab, t_crash))
    no_bypass(checks, pc, {(PROXY_IP, 1080)}, label="A uplink")

    # B. The VPN interface disappears (Android: VPN revoked / service killed) and
    #    nothing at OS level blocks traffic: apps fall back to the underlying network.
    lab.delete_tun()
    t_b = time.time()
    leak = client.curl(f"http://{WEB_IP}/whoami", max_time=4)
    leaked = web_requests(lab, t_b)
    checks.check("B. without OS lockdown, teardown LEAKS (expected; proves why lockdown is required)",
                 leak.code == 200 and any(w["client_ip"] == DEV_IP for w in leaked), [w["client_ip"] for w in leaked])

    # C. Emulate Android 'Block connections without VPN' (lockdown): only protected
    #    (marked) sockets may use the underlying network.
    for tool in (("iptables", "ip6tables") if IPV6 else ("iptables",)):
        nsx(DEV, tool, "-w", "-A", "OUTPUT", "-o", "dev-up", "-m", "mark", "!", "--mark", "438", "-j", "REJECT")
    t_c = time.time()
    blocked = client.curl(f"http://{WEB_IP}/whoami", max_time=4)
    checks.check("C. with lockdown emulation, teardown is fail-closed", blocked.code == 0 and not web_requests(lab, t_c), blocked.__dict__)
    for tool in (("iptables", "ip6tables") if IPV6 else ("iptables",)):
        nsx(DEV, tool, "-w", "-D", "OUTPUT", "-o", "dev-up", "-m", "mark", "!", "--mark", "438", "-j", "REJECT")
    lab.create_tun()
    checks.note("B is a deliberate negative control. On Android the equivalent protection is "
                "Always-on VPN + 'Block connections without VPN' (lockdown); the app must detect and guide it.")
    checks.finish()


def test_E12_concurrency_and_cleanup(lab, ev, checks):
    """200 concurrent flows complete and are released; then 200 flows are held open and the engine is stopped under them.
    Requirement: resource cleanup verified before release; resource limits."""
    import threading
    with SpikeEngine(ev, f"socks5://{PROXY_IP}:1080") as eng:
        time.sleep(0.5)
        idle_rss = rss_kib(eng.pid())
        # Phase 1: 200 concurrent 3-second requests complete normally.
        res = {}
        th = threading.Thread(target=lambda: res.update(client.concurrent_requests(200, "/sleep/3")))
        th.start()
        peak_active, peak_rss = 0, 0
        while th.is_alive():
            s = eng.stats()
            peak_active = max(peak_active, s.get("tcp_active", 0))
            peak_rss = max(peak_rss, rss_kib(eng.pid()))
            time.sleep(0.2)
        th.join()
        time.sleep(1.0)
        settled = eng.stats()
        # Phase 2: hold 200 flows open (30-second requests), then stop the engine under them.
        held = {}
        th2 = threading.Thread(target=lambda: held.update(client.concurrent_requests(200, "/sleep/30")))
        th2.start()
        deadline, open_now = time.time() + 20, 0
        while time.time() < deadline:
            open_now = eng.stats().get("tcp_active", 0)
            if open_now >= 190:
                break
            time.sleep(0.2)
        t_stop = time.time()
    st = eng.final_stats  # leaving the block stopped the engine (SIGTERM -> Stop())
    th2.join(timeout=40)
    # When the apps saw their connections end, measured inside the client process.
    clients_released_s = round(held["last_end"] - t_stop, 3) if "last_end" in held else None
    checks.check("200/200 concurrent requests succeeded", res.get("ok") == 200, res)
    checks.check("engine saw ~200 simultaneous flows", peak_active >= 190, peak_active)
    checks.check("all flows released after completion (tcp_active == 0)", settled.get("tcp_active") == 0, settled.get("tcp_active"))
    checks.check("phase 2: ~200 flows were open when the engine was stopped", st.get("tcp_active_at_stop", 0) >= 190,
                 {"seen_before_stop": open_now, "tcp_active_at_stop": st.get("tcp_active_at_stop")})
    checks.check("graceful stop completed", st.get("stopped") is True, st)
    checks.check("Stop() with ~200 open flows took < 2 s", st.get("stop_ms", 99999) < 2000, st.get("stop_ms"))
    checks.check("goroutines released on stop (<= 12 remain)", st.get("goroutines_after_stop", 999) <= 12,
                 {"before": st.get("goroutines_before_stop"), "after": st.get("goroutines_after_stop")})
    checks.check("held app connections ended within 2 s of the stop signal, as seen by the apps (no 30-second waits)",
                 not th2.is_alive() and held.get("ok", 0) == 0 and clients_released_s is not None and clients_released_s < 2,
                 {"ok": held.get("ok"), "errors": held.get("errors"), "last_app_close_after_stop_s": clients_released_s})
    checks.metric(idle_rss_kib=idle_rss, peak_rss_kib=peak_rss, peak_active=peak_active,
                  tcp_active_at_stop=st.get("tcp_active_at_stop"), stop_ms=st.get("stop_ms"),
                  goroutines_before_stop=st.get("goroutines_before_stop"), goroutines_after_stop=st.get("goroutines_after_stop"),
                  phase1_wall_seconds=res.get("seconds"), clients_released_s=clients_released_s)
    checks.finish()


def _iperf_mbps(j, key="sum_received"):
    try:
        return round(j["end"][key]["bits_per_second"] / 1e6, 1)
    except (KeyError, TypeError):
        return None


def test_E13_throughput_comparison(lab, ev, checks):
    """Indicative throughput / CPU / memory: spike engine vs hev-socks5-tunnel (same VM, same Dante proxy).
    Requirement: M1 task 7 engine evaluation (resource limits)."""
    import os
    hev_bin = Path(os.environ.get("HEV_BIN", SPIKE / "bin/hev-socks5-tunnel"))
    results = {}
    base = nsx("pf-prx", "iperf3", "-J", "-c", WEB_IP, "-t", "4", capture=True, check=False)
    results["no_tunnel_proxy_host_to_dest"] = {"upload_mbps": _iperf_mbps(json.loads(base.stdout or "{}"))}

    def measure(label, ctx, mtu):
        nsx(DEV, "ip", "link", "set", "tun0", "mtu", str(mtu))
        try:
            with ctx as e:
                time.sleep(0.5)
                pid = e.pid()
                c0 = cpu_ticks(pid)
                up = client.iperf("-c", "web.test", "-t", "4")
                down = client.iperf("-c", "web.test", "-t", "4", "-R")
                c1 = cpu_ticks(pid)
                rss = rss_kib(pid)
        finally:
            nsx(DEV, "ip", "link", "set", "tun0", "mtu", "1500")
        upm, downm = _iperf_mbps(up), _iperf_mbps(down)
        cpu = round((c1 - c0) / 100, 2)
        gb = ((upm or 0) + (downm or 0)) / 8 * 4 / 1000
        results[label] = {"tun_mtu": mtu, "upload_mbps": upm, "download_mbps": downm, "engine_cpu_seconds": cpu,
                          "cpu_seconds_per_gb": round(cpu / gb, 2) if gb else None, "rss_kib_after": rss}

    creds = (CREDS["dante_user"], CREDS["dante_pass"])
    for mtu in (1500, 9000):
        measure(f"spike_gvisor_mtu{mtu}", SpikeEngine(ev / f"spike-{mtu}", f"socks5://{PROXY_IP}:1081", *creds,
                                                      extra=["--mtu", str(mtu)]), mtu)
        if hev_bin.exists():
            measure(f"hev_no_offload_mtu{mtu}", HevEngine(ev / f"hev-{mtu}", PROXY_IP, 1081, hev_bin, *creds, mtu=mtu), mtu)
    if hev_bin.exists():
        measure("hev_linux_offloads_mtu1500 (not available on Android)",
                HevEngine(ev / "hev-offload", PROXY_IP, 1081, hev_bin, *creds, mtu=1500, offload=True), 1500)
    (ev / "throughput.json").write_text(json.dumps(results, indent=2))
    checks.metric(**results)
    s = results.get("spike_gvisor_mtu1500", {})
    checks.check("spike engine moved data in both directions", (s.get("upload_mbps") or 0) > 1 and (s.get("download_mbps") or 0) > 1, s)
    checks.note("Single 2-vCPU VM, veth links, Dante as proxy: numbers are relative, not phone performance.")
    checks.finish()


def test_E14_log_hygiene(lab, ev, checks):
    """Credentials, payload and (by default) destinations never appear in engine logs, stats or events.
    Requirement: never log credentials or application payloads."""
    nonce = "lognonce" + secrets.token_hex(6)
    with SpikeEngine(ev / "default", f"http+tls://{PROXY_IP}:3443", CREDS["py_user"], CREDS["py_pass"],
                     sni="proxy.test", ca=lab.certs / "ca.pem"):
        client.curl(f"http://web.test/nonce/{nonce}")
    with SpikeEngine(ev / "bad-creds", f"socks5://{PROXY_IP}:1082", CREDS["py_user"], CREDS["py_pass"] + "x"):
        client.curl("http://web.test/whoami", max_time=4)
    with SpikeEngine(ev / "destinations-on", f"socks5://{PROXY_IP}:1080", extra=["--log-destinations"]):
        client.curl(f"http://web.test/nonce/{nonce}")
    secrets_to_find = [CREDS["py_user"].encode(), CREDS["py_pass"].encode(), nonce.encode()]
    leaked = []
    for f in ev.rglob("*"):
        if f.is_file() and f.suffix in (".log", ".json", ".jsonl"):
            data = f.read_bytes()
            leaked += [f"{f.relative_to(ev)}: {s.decode()[:6]}…" for s in secrets_to_find if s in data]
    checks.check("no username, password or payload in any engine output", not leaked, leaked)
    default_events = (ev / "default/engine-events.jsonl").read_text()
    checks.check("destinations omitted by default", "web.test" not in default_events)
    checks.check("destinations included only when the privacy switch is on",
                 "web.test" in (ev / "destinations-on/engine-events.jsonl").read_text())
    checks.finish()


def test_E15_hev_baseline_behaviour(lab, ev, checks):
    """Same harness against hev-socks5-tunnel (comparison candidate): SOCKS5 only.
    Requirement: M1 task 7 engine evaluation."""
    hev_bin = Path(__import__("os").environ.get("HEV_BIN", SPIKE / "bin/hev-socks5-tunnel"))
    if not hev_bin.exists():
        pytest.skip("hev-socks5-tunnel binary not built")
    t0 = time.time()
    with lab.capture(ev / "pcap") as pc:
        with HevEngine(ev, PROXY_IP, 1080, hev_bin) as h:
            time.sleep(0.5)
            rss = rss_kib(h.pid())
            r = client.curl("http://web.test/whoami")
            p = client.ping("-c", "2", "-W", "1", WEB_IP)
            u = client.udp_probe(WEB_IP, 5000)
    recs = proxy_records(lab, "py-socks", t0)
    checks.check("hev: HTTP 200 through SOCKS5", r.code == 200, r.__dict__)
    checks.check("hev: hostname sent to proxy (mapdns)", any(x.get("atyp") == "domain" and x.get("target") == "web.test:80" for x in recs),
                 [(x.get("atyp"), x.get("target")) for x in recs][:5])
    checks.check("hev: ping not answered (icmp: off)", "100% packet loss" in p.stdout)
    checks.metric(hev_idle_rss_kib=rss, hev_udp_probe=u,
                  hev_udp_associate_attempts=[x.get("result") for x in recs if str(x.get("result", "")).startswith("cmd_")])
    no_bypass(checks, pc, {(PROXY_IP, 1080)})
    checks.note("hev speaks SOCKS5 only: HTTP CONNECT and TLS-to-proxy would need a local SOCKS5 front-end (loopback listener).")
    checks.finish()


def test_E16_proxy_hostname_resolved_on_underlying_network(lab, ev, checks):
    """The proxy's own hostname is resolved via the underlying network with a protected socket; nothing else is.
    Requirement: routing loop prevention; DNS handling."""
    t0 = time.time()
    with lab.capture(ev / "pcap") as pc:
        with SpikeEngine(ev / "ok", "socks5+tls://proxy.test:1443", ca=lab.certs / "ca.pem", proxy_dns=True):
            r = client.curl("http://web.test/whoami")
        with SpikeEngine(ev / "nxdomain", "socks5://no-such-proxy.test:1080", proxy_dns=True) as bad:
            nx = client.curl("http://web.test/whoami", max_time=5)
    q = read_jsonl(lab.logs / "underlay-dns.jsonl", t0)
    names = sorted({x.get("name") for x in q})
    checks.check("TLS proxy addressed by hostname works", r.code == 200, r.__dict__)
    checks.check("underlay resolver saw lookups, and only of the proxy hostnames",
                 bool(names) and set(names) <= {"proxy.test", "no-such-proxy.test"}, names)
    checks.check("unknown proxy hostname categorised PROXY_DNS_FAILED",
                 bad.final_stats.get("failures", {}).get("PROXY_DNS_FAILED", 0) >= 1, bad.final_stats.get("failures"))
    no_bypass(checks, pc, {(PROXY_IP, 1443)}, allowed_udp={(UNDERLAY_DNS, 53)})
    checks.finish()
