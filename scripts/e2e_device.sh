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
# shellcheck source=scripts/adb_reboot.sh
. "$(dirname "$0")/adb_reboot.sh"

# What this script may take in all: the emulator step's limit (android.yml, 130 minutes) less the emulator's setup and
# boot before it (up to 20) and 2 for the action to stop the emulator. Each part after the device tests is bounded by what
# is left, keeping enough for the parts after it, so a slow run ends its own parts and cleans up — Always-on VPN off,
# the device owner given up — before the step's limit stops it in the middle of one.
SCRIPT_BUDGET_S="${E2E_SCRIPT_BUDGET_S:-$((108 * 60))}"
left_s() { echo $((SCRIPT_BUDGET_S - SECONDS)); }
# The time limit, in seconds, of a part that may take up to <max>, leaving <reserve> for what comes after it; 0 when that
# leaves it less than <min>.
part_limit() { # <max> <reserve> <min>
    local limit=$(($(left_s) - $2))
    if [ "$limit" -gt "$1" ]; then limit=$1; fi
    if [ "$limit" -lt "$3" ]; then limit=0; fi
    echo "$limit"
}

# The tests read the lab's addresses and the run's passwords from here (as the shell user), and the lab's CA
# certificate, which the TLS tests trust only where a test says so (ADR-0011).
timeout 60 adb push /tmp/pf/e2e.properties /data/local/tmp/proxyflow-e2e.properties >/dev/null
timeout 30 adb shell chmod 644 /data/local/tmp/proxyflow-e2e.properties
timeout 60 adb push /tmp/pf/pki/ca.crt /data/local/tmp/proxyflow-ca.pem >/dev/null
timeout 30 adb shell chmod 644 /data/local/tmp/proxyflow-ca.pem

# Every adb call that could wait for a wedged device is bounded, so the logs below are always collected (M10-116: two
# were not, so a wedged phone held API 29's job past the tests' own limit).
restart_adbd() {
    # adbd restarts as the new user, and the old connection can still look online for a moment (run 41278339766,
    # attempt 3, API 35: "device offline" when the script then asked whether tcpdump still ran): wait until a command
    # really runs as that user. adb itself can lose the device across the restart (run 41690836372, API 35: "adb root"
    # failed and the device stayed offline, so the phone's captures were never pulled): then it connects again and
    # tries once more, up to three times — all within 4 minutes (a call starts only if it can end in time), so the
    # step's own time limit is never spent here.
    local want=0 deadline=$((SECONDS + 240)) _try _
    if [ "$1" = unroot ]; then want=2000; fi
    for _try in 1 2 3; do
        [ $((deadline - SECONDS)) -ge 30 ] || break
        if timeout 30 adb "$1" >/dev/null 2>&1; then
            for _ in $(seq 1 30); do
                [ $((deadline - SECONDS)) -ge 22 ] || break
                sleep 2
                timeout 10 adb wait-for-device >/dev/null 2>&1 || continue
                if [ "$(timeout 10 adb shell id -u 2>/dev/null | tr -d '\r')" = "$want" ]; then return 0; fi
            done
        fi
        [ $((deadline - SECONDS)) -ge 80 ] || break
        timeout 20 adb reconnect >/dev/null 2>&1 || true
        timeout 60 adb wait-for-device >/dev/null 2>&1 || true
    done
    return 1
}

# The phone's uplink becomes its mobile data, which runs through the emulator's main network
# device on every API level, so the emulator's -tcpdump, which records that device alone, sees it.
# From API 30 on, Wi-Fi (virtio Wi-Fi) is a network device of its own, which that capture does not
# see (M10-89: the emulator's source, and API 34 and 35's runs on Wi-Fi); on API 29 the phone's
# Wi-Fi sits behind the same device as its mobile data (radio0).
#
# The tests need that uplink to reach the runner (10.0.2.2, the emulator's name for the runner's
# loopback). The runner's closed port refuses a connection and the refusal comes back through the
# emulator: that proves the route without touching the proxies or the destination. Without an
# uplink the phone answers "Network is unreachable" itself. Mobile data did not come up on one
# API 35 emulator (run 41219399550: one packet on its uplink all run, every connection "no route
# to the proxy"), so it is switched off and on once, and then Wi-Fi is the fallback (from API 30
# on only tcpdump inside the phone sees it, which runs from API 34 on, and on API 33 when it can be
# run as root through su: elsewhere the evidence then fails, for want of a capture of the uplink;
# it says which captures cover it).
reaches_runner() {
    local out
    out="$(timeout 15 adb shell 'toybox nc -w 3 10.0.2.2 1082 </dev/null; true' 2>&1 | tr -d '\r')"
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
    out="$(timeout 15 adb shell 'ip -o -4 addr show wlan0 2>/dev/null; true' 2>&1)"
    case "$out" in *inet*) return 1 ;; esac
    return 0
}
# One uplink for the whole run, and the other one off. Since M7 the app follows network changes: losing the
# uplink makes it wait Offline, and a move to another network resets every connection — in the middle of a test,
# either fails it. The session's own reports of the network ("network: ...") are in the M7 evidence.
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
    # Mobile data off too, and gone before Wi-Fi is checked: on API 33 airplane mode left it up, and the runner was
    # reached through it (run 41919093108: "mobile data (airplane mode …)", both up after the tests; M10-129).
    timeout 30 adb shell svc data disable >/dev/null 2>&1 || true
    for _ in $(seq 1 10); do
        if ! cellular_up; then break; fi
        sleep 2
    done
    sleep 3
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
    # its last nine runs, and the API 34 image's in five of its last six (M7: it dropped during the tests in four,
    # failing tests twice, and never came up in one), while Wi-Fi alone held on both: there Wi-Fi is the uplink, in
    # airplane mode, and tcpdump inside the phone captures it. The API 33 image's mobile data dropped during the
    # tests in three of the eleven M10 runs it had on it (41822664621, 41856359695, 41896912102: nine retries, seven
    # tests failed "no route to the proxy"; M10-88, M10-113), and its Wi-Fi is not in the emulator's capture (M10-89):
    # there Wi-Fi is the uplink too, if tcpdump can be run inside the phone as root, through su (ROOT_TCPDUMP, found
    # before this runs), adbd left as the shell user; otherwise mobile data, which the emulator's capture records.
    if [ "$API" = 34 ] || [ "$API" = 35 ] || { [ "$API" = 33 ] && [ -n "$ROOT_TCPDUMP" ]; }; then
        wifi_in_airplane_mode
        if uplink_within 60; then
            UPLINK="$(uplink_name) (airplane mode, so the radio is off: mobile data is unreliable on this image)"
        elif {
            # Wi-Fi once more before mobile data, which then dropped during the tests (run 41808719551, API 35: Wi-Fi
            # had not reached the runner after 60 s, mobile data had, and two tests failed "Network is unreachable").
            timeout 30 adb shell svc wifi disable >/dev/null 2>&1 || true
            sleep 5
            timeout 30 adb shell svc wifi enable >/dev/null 2>&1 || true
            uplink_within 90
        }; then
            UPLINK="$(uplink_name) (airplane mode, after switching Wi-Fi off and on: mobile data is unreliable on this image)"
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
    if [ -n "$UPLINK" ] || [ "$API" = 34 ] || [ "$API" = 35 ] || { [ "$API" = 33 ] && [ -n "$ROOT_TCPDUMP" ]; }; then
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
# On API 33: tcpdump as root through su, by name or where Android's images keep it (M10-120: the shell user does not
# see it). Set before the uplink is chosen, which goes by it; empty when there is none, or no su.
ROOT_TCPDUMP=""
if [ "$API" = 33 ]; then
    # Asked twice should adb not answer (an answer ends in "end"), and the binary found is run (--version), so a
    # moment's adb hiccup, or a file that is there but does not run, is not taken for no tcpdump (M10-132).
    for _ in 1 2; do
        found="$(timeout 30 adb shell "su 0 sh -c 'f=\$(command -v tcpdump || ls /system/bin/tcpdump /system/xbin/tcpdump 2>/dev/null | head -1); [ -n \"\$f\" ] && \"\$f\" --version >/dev/null 2>&1 && echo \"\$f\"; echo end'" 2>/dev/null | tr -d '\r')"
        case "$found" in *end*)
            ROOT_TCPDUMP="$(grep -m1 '^/' <<<"$found" || true)"
            break
            ;;
        esac
        sleep 5
    done
fi
START=$SECONDS
choose_uplink
if [ -z "$UPLINK" ]; then
    # A boot that brought up no network at all (API 35, runs 41219399550 and 41238841652: no interface had an
    # address, Wi-Fi or mobile data): the emulator boots once more, and the new boot is waited for (adb_reboot.sh).
    reboot_and_wait 540 || echo "::warning title=Emulator network (API $API)::The second boot did not complete within 9 minutes."
    sleep 10
    choose_uplink
    if [ -n "$UPLINK" ]; then UPLINK="$UPLINK, after a second boot (the first brought up no network)"; fi
fi
{
    echo "uplink: ${UPLINK:-none: the phone never reached the runner} ($((SECONDS - START)) s)"
    echo "routes: $(timeout 30 adb shell ip route 2>&1 | tr -d '\r' | paste -sd ';' -)"
    echo "addresses: $(timeout 30 adb shell ip -o -4 addr show 2>&1 | tr -d '\r' | awk '{print $2, $4}' | paste -sd ';' -)"
    # IPv6 on the uplink: whether a direct IPv6 packet could leave the phone at all (the captures would show it).
    echo "IPv6 addresses: $(timeout 30 adb shell ip -o -6 addr show 2>&1 | tr -d '\r' | awk '{print $2, $4}' | paste -sd ';' -)"
    V6_ROUTES=$(timeout 30 adb shell ip -6 route show table all 2>&1 | tr -d '\r' | grep '^default' | paste -sd ';' -)
    echo "IPv6 default routes: ${V6_ROUTES:-none}"
    # Which build of the image ran: a new revision of an image can change what its adbd or network does (M10-121).
    echo "image: $(timeout 30 adb shell getprop ro.build.fingerprint 2>&1 | tr -d '\r'); system image revision $(sed -n 's/^Pkg.Revision=//p' "${ANDROID_HOME:-/usr/local/lib/android/sdk}/system-images/android-$API/google_apis/x86_64/source.properties" 2>/dev/null)"
} > "$OUT/guest-network.txt"
if [ -z "$UPLINK" ]; then
    echo "::error title=Emulator network (API $API)::The phone never reached the runner (10.0.2.2), over mobile data or Wi-Fi, so no connection to the proxies can work. $(paste -sd ' ' "$OUT/guest-network.txt")"
fi

# tcpdump on each uplink interface (not the VPN's tun0), as root, moved out of adbd's process
# group (init kills that group's cgroup when adbd restarts as the shell user, which the tests
# need: the VPN covers the shell, not root). The evidence counts these captures only if
# tcpdump is still running after the tests.
# Not on API 29: adbd does not come back after adb root and unroot (run 41230555282: the device stayed offline, so no
# test ran). On API 33, when tcpdump can be run as root through su (ROOT_TCPDUMP), its uplink is Wi-Fi, which the
# emulator's own capture does not record (M10-89), and tcpdump is started through su, adbd left as the shell user
# throughout (M10-113); otherwise, and on API 30 to 32, the uplink is mobile data, which the emulator's own capture
# records, and none runs in the phone.
# How a command runs as root on the phone: "" once adbd is root (API 34 on), "su 0 " on API 33.
AS_ROOT=""
# The interfaces the phone's running tcpdump processes capture, from their command lines ("none" when none runs).
tcpdump_interfaces() {
    local found="" pid
    for pid in $(timeout 30 adb shell "${AS_ROOT}pidof tcpdump" 2>/dev/null | tr -d '\r'); do
        found="$found $(timeout 10 adb shell "${AS_ROOT}cat /proc/$pid/cmdline" 2>/dev/null | tr '\0' ' ' |
            sed -n 's/.*tcpdump -i \([a-z0-9]*\) .*/\1/p')"
    done
    found="$(tr ' ' '\n' <<<"$found" | grep -v '^$' | sort -u | paste -sd ' ' -)"
    echo "${found:-none}"
}
# GUEST_CAPTURE: 0 none in the phone; 1 through adb root (API 34 on); 2 through su (API 33).
GUEST_CAPTURE=0
# The first 400 bytes of each packet (IP, TCP, DNS queries and answers in full, long names included): over Wi-Fi, Google
# Play's updates run through the VPN too, and a full capture grew too large to pull in time (run 41237942120, API 35).
start_tcpdump() { # <interface>
    timeout 30 adb shell "${AS_ROOT}setsid ${ROOT_TCPDUMP:-tcpdump} -i $1 -s 400 -U -w /data/local/tmp/pf-$1.pcap </dev/null >/dev/null 2>&1 &"
}
uplink_interfaces() {
    # As root: as the shell user, API 33's image listed none (run 41919093108: "tcpdump on:" empty; M10-129).
    timeout 30 adb shell "${AS_ROOT}ip -o link show" > "$OUT/guest-links.txt" 2>&1
    awk -F': ' '{print $2}' "$OUT/guest-links.txt" | sed 's/@.*//' | tr -d '\r' | grep -E '^(eth|wlan|radio|rmnet)[0-9]+$' || true
}
if [ "$API" = 33 ] && [ -n "$ROOT_TCPDUMP" ]; then
    AS_ROOT="su 0 "
    {
        echo "su: $(timeout 30 adb shell su 0 id 2>&1 | tr -d '\r')"
        # Looked up as root: as the shell user, API 33's image does not show it (run 41911592584: "none"; M10-120).
        echo "tcpdump: $ROOT_TCPDUMP (as root, through su)"
    } > "$OUT/guest-capture.txt" 2>&1
    if grep -q 'uid=0' "$OUT/guest-capture.txt"; then
        IFACES="$(uplink_interfaces)"
        if [ -z "$IFACES" ]; then
            echo "no uplink interface listed: $(tr -d '\r' < "$OUT/guest-links.txt" | head -3 | paste -sd ' ' - | cut -c1-200)" >> "$OUT/guest-capture.txt"
        fi
        for iface in $IFACES; do start_tcpdump "$iface"; done
        echo "$IFACES" > "$OUT/guest-interfaces.txt"
        GUEST_CAPTURE=2
        sleep 2
        {
            echo "tcpdump on: $(paste -sd ' ' "$OUT/guest-interfaces.txt")"
            echo "tcpdump at the start: $(timeout 30 adb shell "${AS_ROOT}pidof tcpdump || echo none" 2>&1 | tr -d '\r')"
            echo "tcpdump at the start on: $(tcpdump_interfaces)"
        } >> "$OUT/guest-capture.txt"
    else
        echo "no su or no tcpdump in this image: the emulator's own capture (-tcpdump), which does not record Wi-Fi, is the only one" >> "$OUT/guest-capture.txt"
    fi
elif [ "$API" -lt 34 ]; then
    # What the emulator's own capture records of the uplink chosen above (M10-103, M10-107): mobile data always; Wi-Fi
    # on API 29 only, where it sits behind the same device (radio0); with both up, Android prefers Wi-Fi.
    case "$UPLINK" in
        "") SEEN="no uplink reached the runner" ;;
        "mobile data and Wi-Fi"*)
            if [ "$API" = 29 ]; then
                SEEN="its uplink is ${UPLINK}, both behind the device the emulator's own capture (-tcpdump) records"
            else
                SEEN="its uplink is ${UPLINK}: Android prefers Wi-Fi, which from API 30 on the emulator's own capture (-tcpdump) does not record"
            fi
            ;;
        "mobile data"*) SEEN="its uplink is mobile data, which the emulator's own capture (-tcpdump) records" ;;
        *)
            if [ "$API" = 29 ]; then
                SEEN="its uplink is ${UPLINK}, behind the device the emulator's own capture (-tcpdump) records on API 29 (radio0)"
            else
                SEEN="its uplink is ${UPLINK}, which from API 30 on the emulator's own capture (-tcpdump) does not record: it records mobile data's device alone"
            fi
            ;;
    esac
    case "$API" in
        29) WHY="adbd did not survive adb root and unroot on API 29" ;;
        33) WHY="no tcpdump could be run as root through su, so mobile data was chosen as the uplink (M10-120)" ;;
        *) WHY="on API 30 to 32 none runs in the phone" ;;
    esac
    echo "not on API $API: $SEEN ($WHY)" > "$OUT/guest-capture.txt"
else
    {
        echo "adb root: $(timeout 30 adb root 2>&1 | tr -d '\r')"
        sleep 3
        timeout 120 adb wait-for-device
        echo "id: $(timeout 30 adb shell id 2>&1 | tr -d '\r')"
        echo "tcpdump: $(timeout 30 adb shell 'command -v tcpdump || echo none' 2>&1 | tr -d '\r')"
    } > "$OUT/guest-capture.txt" 2>&1
    if grep -q 'uid=0' "$OUT/guest-capture.txt" && ! grep -q 'tcpdump: none' "$OUT/guest-capture.txt"; then
        IFACES="$(uplink_interfaces)"
        for iface in $IFACES; do start_tcpdump "$iface"; done
        echo "$IFACES" > "$OUT/guest-interfaces.txt"
        GUEST_CAPTURE=1
        sleep 2
        # The root cgroup: /sys/fs/cgroup (cgroup v2) or /acct (v1).
        timeout 30 adb shell 'for p in $(pidof tcpdump); do for c in /sys/fs/cgroup/cgroup.procs /acct/cgroup.procs; do [ -w "$c" ] && echo "$p" > "$c"; done; done' >/dev/null 2>&1 || true
        echo "tcpdump on: $(paste -sd ' ' "$OUT/guest-interfaces.txt")" >> "$OUT/guest-capture.txt"
        echo "tcpdump at the start: $(timeout 30 adb shell 'pidof tcpdump || echo none' 2>&1 | tr -d '\r')" >> "$OUT/guest-capture.txt"
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
timeout 30 adb shell "setsid sh /data/local/tmp/pf-connecting.sh > /data/local/tmp/pf-connecting.txt 2>/dev/null </dev/null &" || true

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

# M8: failure injection (process death, Android's Always-on VPN with Block connections without VPN), after the tests
# and their logcat, so the M5 to M7 evidence reads the tests alone; it has its own logcat (logcat-m8.txt). The captures
# and the socket samples go on through it. Bounded, so the captures and samples pulled below are never lost to the
# step's own time limit, and not run once the tests themselves ran out of time (m8_evidence.py then finds no steps).
# What the parts after M8 need at least: M10's N (5 min with its kill), the pulls (about 10), M10's R and P (9, and 2 for
# their kill) and the end (1).
AFTER_M8_S=$((27 * 60))
M8_LIMIT_S="$(part_limit "${M8_TIMEOUT_S:-600}" $((AFTER_M8_S + 8 * 60)) 300)"
if [ "$STATUS" = 124 ] || [ "$STATUS" = 137 ]; then
    echo "::error title=M8 failure injection (API $API)::Not run: the device tests ran out of time."
elif [ "$M8_LIMIT_S" = 0 ]; then
    echo "::error title=M8 failure injection (API $API)::Not run: $(($(left_s) / 60)) minutes left of this script's ${SCRIPT_BUDGET_S} s, too few for it and the parts after it."
    STATUS=1
else
    # Stopped at its limit, it cleans up first (Always-on VPN off, the device owner given up): bash runs its trap once
    # the adb step it waits for ends — each has its own limit, the longest 150 s — and the clean-up's own adb calls take
    # up to about 4 minutes with adb wedged, so the kill comes 8 minutes later.
    timeout --kill-after=8m "${M8_LIMIT_S}s" bash scripts/m8_failure_injection.sh "$API" "$OUT"
    M8_STATUS=$?
    if [ "$M8_STATUS" = 124 ] || [ "$M8_STATUS" = 137 ] || [ "$M8_STATUS" = 143 ]; then
        echo "::error title=M8 failure injection (API $API)::Stopped after ${M8_LIMIT_S} s: the steps it took are in m8-steps.txt."
    fi
    if [ "$M8_STATUS" != 0 ] && [ "$STATUS" = 0 ]; then STATUS=$M8_STATUS; fi
fi
# M10's N: Android 13 and later without the notification permission (scripts/m10_scenarios.sh notifications), while the
# phone's captures still run, so the checks over whole captures cover it (no packet to or from a lab address, no lookup
# of a lab name) — not M6's account of each connection while connected, which reads the device tests' logcat and uid,
# where N's ProxyFlow is a new install; adbd is the shell user's here (the VPN covers the shell, not root), which the
# script checks. Not run once the tests ran out of time.
m10_part() { # <part> <limit in seconds> <kill after>: runs it, its status merged into STATUS
    local part_status
    # Stopped at its limit, it cleans up first (power saving reset, Always-on VPN off, the device owner given up).
    timeout --kill-after="$3" "${2}s" bash scripts/m10_scenarios.sh "$API" "$OUT" "$1"
    part_status=$?
    if [ "$part_status" = 124 ] || [ "$part_status" = 137 ] || [ "$part_status" = 143 ]; then
        echo "::error title=M10 scenarios (API $API)::$1: stopped after $2 s: the steps it took are in m10-scenarios.txt."
    fi
    if [ "$part_status" != 0 ] && [ "$STATUS" = 0 ]; then STATUS=$part_status; fi
}
N_LIMIT_S="$(part_limit 240 $((22 * 60)) 120)"
if [ "$STATUS" = 124 ] || [ "$STATUS" = 137 ]; then
    echo "::error title=M10 scenarios (API $API)::Not run: the device tests ran out of time."
elif [ "$N_LIMIT_S" = 0 ]; then
    echo "::error title=M10 scenarios (API $API)::notifications: not run: $(($(left_s) / 60)) minutes left of this script's ${SCRIPT_BUDGET_S} s."
    STATUS=1
else
    m10_part notifications "$N_LIMIT_S" 1m
fi
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
# Pulls the phone's capture of <interface> in 8 MB pieces split on the phone, each up to twice, and puts them together
# again as $OUT/guest-<interface>.pcap; succeeds only if that has <size> bytes. PIECES_WHY says why not.
# adb's last line of a failed pull, without the paths it names ("failed to copy '…' to '…': "), which alone filled the
# 120 characters kept of it (M10-134); "no reason given" when adb said nothing.
reason_of() { # <adb's stderr>
    local last
    last="$(printf '%s' "$1" | tr -d '\r' | grep -v '^$' | tail -1 | sed "s/failed to copy '[^']*' to '[^']*': //")"
    printf '%s' "${last:-no reason given}" | cut -c1-120
}
# Within PIECES_DEADLINE_S in all (M10-133): a piece is not begun past it, so a slow phone cannot spend the step's limit here.
PIECES_DEADLINE_S=600
pull_in_pieces() { # <interface> <size>
    local iface=$1 size=$2 dir=/data/local/tmp/pf-pieces parts part got err deadline=$((SECONDS + PIECES_DEADLINE_S))
    PIECES_WHY=""
    rm -f "$OUT/guest-$iface.pcap" "$OUT"/pf-piece.*
    if ! PIECES_WHY="$(timeout 120 adb shell "${AS_ROOT}sh -c 'rm -rf $dir && mkdir $dir && cd $dir && split -b 8388608 ../pf-$iface.pcap p. && chmod 755 $dir && chmod 644 p.*'" 2>&1)"; then
        PIECES_WHY="split on the phone: ${PIECES_WHY:-failed}"
        timeout 60 adb shell "${AS_ROOT}rm -rf $dir" >/dev/null 2>&1 || true
        return 1
    fi
    parts="$(timeout 30 adb shell "ls $dir" 2>/dev/null | tr -d '\r' | grep '^p\.' | sort)"
    if [ -z "$parts" ]; then
        PIECES_WHY="no pieces on the phone"
        timeout 60 adb shell "${AS_ROOT}rm -rf $dir" >/dev/null 2>&1 || true
        return 1
    fi
    for part in $parts; do
        if [ "$SECONDS" -ge "$deadline" ]; then
            PIECES_WHY="out of time before piece $part of $(wc -w <<<"$parts") ($PIECES_DEADLINE_S s)"
            rm -f "$OUT"/pf-piece.*
            timeout 60 adb shell "${AS_ROOT}rm -rf $dir" >/dev/null 2>&1 || true
            return 1
        fi
        if ! err="$(timeout 120 adb pull "$dir/$part" "$OUT/pf-piece.$part" 2>&1 >/dev/null)" &&
            ! { timeout 60 adb wait-for-device >/dev/null 2>&1 && err="$(timeout 120 adb pull "$dir/$part" "$OUT/pf-piece.$part" 2>&1 >/dev/null)"; }; then
            PIECES_WHY="piece $part of $(wc -w <<<"$parts") did not come: $(reason_of "$err")"
            rm -f "$OUT"/pf-piece.*
            timeout 60 adb shell "${AS_ROOT}rm -rf $dir" >/dev/null 2>&1 || true
            return 1
        fi
    done
    for part in $parts; do cat "$OUT/pf-piece.$part"; done > "$OUT/guest-$iface.pcap"
    rm -f "$OUT"/pf-piece.*
    timeout 60 adb shell "${AS_ROOT}rm -rf $dir" >/dev/null 2>&1 || true
    got="$(wc -c < "$OUT/guest-$iface.pcap" | tr -d '[:space:]')"
    if [ -n "$size" ] && [ "$got" = "$size" ]; then return 0; fi
    PIECES_WHY="the pieces made $got of ${size:-?} bytes"
    return 1
}
if [ "$GUEST_CAPTURE" != 0 ]; then
    # Root again for the end of the captures: adbd once more (API 34 on), su as throughout (API 33).
    if [ "$GUEST_CAPTURE" = 1 ]; then restart_adbd root || true; fi
    echo "tcpdump at the end: $(timeout 30 adb shell "${AS_ROOT}pidof tcpdump || echo none" 2>&1 | tr -d '\r')" >> "$OUT/guest-capture.txt"
    echo "tcpdump at the end on: $(tcpdump_interfaces)" >> "$OUT/guest-capture.txt"
    timeout 30 adb shell "${AS_ROOT}pkill -INT tcpdump || ${AS_ROOT}killall -INT tcpdump" >/dev/null 2>&1 || true
    sleep 3
    # The captures are pulled by the shell user, made readable to it first, as the socket samples are pulled. (That
    # was tried for API 35's pulls of its whole capture, which fail at once whoever pulls: there the pieces are what
    # come whole, M10-121.)
    timeout 30 adb shell "${AS_ROOT}chmod 644 /data/local/tmp/pf-*.pcap" >/dev/null 2>&1 || true
    if [ "$GUEST_CAPTURE" = 1 ]; then
        restart_adbd unroot || true
        AS_ROOT="" # adbd is the shell user again; the captures need no root now
    fi
    LISTING="$(timeout 30 adb shell 'ls -l /data/local/tmp/pf-*.pcap' 2>&1 | tr -d '\r')"
    CAPTURED="$(awk '/^-/ {print $NF, $5}' <<<"$LISTING" | paste -sd ';' -)"
    echo "captured on the phone: ${CAPTURED:-none ($(head -1 <<<"$LISTING" | cut -c1-120))}" >> "$OUT/guest-capture.txt"
    for iface in $(cat "$OUT/guest-interfaces.txt"); do
        # Only a capture the phone has: tcpdump on an interface that was down at the start (eth0 in airplane mode)
        # wrote none. Skipped only on the phone's own "no": an adb that does not answer is no reason not to try.
        there="$(timeout 30 adb shell "[ -f /data/local/tmp/pf-$iface.pcap ] && echo yes || echo no" 2>/dev/null | tr -d '\r[:space:]')"
        if [ "$there" = no ]; then
            echo "no pf-$iface.pcap on the phone" >> "$OUT/guest-capture.txt"
            continue
        fi
        # Its size, from the listing above (or asked again below, should that have failed).
        want="$(awk -v f="/data/local/tmp/pf-$iface.pcap" '$NF == f {print $5}' <<<"$LISTING")"
        if [ -z "$want" ]; then want="$(timeout 30 adb shell "wc -c < /data/local/tmp/pf-$iface.pcap" 2>/dev/null | tr -d '\r[:space:]')"; fi
        # Whether the phone itself reads its capture to the end (M10-121: API 35's pulls stopped at once, the last after
        # 63,488 of 249,702,128 bytes, in run 41911592584): its own dd, to nowhere.
        echo "read on the phone: $(timeout 120 adb shell "dd if=/data/local/tmp/pf-$iface.pcap of=/dev/null bs=1048576 2>&1" 2>&1 | tr -d '\r' | tail -2 | paste -sd ' ' - | cut -c1-200)" >> "$OUT/guest-capture.txt"
        # adb pull, and again once adb has the device again should it fail at once (the device offline for a moment, or
        # the transfer cut: M10-112, M10-121); a third time in 8 MB pieces split on the phone, each pulled up to twice,
        # put together again and its size checked against the phone's — as a whole pull's is, when the size is known
        # (M10-135). A try that took a minute or more is not followed by another, so the step's own limit is not spent
        # here. Each try's exit, seconds and reason are kept, with adbd's pid before and after, in both notes (M10-134).
        how=""
        why=""
        for try in 1 2 3; do
            started=$SECONDS
            reason=""
            adbd_before="$(timeout 10 adb shell pidof adbd 2>/dev/null | tr -d '\r')"
            if [ "$try" -lt 3 ]; then
                if reason="$(timeout 300 adb pull "/data/local/tmp/pf-$iface.pcap" "$OUT/guest-$iface.pcap" 2>&1 >/dev/null)"; then
                    got="$(wc -c < "$OUT/guest-$iface.pcap" 2>/dev/null | tr -d '[:space:]')"
                    if [ -z "$want" ] || [ "$got" = "$want" ]; then how="adb pull"; else reason="adb pull gave $got of $want bytes"; fi
                fi
            else
                pull_in_pieces "$iface" "$want" && how="in pieces"
                reason="$PIECES_WHY"
            fi
            tried="try $try: $([ -n "$how" ] && echo ok || echo failed) in $((SECONDS - started)) s, adbd ${adbd_before:-?} → $(timeout 10 adb shell pidof adbd 2>/dev/null | tr -d '\r' || echo '?')"
            if [ -n "$how" ]; then why="${why:+$why; }$tried"; break; fi
            why="${why:+$why; }$tried: $(reason_of "$reason")"
            if [ "$try" = 3 ] || [ $((SECONDS - started)) -ge 60 ]; then break; fi
            # Before the next try: adb connects again; the size is asked again if the listing had none.
            timeout 20 adb reconnect >/dev/null 2>&1 || true
            timeout 60 adb wait-for-device >/dev/null 2>&1 || break
            if [ -z "$want" ]; then
                want="$(timeout 30 adb shell "wc -c < /data/local/tmp/pf-$iface.pcap" 2>/dev/null | tr -d '\r[:space:]')"
            fi
        done
        if [ -n "$how" ]; then
            if [ "$try" != 1 ]; then echo "pulled pf-$iface.pcap at try $try ($how): $why" >> "$OUT/guest-capture.txt"; fi
        else
            rm -f "$OUT/guest-$iface.pcap"
            echo "pull of pf-$iface.pcap failed or timed out (as uid $(timeout 10 adb shell id -u 2>/dev/null | tr -d '\r' || echo '?')): $why" >> "$OUT/guest-capture.txt"
        fi
    done
fi
# M10's R and P: a restart with ProxyFlow as Always-on VPN and "Block connections without VPN" on, then power saving on
# the connection Android started after it (scripts/m10_scenarios.sh restart), last of all — the restart ends the captures,
# the socket samples (all pulled above) and logcat — while the lab's files are still on the phone. adbd as the shell user
# again first: the VPN covers the shell, not root (the script checks it, before and after the restart). Bounded by what is
# left, 2 minutes kept for the end; not run once the tests ran out of time, or with too little left for it.
if [ "$STATUS" = 124 ] || [ "$STATUS" = 137 ]; then
    echo "::error title=M10 scenarios (API $API)::Not run: the device tests ran out of time."
else
    if [ "$GUEST_CAPTURE" = 1 ]; then restart_adbd unroot || true; fi
    R_LIMIT_S="$(part_limit "${M10_RESTART_TIMEOUT_S:-960}" $((4 * 60)) $((9 * 60)))"
    if [ "$R_LIMIT_S" = 0 ]; then
        echo "::error title=M10 scenarios (API $API)::restart: not run: $(($(left_s) / 60)) minutes left of this script's ${SCRIPT_BUDGET_S} s, too few for a restart and power saving."
        STATUS=1
    else
        m10_part restart "$R_LIMIT_S" 2m
    fi
fi
timeout 30 adb shell rm -f /data/local/tmp/proxyflow-e2e.properties /data/local/tmp/proxyflow-ca.pem /data/local/tmp/pf-client.apk \
    /data/local/tmp/pf-connecting.sh /data/local/tmp/pf-connecting.txt >/dev/null 2>&1 || true
exit "$STATUS"
