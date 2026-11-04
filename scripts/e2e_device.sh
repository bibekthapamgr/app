#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner (one line in the workflow, as
# the action runs each script line on its own): every module's device tests, with
# the end-to-end lab's setup (scripts/e2e_env.sh) and its CA certificate pushed to the
# emulator and the phone's uplink captured from inside it when the image has tcpdump.
# Afterwards scripts/m5_evidence.py and scripts/m6_evidence.py read what this leaves in
# $RUNNER_TEMP/m5-api<level>.
#
# Usage: e2e_device.sh <api level>
set -uo pipefail

API="$1"
OUT="$RUNNER_TEMP/m5-api$API"
mkdir -p "$OUT"

# The tests read the lab's addresses and the run's passwords from here (as the shell user), and the lab's CA
# certificate, which the TLS tests trust only where a test says so (ADR-0011).
adb push /tmp/pf/e2e.properties /data/local/tmp/proxyflow-e2e.properties >/dev/null
adb shell chmod 644 /data/local/tmp/proxyflow-e2e.properties
adb push /tmp/pf/pki/ca.crt /data/local/tmp/proxyflow-ca.pem >/dev/null
adb shell chmod 644 /data/local/tmp/proxyflow-ca.pem

# Every adb call that could wait for a wedged device is bounded, so the logs below are always collected.
restart_adbd() {
    timeout 30 adb "$1" >/dev/null 2>&1 || return 1
    # adbd restarts as the new user, and the old connection can still look online for a moment (run 41278339766,
    # attempt 3, API 35: "device offline" when the script then asked whether tcpdump still ran): wait until a command
    # really runs as that user.
    local want=0
    if [ "$1" = unroot ]; then want=2000; fi
    for _ in $(seq 1 60); do
        sleep 2
        timeout 10 adb wait-for-device >/dev/null 2>&1 || continue
        if [ "$(timeout 10 adb shell id -u 2>/dev/null | tr -d '\r')" = "$want" ]; then return 0; fi
    done
    return 1
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
# One uplink for the whole run, and the other one off: the app follows no network change until M7.
# On API 35 mobile data sometimes comes up late or never, and Wi-Fi then switches itself back on
# (runs 41219399550, 41233261642, 41234640020: routes lost in the middle of the tests). So mobile
# data is waited for first; once it is up, Wi-Fi is switched off (its automatic re-enabling and
# scanning off too) until it stays off. If mobile data never comes up, Wi-Fi is the uplink and
# mobile data is switched off, so it cannot appear in the middle of the tests.
addresses() {
    timeout 30 adb shell 'ip -o -4 addr show' 2>&1 | tr -d '\r'
}
cellular_up() {
    addresses | grep -Eq '^[0-9]+: (eth[0-9]+|radio[0-9]+|rmnet[0-9]+) +inet '
}
wifi_off() {
    timeout 30 adb shell settings put global wifi_wakeup_enabled 0 >/dev/null 2>&1 || true
    timeout 30 adb shell settings put global wifi_scan_always_enabled 0 >/dev/null 2>&1 || true
    for _ in 1 2 3; do
        timeout 30 adb shell svc wifi disable >/dev/null 2>&1 || true
        for _ in $(seq 1 15); do
            if wifi_down; then break; fi
            sleep 1
        done
        sleep 10
        if wifi_down; then return 0; fi
    done
    return 1
}
wifi_only() {
    timeout 30 adb shell svc data disable >/dev/null 2>&1 || true
    timeout 30 adb shell svc wifi enable >/dev/null 2>&1 || true
}
# Airplane mode turns the radio off, so mobile data cannot come up by itself: on API 35 it did, switched off,
# in the middle of the tests (run 41243886217), and a proxy handshake was reset. Wi-Fi goes back on inside it.
wifi_in_airplane_mode() {
    timeout 30 adb shell cmd connectivity airplane-mode enable >/dev/null 2>&1 ||
        timeout 30 adb shell settings put global airplane_mode_on 1 >/dev/null 2>&1 || true
    sleep 5
    timeout 30 adb shell svc wifi enable >/dev/null 2>&1 || true
}
# What the phone uses, read from its interfaces' addresses.
uplink_name() {
    local cell=0 wifi=0
    if cellular_up; then cell=1; fi
    if ! wifi_down; then wifi=1; fi
    case "$cell$wifi" in
        10) echo "mobile data" ;;
        01) echo "Wi-Fi" ;;
        11) echo "mobile data and Wi-Fi, both up" ;;
        *) echo "no interface with an address" ;;
    esac
}
choose_uplink() {
    UPLINK=""
    # The API 35 image's mobile data came up late or never, or dropped or appeared during the tests, in five of
    # its last nine runs, while Wi-Fi alone held: there Wi-Fi is the uplink, in airplane mode.
    if [ "$API" = 35 ]; then
        wifi_in_airplane_mode
        if uplink_within 60; then
            UPLINK="$(uplink_name) (airplane mode, so the radio is off: mobile data is unreliable on this image)"
        else
            timeout 30 adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
            timeout 30 adb shell svc data enable >/dev/null 2>&1 || true
            wifi_off || true
            if uplink_within 90; then UPLINK="$(uplink_name) (Wi-Fi did not reach the runner)"; fi
        fi
    else
        for _ in $(seq 1 45); do
            if cellular_up; then break; fi
            sleep 2
        done
    fi
    if [ -n "$UPLINK" ] || [ "$API" = 35 ]; then
        : # chosen above
    elif cellular_up; then
        NOTE=""
        if ! wifi_off; then NOTE=", Wi-Fi would not stay off"; fi
        if uplink_within 60; then
            UPLINK="$(uplink_name)$NOTE"
        else
            timeout 30 adb shell svc data disable >/dev/null 2>&1 || true
            sleep 3
            timeout 30 adb shell svc data enable >/dev/null 2>&1 || true
            if uplink_within 60; then
                UPLINK="$(uplink_name)$NOTE (after switching mobile data off and on)"
            else
                wifi_only
                if uplink_within 60; then UPLINK="$(uplink_name) (mobile data did not reach the runner)"; fi
            fi
        fi
    else
        wifi_only
        if uplink_within 60; then UPLINK="$(uplink_name) (mobile data never came up, so it is off)"; fi
    fi
}
START=$SECONDS
choose_uplink
if [ -z "$UPLINK" ]; then
    # A boot that brought up no network at all (API 35, runs 41219399550 and 41238841652: no interface had an
    # address, Wi-Fi or mobile data): the emulator boots once more.
    timeout 60 adb reboot >/dev/null 2>&1 || true
    timeout 300 adb wait-for-device || true
    for _ in $(seq 1 120); do
        if [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then break; fi
        sleep 2
    done
    sleep 10
    choose_uplink
    if [ -n "$UPLINK" ]; then UPLINK="$UPLINK, after a second boot (the first brought up no network)"; fi
fi
{
    echo "uplink: ${UPLINK:-none: the phone never reached the runner} ($((SECONDS - START)) s)"
    echo "routes: $(adb shell ip route 2>&1 | tr -d '\r' | paste -sd ';' -)"
    echo "addresses: $(adb shell ip -o -4 addr show 2>&1 | tr -d '\r' | awk '{print $2, $4}' | paste -sd ';' -)"
    # IPv6 on the uplink: whether a direct IPv6 packet could leave the phone at all (the captures would show it).
    echo "IPv6 addresses: $(adb shell ip -o -6 addr show 2>&1 | tr -d '\r' | awk '{print $2, $4}' | paste -sd ';' -)"
    V6_ROUTES=$(adb shell ip -6 route show table all 2>&1 | tr -d '\r' | grep '^default' | paste -sd ';' -)
    echo "IPv6 default routes: ${V6_ROUTES:-none}"
} > "$OUT/guest-network.txt"
if [ -z "$UPLINK" ]; then
    echo "::error title=Emulator network (API $API)::The phone never reached the runner (10.0.2.2), over mobile data or Wi-Fi, so no connection to the proxies can work. $(paste -sd ' ' "$OUT/guest-network.txt")"
fi

# tcpdump on each uplink interface (not the VPN's tun0), as root, moved out of adbd's process
# group (init kills that group's cgroup when adbd restarts as the shell user, which the tests
# need: the VPN covers the shell, not root). The evidence counts these captures only if
# tcpdump is still running after the tests.
# Not on API 29: adbd does not come back after adb root and unroot there (run 41230555282: the device stayed
# offline, so no test ran), and its uplink, mobile data or Wi-Fi, all passes the emulator's own capture.
# The interfaces the phone's running tcpdump processes capture, from their command lines ("none" when none runs).
tcpdump_interfaces() {
    local found
    found=$(timeout 30 adb shell 'for p in $(pidof tcpdump); do tr "\0" " " < /proc/$p/cmdline; echo; done' 2>/dev/null |
        tr -d '\r' | sed -n 's/.*tcpdump -i \([a-z0-9]*\) .*/\1/p' | sort -u | paste -sd ' ' -)
    echo "${found:-none}"
}
GUEST_CAPTURE=0
if [ "$API" -lt 34 ]; then
    echo "not on API $API: adbd does not survive adb root and unroot on this image; the emulator's own capture (-tcpdump) sees the whole uplink" > "$OUT/guest-capture.txt"
else
    {
        echo "adb root: $(timeout 30 adb root 2>&1 | tr -d '\r')"
        sleep 3
        timeout 120 adb wait-for-device
        echo "id: $(adb shell id 2>&1 | tr -d '\r')"
        echo "tcpdump: $(adb shell 'command -v tcpdump || echo none' 2>&1 | tr -d '\r')"
    } > "$OUT/guest-capture.txt" 2>&1
    if grep -q 'uid=0' "$OUT/guest-capture.txt" && ! grep -q 'tcpdump: none' "$OUT/guest-capture.txt"; then
        adb shell ip -o link show > "$OUT/guest-links.txt" 2>&1
        IFACES="$(awk -F': ' '{print $2}' "$OUT/guest-links.txt" | sed 's/@.*//' | tr -d '\r' | grep -E '^(eth|wlan|radio|rmnet)[0-9]+$' || true)"
        for iface in $IFACES; do
            # The first 400 bytes of each packet (IP, TCP, DNS queries and answers in full, long names included): over
            # Wi-Fi, Google Play's updates run through the VPN too, and a full capture grew too large to pull in time
            # (run 41237942120, API 35).
            adb shell "setsid tcpdump -i $iface -s 400 -U -w /data/local/tmp/pf-$iface.pcap </dev/null >/dev/null 2>&1 &"
        done
        echo "$IFACES" > "$OUT/guest-interfaces.txt"
        GUEST_CAPTURE=1
        sleep 2
        # The root cgroup: /sys/fs/cgroup (cgroup v2) or /acct (v1).
        adb shell 'for p in $(pidof tcpdump); do for c in /sys/fs/cgroup/cgroup.procs /acct/cgroup.procs; do [ -w "$c" ] && echo "$p" > "$c"; done; done' >/dev/null 2>&1 || true
        echo "tcpdump on: $(paste -sd ' ' "$OUT/guest-interfaces.txt")" >> "$OUT/guest-capture.txt"
        echo "tcpdump at the start: $(adb shell 'pidof tcpdump || echo none' 2>&1 | tr -d '\r')" >> "$OUT/guest-capture.txt"
        # Which interfaces those processes capture: one surviving process says nothing about the others.
        echo "tcpdump at the start on: $(tcpdump_interfaces)" >> "$OUT/guest-capture.txt"
    else
        echo "no root or no tcpdump in this image: the emulator's own capture (-tcpdump) is the only one" >> "$OUT/guest-capture.txt"
    fi
    restart_adbd unroot || true
fi

timeout 30 adb logcat -G 16M >/dev/null 2>&1 || true
timeout 30 adb logcat -c || true
# Logcat streamed to the runner as the tests run: it survives a device or a step that hangs later.
adb logcat -v threadtime -v UTC > "$OUT/logcat-live.txt" 2>&1 & # UTC, like the captures: the evidence lines them up
LIVE_LOGCAT=$!
# About four times a second, the phone's sockets with their owner's uid, from the kernel's own tables: every TCP socket
# held open (not listening, not TIME-WAIT, which keeps no owner) and every connected UDP socket, so the evidence can name
# who sent a connection, datagram or lookup it finds on the uplink while connected; and every few seconds the phone's
# addresses (a temporary IPv6 address can appear during the tests). The shell's EPOCHREALTIME gives sub-second times.
cat > "$RUNNER_TEMP/pf-connecting.sh" <<'SAMPLER'
n=0
while true; do
    echo "T ${EPOCHREALTIME:-$(date +%s)}"
    for f in tcp tcp6; do grep -v -e ' 0A ' -e ' 06 ' /proc/net/$f 2>/dev/null | sed "s/^/$f /"; done
    for f in udp udp6; do grep ' 01 ' /proc/net/$f 2>/dev/null | sed "s/^/$f /"; done
    if [ $((n % 20)) = 0 ]; then ip -o addr show 2>/dev/null | sed 's/^/A /'; fi
    n=$((n + 1))
    sleep 0.25
done
SAMPLER
timeout 30 adb push "$RUNNER_TEMP/pf-connecting.sh" /data/local/tmp/pf-connecting.sh >/dev/null 2>&1 || true
adb shell "setsid sh /data/local/tmp/pf-connecting.sh > /data/local/tmp/pf-connecting.txt 2>/dev/null </dev/null &" || true

# Bounded well inside the job's own limit, so that logcat, the captures and the evidence are still
# collected when a test hangs (scripts/m5_evidence.py reads how far the tests got from logcat).
# Into a file, not through tee: a Gradle daemon that outlives a stopped build would hold a pipe open.
GRADLE_LOG="$RUNNER_TEMP/gradle-2-device.log"
timeout --kill-after=60s "${DEVICE_TESTS_TIMEOUT:-55m}" \
    ./gradlew :core:data:connectedDebugAndroidTest :core:engine:connectedDebugAndroidTest :app:connectedDebugAndroidTest \
    --continue > "$GRADLE_LOG" 2>&1
STATUS=$?
cat "$GRADLE_LOG"
if [ "$STATUS" = 124 ] || [ "$STATUS" = 137 ]; then
    echo "::error title=Device tests (API $API)::The device tests ran past ${DEVICE_TESTS_TIMEOUT:-55m} and were stopped; the evidence lists from logcat which tests ran, failed or never finished."
    timeout 60 ./gradlew --stop >/dev/null 2>&1 || true # its daemon may still be running them
fi

timeout 120 adb logcat -d -v threadtime -v UTC > "$OUT/logcat.txt" 2>&1 || true
kill "$LIVE_LOGCAT" 2>/dev/null || true
timeout 30 adb shell 'pkill -f pf-connecting.sh' >/dev/null 2>&1 || true
timeout 120 adb pull /data/local/tmp/pf-connecting.txt "$OUT/phone-connecting.txt" >/dev/null 2>&1 || echo "no socket samples" > "$OUT/phone-connecting.txt"
timeout 60 adb shell pm list packages -U 2>/dev/null | tr -d '\r' > "$OUT/packages-uids.txt" || true
# Which apps Android lets leave a VPN (netd's system permission): each package's and shared user's uid and the network
# permissions granted to it. Gradle has removed ProxyFlow's packages by now; its tests log its uid.
timeout 120 adb shell dumpsys package 2>/dev/null | tr -d '\r' |
    grep -E '(Package|SharedUser) \[|(userId|appId)=[0-9]|android\.permission\.[A-Z_]*(NETWORK|CONNECTIVITY)[A-Z_]*: granted=true' \
    > "$OUT/packages-network-permissions.txt" || true
# The phone's network after the tests: the same uplink as before them, or what changed.
{
    echo "after the tests: $(uplink_name)"
    echo "addresses after the tests: $(timeout 30 adb shell ip -o -4 addr show 2>&1 | tr -d '\r' | awk '{print $2, $4}' | paste -sd ';' -)"
    echo "IPv6 addresses after the tests: $(timeout 30 adb shell ip -o -6 addr show 2>&1 | tr -d '\r' | awk '{print $2, $4}' | paste -sd ';' -)"
} >> "$OUT/guest-network.txt"
if [ "$GUEST_CAPTURE" = 1 ]; then
    restart_adbd root || true
    echo "tcpdump at the end: $(timeout 30 adb shell 'pidof tcpdump || echo none' 2>&1 | tr -d '\r')" >> "$OUT/guest-capture.txt"
    echo "tcpdump at the end on: $(tcpdump_interfaces)" >> "$OUT/guest-capture.txt"
    timeout 30 adb shell 'pkill -INT tcpdump || killall -INT tcpdump' >/dev/null 2>&1 || true
    sleep 3
    echo "captured on the phone: $(timeout 30 adb shell 'ls -l /data/local/tmp/pf-*.pcap' 2>&1 | tr -d '\r' | awk '{print $NF, $5}' | paste -sd ';' -)" >> "$OUT/guest-capture.txt"
    for iface in $(cat "$OUT/guest-interfaces.txt"); do
        timeout 300 adb pull "/data/local/tmp/pf-$iface.pcap" "$OUT/guest-$iface.pcap" >/dev/null 2>&1 ||
            echo "pull of pf-$iface.pcap failed or timed out" >> "$OUT/guest-capture.txt"
    done
fi
timeout 30 adb shell rm -f /data/local/tmp/proxyflow-e2e.properties /data/local/tmp/proxyflow-ca.pem /data/local/tmp/pf-client.apk \
    /data/local/tmp/pf-connecting.sh /data/local/tmp/pf-connecting.txt >/dev/null 2>&1 || true
exit "$STATUS"
