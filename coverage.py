"""How much of each connected period scripts/m6_evidence.py checks (it leaves out MARGIN at each end), and what the
phone sent on its own network in the parts it leaves out, classified with the evidence's own rules: expected (its
proxy, its network's resolver, Android's check addresses), owned by an app Android lets leave a VPN, no owner sampled,
or owned by anyone else (the shell, ProxyFlow, other apps: what would fail the run inside a checked part).
Run from a checkout of the verified commit: python3 coverage.py ARTIFACTS_DIR"""
import collections
import datetime
import glob
import os
import re
import sys

sys.path.insert(0, "scripts")
import m6_evidence as m6  # noqa: E402

YEAR = 2026


def state_of(local, windows) -> str:
    for start, end, _ in windows:
        if start <= local <= end:
            if start + m6.MARGIN <= local <= end - m6.MARGIN:
                return "checked"
            if (end - start) <= 2 * m6.MARGIN:
                return "short period"
            return "period edge"
    return "between"


def main(root: str) -> None:
    margin = m6.MARGIN.total_seconds()
    for d in sorted(glob.glob(os.path.join(root, "**", "m5-evidence-api*"), recursive=True)):
        api = d.rstrip("/").rsplit("api", 1)[1]
        dumped, streamed = m6.read(os.path.join(d, "logcat.txt")), m6.read(os.path.join(d, "logcat-live.txt"))
        logcat = dumped if len(dumped) >= len(streamed) else streamed
        windows, starts = m6.connected_windows(logcat, YEAR)
        durations = [(end - start).total_seconds() for start, end, _ in windows]
        checked = [max(0.0, x - 2 * margin) for x in durations]
        print(f"API {api}: {len(windows)} periods, {sum(durations):.1f} s connected; checked: {sum(1 for c in checked if c > 0)} periods, "
              f"{sum(checked):.1f} s (margin {margin:.0f} s at each end)")
        network = m6.read(os.path.join(d, "guest-network.txt"))
        sockets = m6.socket_samples(d)
        names = m6.package_names(d)
        owners = m6.Owners(names, m6.network_grants(d), set())
        owners.own = m6.own_uids(logcat, names)
        print(f"  sampler: {sockets.coverage()}; own uids {sorted(owners.own)}")
        for path in sorted(glob.glob(os.path.join(d, "*.pcap"))):
            capture = m6.read_capture(path)
            if capture.error or not capture.packets:
                print(f"  {os.path.basename(path)}: {capture.error or 'no packets'}")
                continue
            phone = m6.phone_of(capture, network, sockets)
            offset, how = m6.capture_offset(capture, starts, phone)
            if offset is None:
                print(f"  {os.path.basename(path)}: not lined up ({how})")
                continue
            shift = datetime.timedelta(seconds=offset)
            android_addresses = set().union(*(capture.dns_answers.get(n, set()) for n in m6.ANDROID_NAMES))
            tally = collections.defaultdict(collections.Counter)  # (state, verdict) -> items
            connections = set()
            for event in capture.events:
                when, src, dst, kind, port, sport = event
                if when is None or src not in phone:
                    continue
                local = when - shift
                state = state_of(local, windows)
                if state == "between":
                    continue
                if kind == "TCP":
                    if (src, sport, dst, port) in connections:
                        continue
                    connections.add((src, sport, dst, port))
                key = f"{kind} [{dst}]:{port}" if ":" in dst else f"{kind} {dst}:{port}"
                if m6.explained(dst, kind, port, android_addresses):
                    tally[(state, "expected")][key] += 1
                    continue
                uids = sockets.owners(kind.lower(), src, sport, dst, port, local) if kind in ("TCP", "UDP") and sport is not None else []
                if uids and all(owners.may_bypass(uid) for uid in uids):
                    verdict = "allowed owner"
                    key += " " + ", ".join(owners.label(u) for u in uids)
                elif not uids:
                    verdict = "no owner sampled"
                else:
                    verdict = "OTHER OWNER"
                    key += " " + ", ".join(owners.label(u) for u in uids)
                tally[(state, verdict)][key] += 1
            lookups = collections.defaultdict(collections.Counter)
            for (when, n, src, sport, dst, transport) in capture.dns_queries:
                if when is None:
                    continue
                local = when - shift
                state = state_of(local, windows)
                if state == "between":
                    continue
                uids = sockets.owners(transport.lower(), src, sport, dst, 53, local) if sport is not None else []
                who = ", ".join(owners.label(u) for u in uids) or "no owner sampled"
                lookups[state][f"{n} to {dst} ({who})"] += 1
            print(f"  {os.path.basename(path)} (offset {offset:+.2f} s):")
            for (state, verdict), items in sorted(tally.items()):
                print(f"    {state} / {verdict}: {sum(items.values())} {dict(items.most_common())}")
            for state, items in sorted(lookups.items()):
                print(f"    {state} / lookups: {sum(items.values())} {dict(items.most_common())}")


if __name__ == "__main__":
    main(sys.argv[1])
