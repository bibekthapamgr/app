"""1) How far the emulator's own capture really is from the phone's capture of the same uplink (the phone's shares
logcat's clock): the same TCP connections matched by their ends. 2) Logcat around chosen moments."""
import collections
import datetime
import glob
import os
import re
import statistics
import sys

sys.path.insert(0, "scripts")
import m6_evidence as m6  # noqa: E402

YEAR = 2026
MOMENTS = {"35": ["07:39:50.050", "07:41:00.183", "07:40:10.808"], "34": ["07:40:18.971"]}
KEEP = re.compile(r"ProxyFlowVpn|ProxyFlowE2E|ConnectivityService|Vpn\s*:|netd|resolv\s*:.*(GetAddrInfo|query)|TestRunner")


def main(root: str) -> None:
    for d in sorted(glob.glob(os.path.join(root, "**", "m5-evidence-api*"), recursive=True)):
        api = d.rstrip("/").rsplit("api", 1)[1]
        dumped, streamed = m6.read(os.path.join(d, "logcat.txt")), m6.read(os.path.join(d, "logcat-live.txt"))
        logcat = dumped if len(dumped) >= len(streamed) else streamed
        windows, starts = m6.connected_windows(logcat, YEAR)
        network = m6.read(os.path.join(d, "guest-network.txt"))
        sockets = m6.socket_samples(d)
        syns = {}
        for path in sorted(glob.glob(os.path.join(d, "*.pcap"))):
            capture = m6.read_capture(path)
            if capture.error or not capture.packets:
                continue
            phone = m6.phone_of(capture, network, sockets)
            offset, how = m6.capture_offset(capture, starts, phone)
            first = {}
            for when, src, dst, kind, port, sport in capture.events:
                if kind == "TCP" and when is not None and src in phone:
                    first.setdefault((src, sport, dst, port), when)
            syns[os.path.basename(path)] = (first, offset, how)
            print(f"API {api} {os.path.basename(path)}: evidence offset {offset}; {how}")
        if "emulator-uplink.pcap" in syns:
            for name, (first, _, _) in syns.items():
                if name == "emulator-uplink.pcap":
                    continue
                emu = syns["emulator-uplink.pcap"][0]
                diffs = [(first[k] - emu[k]).total_seconds() for k in first.keys() & emu.keys()]
                if diffs:
                    q = statistics.quantiles(diffs, n=4) if len(diffs) > 3 else diffs
                    print(f"API {api} {name} minus emulator, same connections: {len(diffs)} matched; median {statistics.median(diffs):+.3f} s, "
                          f"quartiles {[round(x, 3) for x in q]}, min {min(diffs):+.3f}, max {max(diffs):+.3f}")
            # the emulator's capture against logcat, by the phone's own connection attempts, with the true offset known
        for moment in MOMENTS.get(api, []):
            t0 = datetime.datetime.strptime(f"{YEAR} 11-01 {moment}", "%Y %m-%d %H:%M:%S.%f")
            print(f"API {api} logcat around {moment}:")
            for line in logcat:
                t = m6.logcat_time(line, YEAR)
                if t and -1.2 <= (t - t0).total_seconds() <= 0.4 and KEEP.search(line):
                    print("    " + line[:200])


if __name__ == "__main__":
    main(sys.argv[1])
