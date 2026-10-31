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
adb shell svc wifi disable >/dev/null 2>&1 || true
sleep 8
adb shell ip route > "$OUT/guest-routes.txt" 2>&1 || true

# tcpdump on each uplink interface (not the VPN's tun0), as root, detached so it outlives
# adbd going back to the shell user: the tests must run as shell, which the VPN covers.
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
else
    echo "no root or no tcpdump in this image: the emulator's own capture (-tcpdump) is the only one" >> "$OUT/guest-capture.txt"
fi
restart_adbd unroot || true

adb logcat -G 16M >/dev/null 2>&1 || true
adb logcat -c || true

./gradlew :core:data:connectedDebugAndroidTest :core:engine:connectedDebugAndroidTest :app:connectedDebugAndroidTest \
    --continue 2>&1 | tee "$RUNNER_TEMP/gradle-2-device.log"
STATUS=${PIPESTATUS[0]}

adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
if [ "$GUEST_CAPTURE" = 1 ]; then
    restart_adbd root || true
    adb shell 'pkill -INT tcpdump || killall -INT tcpdump' >/dev/null 2>&1 || true
    sleep 3
    for iface in $(cat "$OUT/guest-interfaces.txt"); do
        adb pull "/data/local/tmp/pf-$iface.pcap" "$OUT/guest-$iface.pcap" >/dev/null 2>&1 || true
    done
fi
adb shell rm -f /data/local/tmp/proxyflow-e2e.properties >/dev/null 2>&1 || true
exit "$STATUS"
