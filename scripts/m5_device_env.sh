#!/usr/bin/env bash
# The M5 end-to-end environment on the CI runner (Ubuntu), set up before the
# emulator starts. The emulator reaches this host's loopback as 10.0.2.2.
#
#   Dante  127.0.0.1:1080  SOCKS5, username/password (RFC 1929) against a system account
#   Squid  127.0.0.1:3128  HTTP CONNECT, Basic sign-in; tunnels to ports 443 and 8080 only
#   silent 127.0.0.1:1081  accepts connections and never answers (pre-flight timeout/cancel)
#   closed 127.0.0.1:1082  nothing listens (PROXY_UNREACHABLE)
#   destination 10.99.0.1:8080, named proxyflow.test in /etc/hosts: the proxies resolve the
#   name; the emulator can only reach it through a proxy unless traffic leaks (which the
#   destination log, the proxy logs and the packet captures would show).
#
# The passwords are random for each run and masked in the log; the tests read them from
# a file pushed to the emulator (scripts/m5_device_e2e.sh), never from a command line.
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
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends dante-server squid >/dev/null
sudo rm -f /usr/sbin/policy-rc.d
sudo systemctl stop squid danted >/dev/null 2>&1 || true

# The destination's own address, and its name for the proxies.
sudo ip addr add 10.99.0.1/32 dev lo
echo "10.99.0.1 proxyflow.test" | sudo tee -a /etc/hosts >/dev/null

# The proxies' logs, readable by the evidence check (scripts/m5_evidence.py) whoever writes them.
touch "$PF/danted.log" "$PF/squid-access.log" "$PF/squid-cache.log"
chmod 0666 "$PF/danted.log" "$PF/squid-access.log" "$PF/squid-cache.log"

# Dante.
sudo useradd --no-create-home --shell /usr/sbin/nologin "$SOCKS_USER"
echo "$SOCKS_USER:$SOCKS_PASS" | sudo chpasswd
cat > "$PF/danted.conf" <<EOF
logoutput: $PF/danted.log
internal: 127.0.0.1 port = 1080
external: lo
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
EOF
sudo danted -D -f "$PF/danted.conf"

# Squid.
printf '%s:%s\n' "$HTTP_USER" "$(openssl passwd -apr1 "$HTTP_PASS")" > "$PF/squid-passwd"
chmod 0644 "$PF/squid-passwd"
cat > "$PF/squid.conf" <<EOF
http_port 127.0.0.1:3128
visible_hostname proxyflow-test
auth_param basic program /usr/lib/squid/basic_ncsa_auth $PF/squid-passwd
auth_param basic realm ProxyFlow test proxy
auth_param basic children 2
acl authed proxy_auth REQUIRED
acl tunnel_ports port 443 8080
acl CONNECT method CONNECT
http_access deny CONNECT !tunnel_ports
http_access allow authed
http_access deny all
connect_timeout 5 seconds
access_log stdio:$PF/squid-access.log squid
cache_log $PF/squid-cache.log
pid_filename $PF/squid.pid
cache deny all
coredump_dir $PF
logfile_rotate 0
EOF
sudo squid -f "$PF/squid.conf"

# The destination and the silent proxy.
nohup python3 scripts/m5_e2e_servers.py destination 10.99.0.1 8080 "$PF/destination.log" > "$PF/destination.out" 2>&1 &
nohup python3 scripts/m5_e2e_servers.py silent 127.0.0.1 1081 > "$PF/silent.out" 2>&1 &

# What the tests read on the emulator.
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
EOF
chmod 0644 "$PF/e2e.properties"

# Both proxies carry a line to the destination from here before the emulator starts.
check_socks() {
    curl -sS --max-time 5 --http0.9 -x "socks5h://$SOCKS_USER:$SOCKS_PASS@127.0.0.1:1080" http://proxyflow.test:8080/ |
        grep '^PROXYFLOW-DEST GET / HTTP/1.1' >/dev/null
}
check_http() {
    curl -sS --max-time 5 --http0.9 -p -x http://127.0.0.1:3128 --proxy-user "$HTTP_USER:$HTTP_PASS" http://proxyflow.test:8080/ |
        grep '^PROXYFLOW-DEST GET / HTTP/1.1' >/dev/null
}
retry() {
    for _ in $(seq 1 50); do
        if "$@" 2>/dev/null; then return 0; fi
        sleep 0.2
    done
    "$@"
}
retry check_socks
retry check_http
echo "::notice title=M5 proxies::Dante $(danted -v 2>&1 | head -1) on :1080 (SOCKS5, sign-in); $(squid -v | head -1) on :3128 (HTTP CONNECT, sign-in); silent proxy :1081; closed port :1082; destination proxyflow.test = 10.99.0.1:8080. Both proxies carried a line to the destination from the runner."
