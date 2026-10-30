"""Network-namespace lab for the ProxyFlow feasibility spike.

Topology (all on one Linux host, root required):

  pf-dev  "the phone"        dev-up 10.10.0.2/24  fd00:10::2/64   + tun0 198.18.0.1 / fd00:66::1
     |                        policy routing: unmarked traffic -> tun0 (the "VPN");
     |                        SO_MARK 438 (= VpnService.protect) -> dev-up (underlying network)
  pf-rtr  "the internet"      routes 10.10/24, 10.20/24, 10.30/24 (+ IPv6); underlay DNS 10.10.0.1:53
     |            \\
  pf-prx "the proxy"          pf-web "destinations"
   10.20.0.2 fd00:20::2        10.30.0.2 fd00:30::2
   spike_proxy socks5 :1080    HTTP :80 :8081, HTTPS :443, UDP echo :5000, iperf3 :5201
   spike_proxy socks5+auth :1082
   spike_proxy http+auth :3128
   Dante (real SOCKS5, user auth) :1081
   Squid (real HTTP proxy, Basic) :3129
   stunnel TLS fronts :1443 :1444(expired) :1445(untrusted) :3443 :3444

Captures are taken on tun0 (what apps sent), dev-up (everything that left the
phone), prx-eth (proxy host) and web-eth (what destinations saw).
"""
from __future__ import annotations

import contextlib
import json
import os
import secrets
import shutil
import signal
import subprocess
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPIKE = HERE.parent

DEV, RTR, PRX, WEB = "pf-dev", "pf-rtr", "pf-prx", "pf-web"
NAMESPACES = (DEV, RTR, PRX, WEB)
MARK = 438
# Some kernels (including the sandbox this spike was first run in) boot with
# ipv6.disable=1. The lab then runs IPv4-only and the IPv6 cases are skipped
# with an explicit reason; engine IPv6 handling is covered by hermetic tests.
IPV6 = os.path.exists("/proc/sys/net/ipv6")

DEV_IP, DEV_IP6 = "10.10.0.2", "fd00:10::2"
UNDERLAY_DNS = "10.10.0.1"
PROXY_IP, PROXY_IP6 = "10.20.0.2", "fd00:20::2"
WEB_IP, WEB_IP6 = "10.30.0.2", "fd00:30::2"
TUN_IP, TUN_IP6, FAKE_DNS = "198.18.0.1", "fd00:66::1", "198.18.0.2"

# Credentials are generated per run (never real secrets). Distinctive values
# make it easy to prove they never appear in engine logs.
CREDS = {
    "py_user": "pfuser-" + secrets.token_hex(3),
    "py_pass": "PFpw-" + secrets.token_hex(8),
    "dante_user": "pfdante",
    "dante_pass": "DNpw-" + secrets.token_hex(8),
    "squid_user": "pfsquid",
    "squid_pass": "SQpw-" + secrets.token_hex(8),
}


def sh(*cmd, check=True, capture=False, timeout=60, env=None):
    r = subprocess.run(list(cmd), check=False, text=True, timeout=timeout, env=env,
                       stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                       stderr=subprocess.PIPE if capture else subprocess.DEVNULL)
    if check and r.returncode != 0:
        raise RuntimeError(f"command failed ({r.returncode}): {' '.join(cmd)}\n{r.stderr if capture else ''}")
    return r


def nsx(ns, *cmd, **kw):
    return sh("ip", "netns", "exec", ns, *cmd, **kw)


class Lab:
    def __init__(self, workdir: Path):
        self.work = Path(workdir)
        self.work.mkdir(parents=True, exist_ok=True)
        self.logs = self.work / "services"
        self.logs.mkdir(exist_ok=True)
        self.certs = self.work / "certs"
        self.procs: dict[str, subprocess.Popen] = {}
        # Squid drops privileges to its own user, which cannot write into a run
        # directory below a private parent (e.g. a 0750 home). It therefore runs
        # from a world-traversable temp dir; logs are copied into evidence on down().
        self.squid_dir: Path | None = None

    @property
    def squid_access_log(self) -> Path:
        return (self.squid_dir or self.work / "squid") / "access.log"

    # ------------------------------------------------------------ topology --
    def down(self):
        for p in list(self.procs.values()):
            with contextlib.suppress(Exception):
                p.terminate()
        for p in list(self.procs.values()):
            with contextlib.suppress(Exception):
                p.wait(timeout=5)
        self.procs.clear()
        for ns in NAMESPACES:
            # kill anything still running inside the namespace
            r = sh("ip", "netns", "pids", ns, check=False, capture=True)
            for pid in (r.stdout or "").split():
                with contextlib.suppress(ProcessLookupError, ValueError):
                    os.kill(int(pid), signal.SIGKILL)
            sh("ip", "netns", "del", ns, check=False)
            shutil.rmtree(f"/etc/netns/{ns}", ignore_errors=True)
        # The lab's Dante account is a real host user (Dante authenticates via
        # the system password database): never leave it behind.
        sh("userdel", CREDS["dante_user"], check=False)
        if self.squid_dir is not None:
            keep = self.work / "squid"
            keep.mkdir(exist_ok=True)
            for name in ("squid.conf", "access.log", "cache.log"):
                with contextlib.suppress(OSError):
                    shutil.copy2(self.squid_dir / name, keep / name)
            shutil.rmtree(self.squid_dir, ignore_errors=True)
            self.squid_dir = None

    def up(self):
        self.down()
        for ns in NAMESPACES:
            sh("ip", "netns", "add", ns)
            nsx(ns, "ip", "link", "set", "lo", "up")
            if IPV6:
                for k in ("all", "default"):
                    nsx(ns, "sysctl", "-qw", f"net.ipv6.conf.{k}.accept_dad=0")
                    nsx(ns, "sysctl", "-qw", f"net.ipv6.conf.{k}.accept_ra=0")
        links = [("dev-up", DEV, "rtr-dev", RTR), ("prx-eth", PRX, "rtr-prx", RTR), ("web-eth", WEB, "rtr-web", RTR)]
        for a, ans, b, bns in links:
            sh("ip", "link", "add", a, "netns", ans, "type", "veth", "peer", "name", b, "netns", bns)
        addr = [
            (DEV, "dev-up", f"{DEV_IP}/24", f"{DEV_IP6}/64"),
            (RTR, "rtr-dev", "10.10.0.1/24", "fd00:10::1/64"),
            (RTR, "rtr-prx", "10.20.0.1/24", "fd00:20::1/64"),
            (RTR, "rtr-web", "10.30.0.1/24", "fd00:30::1/64"),
            (PRX, "prx-eth", f"{PROXY_IP}/24", f"{PROXY_IP6}/64"),
            (WEB, "web-eth", f"{WEB_IP}/24", f"{WEB_IP6}/64"),
        ]
        for ns, dev, v4, v6 in addr:
            nsx(ns, "ip", "addr", "add", v4, "dev", dev)
            if IPV6:
                nsx(ns, "ip", "-6", "addr", "add", v6, "dev", dev, "nodad")
            nsx(ns, "ip", "link", "set", dev, "up")
        nsx(RTR, "sysctl", "-qw", "net.ipv4.ip_forward=1")
        if IPV6:
            nsx(RTR, "sysctl", "-qw", "net.ipv6.conf.all.forwarding=1")
        for ns, gw4, gw6 in ((DEV, "10.10.0.1", "fd00:10::1"), (PRX, "10.20.0.1", "fd00:20::1"), (WEB, "10.30.0.1", "fd00:30::1")):
            nsx(ns, "ip", "route", "add", "default", "via", gw4)
            if IPV6:
                nsx(ns, "ip", "-6", "route", "add", "default", "via", gw6)

        # The "VPN": persistent TUN + policy routing that mirrors Android:
        # every socket uses the VPN unless protected (marked).
        self.create_tun()
        for fam in (("-4", "-6") if IPV6 else ("-4",)):
            nsx(DEV, "ip", fam, "rule", "add", "fwmark", str(MARK), "lookup", "main", "pref", "100")
            nsx(DEV, "ip", fam, "rule", "add", "lookup", "100", "pref", "200")
        for k in ("all", "default", "tun0"):
            nsx(DEV, "sysctl", "-qw", f"net.ipv4.conf.{k}.rp_filter=0")

        # Per-namespace resolv.conf / hosts (bind-mounted by `ip netns exec`).
        for ns, resolv, hosts in (
            (DEV, f"nameserver {FAKE_DNS}\noptions timeout:2 attempts:1\n", "127.0.0.1 localhost\n"),
            (PRX, "nameserver 127.0.0.1\n",
             f"127.0.0.1 localhost\n{WEB_IP} web.test\n{WEB_IP6} web.test\n10.30.0.99 blackhole.test\n"),
            (WEB, "nameserver 127.0.0.1\n", "127.0.0.1 localhost\n"),
            (RTR, "nameserver 127.0.0.1\n", "127.0.0.1 localhost\n"),
        ):
            d = Path(f"/etc/netns/{ns}")
            d.mkdir(parents=True, exist_ok=True)
            (d / "resolv.conf").write_text(resolv)
            (d / "hosts").write_text(hosts)

    def create_tun(self):
        nsx(DEV, "ip", "tuntap", "add", "mode", "tun", "name", "tun0")
        nsx(DEV, "ip", "addr", "add", f"{TUN_IP}/32", "dev", "tun0")
        if IPV6:
            nsx(DEV, "ip", "-6", "addr", "add", f"{TUN_IP6}/128", "dev", "tun0", "nodad")
        nsx(DEV, "ip", "link", "set", "tun0", "mtu", "1500", "up")
        nsx(DEV, "ip", "route", "replace", "default", "dev", "tun0", "table", "100")
        if IPV6:
            nsx(DEV, "ip", "-6", "route", "replace", "default", "dev", "tun0", "table", "100")

    def delete_tun(self):
        nsx(DEV, "ip", "link", "del", "tun0", check=False)

    # ------------------------------------------------------------ services --
    def spawn(self, name, ns, cmd, env=None):
        out = open(self.logs / f"{name}.out", "ab")
        p = subprocess.Popen(["ip", "netns", "exec", ns, *cmd], stdout=out, stderr=subprocess.STDOUT,
                             env={**os.environ, **(env or {})}, start_new_session=True)
        self.procs[name] = p
        return p

    def stop(self, name, sig=signal.SIGTERM):
        p = self.procs.pop(name, None)
        if p and p.poll() is None:
            with contextlib.suppress(ProcessLookupError):
                os.killpg(p.pid, sig)
            with contextlib.suppress(subprocess.TimeoutExpired):
                p.wait(timeout=10)

    def wait_port(self, ns, host, port, timeout=15):
        deadline = time.time() + timeout
        while time.time() < deadline:
            r = nsx(ns, "bash", "-c", f"exec 3<>/dev/tcp/{host}/{port}", check=False)
            if r.returncode == 0:
                return
            time.sleep(0.2)
        raise RuntimeError(f"{host}:{port} in {ns} not listening")

    def start_services(self):
        py = shutil.which("python3")
        sh(py, str(HERE / "certs.py"), str(self.certs))
        L = self.logs
        web_hosts = ["--host", WEB_IP] + (["--host", WEB_IP6] if IPV6 else [])
        self.spawn("web", WEB, [py, str(HERE / "targets/web_server.py"), *web_hosts,
                                "--cert", str(self.certs / "web.pem"), "--key", str(self.certs / "web.key"),
                                "--log", str(L / "web.jsonl")])
        self.spawn("underlay-dns", RTR, [py, str(HERE / "targets/underlay_dns.py"), "--listen", UNDERLAY_DNS,
                                         "--record", f"proxy.test={PROXY_IP}", "--log", str(L / "underlay-dns.jsonl")])
        self.spawn("iperf3", WEB, ["iperf3", "-s", "-B", WEB_IP])
        proxy = str(HERE / "proxies/spike_proxy.py")
        self.spawn("py-socks", PRX, [py, proxy, "socks5", *self.socks_listen(), "--log", str(L / "py-socks.jsonl")])
        self.spawn("py-socks-auth", PRX, [py, proxy, "socks5", "--listen", f"{PROXY_IP}:1082", "--auth", CREDS["py_user"],
                                          "--deny-port", "8081", "--log", str(L / "py-socks-auth.jsonl")],
                   env={"SPIKE_PROXY_PASSWORD": CREDS["py_pass"]})
        self.spawn("py-http", PRX, [py, proxy, "http", "--listen", f"{PROXY_IP}:3128", "--auth", CREDS["py_user"],
                                    "--deny-port", "8081", "--log", str(L / "py-http.jsonl")],
                   env={"SPIKE_PROXY_PASSWORD": CREDS["py_pass"]})
        self._start_dante()
        self._start_squid()
        self._start_stunnel()
        for ns, host, port in ((WEB, WEB_IP, 80), (WEB, WEB_IP, 443), (PRX, PROXY_IP, 1080), (PRX, PROXY_IP, 1082),
                               (PRX, PROXY_IP, 3128), (PRX, PROXY_IP, 1081), (PRX, PROXY_IP, 3129), (PRX, PROXY_IP, 1443),
                               (PRX, PROXY_IP, 3444), (WEB, WEB_IP, 5201)):
            self.wait_port(ns, host, port)

    @staticmethod
    def socks_listen():
        return ["--listen", f"{PROXY_IP}:1080"] + (["--listen", f"[{PROXY_IP6}]:1080"] if IPV6 else [])

    def _start_dante(self):
        user, pw = CREDS["dante_user"], CREDS["dante_pass"]
        if sh("id", user, check=False).returncode != 0:
            sh("useradd", "-M", "-s", "/usr/sbin/nologin", user)
        subprocess.run(["chpasswd"], input=f"{user}:{pw}\n", text=True, check=True)
        conf = self.work / "danted.conf"
        conf.write_text(f"""logoutput: {self.logs / 'dante.log'}
internal: {PROXY_IP} port = 1081
external: prx-eth
socksmethod: username
user.privileged: root
user.unprivileged: nobody
client pass {{
    from: 0.0.0.0/0 to: 0.0.0.0/0
    log: connect error
}}
socks pass {{
    from: 0.0.0.0/0 to: 0.0.0.0/0
    command: connect
    socksmethod: username
    log: connect disconnect error
}}
""")
        self.spawn("dante", PRX, ["danted", "-f", str(conf), "-N", "1", "-p", str(self.work / "danted.pid")])

    def _start_squid(self):
        d = Path(tempfile.mkdtemp(prefix="pf-squid-"))
        os.chmod(d, 0o777)
        self.squid_dir = d
        hashed = sh("openssl", "passwd", "-apr1", CREDS["squid_pass"], capture=True).stdout.strip()
        (d / "passwd").write_text(f"{CREDS['squid_user']}:{hashed}\n")
        os.chmod(d / "passwd", 0o644)
        helper = next(p for p in ("/usr/lib/squid/basic_ncsa_auth", "/usr/lib/squid3/basic_ncsa_auth") if Path(p).exists())
        conf = d / "squid.conf"
        conf.write_text(f"""http_port {PROXY_IP}:3129
visible_hostname spike-proxy
auth_param basic program {helper} {d / 'passwd'}
auth_param basic realm spike
acl authed proxy_auth REQUIRED
acl CONNECT method CONNECT
acl tunnel_ports port 80 443 5201
http_access deny CONNECT !tunnel_ports
http_access allow authed
http_access deny all
cache deny all
access_log stdio:{d / 'access.log'}
cache_log {d / 'cache.log'}
pid_filename {d / 'squid.pid'}
coredump_dir {d}
dns_v4_first on
shutdown_lifetime 1 seconds
""")
        self.spawn("squid", PRX, ["squid", "-N", "-f", str(conf)])

    def _start_stunnel(self):
        c = self.certs
        conf = self.work / "stunnel.conf"
        conf.write_text(f"""foreground = yes
pid = {self.work / 'stunnel.pid'}
output = {self.logs / 'stunnel.log'}
[socks-tls]
accept = {PROXY_IP}:1443
connect = {PROXY_IP}:1080
cert = {c / 'proxy.bundle'}
[socks-tls-expired]
accept = {PROXY_IP}:1444
connect = {PROXY_IP}:1080
cert = {c / 'proxy-expired.bundle'}
[socks-tls-untrusted]
accept = {PROXY_IP}:1445
connect = {PROXY_IP}:1080
cert = {c / 'proxy-untrusted.bundle'}
[http-auth-tls]
accept = {PROXY_IP}:3443
connect = {PROXY_IP}:3128
cert = {c / 'proxy.bundle'}
[squid-tls]
accept = {PROXY_IP}:3444
connect = {PROXY_IP}:3129
cert = {c / 'proxy.bundle'}
""")
        binary = shutil.which("stunnel4") or shutil.which("stunnel")
        self.spawn("stunnel", PRX, [binary, str(conf)])

    # ------------------------------------------------------------ captures --
    @contextlib.contextmanager
    def capture(self, outdir: Path, snaplen: int = 0, ifaces=None):
        outdir.mkdir(parents=True, exist_ok=True)
        ifaces = ifaces or [(DEV, "tun0", "1-app-tun0"), (DEV, "dev-up", "2-device-uplink"),
                            (PRX, "prx-eth", "3-proxy-host"), (WEB, "web-eth", "4-destination")]
        procs, files = [], {}
        for ns, iface, label in ifaces:
            f = outdir / f"{label}.pcap"
            files[label] = f
            # -Z root: do not drop to the tcpdump user, which may not be able to
            # write below a private run directory
            p = subprocess.Popen(["ip", "netns", "exec", ns, "tcpdump", "-Z", "root", "-i", iface, "-n", "-U",
                                  "-s", str(snaplen), "-w", str(f)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            procs.append(p)
        time.sleep(1.0)  # let tcpdump attach
        try:
            yield files
        finally:
            time.sleep(0.7)
            for p in procs:
                p.send_signal(signal.SIGINT)
            for p in procs:
                with contextlib.suppress(subprocess.TimeoutExpired):
                    p.wait(timeout=5)


def read_jsonl(path: Path, since: float = 0.0):
    if not path.exists():
        return []
    out = []
    for line in path.read_text().splitlines():
        with contextlib.suppress(json.JSONDecodeError):
            rec = json.loads(line)
            if rec.get("ts", 1e18) >= since:
                out.append(rec)
    return out
