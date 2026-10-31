#!/usr/bin/env bash
# Reviewer experiment: pfspike on a TUN in an isolated namespace, raw ICMP probes.
# Usage: run_exp.sh <outdir> <pfspike-binary> [probe,list]
set -u
OUT=$1; BIN=$2; WHICH=${3:-}
HERE=$(cd "$(dirname "$0")" && pwd)
NS=pfrv-$$
mkdir -p "$OUT"
cleanup() {
  for p in $(ip netns pids "$NS" 2>/dev/null); do kill -9 "$p" 2>/dev/null; done
  ip netns del "$NS" 2>/dev/null
}
trap cleanup EXIT
ip netns add "$NS"
ip netns exec "$NS" ip link set lo up
ip netns exec "$NS" ip tuntap add mode tun name tun0
ip netns exec "$NS" ip addr add 198.18.0.1/32 dev tun0
ip netns exec "$NS" ip link set tun0 mtu ${TUNMTU:-1500} up
ip netns exec "$NS" tcpdump -Z root -i tun0 -n -U -s 0 -w "$OUT/tun0.pcap" >/dev/null 2>&1 &
sleep 1
rm -f "$OUT/ready"
ip netns exec "$NS" "$BIN" --tun tun0 --mtu ${TUNMTU:-1500} --proxy socks5://127.0.0.1:1080 \
  --stats "$OUT/stats.json" --events "$OUT/events.jsonl" --ready "$OUT/ready" >"$OUT/engine.log" 2>&1 &
EPID=$!
for i in $(seq 1 100); do [ -e "$OUT/ready" ] && break; sleep 0.05; done
sleep 0.3
ip netns exec "$NS" python3 -I "$HERE/inject.py" tun0 $WHICH > "$OUT/inject.json" 2>"$OUT/inject.err"
if [ -n "${KPING:-}" ]; then
  ip netns exec "$NS" ip route add default dev tun0
  ip netns exec "$NS" ping -n -c 2 -W 1 10.30.0.2 > "$OUT/kping-small.txt" 2>&1
  ip netns exec "$NS" ping -n -c 2 -W 1 -s 2000 10.30.0.2 > "$OUT/kping-large.txt" 2>&1
fi
sleep 0.7
kill -TERM $EPID; wait $EPID 2>/dev/null
sleep 0.5
pkill -INT -f "tcpdump -Z root -i tun0 -n -U -s 0 -w $OUT/tun0.pcap" 2>/dev/null
sleep 0.5
echo "ns=$NS done"
