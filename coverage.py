"""For the packets ordinary apps sent on the phone's own network inside connected periods' unchecked parts: their
times in every capture of that level (lined up with logcat), and the logcat lines around them."""
import datetime
import glob
import os
import re
import sys

sys.path.insert(0, "scripts")
import m6_evidence as m6  # noqa: E402

YEAR = 2026
TARGETS = {  # api -> (protocol, destination, port) of the items to follow
    "34": [("UDP", "198.18.0.2", 53), ("UDP", "172.217.115.4", 443)],
    "35": [("UDP", "198.18.0.2", 53)],
}
KEEP = re.compile(r"ProxyFlowVpn|ProxyFlowE2E|ConnectivityService|Vpn\s*:|VpnService|DnsResolver|netd|NetworkAgent|resolv|TestRunner")


def main(root: str) -> None:
    for d in sorted(glob.glob(os.path.join(root, "**", "m5-evidence-api*"), recursive=True)):
        api = d.rstrip("/").rsplit("api", 1)[1]
        if api not in TARGETS:
            continue
        dumped, streamed = m6.read(os.path.join(d, "logcat.txt")), m6.read(os.path.join(d, "logcat-live.txt"))
        logcat = dumped if len(dumped) >= len(streamed) else streamed
        windows, starts = m6.connected_windows(logcat, YEAR)
        network = m6.read(os.path.join(d, "guest-network.txt"))
        sockets = m6.socket_samples(d)
        moments = []
        for path in sorted(glob.glob(os.path.join(d, "*.pcap"))):
            capture = m6.read_capture(path)
            if capture.error or not capture.packets:
                continue
            phone = m6.phone_of(capture, network, sockets)
            offset, how = m6.capture_offset(capture, starts, phone)
            if offset is None:
                continue
            shift = datetime.timedelta(seconds=offset)
            for when, src, dst, kind, port, sport in capture.events:
                if when is None or src not in phone or (kind, dst, port) not in TARGETS[api]:
                    continue
                local = when - shift
                inside = [(s, e) for s, e, _ in windows if s - datetime.timedelta(seconds=3) <= local <= e + datetime.timedelta(seconds=3)]
                where = "; ".join(f"period {s:%H:%M:%S.%f}"[:-3] + f"–{e:%H:%M:%S.%f}"[:-3] for s, e in inside) or "no period within 3 s"
                print(f"API {api} {os.path.basename(path)}: {kind} {dst}:{port} from port {sport} at {local:%H:%M:%S.%f}"[:-3] + f" ({where})")
                moments.append(local)
        shown = set()
        for moment in sorted(moments)[:6]:
            print(f"API {api} logcat around {moment:%H:%M:%S.%f}"[:-3] + ":")
            for line in logcat:
                t = m6.logcat_time(line, YEAR)
                if t and abs((t - moment).total_seconds()) <= 0.6 and KEEP.search(line) and line not in shown:
                    shown.add(line)
                    print("    " + line[:230])


if __name__ == "__main__":
    main(sys.argv[1])
