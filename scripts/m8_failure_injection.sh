#!/usr/bin/env bash
# M8: failure injection on the emulator (docs/m8/failure-injection.md), run by scripts/e2e_device.sh after the
# instrumented tests, with the app and its test APK installed again (Gradle removed them). What no test inside the
# app's process can do to it is done from here, and what got out meanwhile is checked from outside:
#
#   A  the app's process is killed while connected, as Android's low-memory killer does (SIGKILL): Android removes
#      the VPN, and without its Always-on VPN with "Block connections without VPN" apps use the normal network — the
#      shell then reaches the lab's witness directly. The next start of the app finds the interruption and logs it.
#   B  Always-on VPN with "Block connections without VPN" turned on for ProxyFlow, as a user does in Android's VPN
#      settings (here through the lab's device owner, the test APK's LabDeviceAdmin): Android starts ProxyFlow, which
#      connects the chosen profile and reads that blocking is on; its process is killed; while it is gone the witness
#      must never be reached; the next start has kept what Android said and connects again. Afterwards the setting is
#      turned off and the witness is reached directly again.
#   C  an exception with a planted sign-in in its message crashes the app while connected: Android's crash report
#      (dropbox) must not have the planted secret; the next start finds a crash.
#
# The witness (scripts/e2e_env.sh) listens on the runner's loopback only: the phone reaches it directly as
# 10.0.2.2:<witness.port>, but never through a proxy (the proxies would connect to 10.0.2.2 itself, where nothing
# listens), so every line it logs got out around ProxyFlow. Nothing here looks a lab name up or contacts a lab
# destination while ProxyFlow is not connected: scripts/m5_evidence.py and m6_evidence.py check the whole run's DNS
# and captures for that. Each step is noted in m8-steps.txt and in logcat (tag ProxyFlowM8), with the phone's time;
# scripts/m8_evidence.py checks them against the witness's log, the captures and the crash report.
#
# Usage: m8_failure_injection.sh <api level> <out dir>
set -uo pipefail

API="$1"
OUT="$2"
mkdir -p "$OUT"
STEPS="$OUT/m8-steps.txt"
: > "$STEPS"
LOGCAT="$OUT/logcat-m8.txt"

APP_ID="$(sed -n 's/^proxyflow.applicationId=//p' gradle.properties | tr -d '[:space:]').debug"
TEST_ID="$APP_ID.test"
RUNNER="$TEST_ID/app.proxyflow.e2e.E2eTestRunner"
STEP_CLASS=app.proxyflow.e2e.KillSwitchEndToEndTest
APP_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
CLIENT=/data/local/tmp/pf-client.apk
WITNESS_PORT="$(sed -n 's/^witness.port=//p' /tmp/pf/e2e.properties)"
DESTINATION_NAME="$(sed -n 's/^destination.name=//p' /tmp/pf/e2e.properties)"
DESTINATION_PORT="$(sed -n 's/^destination.port=//p' /tmp/pf/e2e.properties)"
STATUS=0

# The phone's clock (logcat's, which the phone's own captures share), in seconds.
phone_time() { timeout 10 adb shell 'echo $EPOCHREALTIME' 2>/dev/null | tr -d '\r'; }

note() { # <text>: one line in m8-steps.txt and in logcat
    echo "$(phone_time) $*" >> "$STEPS"
    timeout 10 adb shell "log -t ProxyFlowM8 'step $(echo "$*" | tr -d "'")'" >/dev/null 2>&1 || true
}

fail() { # <text>
    note "FAILED $*"
    echo "::error title=M8 failure injection (API $API)::$*"
    STATUS=1
}

# The shell client's one line (app/src/androidTest/java/app/proxyflow/e2e/ShellClient.java), as the shell user, whom
# Android routes into a VPN.
client() { timeout 40 adb shell "CLASSPATH=$CLIENT app_process /system/bin app.proxyflow.e2e.ShellClient $*" 2>&1 | tr -d '\r' | grep -v '^$' | tail -1; }

lines() { wc -l < "$LOGCAT" 2>/dev/null || echo 0; }

wait_log() { # <after line> <seconds> <pattern>: the first logcat line after <after line> matching <pattern>; looks once at least
    local after="$1" end=$((SECONDS + $2)) found
    while true; do
        found="$(tail -n "+$((after + 1))" "$LOGCAT" 2>/dev/null | grep -m1 -E "$3")"
        if [ -n "$found" ]; then
            echo "$found"
            return 0
        fi
        [ "$SECONDS" -lt "$end" ] || return 1
        sleep 0.5
    done
}

step_output() { tr -d '\r' < "$OUT/m8-$1-$2.txt" 2>/dev/null; }

instrument() { # <step> <token> <expect> [seconds]: one step of KillSwitchEndToEndTest; succeeds when it passed
    timeout "${4:-150}" adb shell am instrument -w -e m8 "$1" -e token "$2" -e expect "$3" -e class "$STEP_CLASS#$1" "$RUNNER" \
        > "$OUT/m8-$1-$2.txt" 2>&1
    step_output "$1" "$2" | grep -q '^OK (1 test)'
}

step_failure() { step_output "$1" "$2" | grep -m4 -E 'Error|Exception|expected|FAIL' | paste -sd ' ' | cut -c1-700; }

admin() { # <command> [extras...]: what the lab's device owner answered (LabAdminCommands)
    timeout 30 adb shell am broadcast --include-stopped-packages -n "$TEST_ID/app.proxyflow.e2e.LabAdminCommands" \
        -a "app.proxyflow.e2e.$1" "${@:2}" 2>&1 | tr -d '\r' | sed -n 's/^Broadcast completed: result=\([0-9]*\), data="\(.*\)"$/result \1: \2/p'
}

app_pid() { timeout 10 adb shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

alive() { timeout 10 adb shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' | tr ' ' '\n' | grep -qx "$1"; }

ended() { # <pid>: the process is gone within 5 s
    for _ in $(seq 1 10); do
        alive "$1" || return 0
        sleep 0.5
    done
    return 1
}

# SIGKILL, as the low-memory killer ends a process: from the app's own user (run-as), else as root (su: Android 10's
# run-as can't signal the app), else a force-stop. Prints how it ended, and what Android should then say of it
# (ExitReason keys: Android 10 says nothing).
KILLED_BY=""
EXPECT=""
kill_app() { # <pid>
    local pid="$1" said=""
    said="$(timeout 20 adb shell run-as "$APP_ID" kill -9 "$pid" 2>&1 | tr -d '\r')"
    if ended "$pid"; then
        KILLED_BY="SIGKILL (run-as)"
    else
        said="$said / $(timeout 20 adb shell su 0 kill -9 "$pid" 2>&1 | tr -d '\r')"
        if ended "$pid"; then
            KILLED_BY="SIGKILL (su)"
        else
            timeout 30 adb shell am force-stop "$APP_ID" >/dev/null 2>&1
            if ended "$pid"; then KILLED_BY="force-stop (SIGKILL refused: $said)"; else KILLED_BY="NOT KILLED ($said)"; fi
        fi
    fi
    if [ "$API" -lt 30 ]; then
        EXPECT=unknown
    elif [ "${KILLED_BY#force-stop}" != "$KILLED_BY" ]; then
        EXPECT=force_stopped
    else
        EXPECT=killed,other,low_memory
    fi
}

witness() { # <label>: one line to the witness; what came back
    client tcp 10.0.2.2 "$WITNESS_PORT" "PF8-$1" 3000
}

vpn_networks() { timeout 20 adb shell dumpsys connectivity 2>/dev/null | tr -d '\r' | grep -E 'VPN' | grep -E 'NetworkAgentInfo' | head -2 | cut -c1-240; }

# --- setup ---------------------------------------------------------------------------------------------------------
if [ ! -f "$APP_APK" ] || [ ! -f "$TEST_APK" ]; then
    timeout 900 ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -q > "$OUT/m8-build.txt" 2>&1 || fail "the APKs did not build"
fi
timeout 120 adb install -r -t -g "$APP_APK" > "$OUT/m8-install.txt" 2>&1 || fail "the app did not install: $(tail -1 "$OUT/m8-install.txt")"
timeout 120 adb install -r -t "$TEST_APK" >> "$OUT/m8-install.txt" 2>&1 || fail "the test APK did not install: $(tail -1 "$OUT/m8-install.txt")"
timeout 60 adb push "$TEST_APK" "$CLIENT" >/dev/null 2>&1 && timeout 10 adb shell chmod 644 "$CLIENT"
adb logcat -v threadtime -v UTC -T 1 > "$LOGCAT" 2>&1 &
LOGCAT_PID=$!
sleep 1
note "M8 failure injection on API $API: app $APP_ID, witness 10.0.2.2:$WITNESS_PORT, su $(timeout 10 adb shell 'command -v su || echo none' | tr -d '\r')"
note "before: witness $(witness before)"

if instrument prepare P1 -; then
    note "prepared: $(step_output prepare P1 | grep -m1 'OK (')"
else
    fail "the prepare step failed: $(step_failure prepare P1)"
fi

# Connects through the chosen profile in an instrumented step that then waits (to be killed, or after crashing):
# CONNECTED_PID is its process, INSTRUMENT_PID the am instrument that runs it. Not in a subshell: it sets both.
CONNECTED_PID=""
INSTRUMENT_PID=""
connect_then() { # <label> <step> <token>
    local start line
    start="$(lines)"
    CONNECTED_PID=""
    timeout 300 adb shell am instrument -w -e m8 "$2" -e token "$3" -e class "$STEP_CLASS#$2" "$RUNNER" > "$OUT/m8-$2-$3.txt" 2>&1 &
    INSTRUMENT_PID=$!
    if ! line="$(wait_log "$start" 120 "ProxyFlowM8.*$3 connected pid [0-9]+")"; then
        fail "$1: never connected: $(step_failure "$2" "$3")"
        kill "$INSTRUMENT_PID" 2>/dev/null
        return 1
    fi
    CONNECTED_PID="$(echo "$line" | sed -n 's/.*connected pid \([0-9]*\).*/\1/p')"
}

# --- A: killed while connected, no Always-on VPN --------------------------------------------------------------------
scenario_a() {
    local token="A$RANDOM" pid out n
    note "A: connecting through the chosen profile, then the process is killed (no Always-on VPN)"
    connect_then A connectAndHold "$token" || return
    pid="$CONNECTED_PID"
    note "A: VPN before the kill: $(vpn_networks | paste -sd ' ')"
    note "A: killing pid $pid while connected"
    kill_app "$pid"
    note "A: killed pid $pid by $KILLED_BY"
    case "$KILLED_BY" in "NOT KILLED"*) fail "A: the process could not be killed: $KILLED_BY"; return ;; esac
    wait "$INSTRUMENT_PID" 2>/dev/null
    note "A: the instrumentation ended: $(step_output connectAndHold "$token" | grep -v '^$' | tail -2 | paste -sd ' ')"
    # Android removes the VPN with the process; then apps use the normal network.
    for n in $(seq 1 20); do
        out="$(witness "A-$token-$n")"
        note "A: witness probe $n: $out"
        case "$out" in REPLY*) break ;; esac
        sleep 0.5
    done
    case "$out" in REPLY*) ;; *) fail "A: the witness was never reached after the kill (the negative control): $out" ;; esac
    note "A: VPN after the kill: $(vpn_networks | paste -sd ' ')"
    if instrument afterProcessDeath "$token" "$EXPECT" 120; then
        note "A: the next start: passed ($(step_output afterProcessDeath "$token" | grep -m1 -o 'OK (.*'); expected $EXPECT)"
    else
        fail "A: the next start: $(step_failure afterProcessDeath "$token")"
    fi
    note "A: found: $(grep -E "ProxyFlowM8.*$token interruption:" "$LOGCAT" | tail -1 | sed 's/.*interruption:/interruption:/')"
}

# --- C: a crash with a planted secret in its message ----------------------------------------------------------------
scenario_c() {
    local token="C$RANDOM" pid dropbox
    note "C: connecting, then an exception with a planted sign-in in its message crashes the app"
    connect_then C crashWithPlantedSecret "$token" || return
    pid="$CONNECTED_PID"
    if ended "$pid" || ended "$pid"; then note "C: pid $pid crashed"; else fail "C: pid $pid did not end after the crash"; fi
    wait "$INSTRUMENT_PID" 2>/dev/null
    note "C: the instrumentation ended: $(step_output crashWithPlantedSecret "$token" | grep -v '^$' | grep -m2 -E 'shortMsg|INSTRUMENTATION_CODE' | paste -sd ' ' | sed "s/PF8SECRET$token/<planted secret>/g")"
    sleep 2
    dropbox="$OUT/m8-dropbox-$token.txt"
    timeout 60 adb shell dumpsys dropbox --print data_app_crash 2>/dev/null | tr -d '\r' > "$dropbox"
    if ! grep -q "PF8-planted-$token" "$dropbox"; then
        fail "C: Android's crash report of the planted exception was not found ($(wc -l < "$dropbox") lines of crash reports)"
    elif grep -q "PF8SECRET$token" "$dropbox"; then
        fail "C: Android's crash report has the planted secret"
    else
        note "C: Android's crash report names the exception, without the planted secret: $(grep -m1 "PF8-planted-$token" "$dropbox" | cut -c1-200)"
    fi
    # Android writes the uncaught exception to the phone's log itself before any app code runs (its pre-handler).
    note "C: the phone's log lines with the planted secret (Android's own, before any app code): $(grep -c "PF8SECRET$token" "$LOGCAT")"
    local expect=crash
    [ "$API" -lt 30 ] && expect=unknown
    if instrument afterProcessDeath "$token" "$expect" 120; then
        note "C: the next start: passed (expected $expect)"
    else
        fail "C: the next start: $(step_failure afterProcessDeath "$token")"
    fi
    note "C: found: $(grep -E "ProxyFlowM8.*$token interruption:" "$LOGCAT" | tail -1 | sed 's/.*interruption:/interruption:/')"
}

# --- B: Always-on VPN with Block connections without VPN ----------------------------------------------------------
scenario_b() {
    local token="B$RANDOM" start line pid out n back=""
    note "B: device owner: $(timeout 30 adb shell dpm set-device-owner "$TEST_ID/app.proxyflow.e2e.LabDeviceAdmin" 2>&1 | tr -d '\r' | paste -sd ' ')"
    start="$(lines)"
    out="$(admin ALWAYS_ON --es package "$APP_ID" --ez lockdown true)"
    note "B: Always-on VPN with Block connections without VPN: $out"
    case "$out" in *"lockdown true"*) ;; *) fail "B: Android did not take the setting: $out"; return ;; esac
    if ! line="$(wait_log "$start" 90 "ProxyFlowVpn *: connected ")"; then
        fail "B: Android's Always-on VPN start did not connect ProxyFlow within 90 s: $(tail -n "+$((start + 1))" "$LOGCAT" | grep -E 'ProxyFlowVpn' | tail -5 | paste -sd ' ' | cut -c1-600)"
        return
    fi
    pid="$(app_pid)"
    note "B: connected by Always-on VPN (pid $pid): $(echo "$line" | cut -c1-120)"
    if line="$(wait_log "$start" 10 "ProxyFlowVpn *: system kill switch: ")"; then
        note "B: what ProxyFlow read from Android: $(echo "$line" | sed 's/.*system kill switch: /system kill switch: /')"
    else
        fail "B: ProxyFlow did not read Android's kill switch while connected"
    fi
    note "B: a line to the destination through the proxy: $(client tcp "$DESTINATION_NAME" "$DESTINATION_PORT" "PF8-B-up-$token" 5000)"
    note "B: VPN: $(vpn_networks | paste -sd ' ')"
    start="$(lines)"
    note "B: killing pid $pid (Always-on VPN, Block connections without VPN)"
    kill_app "$pid"
    note "B: killed pid $pid by $KILLED_BY"
    case "$KILLED_BY" in "NOT KILLED"*) fail "B: the process could not be killed: $KILLED_BY" ;; esac
    for n in $(seq 1 40); do
        out="$(witness "B-$token-$n")"
        note "B: witness probe $n with ProxyFlow gone: $out"
        case "$out" in REPLY*) fail "B: the witness was reached while Block connections without VPN was on: $out" ;; esac
        if line="$(wait_log "$start" 0 "ProxyFlowVpn *: connected ")"; then
            back="$line"
            break
        fi
        sleep 0.5
    done
    if [ -n "$back" ]; then
        note "B: Android started ProxyFlow again (pid $(app_pid)): $(echo "$back" | cut -c1-120)"
    else
        note "B: Android did not start ProxyFlow again within the probes"
    fi
    if instrument afterLockdownKill "$token" "$EXPECT" 150; then
        note "B: the next start: passed (kept what Android said; connected again while Android blocks; expected $EXPECT)"
    else
        fail "B: the next start: $(step_failure afterLockdownKill "$token")"
    fi
    note "B: found: $(grep -E "ProxyFlowM8.*$token (interruption|kept kill switch):" "$LOGCAT" | sed 's/.*\(interruption\|kept kill switch\):/\1:/' | paste -sd ' ')"
    note "B: status: $(admin STATUS)"
    note "B: Always-on VPN off: $(admin ALWAYS_ON --es package "''")"
    timeout 30 adb shell am force-stop "$APP_ID" >/dev/null 2>&1
    note "B: ProxyFlow force-stopped"
    for n in $(seq 1 20); do
        out="$(witness "B-after-$token-$n")"
        note "B: witness probe $n after the setting was turned off: $out"
        case "$out" in REPLY*) break ;; esac
        sleep 0.5
    done
    case "$out" in REPLY*) ;; *) fail "B: the witness was not reached once the setting was off: $out" ;; esac
    note "B: release: $(admin RELEASE)"
}

# B before C: a crash marks an app Android may then be slower to start in the background (as Always-on VPN does).
scenario_a
scenario_b
scenario_c

if [ "$API" -ge 30 ]; then
    timeout 30 adb shell dumpsys activity exit-info "$APP_ID" 2>/dev/null | tr -d '\r' | grep -E 'ApplicationExitInfo|reason=|timestamp=' | head -60 > "$OUT/m8-exit-info.txt" || true
fi
note "M8 failure injection done (status $STATUS)"
sleep 1
kill "$LOGCAT_PID" 2>/dev/null || true
# The steps in the job's log too: the evidence reads them through the API, which can't download artifacts.
TOKEN="$(openssl rand -hex 16 2>/dev/null || echo "m8$RANDOM$RANDOM")"
echo "::group::M8 failure injection steps (API $API)"
echo "::stop-commands::$TOKEN" # what the phone printed, with workflow commands off
cat "$STEPS"
echo "::$TOKEN::"
echo "::endgroup::"
timeout 60 adb uninstall "$TEST_ID" >/dev/null 2>&1 || true
timeout 60 adb uninstall "$APP_ID" >/dev/null 2>&1 || true
timeout 10 adb shell rm -f "$CLIENT" >/dev/null 2>&1 || true
exit "$STATUS"
