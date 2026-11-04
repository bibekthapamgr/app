#!/usr/bin/env bash
# M10: what no test inside the app's process can show, after the device tests and M8's failure injection, with the app
# and its test APK installed again (M8 removed them). Two parts, each a run of this script of its own:
#
#   notifications  N  Android 13 and later without the notification permission: revoked while ProxyFlow is not running
#      (revoking it ends the app's process), then NotificationPermissionEndToEndTest alone connects, carries a line
#      through the proxy and finds none of ProxyFlow's notifications shown. Given back afterwards. Run while the phone's
#      captures still run, so the checks over whole captures cover it (M6: no packet to or from a lab address, no lookup
#      of a lab name); M6's account of each connection that left the phone while ProxyFlow was connected does not: it
#      reads the device tests' logcat and their ProxyFlow's uid, and N's ProxyFlow is a new install (a new uid, its own
#      logcat).
#   restart  R, P  ProxyFlow as the phone's Always-on VPN with "Block connections without VPN" on, as a user sets them
#      in Android's VPN settings (here through the lab's device owner, the test APK's LabDeviceAdmin, as in M8's
#      scenario B). Android starts ProxyFlow, which connects the chosen profile by itself, in a process of its own: no
#      instrumentation runs in it, whose rank would keep any process on the network.
#      R  the phone restarts. Until ProxyFlow is connected again, Android's blocking keeps apps off the network: a probe
#         of the lab's witness — which only a connection around ProxyFlow reaches — made while Android has no VPN
#         network must be refused as that blocking refuses (M8's scenario B), and none may ever be answered; Android
#         must still have the setting, and ProxyFlow log its Always-on start; then a line goes through the proxy to the
#         destination by name, which only a proxy reaches.
#      P  then, on the connection Android started after the restart: Android's power saving, each on in turn, once
#         Android's own allowance for a VPN it has just started is over: deep Doze (on battery, the screen off), App
#         Standby's most restricted bucket, Battery Saver. With each on, Android still has ProxyFlow's VPN, reads back
#         the state asked for, and a line from the shell goes through the proxy to the destination by name. Android
#         binds a VPN service with BIND_FOREGROUND_SERVICE, which may by itself let its process past all three, so what
#         ties the result to ProxyFlow's own foreground service is the rank check: Android must rank ProxyFlow's process
#         as a started foreground service (FGS), with no instrumentation in it. P runs after R: a restart after P once
#         made API 30's emulator exit (run 41826918038).
#
# Run by scripts/e2e_device.sh: N before the phone's captures stop, R and P last, after every capture and sample was
# pulled (the restart ends them), while the lab's files are still on the phone. adbd must run as the shell user (the VPN
# covers the shell, not root): checked before each part and after the restart. Nothing here looks a lab name up while
# ProxyFlow is not connected: the destination is tried only once Android has a VPN network (the lab's uplink resolver
# logs every name the phone asks it, the whole run, which the M6 evidence checks), and every connection to the
# destination is a proxy's tunnel (the M5 evidence counts them). Logcat is streamed to logcat-m10-<part>.txt. Whatever
# way it ends, power saving is reset, Always-on VPN turned off, the device owner given up and both APKs removed
# (on_exit). The steps are in m10-scenarios.txt and the job's log; it fails on any of the above that does not hold.
#
# Usage: m10_scenarios.sh <api level> <out dir> notifications|restart
set -uo pipefail

API="$1"
OUT="$2"
PART="${3:-}"
case "$PART" in
    notifications | restart) ;;
    *)
        echo "usage: m10_scenarios.sh <api level> <out dir> notifications|restart" >&2
        exit 2
        ;;
esac
# shellcheck source=scripts/adb_reboot.sh
. "$(dirname "$0")/adb_reboot.sh"
STEPS="$OUT/m10-scenarios.txt"
if [ "$PART" = notifications ]; then : > "$STEPS"; fi
LOGCAT="$OUT/logcat-m10-$PART.txt"
APP_ID="$(sed -n 's/^proxyflow.applicationId=//p' gradle.properties | tr -d '[:space:]').debug"
TEST_ID="$APP_ID.test"
RUNNER="$TEST_ID/app.proxyflow.e2e.E2eTestRunner"
APP_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
CLIENT=/data/local/tmp/pf-client.apk
WITNESS_PORT="$(sed -n 's/^witness.port=//p' /tmp/pf/e2e.properties)"
DESTINATION_NAME="$(sed -n 's/^destination.name=//p' /tmp/pf/e2e.properties)"
DESTINATION_PORT="$(sed -n 's/^destination.port=//p' /tmp/pf/e2e.properties)"
# How a connect fails that Android's "Block connections without VPN" refuses (as scripts/m8_failure_injection.sh has it).
LOCKDOWN_REFUSAL='EACCES|Permission denied|EPERM|Operation not permitted'
STATUS=0
OWNER_SET=""
ALWAYS_ON_SET=""
POWER_SET=""
DEEP_ENABLED_BY_US=""
LOGCAT_PID=""

note() { echo "$(date -u +%H:%M:%S) $*" >> "$STEPS"; }
fail() {
    note "FAIL: $*"
    STATUS=1
}

# The shell client's one line (app/src/androidTest/java/app/proxyflow/e2e/ShellClient.java), as the shell user.
client() { timeout 40 adb shell "CLASSPATH=$CLIENT app_process /system/bin app.proxyflow.e2e.ShellClient $*" 2>&1 | tr -d '\r' | grep -v '^$' | tail -1; }
witness() { client tcp 10.0.2.2 "$WITNESS_PORT" "PF10-reboot-$1" 3000; }
line_through() { # <label>: one line to the destination by name, which only a proxy reaches; succeeds when it came back
    local reply
    LAST_REPLY=""
    reply="$(client tcp "$DESTINATION_NAME" "$DESTINATION_PORT" "PF10-$1" 10000)"
    LAST_REPLY="${reply:-no reply to PF10-$1}" # sent, whatever came back: "not sent" is for a line never tried
    [ "$reply" = "REPLY PROXYFLOW-DEST PF10-$1" ]
}
admin() { # <command> [extras...]: what the lab's device owner answered (LabAdminCommands)
    timeout 30 adb shell am broadcast --include-stopped-packages -n "$TEST_ID/app.proxyflow.e2e.LabAdminCommands" \
        -a "app.proxyflow.e2e.$1" "${@:2}" 2>&1 | tr -d '\r' | sed -n 's/^Broadcast completed: result=\([0-9]*\), data="\(.*\)"$/result \1: \2/p'
}
# Android's VPN networks, as in scripts/m8_failure_injection.sh: a line each, "none", or "unknown".
vpn_networks() {
    local out
    out="$(timeout 20 adb shell 'dumpsys connectivity; echo "dumpsys-rc=$?"' 2>/dev/null | tr -d '\r')"
    case "$out" in *dumpsys-rc=0*) ;; *)
        echo unknown
        return
        ;;
    esac
    out="$(echo "$out" | grep -E 'NetworkAgentInfo\{' | grep -E 'ni\{(\[type: VPN\[[^]]*\], state: CONNECTED|VPN CONNECTED)' | head -2 | cut -c1-240)"
    echo "${out:-none}"
}
vpn_up() { case "$1" in none | unknown) return 1 ;; *) return 0 ;; esac }
# adbd as the shell user, whose traffic the VPN covers (root's goes around it: a probe as root would prove nothing).
shell_user() { # <when>
    local id
    id="$(timeout 10 adb shell id -u 2>/dev/null | tr -d '\r[:space:]')"
    [ "$id" = 2000 ] && return 0
    fail "$1: adbd runs as uid ${id:-unknown}, not the shell user (2000): the VPN covers the shell, not root, so nothing here could test it"
    return 1
}
# One instrumented test alone; succeeds when it ran and passed. Its raw output (-r) in m10-<name>.txt: each test ends with
# its status code, 0 when it passed, -2 failed, -3 ignored, -4 an assumption failed — "OK (1 test)" alone is printed for
# a skipped test too.
instrument() { # <name> <seconds> <am instrument arguments...>
    local name="$1" seconds="$2" out
    shift 2
    timeout "$seconds" adb shell am instrument -r -w "$@" "$RUNNER" > "$OUT/m10-$name.txt" 2>&1
    out="$(tr -d '\r' < "$OUT/m10-$name.txt")"
    [ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' <<< "$out")" = 1 ] && ! grep -qE '^INSTRUMENTATION_STATUS_CODE: -[0-9]+$' <<< "$out"
}
failure_of() {
    tr -d '\r' < "$OUT/m10-$1.txt" | grep -m3 -E 'Error|Exception|expected|FAIL|Assumption|INSTRUMENTATION_STATUS_CODE: -|shortMsg' |
        paste -sd ' ' | cut -c1-500
}
stream_logcat() { # [-T 1]: new lines only
    adb logcat -v threadtime -v UTC "$@" >> "$LOGCAT" 2>&1 &
    LOGCAT_PID=$!
}
stop_logcat() {
    if [ -n "$LOGCAT_PID" ]; then
        kill "$LOGCAT_PID" 2>/dev/null
        wait "$LOGCAT_PID" 2>/dev/null
        LOGCAT_PID=""
    fi
}
install() {
    timeout 120 adb install -r -t -g "$APP_APK" > "$OUT/m10-install-$PART.txt" 2>&1 || {
        fail "the app did not install: $(tail -1 "$OUT/m10-install-$PART.txt")"
        return 1
    }
    timeout 120 adb install -r -t "$TEST_APK" >> "$OUT/m10-install-$PART.txt" 2>&1 || {
        fail "the test APK did not install"
        return 1
    }
    if ! { timeout 60 adb push "$TEST_APK" "$CLIENT" >/dev/null 2>&1 && timeout 10 adb shell chmod 644 "$CLIENT" >/dev/null 2>&1; }; then
        fail "the shell client could not be copied to the phone"
        return 1
    fi
}

reset_power() {
    [ -n "$POWER_SET" ] || return 0
    POWER_SET=""
    timeout 10 adb shell dumpsys deviceidle unforce >/dev/null 2>&1
    if [ -n "$DEEP_ENABLED_BY_US" ]; then timeout 10 adb shell dumpsys deviceidle disable deep >/dev/null 2>&1; fi
    timeout 10 adb shell cmd power set-mode 0 >/dev/null 2>&1
    timeout 10 adb shell settings put global low_power 0 >/dev/null 2>&1
    timeout 10 adb shell am set-standby-bucket "$APP_ID" active >/dev/null 2>&1
    timeout 10 adb shell dumpsys battery reset >/dev/null 2>&1
    timeout 10 adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    timeout 10 adb shell wm dismiss-keyguard >/dev/null 2>&1
}

# A clean-up step the lab's device owner answers: only the answer asked for passes; no answer, an error or another
# state fails the part (the phone may still have it).
cleaned_up() { # <step> <answer> <the answer asked for>
    case "$2" in
        "$3") note "$1: $2" ;;
        "") fail "$1: no answer from the phone" ;;
        *) fail "$1: $2" ;;
    esac
}

on_exit() {
    local code=$?
    trap '' TERM INT
    reset_power
    if [ -n "$ALWAYS_ON_SET" ]; then
        ALWAYS_ON_SET=""
        cleaned_up "Always-on VPN off" "$(admin ALWAYS_ON --es package "''")" "result 1: always-on none"
    fi
    timeout 30 adb shell am force-stop "$APP_ID" >/dev/null 2>&1
    if [ -n "$OWNER_SET" ]; then
        OWNER_SET=""
        cleaned_up "device owner released" "$(admin RELEASE)" "result 1: released, device owner false"
    fi
    timeout 60 adb uninstall "$TEST_ID" >/dev/null 2>&1 || true
    timeout 60 adb uninstall "$APP_ID" >/dev/null 2>&1 || true
    timeout 10 adb shell rm -f "$CLIENT" >/dev/null 2>&1 || true
    stop_logcat
    note "M10 $PART done (status $STATUS)"
    local token title
    token="$(openssl rand -hex 16 2>/dev/null || echo "m10$RANDOM$RANDOM")"
    case "$PART" in
        notifications) title="M10 scenarios: no notification permission (API $API)" ;;
        *) title="M10 scenarios: a restart with Always-on VPN, then power saving (API $API)" ;;
    esac
    echo "::group::$title"
    echo "::stop-commands::$token" # what the phone printed, with workflow commands off
    cat "$STEPS"
    echo "::$token::"
    echo "::endgroup::"
    if [ "$STATUS" != 0 ]; then
        echo "::error title=$title::$(grep 'FAIL: ' "$STEPS" | head -3 | sed 's/^[0-9:]* FAIL: //' | paste -sd ' ' | cut -c1-900)"
    else
        echo "::notice title=$title::$(grep -E '^[0-9:]+ (N|P|R): (connected|not on API|deep Doze|the most restricted|Battery Saver|after the restart)' "$STEPS" | sed 's/^[0-9:]* //' | paste -sd ' ' | cut -c1-3000)"
    fi
    # A part that passed but whose clean-up failed exits with the failure; one stopped (143) keeps its status.
    if [ "$code" = 0 ] && [ "$STATUS" != 0 ]; then exit "$STATUS"; fi
}
trap on_exit EXIT
trap 'fail "stopped by a signal (its time limit?)"; exit 143' TERM INT

# --- N: no notification permission (Android 13 and later) ---------------------------------------------------------
part_notifications() {
    if [ "$API" -lt 33 ]; then
        note "N: not on API $API: Android asks for no notification permission before 13"
        return
    fi
    shell_user "N" || return
    install || return
    stream_logcat -T 1
    timeout 30 adb shell am force-stop "$APP_ID" >/dev/null 2>&1
    # As for a newly installed app whose user said Not now (developer.android.com, the notification permission's tests).
    timeout 10 adb shell pm revoke "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
    timeout 10 adb shell pm clear-permission-flags "$APP_ID" android.permission.POST_NOTIFICATIONS user-set >/dev/null 2>&1
    timeout 10 adb shell pm clear-permission-flags "$APP_ID" android.permission.POST_NOTIFICATIONS user-fixed >/dev/null 2>&1
    note "N: the notification permission: $(timeout 20 adb shell dumpsys package "$APP_ID" 2>/dev/null | tr -d '\r' | grep -m1 'POST_NOTIFICATIONS: granted=' | sed 's/^ *//')"
    if instrument notifications 200 -e m10 notifications -e class app.proxyflow.e2e.NotificationPermissionEndToEndTest; then
        note "N: connected without the notification permission; a line through the proxy; none of ProxyFlow's notifications shown"
    else
        fail "N: without the notification permission: $(failure_of notifications)"
    fi
    timeout 30 adb shell am force-stop "$APP_ID" >/dev/null 2>&1
    timeout 10 adb shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
}

# --- P: power saving, ProxyFlow started by Android for Always-on VPN -----------------------------------------------
# ProxyFlow's process as Android ranks it: its pid, its line of the OOM list (the process state, FGS for a foreground
# service, and why), and whether instrumentation runs in it.
app_pid() { timeout 10 adb shell pidof "$APP_ID" 2>/dev/null | tr -d '\r'; }
app_oom() { timeout 20 adb shell dumpsys activity o 2>/dev/null | tr -d '\r' | grep -m1 -E " [0-9]+:$APP_ID/" | sed 's/^ *//' | cut -c1-200; }
app_instrumented() { timeout 20 adb shell dumpsys activity processes "$APP_ID" 2>/dev/null | tr -d '\r' | grep -cE 'Active instrumentation|Instrumentation #'; }
app_uid() { timeout 20 adb shell cmd package list packages -U "$APP_ID" 2>/dev/null | tr -d '\r' | sed -n "s/^package:$APP_ID uid:\([0-9]*\).*/\1/p" | head -1; }
# Battery Saver as Android's power service has it ("true", "false"), or its setting when the service's dump doesn't say.
saver_state() {
    local out
    out="$(timeout 20 adb shell dumpsys power 2>/dev/null | tr -d '\r' | sed -n '/^Battery saver state machine:/,/^[^ ]/p' | grep -m1 -E '^ +Enabled=' | sed 's/^ *Enabled=//')"
    if [ -n "$out" ]; then echo "$out"; else echo "unread (its setting: low_power $(timeout 10 adb shell settings get global low_power 2>/dev/null | tr -d '\r'))"; fi
}
connected_now() { # <label> <what>: Android still has ProxyFlow's VPN and a line goes through the proxy; a failure if not
    local vpn
    vpn="$(vpn_networks)"
    LAST_REPLY=""
    if vpn_up "$vpn" && line_through "$1"; then return 0; fi
    fail "P: $2: Android's VPN: $(echo "$vpn" | cut -c1-120); a line through the proxy: ${LAST_REPLY:-not sent}"
    return 1
}

part_power() {
    local pid oom instr uid since deep bucket want got saver
    pid="$(app_pid)"
    oom="$(app_oom)"
    instr="$(app_instrumented)"
    note "P: ProxyFlow's process, started by Android for Always-on VPN: pid ${pid:-none}; ${oom:-not in the OOM list}; instrumentation ${instr:-?}"
    [ -n "$pid" ] || {
        fail "P: ProxyFlow is not running"
        return
    }
    case "$oom" in
        *"(instrumentation)"*) fail "P: instrumentation runs in ProxyFlow's process: its rank would keep it on the network" ;;
        "") note "P: Android's OOM list did not show ProxyFlow's process: its rank is not checked" ;;
        *) echo "$oom" | grep -qE '/FGSL?([^A-Z]|$)' || fail "P: ProxyFlow's process is not ranked as a foreground service: $oom" ;;
    esac
    if [ "${instr:-0}" != 0 ]; then fail "P: an instrumentation is active in ProxyFlow's process"; fi
    # Android lets a VPN it has just started for Always-on through its power saving for a while (its temporary
    # allowlist): only after that does power saving test ProxyFlow's own foreground service.
    uid="$(app_uid)"
    since=$SECONDS
    local list allowance=""
    while [ $((SECONDS - since)) -lt 120 ]; do
        list="$(timeout 20 adb shell 'dumpsys deviceidle tempwhitelist; echo "dumpsys-rc=$?"' 2>/dev/null | tr -d '\r')"
        # Without the uid, or the list, nothing tells the allowance is over: it is waited out (Android's is 60 s or less).
        # An empty list is read (dumpsys answered): the allowance is over.
        if [ -z "$uid" ] || ! echo "$list" | grep -q '^dumpsys-rc=0$'; then
            if [ $((SECONDS - since)) -ge 75 ]; then
                allowance="not readable (uid ${uid:-unknown}): waited it out, $((SECONDS - since)) s"
                break
            fi
        elif ! echo "$list" | grep -qE "UID=$((uid % 100000))[^0-9]"; then
            allowance="none now (uid $uid; waited $((SECONDS - since)) s)"
            break
        fi
        sleep 5
    done
    note "P: Android's temporary allowance for ProxyFlow: ${allowance:-still there after 120 s}"
    [ -n "$allowance" ] || fail "P: Android's temporary allowance for ProxyFlow was still there after 120 s: power saving would not test its own service"
    POWER_SET=1
    timeout 10 adb shell dumpsys battery unplug >/dev/null 2>&1

    # Deep Doze, the screen off, as deep as it goes: Android's own idle maintenance would come only after a long while.
    if [ "$(timeout 10 adb shell dumpsys deviceidle enabled deep 2>/dev/null | tr -d '\r[:space:]')" != 1 ]; then
        timeout 10 adb shell dumpsys deviceidle enable deep >/dev/null 2>&1
        DEEP_ENABLED_BY_US=1
    fi
    timeout 10 adb shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1
    timeout 10 adb shell dumpsys deviceidle force-idle >/dev/null 2>&1
    deep=""
    for _ in $(seq 1 20); do
        deep="$(timeout 10 adb shell dumpsys deviceidle get deep 2>/dev/null | tr -d '\r[:space:]')"
        [ "$deep" = IDLE ] && break
        sleep 1
    done
    if [ "$deep" != IDLE ]; then
        fail "P: deep Doze did not start: $deep"
    else
        sleep 10
        deep="$(timeout 10 adb shell dumpsys deviceidle get deep 2>/dev/null | tr -d '\r[:space:]')"
        if [ "$deep" = IDLE ] && connected_now doze "in deep Doze"; then
            note "P: deep Doze ($deep), the screen off, 10 s: Android has ProxyFlow's VPN; a line through the proxy"
        elif [ "$deep" != IDLE ]; then
            fail "P: deep Doze ended during the hold: $deep"
        fi
    fi
    timeout 10 adb shell dumpsys deviceidle unforce >/dev/null 2>&1
    timeout 10 adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    timeout 10 adb shell wm dismiss-keyguard >/dev/null 2>&1

    # App Standby's most restricted bucket, read back after the hold: restricted (45) from Android 12; on Android 11, asked
    # for restricted, Android gave rare (40) — seen on API 30 — the most restricted it gives there; rare before.
    if [ "$API" -ge 30 ]; then
        bucket=restricted
        want=45
    else
        bucket=rare
        want=40
    fi
    timeout 10 adb shell am set-standby-bucket "$APP_ID" "$bucket" >/dev/null 2>&1
    sleep 10
    got="$(timeout 10 adb shell am get-standby-bucket "$APP_ID" 2>/dev/null | tr -d '\r[:space:]')"
    if [ "$API" = 30 ] && [ "$got" = 40 ]; then
        bucket="rare (what Android 11 gave for restricted)"
        want=40
    fi
    if [ "$got" != "$want" ]; then
        fail "P: Android did not keep ProxyFlow in the $bucket bucket ($want): $got"
    elif connected_now standby "in the $bucket bucket"; then
        note "P: the most restricted standby bucket ($bucket, $got), 10 s: Android has ProxyFlow's VPN; a line through the proxy"
    fi
    timeout 10 adb shell am set-standby-bucket "$APP_ID" active >/dev/null 2>&1

    # Battery Saver, which keeps apps that are not in the foreground off the network; on battery (it can't start charging).
    timeout 10 adb shell cmd power set-mode 1 >/dev/null 2>&1 || timeout 10 adb shell settings put global low_power 1 >/dev/null 2>&1
    sleep 10
    saver="$(saver_state)"
    case "$saver" in
        true | *"low_power 1)") ;;
        *) fail "P: Battery Saver did not start: $saver" ;;
    esac
    case "$saver" in true | *"low_power 1)")
        if connected_now saver "with Battery Saver on"; then
            note "P: Battery Saver on (Android's power service: $saver), 10 s: Android has ProxyFlow's VPN; a line through the proxy"
        fi
        ;;
    esac
    reset_power
    note "P: ProxyFlow's process afterwards: pid $(app_pid) (before: $pid); $(app_oom)"
    [ "$(app_pid)" = "$pid" ] || fail "P: ProxyFlow's process changed during power saving (pid $pid, now $(app_pid))"
}

# --- R: the restart with Always-on VPN and Android's blocking -------------------------------------------------------
# The uplink the run chose (e2e_device.sh: guest-network.txt) has an address again after the restart: Wi-Fi in airplane
# mode on API 34 and 35 sometimes needs switching off and on before it does.
uplink_iface() {
    case "$(sed -n 's/^uplink: //p' "$OUT/guest-network.txt" 2>/dev/null | head -1)" in
        Wi-Fi*) echo wifi ;;
        "mobile data"*) echo data ;;
        *) echo unknown ;;
    esac
}
uplink_up() { # wifi|data
    local re='^[0-9]+: wlan[0-9]+ +inet '
    [ "$1" = data ] && re='^[0-9]+: (eth[0-9]+|radio[0-9]+|rmnet[0-9]+) +inet '
    timeout 10 adb shell ip -o -4 addr show 2>/dev/null | tr -d '\r' | grep -Eq "$re"
}

part_restart() {
    shell_user "R: before the restart" || exit 1
    install || exit 1
    stream_logcat -T 1
    if ! instrument prepare 150 -e m8 prepare -e token R1 -e expect - -e since 0 -e class app.proxyflow.e2e.KillSwitchEndToEndTest#prepare; then
        fail "R: the prepare step failed: $(failure_of prepare)"
        exit 1
    fi
    note "R: prepared: a SOCKS5 profile through the lab's Dante, chosen"
    OWNER_SET=1
    note "R: device owner: $(timeout 30 adb shell dpm set-device-owner "$TEST_ID/app.proxyflow.e2e.LabDeviceAdmin" 2>&1 | tr -d '\r' | paste -sd ' ')"
    ALWAYS_ON_SET=1
    local out status
    out="$(admin ALWAYS_ON --es package "$APP_ID" --ez lockdown true)"
    status="$(admin STATUS)"
    note "R: Always-on VPN with Block connections without VPN: $out; read back: $status"
    case "$status" in *"always-on $APP_ID lockdown true"*) ;; *)
        fail "R: Android did not take the setting: $out; $status"
        exit 1
        ;;
    esac
    # Android starts ProxyFlow, which holds traffic on its interface until the proxy is checked (M8): the line is tried once
    # Android has the VPN network, again until it gets through.
    local started=$SECONDS through=""
    while [ $((SECONDS - started)) -lt 120 ]; do
        if vpn_up "$(vpn_networks)" && line_through reboot-before; then
            through=1
            break
        fi
        sleep 2
    done
    note "R: before the restart: VPN $(vpn_networks | cut -c1-120); a line through the proxy: ${LAST_REPLY:-none}"
    [ -n "$through" ] || {
        fail "R: before the restart, Always-on VPN did not carry a line through the proxy: ${LAST_REPLY:-none}"
        exit 1
    }

    note "R: restarting the phone"
    stop_logcat
    # The emulators restarted in 22 to 40 s (runs 41826918038 and 41837393553): five minutes is seven times that, and leaves
    # R's shortest limit (9 minutes, scripts/e2e_device.sh) the time to say what it saw.
    if ! reboot_and_wait 300; then
        fail "R: the phone did not start again within 5 minutes (a new boot, its boot completed): $REBOOT_SEEN"
        exit 1
    fi
    local up=$SECONDS
    note "R: started again: a new boot, completed$([ -n "$REBOOT_WENT_DOWN" ] || echo "; adb was never seen leaving the old one")"
    stream_logcat
    shell_user "R: after the restart" || exit 1

    # Every probe notes whether Android had a VPN network just before it. Without one, Android's blocking must refuse it;
    # with one, it goes into ProxyFlow's VPN, which holds or refuses it — the witness is reached only around ProxyFlow
    # (it listens on the runner's loopback, where a proxy's connection never arrives), so an answer at any time is a
    # connection around it. The destination by name is tried once a VPN network is there.
    local probes=0 refused=0 answered=0 with_vpn=0 unknown=0 connected="" vpn uplink toggled="" reply=""
    uplink="$(uplink_iface)"
    LAST_REPLY="" # the line before the restart says nothing of after it
    while [ $((SECONDS - up)) -lt 240 ]; do
        vpn="$(vpn_networks)"
        probes=$((probes + 1))
        out="$(witness "$probes")"
        if [ "${out#REPLY}" != "$out" ]; then
            answered=$((answered + 1))
            fail "R: the witness answered after the restart (probe $probes, Android's VPN: $(echo "$vpn" | cut -c1-60)): $out"
        elif [ "$vpn" = unknown ]; then
            unknown=$((unknown + 1))
        elif [ "$vpn" = none ]; then
            if echo "$out" | grep -qE "$LOCKDOWN_REFUSAL"; then
                refused=$((refused + 1))
            elif vpn_up "$(vpn_networks)"; then
                with_vpn=$((with_vpn + 1)) # ProxyFlow's VPN came up while the probe ran
            else
                fail "R: probe $probes, with no VPN network, was not refused as Android's blocking refuses: $out"
            fi
        else
            with_vpn=$((with_vpn + 1))
            if line_through reboot-after; then
                connected=$((SECONDS - up))
                reply="$LAST_REPLY"
                break
            fi
        fi
        # The uplink back: once, a minute in, the run's uplink is switched off and on if it has no address.
        if [ -z "$toggled" ] && [ $((SECONDS - up)) -ge 60 ] && [ "$uplink" != unknown ] && ! uplink_up "$uplink"; then
            toggled=1
            note "R: the run's uplink ($uplink) has no address a minute after the restart: switching it off and on"
            timeout 30 adb shell svc "$uplink" disable >/dev/null 2>&1
            sleep 5
            timeout 30 adb shell svc "$uplink" enable >/dev/null 2>&1
        fi
        sleep 1
    done
    note "R: after the restart: $probes witness probes, $answered answered; $refused made with no VPN network, each refused as Android's blocking refuses; $with_vpn with ProxyFlow's VPN there; $unknown while Android could not say; the last: $out"
    if [ "$refused" = 0 ]; then
        note "R: after the restart: no probe was made while Android had no VPN network (ProxyFlow's was up first): this restart did not probe Android's blocking alone; M8's scenario B does"
    fi
    status="$(admin STATUS)"
    note "R: after the restart: Android's setting: $status"
    case "$status" in *"always-on $APP_ID lockdown true"*) ;; *) fail "R: after the restart, Android no longer has ProxyFlow as Always-on VPN with blocking: $status" ;; esac
    local start_line
    start_line="$(timeout 30 adb logcat -d -v brief -s ProxyFlowVpn 2>/dev/null | tr -d '\r' | grep -m1 'always-on start')"
    if [ -z "$start_line" ]; then fail "R: after the restart, ProxyFlow's log has no Always-on start"; fi
    if [ -n "$connected" ]; then
        note "R: after the restart: Android started ProxyFlow (${start_line:+its log: }$(echo "${start_line:-no Always-on start in its log}" | cut -c1-80)), which connected by itself ${connected} s after the new boot completed$([ -n "$toggled" ] && echo ", the uplink switched off and on"); a line through the proxy: $reply"
        note "R: VPN: $(vpn_networks | cut -c1-160)"
        note "R: ProxyFlow's session: $(timeout 30 adb logcat -d -v brief -s ProxyFlowVpn 2>/dev/null | tr -d '\r' | grep -m6 -E 'always-on start|connected |reconnect|blocked' | paste -sd ';' | cut -c1-600)"
    else
        fail "R: after the restart, no line went through the proxy within 240 s: VPN $(vpn_networks | cut -c1-120); the last reply: ${LAST_REPLY:-none sent since the restart}"
        return
    fi

    # P on the connection Android started after the restart, with no instrumentation in its process: a restart after P
    # once made API 30's emulator exit (run 41826918038), so P comes last.
    part_power
}

LAST_REPLY=""
case "$PART" in
    notifications) part_notifications ;;
    restart) part_restart ;;
esac
exit "$STATUS"
