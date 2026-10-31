#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner (one line in the workflow, as
# the action runs each script line on its own): every module's device tests, with
# the M5 end-to-end setup pushed to the emulator and the phone's uplink captured
# from inside it when the image has tcpdump. Afterwards scripts/m5_evidence.py
# reads what this leaves in $RUNNER_TEMP/m5-api<level>.
#
# Usage: m5_device_e2e.sh <api level>
set -uo pipefail

API="$1"
OUT="$RUNNER_TEMP/m5-api$API"
mkdir -p "$OUT"

# The tests read the proxies' addresses and the run's passwords from here (as the shell user).
adb push /tmp/pf/e2e.properties /data/local/tmp/proxyflow-e2e.properties >/dev/null
adb shell chmod 644 /data/local/tmp/proxyflow-e2e.properties

restart_adbd() {
    adb "$1" >/dev/null 2>&1 || return 1
    sleep 3
    adb wait-for-device
    adb shell true >/dev/null 2>&1
}

# The phone's uplink becomes its mobile data, which runs through the emulator's own network
# stack on every API level, so the emulator's -tcpdump sees it; from API 34 on, Wi-Fi runs
# through a separate simulator that capture does not see.
#
# The tests need that uplink to reach the runner (10.0.2.2, the emulator's name for the runner's
# loopback). The runner's closed port refuses a connection and the refusal comes back through the
# emulator: that proves the route without touching the proxies or the destination. Without an
# uplink the phone answers "Network is unreachable" itself. Mobile data did not come up on one
# API 35 emulator (run 41219399550: one packet on its uplink all run, every connection "no route
# to the proxy"), so it is switched off and on once, and then Wi-Fi is the fallback (from API 34
# on only tcpdump inside the phone sees it; the evidence says which captures cover the uplink).
reaches_runner() {
    local out
    out="$(adb shell 'toybox nc -w 3 10.0.2.2 1082 </dev/null; true' 2>&1 | tr -d '\r')"
    case "$out" in *[Rr]efused*) return 0 ;; esac
    return 1
}
uplink_within() { # <seconds>
    local end=$((SECONDS + $1))
    while [ "$SECONDS" -lt "$end" ]; do
        if reaches_runner; then return 0; fi
        sleep 2
    done
    return 1
}
wifi_down() {
    local out
    out="$(adb shell 'ip -o -4 addr show wlan0 2>/dev/null; true' 2>&1)"
    case "$out" in *inet*) return 1 ;; esac
    return 0
}
START=$SECONDS
adb shell svc wifi disable >/dev/null 2>&1 || true
for _ in $(seq 1 15); do
    if wifi_down; then break; fi
    sleep 1
done
MOBILE="mobile data"
if ! wifi_down; then MOBILE="mobile data or Wi-Fi (Wi-Fi did not switch off)"; fi
UPLINK=""
if uplink_within 60; then
    UPLINK="$MOBILE"
else
    adb shell svc data disable >/dev/null 2>&1 || true
    sleep 3
    adb shell svc data enable >/dev/null 2>&1 || true
    if uplink_within 60; then
        UPLINK="$MOBILE (after switching mobile data off and on)"
    else
        adb shell svc wifi enable >/dev/null 2>&1 || true
        if uplink_within 60; then UPLINK="Wi-Fi (mobile data never reached the runner)"; fi
    fi
fi
{
    echo "uplink: ${UPLINK:-none: the phone never reached the runner} ($((SECONDS - START)) s)"
    echo "routes: $(adb shell ip route 2>&1 | tr -d '\r' | paste -sd ';' -)"
    echo "addresses: $(adb shell ip -o -4 addr show 2>&1 | tr -d '\r' | awk '{print $2, $4}' | paste -sd ';' -)"
} > "$OUT/guest-network.txt"
if [ -z "$UPLINK" ]; then
    echo "::error title=Emulator network (API $API)::The phone never reached the runner (10.0.2.2), over mobile data or Wi-Fi, so no connection to the proxies can work. $(paste -sd ' ' "$OUT/guest-network.txt")"
fi

# tcpdump on each uplink interface (not the VPN's tun0), as root, moved out of adbd's process
# group (init kills that group's cgroup when adbd restarts as the shell user, which the tests
# need: the VPN covers the shell, not root). The evidence counts these captures only if
# tcpdump is still running after the tests.
GUEST_CAPTURE=0
{
    echo "adb root: $(adb root 2>&1 | tr -d '\r')"
    sleep 3
    adb wait-for-device
    echo "id: $(adb shell id 2>&1 | tr -d '\r')"
    echo "tcpdump: $(adb shell 'command -v tcpdump || echo none' 2>&1 | tr -d '\r')"
} > "$OUT/guest-capture.txt" 2>&1
if grep -q 'uid=0' "$OUT/guest-capture.txt" && ! grep -q 'tcpdump: none' "$OUT/guest-capture.txt"; then
    adb shell ip -o link show > "$OUT/guest-links.txt" 2>&1
    IFACES="$(awk -F': ' '{print $2}' "$OUT/guest-links.txt" | sed 's/@.*//' | tr -d '\r' | grep -E '^(eth|wlan|radio|rmnet)[0-9]+$' || true)"
    for iface in $IFACES; do
        adb shell "setsid tcpdump -i $iface -s 0 -U -w /data/local/tmp/pf-$iface.pcap </dev/null >/dev/null 2>&1 &"
    done
    echo "$IFACES" > "$OUT/guest-interfaces.txt"
    GUEST_CAPTURE=1
    sleep 2
    # The root cgroup: /sys/fs/cgroup (cgroup v2, API 30+) or /acct (v1, API 29).
    adb shell 'for p in $(pidof tcpdump); do for c in /sys/fs/cgroup/cgroup.procs /acct/cgroup.procs; do [ -w "$c" ] && echo "$p" > "$c"; done; done' >/dev/null 2>&1 || true
    echo "tcpdump at the start: $(adb shell 'pidof tcpdump || echo none' 2>&1 | tr -d '\r')" >> "$OUT/guest-capture.txt"
else
    echo "no root or no tcpdump in this image: the emulator's own capture (-tcpdump) is the only one" >> "$OUT/guest-capture.txt"
fi
restart_adbd unroot || true

adb logcat -G 16M >/dev/null 2>&1 || true
adb logcat -c || true

# Bounded well inside the job's own limit, so that logcat, the captures and the evidence are still
# collected when a test hangs (scripts/m5_evidence.py reads how far the tests got from logcat).
timeout --kill-after=60s "${DEVICE_TESTS_TIMEOUT:-45m}" \
    ./gradlew :core:data:connectedDebugAndroidTest :core:engine:connectedDebugAndroidTest :app:connectedDebugAndroidTest \
    --continue 2>&1 | tee "$RUNNER_TEMP/gradle-2-device.log"
STATUS=${PIPESTATUS[0]}
if [ "$STATUS" = 124 ] || [ "$STATUS" = 137 ]; then
    echo "::error title=Device tests (API $API)::The device tests ran past ${DEVICE_TESTS_TIMEOUT:-45m} and were stopped; the evidence lists from logcat which tests ran, failed or never finished."
fi

adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
if [ "$GUEST_CAPTURE" = 1 ]; then
    restart_adbd root || true
    echo "tcpdump at the end: $(adb shell 'pidof tcpdump || echo none' 2>&1 | tr -d '\r')" >> "$OUT/guest-capture.txt"
    adb shell 'pkill -INT tcpdump || killall -INT tcpdump' >/dev/null 2>&1 || true
    sleep 3
    for iface in $(cat "$OUT/guest-interfaces.txt"); do
        adb pull "/data/local/tmp/pf-$iface.pcap" "$OUT/guest-$iface.pcap" >/dev/null 2>&1 || true
    done
fi
adb shell rm -f /data/local/tmp/proxyflow-e2e.properties >/dev/null 2>&1 || true
exit "$STATUS"
