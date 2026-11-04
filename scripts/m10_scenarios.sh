#!/usr/bin/env bash
# M10: two scenarios no test inside the app's process can run, after the device tests and M8's failure injection, with
# the app and its test APK installed again (M8 removed them):
#
#   N  Android 13 and later without the notification permission: revoked while ProxyFlow is not running (revoking it
#      ends the app's process), then NotificationPermissionEndToEndTest alone connects and carries a line through the
#      proxy. Given back afterwards.
#   R  the phone restarts with ProxyFlow as its Always-on VPN and "Block connections without VPN" on, as a user sets
#      them in Android's VPN settings (here through the lab's device owner, the test APK's LabDeviceAdmin, as in M8's
#      scenario B). Once the phone is up again Android starts ProxyFlow, which connects the chosen profile by itself;
#      until it is connected, Android's blocking keeps apps off the network — the lab's witness, which only a connection
#      around ProxyFlow reaches, must never answer — and then a line goes through the proxy to the destination by name,
#      which only a proxy reaches.
#
# Run by scripts/e2e_device.sh last, after every capture and sample was pulled (the restart ends them) and before the
# lab's files leave the phone. Nothing here looks a lab name up while ProxyFlow is not connected (the M5 evidence checks
# the whole run's DNS for that): the destination is tried only once Android has a VPN network again. Whatever way it
# ends, Always-on VPN is turned off, the device owner given up and both APKs removed (on_exit). The steps are in
# m10-scenarios.txt and the job's log; it fails on any of the above that does not hold.
#
# Usage: m10_scenarios.sh <api level> <out dir>
set -uo pipefail

API="$1"
OUT="$2"
STEPS="$OUT/m10-scenarios.txt"
: > "$STEPS"
APP_ID="$(sed -n 's/^proxyflow.applicationId=//p' gradle.properties | tr -d '[:space:]').debug"
TEST_ID="$APP_ID.test"
RUNNER="$TEST_ID/app.proxyflow.e2e.E2eTestRunner"
APP_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
CLIENT=/data/local/tmp/pf-client.apk
WITNESS_PORT="$(sed -n 's/^witness.port=//p' /tmp/pf/e2e.properties)"
DESTINATION_NAME="$(sed -n 's/^destination.name=//p' /tmp/pf/e2e.properties)"
DESTINATION_PORT="$(sed -n 's/^destination.port=//p' /tmp/pf/e2e.properties)"
STATUS=0
OWNER_SET=""
ALWAYS_ON_SET=""

note() { echo "$(date -u +%H:%M:%S) $*" >> "$STEPS"; }
fail() { note "FAIL: $*"; STATUS=1; }

# The shell client's one line (app/src/androidTest/java/app/proxyflow/e2e/ShellClient.java), as the shell user.
client() { timeout 40 adb shell "CLASSPATH=$CLIENT app_process /system/bin app.proxyflow.e2e.ShellClient $*" 2>&1 | tr -d '\r' | grep -v '^$' | tail -1; }
witness() { client tcp 10.0.2.2 "$WITNESS_PORT" "PF10-reboot-$1" 3000; }
admin() { # <command> [extras...]: what the lab's device owner answered (LabAdminCommands)
    timeout 30 adb shell am broadcast --include-stopped-packages -n "$TEST_ID/app.proxyflow.e2e.LabAdminCommands" \
        -a "app.proxyflow.e2e.$1" "${@:2}" 2>&1 | tr -d '\r' | sed -n 's/^Broadcast completed: result=\([0-9]*\), data="\(.*\)"$/result \1: \2/p'
}
# Android's VPN networks, as in scripts/m8_failure_injection.sh: a line each, "none", or "unknown".
vpn_networks() {
    local out
    out="$(timeout 20 adb shell 'dumpsys connectivity; echo "dumpsys-rc=$?"' 2>/dev/null | tr -d '\r')"
    case "$out" in *dumpsys-rc=0*) ;; *) echo unknown; return ;; esac
    out="$(echo "$out" | grep -E 'NetworkAgentInfo\{' | grep -E 'ni\{(\[type: VPN\[[^]]*\], state: CONNECTED|VPN CONNECTED)' | head -2 | cut -c1-240)"
    echo "${out:-none}"
}
booted() { [ "$(timeout 10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }
# One instrumented test alone; succeeds when it passed. Its output in m10-<name>.txt.
instrument() { # <name> <seconds> <am instrument arguments...>
    local name="$1" seconds="$2"
    shift 2
    timeout "$seconds" adb shell am instrument -w "$@" "$RUNNER" > "$OUT/m10-$name.txt" 2>&1
    tr -d '\r' < "$OUT/m10-$name.txt" | grep -q '^OK (1 test)'
}
failure_of() { tr -d '\r' < "$OUT/m10-$1.txt" | grep -m3 -E 'Error|Exception|expected|FAIL|Assumption' | paste -sd ' ' | cut -c1-500; }

on_exit() {
    trap '' TERM INT
    if [ -n "$ALWAYS_ON_SET" ]; then
        ALWAYS_ON_SET=""
        note "Always-on VPN off: $(admin ALWAYS_ON --es package "''")"
    fi
    timeout 30 adb shell am force-stop "$APP_ID" >/dev/null 2>&1
    if [ -n "$OWNER_SET" ]; then
        OWNER_SET=""
        note "device owner released: $(admin RELEASE)"
    fi
    timeout 60 adb uninstall "$TEST_ID" >/dev/null 2>&1 || true
    timeout 60 adb uninstall "$APP_ID" >/dev/null 2>&1 || true
    timeout 10 adb shell rm -f "$CLIENT" >/dev/null 2>&1 || true
    note "M10 scenarios done (status $STATUS)"
    local token
    token="$(openssl rand -hex 16 2>/dev/null || echo "m10$RANDOM$RANDOM")"
    echo "::group::M10 scenarios: no notification permission, a restart with Always-on VPN (API $API)"
    echo "::stop-commands::$token" # what the phone printed, with workflow commands off
    cat "$STEPS"
    echo "::$token::"
    echo "::endgroup::"
    if [ "$STATUS" != 0 ]; then
        echo "::error title=M10 scenarios (API $API)::$(grep 'FAIL: ' "$STEPS" | head -3 | sed 's/^[0-9:]* FAIL: //' | paste -sd ' ' | cut -c1-900)"
    else
        echo "::notice title=M10 scenarios (API $API)::$(grep -E '^[0-9:]+ (N|R): (connected|after the restart: Android)' "$STEPS" | sed 's/^[0-9:]* //' | paste -sd ' ' | cut -c1-900)"
    fi
}
trap on_exit EXIT
trap 'fail "stopped by a signal (its time limit?)"; exit 143' TERM INT

timeout 120 adb install -r -t -g "$APP_APK" > "$OUT/m10-install.txt" 2>&1 || { fail "the app did not install: $(tail -1 "$OUT/m10-install.txt")"; exit 1; }
timeout 120 adb install -r -t "$TEST_APK" >> "$OUT/m10-install.txt" 2>&1 || { fail "the test APK did not install"; exit 1; }
timeout 60 adb push "$TEST_APK" "$CLIENT" >/dev/null 2>&1 && timeout 10 adb shell chmod 644 "$CLIENT"

# --- N: no notification permission (Android 13 and later) ---------------------------------------------------------
if [ "$API" -ge 33 ]; then
    timeout 30 adb shell am force-stop "$APP_ID" >/dev/null 2>&1
    # As for a newly installed app whose user said Not now (developer.android.com, the notification permission's tests).
    timeout 10 adb shell pm revoke "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
    timeout 10 adb shell pm clear-permission-flags "$APP_ID" android.permission.POST_NOTIFICATIONS user-set >/dev/null 2>&1
    timeout 10 adb shell pm clear-permission-flags "$APP_ID" android.permission.POST_NOTIFICATIONS user-fixed >/dev/null 2>&1
    note "N: the notification permission: $(timeout 20 adb shell dumpsys package "$APP_ID" 2>/dev/null | tr -d '\r' | grep -m1 'POST_NOTIFICATIONS: granted=' | sed 's/^ *//')"
    if instrument notifications 200 -e m10 notifications -e class app.proxyflow.e2e.NotificationPermissionEndToEndTest; then
        note "N: connected without the notification permission; a line through the proxy; no notification shown"
    else
        fail "N: without the notification permission: $(failure_of notifications)"
    fi
    timeout 30 adb shell am force-stop "$APP_ID" >/dev/null 2>&1
    timeout 10 adb shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
else
    note "N: not on API $API: Android asks for no notification permission before 13"
fi

# --- R: before the restart: the chosen profile, Always-on VPN with blocking, connected --------------------------------
if ! instrument prepare 150 -e m8 prepare -e token R1 -e expect - -e since 0 -e class app.proxyflow.e2e.KillSwitchEndToEndTest#prepare; then
    fail "R: the prepare step failed: $(failure_of prepare)"
    exit 1
fi
note "R: prepared: a SOCKS5 profile through the lab's Dante, chosen"
OWNER_SET=1
note "R: device owner: $(timeout 30 adb shell dpm set-device-owner "$TEST_ID/app.proxyflow.e2e.LabDeviceAdmin" 2>&1 | tr -d '\r' | paste -sd ' ')"
ALWAYS_ON_SET=1
out="$(admin ALWAYS_ON --es package "$APP_ID" --ez lockdown true)"
note "R: Always-on VPN with Block connections without VPN: $out"
case "$out" in *"lockdown true"*) ;; *) fail "R: Android did not take the setting: $out"; exit 1 ;; esac
# Android starts ProxyFlow, which holds traffic on its interface until the proxy is checked (M8): the line is tried once
# Android has the VPN network, again until it gets through.
reply=""
started=$SECONDS
while [ $((SECONDS - started)) -lt 120 ]; do
    vpn="$(vpn_networks)"
    if [ "$vpn" != none ] && [ "$vpn" != unknown ]; then
        reply="$(client tcp "$DESTINATION_NAME" "$DESTINATION_PORT" "PF10-reboot-before" 10000)"
        [ "$reply" = "REPLY PROXYFLOW-DEST PF10-reboot-before" ] && break
    fi
    sleep 2
done
note "R: before the restart: VPN $(vpn_networks | cut -c1-120); a line through the proxy: ${reply:-none}"
case "$reply" in "REPLY PROXYFLOW-DEST PF10-reboot-before") ;; *) fail "R: before the restart, Always-on VPN did not carry a line through the proxy: ${reply:-none}"; exit 1 ;; esac

# --- R: the restart -------------------------------------------------------------------------------------------------
note "R: restarting the phone"
timeout 60 adb reboot >/dev/null 2>&1 || true
timeout 300 adb wait-for-device >/dev/null 2>&1 || true
up=""
for _ in $(seq 1 150); do
    if booted; then up=1; break; fi
    sleep 2
done
[ -n "$up" ] || { fail "R: the phone did not finish starting within 5 minutes"; exit 1; }
note "R: started again"

# Until Android has ProxyFlow's VPN up again, every witness probe must be refused by Android's blocking; after that the
# witness is reached only through the proxy, which can't reach it (it listens on the runner's loopback), so an answer
# from it at any time is a connection around ProxyFlow. The destination by name is tried once a VPN network is there.
probes=0
connected=""
out=""
started=$SECONDS
while [ $((SECONDS - started)) -lt 240 ]; do
    probes=$((probes + 1))
    out="$(witness "$probes")"
    case "$out" in REPLY*) fail "R: the witness answered after the restart (probe $probes): $out" ;; esac
    vpn="$(vpn_networks)"
    if [ "$vpn" != none ] && [ "$vpn" != unknown ]; then
        reply="$(client tcp "$DESTINATION_NAME" "$DESTINATION_PORT" "PF10-reboot-after" 10000)"
        if [ "$reply" = "REPLY PROXYFLOW-DEST PF10-reboot-after" ]; then
            connected=$((SECONDS - started))
            break
        fi
    fi
    sleep 1
done
note "R: after the restart: $probes witness probes, none answered; the last: $out"
if [ -n "$connected" ]; then
    note "R: after the restart: Android started ProxyFlow, which connected by itself ${connected} s after the phone was up; a line through the proxy: $reply"
    note "R: VPN: $(vpn_networks | cut -c1-160)"
    note "R: ProxyFlow's session: $(timeout 30 adb logcat -d -v brief -s ProxyFlowVpn 2>/dev/null | tr -d '\r' | grep -m6 -E 'always-on start|connected |reconnect|blocked' | paste -sd ';' | cut -c1-600)"
else
    fail "R: after the restart, no line went through the proxy within 240 s: VPN $(vpn_networks | cut -c1-120); the last reply: ${reply:-none}"
fi
exit "$STATUS"
