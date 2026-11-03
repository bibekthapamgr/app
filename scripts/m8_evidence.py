#!/usr/bin/env python3
"""Checks the M8 failure-injection evidence after the device tests (CI device job), beside m5_evidence.py to m7_evidence.py.

scripts/m8_failure_injection.sh notes each step it took in m8-steps.txt ("<phone time> <text>"), with what the phone
answered. For one API level this reads those notes, the lab's witness log (scripts/e2e_env.sh: it hears only what gets
out around ProxyFlow), the packet captures of the phone's uplink and the crash reports the script pulled, and checks:

  A  killed while connected without Android's kill switch: the process was killed (how is reported), the witness then
     heard the shell directly — Android's normal behaviour, the negative control that shows the witness and the
     captures would see a leak — and the next start found the interruption (the reason Android gave is reported);
  B  Always-on VPN with "Block connections without VPN": Android started ProxyFlow, which connected and read that
     blocking is on; after the kill, every probe of the witness failed on the phone, the witness heard none of them and
     no capture that covers the uplink has a SYN to it until the setting was turned off; the next start kept what
     Android said and connected again; once the setting was off, the witness heard the shell directly again;
  C  a crash with a planted sign-in in its message: Android's crash report has the exception but not the planted
     secret, and the next start found a crash (Android's own line in the phone's log, written before any app code,
     is reported, not failed: no app can change it).

It fails on any of these that does not hold, on any step the script marked FAILED, or when the steps did not run. It
also reports, without failing, what left the phone on its normal network for ProxyFlow's own addresses (198.18.0.0/15:
its placeholders and DNS server) while the VPN was gone — apps that kept what the VPN gave them.

Usage: m8_evidence.py --api N --pf DIR --out DIR [--host-pcap FILE]
"""

from __future__ import annotations

import argparse
import collections
import datetime
import glob
import ipaddress
import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import m6_evidence  # noqa: E402
from m5_evidence import annotate, ip_of, network_payload  # noqa: E402

GATEWAY = "10.0.2.2"  # the runner as the emulator sees it
OWN = ipaddress.ip_network("198.18.0.0/15")  # ProxyFlow's placeholders and its DNS server 198.18.0.2, valid only inside its VPN
WITNESS_PORT = 8095
MARGIN_S = 2.0  # the emulator's own capture runs on the runner's clock, which agrees with the phone's to about a second


def read(path: str) -> list[str]:
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()
    except OSError:
        return []


def baseline(pf: str) -> dict[str, int]:
    values = {}
    for line in read(os.path.join(pf, "log-baseline.properties")):
        if "=" in line:
            name, count = line.split("=", 1)
            values[name.strip()] = int(count.strip() or 0)
    return values


def steps(lines: list[str]) -> list[tuple[float, str]]:
    """The script's notes: (phone time in seconds, text); lines without a time are left out."""
    out = []
    for line in lines:
        head, _, text = line.partition(" ")
        try:
            out.append((float(head), text))
        except ValueError:
            continue
    return out


def first(noted: list[tuple[float, str]], pattern: str) -> tuple[float, re.Match] | None:
    for when, text in noted:
        m = re.search(pattern, text)
        if m:
            return when, m
    return None


def every(noted: list[tuple[float, str]], pattern: str) -> list[tuple[float, re.Match]]:
    return [(when, m) for when, text in noted if (m := re.search(pattern, text))]


def syns_to(path: str, address: str, port: int) -> tuple[list[float], str | None]:
    """The times of the SYNs (without ACK) to address:port in a pcap file, IPv4 or IPv6; or why it can't be read."""
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError as e:
        return [], str(e)
    if len(data) < 24:
        return [], f"{len(data)} bytes: no capture"
    magic = data[:4]
    endian = {b"\xd4\xc3\xb2\xa1": "<", b"\x4d\x3c\xb2\xa1": "<", b"\xa1\xb2\xc3\xd4": ">", b"\xa1\xb2\x3c\x4d": ">"}.get(magic)
    if endian is None:
        return [], f"not a pcap file (magic {magic.hex()})"
    nanos = magic in (b"\x4d\x3c\xb2\xa1", b"\xa1\xb2\x3c\x4d")
    linktype = struct.unpack(endian + "I", data[20:24])[0] & 0x0FFFFFFF
    found, at = [], 24
    while at + 16 <= len(data):
        seconds, fraction, incl_len = struct.unpack(endian + "III", data[at : at + 12])
        frame = data[at + 16 : at + 16 + incl_len]
        at += 16 + incl_len
        try:
            packet = network_payload(linktype, frame)
            if not packet:
                continue
            version = packet[0] >> 4
            if version == 4 and len(packet) >= 20:
                header = (packet[0] & 0x0F) * 4
                proto, dst, rest = packet[9], packet[16:20], packet[header:]
            elif version == 6 and len(packet) >= 40:
                proto, dst, rest = packet[6], packet[24:40], packet[40:]
            else:
                continue
            if proto != 6 or len(rest) < 14 or ip_of(dst) != address:
                continue
            dport, flags = struct.unpack("!H", rest[2:4])[0], rest[13]
            if dport == port and flags & 0x02 and not flags & 0x10:
                found.append(seconds + fraction / (1e9 if nanos else 1e6))
        except (IndexError, struct.error, ValueError):
            continue  # a truncated frame
    return found, None


def heard(witness: list[str], label: str) -> list[str]:
    """The witness's lines ("<time> <peer> <line>") with a line that starts with [label]."""
    return [line for line in witness if len(parts := line.split(" ", 2)) == 3 and parts[2].startswith(label)]


def check_a(noted: list[tuple[float, str]], witness: list[str]) -> tuple[list[str], list[str], tuple[float, float] | None]:
    facts, problems = [], []
    killed = first(noted, r"^A: killed pid (\d+) by (.+)$")
    if killed is None:
        return facts, ["A: no kill was noted: the scenario did not run"], None
    facts.append(f"A: killed while connected by {killed[1].group(2)}")
    if killed[1].group(2).startswith("NOT KILLED"):
        problems.append(f"A: the process could not be killed: {killed[1].group(2)}")
    replies = every(noted, r"^A: witness probe (\d+): REPLY PROXYFLOW-DEST (PF8-A-\S+)$")
    if not replies:
        problems.append("A: the shell never reached the witness after the kill: the negative control failed")
        end = None
    else:
        when, m = replies[0]
        delay = when - killed[0]
        lines = heard(witness, m.group(2))
        facts.append(
            f"A: the shell reached the witness directly {delay:.1f} s after the kill (probe {m.group(1)}); the witness logged it "
            f"{len(lines)} time(s){', from ' + lines[0].split(' ', 2)[1] if lines else ''} — Android's normal network, as expected without its kill switch"
        )
        if not lines:
            problems.append(f"A: the witness did not log {m.group(2)}, which the shell had an answer for")
        end = when
    nxt = first(noted, r"^A: the next start: passed")
    if nxt is None:
        problems.append("A: the next start did not pass (or did not run)")
    found = first(noted, r"^A: found: interruption: ([^,\s]+), ended at ([^,\s]+)")
    if found:
        facts.append(f"A: the next start found the interruption: reason {found[1].group(1)}, end time {'given' if found[1].group(2) != '-' else 'not given'}")
    return facts, problems, (killed[0] - MARGIN_S, end + MARGIN_S) if end is not None else None


def check_b(noted: list[tuple[float, str]], witness: list[str]) -> tuple[list[str], list[str], tuple[float, float] | None]:
    facts, problems = [], []
    on = first(noted, r"^B: Always-on VPN with Block connections without VPN: result 1: always-on \S+ lockdown true")
    if on is None:
        return facts, ["B: Always-on VPN with Block connections without VPN was not set: the scenario did not run"], None
    connected = first(noted, r"^B: connected by Always-on VPN")
    if connected is None:
        problems.append("B: Android's Always-on VPN start did not connect ProxyFlow")
    else:
        facts.append(f"B: Android started ProxyFlow for Always-on VPN; connected {connected[0] - on[0]:.1f} s after the setting")
    read_back = first(noted, r"^B: what ProxyFlow read from Android: system kill switch: (\S+)")
    if read_back is None or read_back[1].group(1) != "on":
        problems.append(f"B: ProxyFlow did not read that blocking is on: {read_back[1].group(0) if read_back else 'nothing read'}")
    else:
        facts.append("B: ProxyFlow read from Android: Always-on VPN on, blocking without VPN")
    through = first(noted, r"^B: a line to the destination through the proxy: (.*)$")
    if through is None or not through[1].group(1).startswith("REPLY"):
        problems.append(f"B: no line reached the destination through the proxy: {through[1].group(1) if through else 'none sent'}")
    killed = first(noted, r"^B: killed pid (\d+) by (.+)$")
    off = first(noted, r"^B: Always-on VPN off: result 1: always-on none")
    if killed is None or off is None:
        problems.append("B: the kill or the end of the setting was not noted")
        return facts, problems, None
    facts.append(f"B: killed by {killed[1].group(2)}")
    if killed[1].group(2).startswith("NOT KILLED"):
        problems.append(f"B: the process could not be killed: {killed[1].group(2)}")
    probes = every(noted, r"^B: witness probe (\d+) with ProxyFlow gone: (.*)$")
    answered = [m.group(2) for _, m in probes if m.group(2).startswith("REPLY")]
    kinds = sorted({re.sub(r".*: ", "", m.group(2))[:60] for _, m in probes if not m.group(2).startswith("REPLY")})
    facts.append(f"B: {len(probes)} probes of the witness with ProxyFlow gone, {len(answered)} answered; failures: {kinds}")
    if not probes:
        problems.append("B: the witness was never probed after the kill")
    if answered:
        problems.append(f"B: the witness answered while Block connections without VPN was on: {answered[:2]}")
    leaked = [line for line in witness if " PF8-B-B" in line and " PF8-B-after-" not in line]
    if leaked:
        problems.append(f"B: the witness heard lines sent while blocking was on: {leaked[:3]}")
    restarted = first(noted, r"^B: Android started ProxyFlow again")
    facts.append(
        f"B: Android {'started ProxyFlow again ' + format(restarted[0] - killed[0], '.1f') + ' s after the kill' if restarted else 'did not start ProxyFlow again'} "
        f"within the {len(probes)} probes ({(probes[-1][0] - killed[0]) if probes else 0:.0f} s)"
    )
    nxt = first(noted, r"^B: the next start: passed")
    if nxt is None:
        problems.append("B: the next start did not pass: it must keep what Android said and connect again")
    found = first(noted, r"^B: found: (.*)$")
    if found:
        facts.append(f"B: the next start: {found[1].group(1)[:200]}")
    after = every(noted, r"^B: witness probe (\d+) after the setting was turned off: REPLY PROXYFLOW-DEST (PF8-B-after-\S+)$")
    if not after:
        problems.append("B: the shell did not reach the witness once the setting was off: the negative control failed")
    else:
        facts.append(f"B: once the setting was off, the shell reached the witness directly again ({len(heard(witness, after[0][1].group(2)))} line logged)")
    return facts, problems, (on[0] + MARGIN_S, off[0] - MARGIN_S)


def check_c(noted: list[tuple[float, str]], out: str) -> tuple[list[str], list[str]]:
    facts, problems = [], []
    if first(noted, r"^C: connecting") is None:
        return facts, ["C: the crash scenario did not run"]
    report = first(noted, r"^C: Android's crash report names the exception, without the planted secret")
    if report is None:
        problems.append("C: Android's crash report of the planted exception was not checked clean")
    for path in sorted(glob.glob(os.path.join(out, "m8-dropbox-C*.txt"))):
        token = os.path.basename(path)[len("m8-dropbox-") : -len(".txt")]
        text = "\n".join(read(path))
        if f"PF8SECRET{token}" in text:
            problems.append(f"C: Android's crash report ({os.path.basename(path)}) has the planted secret")
        elif f"PF8-planted-{token}" in text:
            facts.append(f"C: Android's crash report has the exception (PF8-planted-{token}), not the planted secret")
    own = first(noted, r"^C: the phone's log lines with the planted secret .*: (\d+)$")
    if own:
        facts.append(f"C: Android's own uncaught-exception line in the phone's log (before any app code): {own[1].group(1)} line(s) with the planted secret")
    if first(noted, r"^C: the next start: passed") is None:
        problems.append("C: the next start did not pass: it must find the crash")
    found = first(noted, r"^C: found: interruption: ([^,\s]+)")
    if found:
        facts.append(f"C: the next start found the interruption: reason {found[1].group(1)}")
    return facts, problems


def check_captures(paths: list[str], window_a: tuple[float, float] | None, window_b: tuple[float, float] | None) -> tuple[list[str], list[str]]:
    """In each capture: the SYN of A's direct line to the witness (so the capture sees the uplink then), none while B
    blocked. A capture that sees the uplink is required when A's line got out: without one, "no SYN while blocked"
    would hold for a capture that sees nothing."""
    facts, problems = [], []
    covering = 0
    for path in paths:
        name = os.path.basename(path)
        times, error = syns_to(path, GATEWAY, WITNESS_PORT)
        if error:
            facts.append(f"{name}: not read ({error})")
            continue
        in_a = [t for t in times if window_a and window_a[0] <= t <= window_a[1]]
        in_b = [t for t in times if window_b and window_b[0] <= t <= window_b[1]]
        covering += bool(in_a)
        facts.append(
            f"{name}: {len(times)} SYNs to the witness; {len(in_a)} with A's direct line "
            f"({'it sees the uplink' if in_a else 'it does not see the uplink then'}); {len(in_b)} while B blocked"
        )
        if in_b:
            problems.append(f"{name}: {len(in_b)} SYN(s) to the witness while Block connections without VPN was on")
        if window_a and not in_a and times:
            facts.append(f"{name}: its SYNs to the witness, in seconds from A's kill: {[round(t - window_a[0] - MARGIN_S, 1) for t in times[:8]]}")
    if window_a and covering == 0:
        problems.append("no capture saw the SYN of A's direct line, which the witness heard: none shows the uplink during M8, so none can show that nothing left while B blocked")
    return facts, problems


def after_the_vpn(paths: list[str], noted: list[tuple[float, str]]) -> list[str]:
    """Reported, never failed: what left the phone on its normal network for ProxyFlow's own addresses (its placeholders
    and its DNS server, 198.18.0.0/15) while its VPN was gone — after A's kill, after Always-on VPN was turned off in B,
    after C's crash. Only an app that kept what the VPN gave it (a placeholder, the VPN's DNS server) sends there; nothing
    answers such an address outside the VPN, but a DNS query's name crosses the network in clear (docs/m6 §4)."""
    windows = []
    for start, end, label in (
        (r"^A: killed pid", r"^B: device owner", "after A's kill"),
        (r"^B: Always-on VPN off", r"^C: connecting", "after Always-on VPN was turned off"),
        (r"^C: pid \d+ crashed", r"^C: the next start", "after C's crash"),
    ):
        a, b = first(noted, start), first(noted, end)
        if a and b and b[0] > a[0]:
            windows.append((label, phone_time(a[0]), phone_time(b[0]), b[0] - a[0]))
    facts = []
    for path in paths:
        capture = m6_evidence.read_capture(path)
        if capture.error:
            continue
        for label, lo, hi, seconds in windows:
            sent = collections.Counter(
                f"{kind} {dst}:{port}" for (when, _, dst, kind, port, _) in capture.events
                if when and lo <= when <= hi and own(dst)
            )
            names = collections.Counter(n for (when, n, _, _, dst, _) in capture.dns_queries if when and lo <= when <= hi and own(dst))
            facts.append(
                f"{os.path.basename(path)} {label} ({seconds:.0f} s): to ProxyFlow's addresses on the normal network "
                f"{dict(sent.most_common(6)) or 'nothing'}; DNS names in it {dict(names.most_common(8)) or 'none'}"
            )
    return facts


def phone_time(seconds: float) -> datetime.datetime:
    """The phone's epoch time as the captures' times are read (UTC, naive)."""
    return datetime.datetime.fromtimestamp(seconds, datetime.timezone.utc).replace(tzinfo=None)


def own(address: str) -> bool:
    try:
        return ipaddress.ip_address(address) in OWN
    except ValueError:
        return False


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--api", required=True)
    parser.add_argument("--pf", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--host-pcap")
    args = parser.parse_args(argv[1:])
    facts: list[str] = [f"API {args.api}"]
    problems: list[str] = []
    noted = steps(read(os.path.join(args.out, "m8-steps.txt")))
    if not noted:
        problems.append("no m8-steps.txt: the failure injection did not run")
    for _, text in noted:
        if text.startswith("FAILED "):
            problems.append(f"the script: {text[len('FAILED '):][:300]}")
    witness = read(os.path.join(args.pf, "witness.log"))[baseline(args.pf).get("witness.log", 0) :]
    facts_a, problems_a, window_a = check_a(noted, witness)
    facts_b, problems_b, window_b = check_b(noted, witness)
    facts_c, problems_c = check_c(noted, args.out)
    paths = sorted(glob.glob(os.path.join(args.out, "guest-*.pcap"))) + ([args.host_pcap] if args.host_pcap else [])
    facts_cap, problems_cap = check_captures(paths, window_a, window_b)
    facts += facts_a + facts_b + facts_c + facts_cap + after_the_vpn(paths, noted)
    problems += problems_a + problems_b + problems_c + problems_cap
    facts.append(f"witness: {len(witness)} lines after the lab's own checks")

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "m8-evidence.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(facts + [f"PROBLEM: {p}" for p in problems]) + "\n")
    annotate("notice", f"M8 evidence (API {args.api})", facts)
    if problems:
        annotate("error", f"M8 evidence (API {args.api})", problems)
        return 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception:  # an evidence check that cannot finish is a failed check, said where CI shows annotations
        import traceback

        from m5_evidence import escape

        api = next((sys.argv[i + 1] for i, a in enumerate(sys.argv[:-1]) if a == "--api"), "?")
        print(f"::error title=M8 failure-injection evidence (API {api})::the evidence script failed: {escape(traceback.format_exc())}")
        sys.exit(1)
