"""How much of each connected period scripts/m6_evidence.py checks (it leaves out MARGIN at each end), which
periods have Android's view of the VPN logged in them, and where the phone's pings on its own network fell.
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
        print(f"  durations (s): {sorted(round(x, 1) for x in durations)}")
        # Android's view of the VPN: which periods have one logged inside them.
        views = [m6.logcat_time(line, YEAR) for line in logcat if re.search(r"ProxyFlowE2E\s*:\s*android's view of the VPN", line)]
        views = [v for v in views if v]
        test_of = []
        current = None
        for line in logcat:
            m = re.search(r"TestRunner: started: (\S+)\(([\w.]+)\)", line)
            if m:
                current = f"{m.group(2).rsplit('.', 1)[-1]}.{m.group(1)}"
            when = m6.logcat_time(line, YEAR)
            if when:
                test_of.append((when, current))
        def test_at(when):
            name = None
            for t, n in test_of:
                if t > when:
                    break
                name = n
            return name
        without = [(f"{start:%H:%M:%S}", round((end - start).total_seconds(), 1), test_at(start)) for start, end, _ in windows
                   if not any(start <= v <= end for v in views)]
        print(f"  views logged: {len(views)}; periods without one inside: {without}")
        short_tests = collections.Counter(test_at(start) for (start, end, _), c in zip(windows, checked) if c == 0)
        print(f"  periods not checked at all (under {2 * margin:.0f} s), by test: {dict(short_tests)}")
        # Pings from the phone on its own network, by where they fell.
        network = m6.read(os.path.join(d, "guest-network.txt"))
        sockets = m6.socket_samples(d)
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
            where = collections.Counter()
            others = collections.Counter()
            for when, src, dst, kind, port, sport in capture.events:
                if src not in phone or when is None:
                    continue
                local = when - shift
                state = "between periods"
                for start, end, _ in windows:
                    if start <= local <= end:
                        if start + m6.MARGIN <= local <= end - m6.MARGIN:
                            state = "checked part"
                        elif (end - start).total_seconds() <= 2 * margin:
                            state = "unchecked short period"
                        else:
                            state = "within 2 s of a period's end"
                        break
                if kind == "ICMP":
                    where[f"{dst} {state}"] += 1
                elif state != "between periods" and state != "checked part" and not m6.explained(dst, kind, port, set()):
                    others[f"{kind} {dst}:{port} {state}"] += 1
            print(f"  {os.path.basename(path)} (offset {offset:+.2f} s): pings {dict(where) or 'none'}")
            print(f"    other traffic in the unchecked parts not to the proxy, resolver or check hosts (rough, Android's check addresses not excluded): "
                  f"{dict(others.most_common(10)) or 'none'}")


if __name__ == "__main__":
    main(sys.argv[1])
