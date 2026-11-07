#!/usr/bin/env bash
# M11: the signed App Bundle, installed on a clean emulator the way Google Play installs it (bundletool's APK set,
# built and signed from the bundle in the bundle job, from which install-apks picks this device's split APKs), then used
# as a person would, through its own screens — no test
# hooks, no debug build: onboarding, a proxy added (the lab's SOCKS5 proxy with sign-in), Connect, Android's VPN
# consent, Connected, one line through the proxy from the shell, Disconnect — with the screen recorded (the video
# Play's foreground-service declaration asks for) and screenshots taken. Checks, each a failure on its own:
#   - the installed app's signing certificate is the key's (its SHA-256 fingerprint);
#   - the installed app is the release build: not debuggable, the version name and code expected, the application
#     id expected;
#   - Connected is reached, and the line to the destination, reachable only through a proxy, came back;
#   - the proxy's log shows the tunnel; nothing reached the witness.
# Usage: scripts/m11_signed_smoke.sh <release.apks> <api> <out dir>
#   with EXPECTED_FINGERPRINT (SHA-256 of the certificate, colon-separated), EXPECTED_VERSION_NAME, EXPECTED_VERSION_CODE,
#   APPLICATION_ID, BUNDLETOOL (the jar), CLIENT_APK (the instrumented tests' APK, for the shell client only),
#   and the lab of scripts/e2e_env.sh up (/tmp/pf/e2e.properties).
set -euo pipefail

APKS=$1
API=$2
OUT=$3
mkdir -p "$OUT"
UI="python3 $(dirname "$0")/release/ui.py"
PROPS=/tmp/pf/e2e.properties
prop() { sed -n "s/^$1=//p" "$PROPS"; }

STATUS=0
fail() { echo "::error title=Signed bundle smoke (API $API)::$1"; STATUS=1; }
note() { echo "::notice title=Signed bundle smoke (API $API)::$1"; }

# 1. This device's split APKs, picked from the signed APK set and installed (what Play does for a phone).
java -jar "$BUNDLETOOL" install-apks --apks="$APKS" 2>&1 | tail -3
rm -f "$OUT/device-spec.json"
java -jar "$BUNDLETOOL" get-device-spec --output="$OUT/device-spec.json" >/dev/null 2>&1 || true
java -jar "$BUNDLETOOL" get-size total --apks="$APKS" --device-spec="$OUT/device-spec.json" > "$OUT/install-size.txt" 2>&1 || true
note "installed from the bundle's APK set: download size $(tail -1 "$OUT/install-size.txt" | tr -d '\r') bytes (MIN,MAX)"

# 2. What was installed: the certificate, debuggable, version, id (pm dump; the APK pulled for apksigner's certs).
timeout 60 adb shell dumpsys package "$APPLICATION_ID" | tr -d '\r' > "$OUT/package.txt"
version_name="$(sed -n 's/^ *versionName=//p' "$OUT/package.txt" | head -1)"
version_code="$(sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' "$OUT/package.txt" | head -1)"
[ "$version_name" = "$EXPECTED_VERSION_NAME" ] || fail "versionName is '$version_name', not '$EXPECTED_VERSION_NAME'"
[ "$version_code" = "$EXPECTED_VERSION_CODE" ] || fail "versionCode is '$version_code', not '$EXPECTED_VERSION_CODE'"
if grep -q 'DEBUGGABLE' "$OUT/package.txt"; then fail "the installed app is debuggable"; fi
base_apk="$(timeout 30 adb shell pm path "$APPLICATION_ID" | tr -d '\r' | sed -n 's/^package://p' | grep 'base.apk' | head -1)"
timeout 120 adb pull "$base_apk" "$OUT/installed-base.apk" >/dev/null
tools="$(ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/* | sort -V | tail -1)"
"$tools/apksigner" verify --print-certs "$OUT/installed-base.apk" > "$OUT/installed-certs.txt"
installed_fp="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$OUT/installed-certs.txt" | head -1 | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')"
if [ "$installed_fp" != "$EXPECTED_FINGERPRINT" ]; then
    fail "the installed app's certificate is $installed_fp, not the key's $EXPECTED_FINGERPRINT"
else
    note "signed with the key: certificate SHA-256 $installed_fp; version $version_name ($version_code); not debuggable"
fi

# 3. The shell client (the instrumented tests' APK, run with app_process as the shell user: one line to a destination).
CLIENT=/data/local/tmp/pf-client.apk
timeout 60 adb push "$CLIENT_APK" "$CLIENT" >/dev/null
client() { timeout 40 adb shell "CLASSPATH=$CLIENT app_process /system/bin app.proxyflow.e2e.ShellClient $*" 2>&1 | tr -d '\r' | grep -v '^$' | tail -1; }

# 4. The recording, and the app, used through its screens.
timeout 30 adb shell "rm -f /sdcard/pf-smoke.mp4; (screenrecord --time-limit 170 /sdcard/pf-smoke.mp4 </dev/null >/dev/null 2>&1 &)" || true
timeout 30 adb shell am start -W -n "$APPLICATION_ID/app.proxyflow.MainActivity" >/dev/null
$UI tap "Next"
$UI tap "Continue"
if [ "$API" -ge 33 ]; then $UI tap "Not now"; fi
$UI wait "Add a proxy to get started" 20
$UI screenshot "$OUT/1-home-empty.png"
$UI tap "Add proxy"
$UI wait "Add proxy" 20
$UI type "Server" "$(prop proxy.host)"
$UI type "Port" "$(prop socks.port)"
$UI tap "Proxy requires sign-in"
$UI type "Username" "$(prop socks.user)"
$UI type "Password" "$(prop socks.password)"
$UI screenshot "$OUT/2-add-proxy.png"
$UI tap "Save"
$UI wait "Connect" 20
$UI tap "Connect"
# Android's own consent dialog ("Connection request"): OK, on every level. A fresh device always asks.
$UI tap "OK" || note "Android's VPN consent dialog did not show"
if $UI wait "Connected" 60; then
    note "Connected through the lab's SOCKS5 proxy, from the app's own screens"
else
    fail "the app did not reach Connected within 60 s"
fi
$UI screenshot "$OUT/3-connected.png"
reply="$(client tcp "$(prop destination.name)" "$(prop destination.port)" "PF11-$API" 10000)"
if [ "$reply" = "REPLY PROXYFLOW-DEST PF11-$API" ]; then
    note "a line to $(prop destination.name), reachable only through a proxy, came back through the tunnel"
else
    fail "the line through the proxy did not come back: ${reply:-no reply}"
fi
witness="$(client tcp "$(prop proxy.host)" "$(prop witness.port)" "PF11-witness-$API" 3000)"
case "$witness" in REPLY*) fail "the witness, which only a packet around the proxy reaches, answered: $witness" ;; *) note "the witness was not reached ($witness)" ;; esac
$UI tap "Disconnect"
$UI wait "Not connected" 30 || fail "the app did not show Not connected after Disconnect"
$UI screenshot "$OUT/4-disconnected.png"
sleep 2
timeout 30 adb shell pkill -INT screenrecord || true
sleep 3
timeout 120 adb pull /sdcard/pf-smoke.mp4 "$OUT/smoke.mp4" >/dev/null 2>&1 || note "no screen recording came back"

# 5. The lab's logs: Dante's shows the tunnel to the destination; the witness heard nothing since the lab came up; the
#    phone's uplink resolver was never asked for the destination's name (the proxy resolves it).
sudo chmod -R a+rX /tmp/pf 2>/dev/null || true
if tail -n +"$(( $(sed -n 's/^dns-uplink.log=//p' /tmp/pf/log-baseline.properties) + 1 ))" /tmp/pf/dns-uplink.log | grep -q "$(prop destination.name)"; then
    fail "the phone's uplink resolver was asked for $(prop destination.name): a name looked up around the proxy"
fi
if grep -q "$(prop destination.name)" /tmp/pf/danted.log 2>/dev/null; then note "Dante's log shows the tunnel to $(prop destination.name)"; else fail "Dante's log shows no tunnel to the destination"; fi
heard=$(( $(wc -l < /tmp/pf/witness.log) - $(sed -n 's/^witness.log=//p' /tmp/pf/log-baseline.properties) ))
if [ "$heard" -gt 0 ]; then fail "the witness's log has $heard new line(s)"; fi
# The app is removed: a clean device for the next run.
timeout 60 adb uninstall "$APPLICATION_ID" >/dev/null 2>&1 || true
timeout 30 adb shell rm -f "$CLIENT" /sdcard/pf-smoke.mp4 /sdcard/pf-ui.xml >/dev/null 2>&1 || true
exit "$STATUS"
