#!/usr/bin/env bash
# The end-to-end lab on the CI runner (Ubuntu), set up before the emulator starts (M5, M6).
# The emulator reaches this host's loopback as 10.0.2.2; the app's own process (outside its
# VPN) does too.
#
# Proxies (the phone reaches each at 10.0.2.2:<port>):
#   Dante   :1080  SOCKS5, username/password (RFC 1929) against a system account; IPv4 and IPv6
#   Squid   :3128  HTTP CONNECT, Basic sign-in; tunnels to ports 443, 8080, 8082 and 8443 only
#   Both resolve names with the proxies' DNS server below, never the runner's own resolver, and
#   reach the Internet too: the app's pre-flight check opens a tunnel to
#   connectivitycheck.gstatic.com:443 through them (ADR-0010 §4).
#   stunnel, TLS in front of them, certificates from this run's test CA (scripts/e2e_pki.py):
#     :3129 HTTP CONNECT over TLS, :1443 SOCKS5 over TLS   proxy.proxyflow.test, valid
#     :3130 other.proxyflow.test (wrong name)   :3131 expired   :3132 issued by a CA nothing trusts
#   gate    :1090  relays to Dante, or refuses, or never answers, as the tests set it over :1091
#   silent  :1081  accepts connections and never answers (pre-flight timeout and cancel)
#   closed  :1082  nothing listens (PROXY_UNREACHABLE)
#
# DNS servers, both logging every query:
#   10.99.0.53  the proxies' resolver: proxyflow.test and secure.proxyflow.test = 10.99.0.1,
#               proxyflow6.test = fd99::1 only (no IPv4 address), any other *.proxyflow.test
#               does not exist; other names are forwarded to the runner's resolver.
#   10.99.0.54  the phone's uplink resolver (the emulator's -dns-server): only
#               proxy.proxyflow.test = 10.0.2.2 exists among the lab's names (the app looks its
#               proxy's name up itself, ADR-0003); the rest is forwarded. A lookup of any
#               destination's name here would be a direct DNS lookup the app must never cause.
#
# Destinations, reachable from the phone only through a proxy unless traffic leaks (which their
# logs, the proxy logs and the packet captures would show):
#   proxyflow.test       10.99.0.1:8080   by name (M5)
#   sink                 10.99.0.1:8081   only lines sent while traffic must be held go there
#   by address           10.99.0.2:8080   asked for by its IPv4 address
#   IPv6                 [fd99::1]:8080   by its IPv6 address, and by name (proxyflow6.test)
#   LAN                  10.0.2.2:8082    the emulator's view of this host: the phone reaching it
#                                         directly would land on 127.0.0.1:8082, where nothing
#                                         listens, so an answer proves the proxy carried it
#   HTTPS                10.99.0.1:8443   secure.proxyflow.test, the test CA's certificate
#   UDP sink             10.99.0.1:9999 and [fd99::1]:9999: UDP must never arrive
#
# The passwords are random for each run and masked in the log; the tests read them from a file
# pushed to the emulator (scripts/e2e_device.sh), never from a command line. After the checks at
# the end, log-baseline.properties records how long each log was, so the evidence counts only
# what the device tests caused.
set -euo pipefail

PF=/tmp/pf
sudo rm -rf "$PF"
mkdir -p "$PF"
chmod 0777 "$PF"

SOCKS_USER=pfsocks
HTTP_USER=pfhttp
SOCKS_PASS="$(openssl rand -hex 16)"
HTTP_PASS="$(openssl rand -hex 16)"
echo "::add-mask::$SOCKS_PASS"
echo "::add-mask::$HTTP_PASS"

# The packages, without starting their default services.
printf '#!/bin/sh\nexit 101\n' | sudo tee /usr/sbin/policy-rc.d >/dev/null
sudo chmod +x /usr/sbin/policy-rc.d
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \
    dante-server squid stunnel4 dnsmasq-base python3-cryptography >/dev/null
sudo rm -f /usr/sbin/policy-rc.d
sudo systemctl stop squid danted >/dev/null 2>&1 || true

# The lab's addresses, all on this host's loopback.
for address in 10.99.0.1 10.99.0.2 10.99.0.53 10.99.0.54 10.0.2.2; do
    sudo ip addr add "$address/32" dev lo
done
sudo sysctl -q -w net.ipv6.conf.all.disable_ipv6=0 net.ipv6.conf.lo.disable_ipv6=0
sudo ip -6 addr add fd99::1/128 dev lo nodad
# The proxy's name for the phone, if the emulator asks this host's resolver instead of 10.99.0.54.
echo "10.0.2.2 proxy.proxyflow.test" | sudo tee -a /etc/hosts >/dev/null
UPSTREAM_DNS="$(awk '/^nameserver/ { print $2; exit }' /etc/resolv.conf)"
test -n "$UPSTREAM_DNS" || { echo "::error title=E2E lab::the runner has no DNS server in /etc/resolv.conf"; exit 1; }

python3 scripts/e2e_pki.py "$PF/pki" >/dev/null
CA_SHA256="$(cat "$PF/pki/destination.sha256")"

# Every log, readable by the evidence scripts whoever writes them.
LOGS="danted.log squid-access.log squid-cache.log destination.log sink.log destination-address.log destination6.log
      destination-lan.log tls-destination.log udp-sink.log gate.log stunnel.log dns-proxies.log dns-uplink.log"
for log in $LOGS; do
    touch "$PF/$log"
    chmod 0666 "$PF/$log"
done

# The DNS servers.
cat > "$PF/dns-proxies.conf" <<EOF
port=53
listen-address=10.99.0.53
bind-interfaces
no-resolv
no-hosts
server=$UPSTREAM_DNS
log-queries=extra
log-facility=$PF/dns-proxies.log
pid-file=$PF/dns-proxies.pid
local=/proxyflow.test/
local=/proxyflow6.test/
host-record=proxyflow.test,10.99.0.1
host-record=secure.proxyflow.test,10.99.0.1
host-record=proxyflow6.test,fd99::1
EOF
cat > "$PF/dns-uplink.conf" <<EOF
port=53
listen-address=10.99.0.54
bind-interfaces
no-resolv
no-hosts
server=$UPSTREAM_DNS
log-queries=extra
log-facility=$PF/dns-uplink.log
pid-file=$PF/dns-uplink.pid
local=/proxyflow.test/
local=/proxyflow6.test/
host-record=proxy.proxyflow.test,10.0.2.2
EOF
sudo dnsmasq -C "$PF/dns-proxies.conf"
sudo dnsmasq -C "$PF/dns-uplink.conf"
echo "nameserver 10.99.0.53" > "$PF/proxies-resolv.conf"
chmod 0644 "$PF/proxies-resolv.conf"

# Dante. It connects out from the address the routing table gives for each destination: the
# default route's interface for the Internet (the pre-flight check), the loopback for the lab's
# destinations, IPv6 ones included. It runs in a mount namespace whose /etc/resolv.conf names the
# proxies' DNS server, so it resolves names there and nowhere else.
EXTERNAL_IF="$(ip -4 route show default | awk '{ for (i = 1; i < NF; i++) if ($i == "dev") { print $(i + 1); exit } }')"
test -n "$EXTERNAL_IF" || { echo "::error title=E2E lab::the runner has no default route"; exit 1; }
sudo useradd --no-create-home --shell /usr/sbin/nologin "$SOCKS_USER"
echo "$SOCKS_USER:$SOCKS_PASS" | sudo chpasswd
cat > "$PF/danted.conf" <<EOF
logoutput: $PF/danted.log
internal: 127.0.0.1 port = 1080
external: $EXTERNAL_IF
external: lo
external.rotation: route
clientmethod: none
socksmethod: username
user.privileged: root
user.unprivileged: nobody
client pass {
    from: 127.0.0.0/8 to: 0.0.0.0/0
    log: error connect disconnect
}
socks pass {
    from: 127.0.0.0/8 to: 0.0.0.0/0
    command: connect
    socksmethod: username
    log: error connect disconnect
}
socks pass {
    from: 127.0.0.0/8 to: ::/0
    command: connect
    socksmethod: username
    log: error connect disconnect
}
EOF
sudo unshare --mount sh -c "mount --bind $PF/proxies-resolv.conf /etc/resolv.conf && exec danted -D -f $PF/danted.conf"

# Squid, resolving with the proxies' DNS server only (its own client, not /etc/hosts).
printf '%s:%s\n' "$HTTP_USER" "$(openssl passwd -apr1 "$HTTP_PASS")" > "$PF/squid-passwd"
chmod 0644 "$PF/squid-passwd"
cat > "$PF/squid.conf" <<EOF
http_port 127.0.0.1:3128
visible_hostname proxyflow-test
auth_param basic program /usr/lib/squid/basic_ncsa_auth $PF/squid-passwd
auth_param basic realm ProxyFlow test proxy
auth_param basic children 2
acl authed proxy_auth REQUIRED
acl tunnel_ports port 443 8080 8082 8443
acl CONNECT method CONNECT
http_access deny CONNECT !tunnel_ports
http_access allow authed
http_access deny all
dns_nameservers 10.99.0.53
hosts_file none
connect_timeout 5 seconds
access_log stdio:$PF/squid-access.log squid
cache_log $PF/squid-cache.log
pid_filename $PF/squid.pid
cache deny all
coredump_dir $PF
logfile_rotate 0
EOF
sudo squid -f "$PF/squid.conf"

# The TLS fronts, one stunnel process each, so no TLS session or ticket is shared between two
# certificates (a resumed session would skip the certificate the test means to change).
tls_front() { # <name> <port> <proxy port> <certificate>
    cat > "$PF/stunnel-$1.conf" <<EOF
foreground = no
pid = $PF/stunnel-$1.pid
output = $PF/stunnel.log
syslog = no
debug = info
[$1]
accept = 127.0.0.1:$2
connect = 127.0.0.1:$3
cert = $PF/pki/$4.pem
EOF
    stunnel4 "$PF/stunnel-$1.conf"
}
tls_front https-proxy 3129 3128 proxy-ok
tls_front socks-tls 1443 1080 proxy-ok
tls_front tls-other 3130 3128 proxy-other
tls_front tls-expired 3131 3128 proxy-expired
tls_front tls-untrusted 3132 3128 proxy-untrusted

# The destinations, the sinks, the silent proxy and the gate.
server() { # <name> <arguments...>
    local name="$1"
    shift
    nohup python3 scripts/e2e_servers.py "$@" > "$PF/$name.out" 2>&1 &
}
server destination destination 10.99.0.1 8080 "$PF/destination.log"
server sink destination 10.99.0.1 8081 "$PF/sink.log"
server destination-address destination 10.99.0.2 8080 "$PF/destination-address.log"
server destination6 destination fd99::1 8080 "$PF/destination6.log"
server destination-lan destination 10.0.2.2 8082 "$PF/destination-lan.log"
server tls-destination tls-destination 10.99.0.1 8443 "$PF/pki/destination.pem" "$PF/tls-destination.log"
server udp-sink udp-sink 10.99.0.1 9999 "$PF/udp-sink.log"
server udp-sink6 udp-sink fd99::1 9999 "$PF/udp-sink.log"
server silent silent 127.0.0.1 1081
server gate gate 127.0.0.1 1090 1091 127.0.0.1:1080 "$PF/gate.log"

# What the tests read on the emulator. The CA certificate goes there too (proxyflow-ca.pem).
cat > "$PF/e2e.properties" <<EOF
proxy.host=10.0.2.2
socks.port=1080
socks.user=$SOCKS_USER
socks.password=$SOCKS_PASS
http.port=3128
http.user=$HTTP_USER
http.password=$HTTP_PASS
silent.port=1081
closed.port=1082
destination.name=proxyflow.test
destination.address=10.99.0.1
destination.port=8080
sink.port=8081
proxy.name=proxy.proxyflow.test
tls.http.port=3129
tls.socks.port=1443
tls.other.port=3130
tls.expired.port=3131
tls.untrusted.port=3132
gate.port=1090
gate.control.port=1091
address.destination=10.99.0.2
address.port=8080
v6.address=fd99::1
v6.name=proxyflow6.test
v6.port=8080
lan.address=10.0.2.2
lan.port=8082
missing.name=missing.proxyflow.test
https.name=secure.proxyflow.test
https.port=8443
https.sha256=$CA_SHA256
udp.port=9999
ca.path=/data/local/tmp/proxyflow-ca.pem
EOF
chmod 0644 "$PF/e2e.properties"

# Everything above works from here before the emulator starts: every proxy reaches every
# destination, names resolve only where they should, the TLS fronts present the certificates
# they should, and both proxies reach the pre-flight check's target.
retry() {
    for _ in $(seq 1 50); do
        if "$@" 2>/dev/null; then return 0; fi
        sleep 0.2
    done
    "$@"
}
retry python3 scripts/e2e_lab_check.py --quiet "$PF"
python3 scripts/e2e_lab_check.py "$PF"

# The log lengths before any device test (see the header), once the checks' last lines are written.
sleep 2
: > "$PF/log-baseline.properties"
for log in $LOGS; do
    echo "$log=$(sudo wc -l < "$PF/$log")" >> "$PF/log-baseline.properties"
done
chmod 0644 "$PF/log-baseline.properties"
echo "::notice title=E2E lab::Dante $(danted -v 2>&1 | head -1) on :1080 (SOCKS5, sign-in, IPv4 and IPv6, out through $EXTERNAL_IF and lo); $(squid -v | head -1) on :3128; stunnel TLS fronts :3129 :1443 (valid) :3130 (wrong name) :3131 (expired) :3132 (untrusted CA); gate :1090; silent :1081; closed :1082; DNS 10.99.0.53 for the proxies and 10.99.0.54 for the phone's uplink, both logging (upstream $UPSTREAM_DNS); destinations proxyflow.test = 10.99.0.1:8080, 10.99.0.2:8080, [fd99::1]:8080 (also proxyflow6.test), LAN 10.0.2.2:8082, HTTPS secure.proxyflow.test:8443, sink :8081, UDP sinks :9999. Every check from the runner passed."
