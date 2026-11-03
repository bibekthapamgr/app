#!/usr/bin/env python3
"""Checks the M8 failure-injection evidence after the device tests (CI device job), beside m5_evidence.py to m7_evidence.py.

scripts/m8_failure_injection.sh notes each step it took in m8-steps.txt ("<phone time> <text>"), with what the phone
answered. For one API level this reads those notes, the lab's witness log (scripts/e2e_env.sh: it hears only what gets
out around ProxyFlow), the packet captures of the phone's uplink and the crash reports the script pulled, and checks:

  A  killed while connected without Android's kill switch: the process was killed (how is reported), the witness then
     heard the shell directly — Android's normal behaviour, the negative control that shows the witness and the
     captures would see a leak — and the next start found the interruption (the reason Android gave is reported);
  B  Always-on VPN with "Block connections without VPN": Android started ProxyFlow, which connected and read that
     blocking is on; after the kill Android had no VPN network left, and every probe of the witness until Android
     started ProxyFlow again (if it did) was refused as Android's blocking refuses (EACCES, "Permission denied"); the
     witness heard nothing while B ran, and no capture that sees the uplink through B (it holds the SYNs of A's and of
     B-after's answered lines, and its own clock lines up with the phone's, measured from them) has a SYN to it while
     blocking was on; the next start kept what Android said and connected again; once the setting was off, the witness
     heard the shell directly again;
  C  a crash with a planted sign-in in its message: Android's crash report has the exception but none of the planted
     sign-in (password, user, server), and the next start found a crash (Android's own line in the phone's log,
     written before any app code, is reported, not failed: no app can change it).

Only the shell's TCP to the witness's one IPv4 address is checked through B: what else apps sent then (UDP, DNS, IPv6)
is not. It fails on any of these that does not hold, on any step the script marked FAILED (with or without a time),
on any line the witness heard that no step sent, or when the steps did not run. It also reports, without failing,
what left the phone on its normal network for ProxyFlow's own addresses (198.18.0.0/15: its placeholders and DNS
server) while the VPN was gone — apps that kept what the VPN gave them.

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
from m5_evidence import annotate, guest_capture_partial, ip_of, network_payload  # noqa: E402

GATEWAY = "10.0.2.2"  # the runner as the emulator sees it
OWN = ipaddress.ip_network("198.18.0.0/15")  # ProxyFlow's placeholders and its DNS server 198.18.0.2, valid only inside its VPN
WITNESS_PORT = 8095
# How a connect fails that Android's "Block connections without VPN" refuses (the shell client prints the errno).
LOCKDOWN = re.compile(r"EACCES|Permission denied|EPERM|Operation not permitted")
# A capture's clock and the phone's: the emulator's own capture runs on the runner's clock, the phone's on the phone's;
# they agree to a second or so, never to more than this.
MAX_OFFSET_S = 30.0
# What the witness may hear: the line before the tests, A's probes and the probes once B's setting was off.
EXPECTED_LINE = re.compile(r"^PF8-(before|A-A\d+-\d+|B-after-B\d+-\d+)$")


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


def failures(lines: list[str]) -> list[str]:
    """The script's FAILED notes, timed or not: a note whose phone time could not be read has "-" for it."""
    out = []
    for line in lines:
        head, _, text = line.partition(" ")
        try:
            float(head)
        except ValueError:
            if head != "-":
                text = line.strip()
        if text.startswith("FAILED "):
            out.append(text[len("FAILED ") :])
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


def witness_lines(witness: list[str]) -> list[tuple[float | None, str, str]]:
    """The witness's lines as (runner time, peer, line); "-" is a connection that sent no line."""
    out = []
    for line in witness:
        parts = line.split(" ", 2)
        try:
            when = float(parts[0])
        except ValueError:
            when = None
        out.append((when, parts[1] if len(parts) > 1 else "?", parts[2] if len(parts) > 2 else "-"))
    return out


def check_witness(witness: list[str]) -> tuple[list[str], list[str]]:
    """Nothing reached the witness but what a step sent around ProxyFlow on purpose — the line before the tests, A's
    probes, the probes once B's setting was off — and nothing at all while B ran: between A's last line and the first
    once the setting was off (by the runner's clock, the witness's own)."""
    facts, problems = [], []
    lines = witness_lines(witness)
    a_times = [when for when, _, text in lines if when is not None and text.startswith("PF8-A-")]
    after_times = [when for when, _, text in lines if when is not None and text.startswith("PF8-B-after-")]
    blocked = [f"{when} {peer} {text}" for when, peer, text in lines if text.startswith(("PF8-B-B", "PF8-B-check-"))]
    if blocked:
        problems.append(f"B: the witness heard lines sent while blocking was on: {blocked[:3]}")
    unknown = [f"{when} {peer} {text}" for when, peer, text in lines if not EXPECTED_LINE.match(text) and not text.startswith(("PF8-B-B", "PF8-B-check-"))]
    if unknown:
        problems.append(f"the witness heard lines no step sent around ProxyFlow on purpose: {unknown[:3]}")
    if a_times and after_times:
        during = [f"{when} {peer} {text}" for when, peer, text in lines if when is not None and max(a_times) < when < min(after_times)]
        if during:
            problems.append(f"B: the witness heard something while B ran (between A's line and the first once the setting was off): {during[:3]}")
    facts.append(f"witness: {len(lines)} lines after the lab's own checks, {len(unknown) + len(blocked)} that no step sent on purpose")
    return facts, problems


def check_a(noted: list[tuple[float, str]], witness: list[str]) -> tuple[list[str], list[str]]:
    facts, problems = [], []
    killed = first(noted, r"^A: killed pid (\d+) by (.+)$")
    if killed is None:
        return facts, ["A: no kill was noted: the scenario did not run"]
    facts.append(f"A: killed while connected by {killed[1].group(2)}")
    if killed[1].group(2).startswith("NOT KILLED"):
        problems.append(f"A: the process could not be killed: {killed[1].group(2)}")
    replies = every(noted, r"^A: witness probe (\d+): REPLY PROXYFLOW-DEST (PF8-A-\S+)$")
    if not replies:
        problems.append("A: the shell never reached the witness after the kill: the negative control failed")
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
    nxt = first(noted, r"^A: the next start: passed")
    if nxt is None:
        problems.append("A: the next start did not pass (or did not run)")
    found = first(noted, r"^A: found: interruption: ([^,\s]+), ended at ([^,\s]+)")
    if found:
        facts.append(f"A: the next start found the interruption: reason {found[1].group(1)}, end time {'given' if found[1].group(2) != '-' else 'not given'}")
    return facts, problems


def check_b(noted: list[tuple[float, str]], witness: list[str]) -> tuple[list[str], list[str], tuple[float, float] | None]:
    """B's checks, and the phone times between which blocking was surely on: from the setting's note to the note before
    the one that turned it off."""
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
    off_at = next((i for i, (_, text) in enumerate(noted) if re.search(r"^B: Always-on VPN off: result 1: always-on none", text)), None)
    if killed is None or off_at is None:
        problems.append("B: the kill or the end of the setting was not noted")
        return facts, problems, None
    facts.append(f"B: killed by {killed[1].group(2)}")
    if killed[1].group(2).startswith("NOT KILLED"):
        problems.append(f"B: the process could not be killed: {killed[1].group(2)}")
    gone = first(noted, r"^B: VPN after the kill: (.*)$")
    if gone is None or gone[1].group(1) != "none":
        problems.append(f"B: Android's VPN network was not seen gone before the probes: {gone[1].group(1)[:160] if gone else 'not looked at'}")
    else:
        facts.append(f"B: Android had no VPN network {gone[0] - killed[0]:.1f} s after the kill")
    probes = every(noted, r"^B: witness probe (\d+) with ProxyFlow gone: (.*)$")
    refused = [m.group(2) for _, m in probes if LOCKDOWN.search(m.group(2))]
    answered_ = [m.group(2) for _, m in probes if m.group(2).startswith("REPLY")]
    other = [m.group(2) for _, m in probes if not LOCKDOWN.search(m.group(2)) and not m.group(2).startswith("REPLY")]
    kinds = sorted({re.sub(r".*: ", "", text)[:60] for text in refused})
    facts.append(f"B: {len(probes)} probes of the witness with ProxyFlow gone, {len(refused)} refused as Android's blocking refuses: {kinds}")
    if not probes:
        problems.append("B: the witness was never probed after the kill")
    if answered_:
        problems.append(f"B: the witness answered while Block connections without VPN was on: {answered_[:2]}")
    if other:
        problems.append(f"B: {len(other)} probe(s) with ProxyFlow gone not refused as Android's blocking refuses: {other[:2]}")
    restarted = first(noted, r"^B: Android started ProxyFlow again")
    again = every(noted, r"^B: witness probe (\d+) reached ProxyFlow started again: (.*)$")
    facts.append(
        f"B: Android {'started ProxyFlow again ' + format(restarted[0] - killed[0], '.1f') + ' s after the kill' if restarted else 'did not start ProxyFlow again'} "
        f"within the {len(probes)} probes ({(probes[-1][0] - killed[0]) if probes else 0:.0f} s)"
        + (f"; the probe then went to its new VPN: {again[0][1].group(2)[:100]}" if again else "")
    )
    if again and not restarted:
        problems.append("B: a probe was put down to ProxyFlow started again, but no start was noted")
    nxt = first(noted, r"^B: the next start: passed")
    if nxt is None:
        problems.append("B: the next start did not pass: it must keep what Android said, find Android still blocking, and connect again")
    found = first(noted, r"^B: found: (.*)$")
    if found:
        facts.append(f"B: the next start: {found[1].group(1)[:260]}")
    after = every(noted, r"^B: witness probe (\d+) after the setting was turned off: REPLY PROXYFLOW-DEST (PF8-B-after-\S+)$")
    if not after:
        problems.append("B: the shell did not reach the witness once the setting was off: the negative control failed")
    else:
        facts.append(f"B: once the setting was off, the shell reached the witness directly again ({len(heard(witness, after[0][1].group(2)))} line logged)")
    # Surely on: from the setting's note to the note before the one that turned it off (it went off in between).
    return facts, problems, (on[0], noted[off_at - 1][0])


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
        # The planted sign-in whole (ADR-0013 §7): its password, its user and its server.
        planted = [name for part, name in ((f"PF8SECRET{token}", "the password"), ("pf8user", "the user"), ("planted.pf8.invalid", "the server")) if part in text]
        if planted:
            problems.append(f"C: Android's crash report ({os.path.basename(path)}) has the planted sign-in: {', '.join(planted)}")
        elif f"PF8-planted-{token}" in text:
            facts.append(f"C: Android's crash report has the exception (PF8-planted-{token}), none of the planted sign-in")
    own = first(noted, r"^C: the phone's log lines with the planted secret .*: (\d+)$")
    if own:
        facts.append(f"C: Android's own uncaught-exception line in the phone's log (before any app code): {own[1].group(1)} line(s) with the planted secret")
    if first(noted, r"^C: the next start: passed") is None:
        problems.append("C: the next start did not pass: it must find the crash")
    found = first(noted, r"^C: found: interruption: ([^,\s]+)")
    if found:
        facts.append(f"C: the next start found the interruption: reason {found[1].group(1)}")
    return facts, problems


def answered(noted: list[tuple[float, str]], pattern: str) -> tuple[float, float] | None:
    """The phone times between which the SYN of the first answered probe matching [pattern] left: after the note before
    its answer's (the probe started once that note was written) and before its answer's note (the answer came first)."""
    for i, (when, text) in enumerate(noted):
        if i and re.search(pattern, text):
            return noted[i - 1][0], when
    return None


def offset(times: list[float], a: tuple[float, float], b: tuple[float, float]) -> tuple[float, float] | None:
    """A capture's clock minus the phone's, as bounds (low, high), from the SYNs of A's and of B-after's answered lines:
    a pair of SYNs that fits both, on one clock difference within MAX_OFFSET_S. None when none fits — the capture did not
    see the uplink at both, so it can't speak for B between them, or its clock doesn't line up."""
    found = None
    for s in times:
        low_a, high_a = s - a[1], s - a[0]
        if min(abs(low_a), abs(high_a)) > MAX_OFFSET_S:
            continue
        for t in times:
            if t <= s:
                continue
            low, high = max(low_a, t - b[1]), min(high_a, t - b[0])
            if low <= high:
                found = (low, high) if found is None else (min(found[0], low), max(found[1], high))
    return found


def check_captures(paths: list[str], noted: list[tuple[float, str]], blocking: tuple[float, float] | None, partial: set[str]) -> tuple[list[str], list[str]]:
    """No SYN to the witness while B blocked, in each capture that sees the uplink through B: it holds the SYNs of A's
    answered line before B and of B-after's after it, on a clock that lines up with the phone's (measured from those
    two: each SYN left between two notes). One such capture is required, and its tcpdump must have run to the end of
    the tests (a capture that stopped early speaks for nothing after): without one, "no SYN while blocked" would hold
    for a capture that sees nothing."""
    facts, problems = [], []
    a = answered(noted, r"^A: witness probe \d+: REPLY PROXYFLOW-DEST PF8-A-")
    b = answered(noted, r"^B: witness probe \d+ after the setting was turned off: REPLY PROXYFLOW-DEST PF8-B-after-")
    covering = 0
    for path in paths:
        name = os.path.basename(path)
        times, error = syns_to(path, GATEWAY, WITNESS_PORT)
        if error:
            facts.append(f"{name}: not read ({error})")
            continue
        lined = offset(times, a, b) if a and b else None
        if lined is None or blocking is None:
            facts.append(f"{name}: {len(times)} SYNs to the witness; none pair as A's and B-after's direct lines, so it does not see the uplink through B")
            continue
        low, high = lined
        window = (blocking[0] + high, blocking[1] + low)  # capture times surely inside, whatever the offset within its bounds
        inside = [t for t in times if window[0] <= t <= window[1]]
        stopped = name in partial
        covering += not stopped
        facts.append(
            f"{name}: {len(times)} SYNs to the witness, A's and B-after's among them; its clock is the phone's "
            f"{'+' if low >= 0 else ''}{low:.1f} to {'+' if high >= 0 else ''}{high:.1f} s; {len(inside)} while B blocked"
            + ("; its tcpdump had stopped before the end of the tests, so it does not count as coverage" if stopped else "")
        )
        if inside:
            problems.append(f"{name}: {len(inside)} SYN(s) to the witness while Block connections without VPN was on")
    if covering == 0:
        problems.append(
            "no capture saw the uplink through B (the SYNs of both A's and B-after's direct lines, the witness heard both, "
            "on a clock that lines up, and its tcpdump running to the end): none can show that nothing left while B blocked"
        )
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


def partial_captures(out: str, paths: list[str]) -> set[str]:
    """The phone's captures whose tcpdump had stopped before the end of the tests (M5's rule, guest-capture.txt): they
    may have stopped before M8 too."""
    guest = read(os.path.join(out, "guest-capture.txt"))
    spans = any(line.startswith("tcpdump at the end: ") and "none" not in line for line in guest)
    at_end = next((line.split(":", 1)[1].split() for line in guest if line.startswith("tcpdump at the end on:")), None)
    at_start = next((line.split(":", 1)[1].split() for line in guest if line.startswith("tcpdump at the start on:")), None)
    names = {os.path.basename(path) for path in paths}
    return {name for name in names if guest_capture_partial(name, spans, at_end, at_start)}


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
    raw = read(os.path.join(args.out, "m8-steps.txt"))
    noted = steps(raw)
    if not noted:
        problems.append("no m8-steps.txt: the failure injection did not run")
    for text in failures(raw):
        problems.append(f"the script: {text[:300]}")
    witness = read(os.path.join(args.pf, "witness.log"))[baseline(args.pf).get("witness.log", 0) :]
    facts_a, problems_a = check_a(noted, witness)
    facts_b, problems_b, blocking = check_b(noted, witness)
    facts_c, problems_c = check_c(noted, args.out)
    facts_w, problems_w = check_witness(witness)
    paths = sorted(glob.glob(os.path.join(args.out, "guest-*.pcap"))) + ([args.host_pcap] if args.host_pcap else [])
    facts_cap, problems_cap = check_captures(paths, noted, blocking, partial_captures(args.out, paths))
    facts += facts_a + facts_b + facts_c + facts_w + facts_cap + after_the_vpn(paths, noted)
    problems += problems_a + problems_b + problems_c + problems_w + problems_cap

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
