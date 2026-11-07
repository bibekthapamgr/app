#!/usr/bin/env bash
# M11: the signed App Bundle, installed on a clean emulator the way Google Play installs it (bundletool's APK set,
# built and signed from the bundle in the bundle job, from which install-apks picks this device's split APKs), then used
# as a person would, through its own screens — no test hooks, no debug build: onboarding, a proxy added (the lab's SOCKS5
# proxy with sign-in), Connect, ProxyFlow's own VPN explainer (board B9), Android's consent, Connected, one line through
# the proxy from the shell, Disconnect — with the screen recorded and screenshots taken. Checks, each a failure on its own:
#   - the package installed is the application id expected, its signing certificate the key's (its SHA-256
#     fingerprint), not debuggable, the version name and code expected;
#   - Connected is reached, and the line to the destination, reachable only through a proxy, came back;
#   - the lab's logs since the lab came up: the destination's connections equal Dante's tunnels to it (exactly one,
#     the line); the witness (reached only around the proxy) heard nothing; the uplink resolver was never asked for the
#     destination's name.
# A step that fails stops the flow with an error annotation, a screenshot and the screen's texts; the recording is
# pulled and the device cleaned whatever happened.
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
baseline() { sed -n "s/^$1=//p" /tmp/pf/log-baseline.properties; }
since() { tail -n +"$(( $(baseline "$1") + 1 ))" "/tmp/pf/$1"; }

STATUS=0
STEP="setup"
fail() { echo "::error title=Signed bundle smoke (API $API)::$1"; STATUS=1; }
note() { echo "::notice title=Signed bundle smoke (API $API)::$1"; }
step() { STEP="$1"; echo "--- $1"; }
CLIENT=/data/local/tmp/pf-client.apk
RECORDING=0

cleanup() {
    local code=$?
    trap - ERR EXIT
    if [ "$code" -ne 0 ] && [ "$STATUS" -eq 0 ]; then
        echo "::error title=Signed bundle smoke (API $API)::stopped at: $STEP (exit $code)"
        STATUS=1
    fi
    if [ "$code" -ne 0 ]; then
        $UI screenshot "$OUT/failed-at-$(tr -c 'A-Za-z0-9' '-' <<<"$STEP").png" 2>/dev/null || true
        echo "the screen showed: $($UI texts 2>/dev/null | tr '\n' '|' | cut -c1-600)"
    fi
    if [ "$RECORDING" = 1 ]; then
        timeout 30 adb shell pkill -INT screenrecord >/dev/null 2>&1 || true
        sleep 3
        timeout 120 adb pull /sdcard/pf-smoke.mp4 "$OUT/smoke.mp4" >/dev/null 2>&1 || note "no screen recording came back"
    fi
    timeout 60 adb uninstall "$APPLICATION_ID" >/dev/null 2>&1 || true
    timeout 30 adb shell rm -f "$CLIENT" /sdcard/pf-smoke.mp4 /sdcard/pf-ui.xml >/dev/null 2>&1 || true
    exit "$STATUS"
}
trap cleanup EXIT
trap 'exit 1' ERR

# 1. This device's split APKs, picked from the signed APK set and installed (what Play does for a phone).
step "install from the APK set"
if ! java -jar "$BUNDLETOOL" install-apks --apks="$APKS" > "$OUT/install.txt" 2>&1; then
    fail "bundletool install-apks failed: $(tail -3 "$OUT/install.txt" | tr '\n' ' ')"
    exit 1
fi
rm -f "$OUT/device-spec.json"
java -jar "$BUNDLETOOL" get-device-spec --output="$OUT/device-spec.json" >/dev/null 2>&1 || true
java -jar "$BUNDLETOOL" get-size total --apks="$APKS" --device-spec="$OUT/device-spec.json" > "$OUT/install-size.txt" 2>&1 || true
note "installed from the bundle's APK set: download size $(tail -1 "$OUT/install-size.txt" | tr -d '\r') bytes (MIN,MAX)"

# 2. What was installed: the package, the certificate, debuggable, version (pm; the APK pulled for apksigner's certs).
step "the installed package"
if ! timeout 30 adb shell pm list packages | tr -d '\r' | grep -qx "package:$APPLICATION_ID"; then
    fail "no package $APPLICATION_ID is installed; third-party packages: $(timeout 30 adb shell pm list packages -3 | tr -d '\r' | tr '\n' ' ')"
    exit 1
fi
timeout 60 adb shell dumpsys package "$APPLICATION_ID" | tr -d '\r' > "$OUT/package.txt" || { fail "dumpsys package failed"; exit 1; }
version_name="$(sed -n 's/^ *versionName=//p' "$OUT/package.txt" | head -1)"
version_code="$(sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' "$OUT/package.txt" | head -1)"
[ "$version_name" = "$EXPECTED_VERSION_NAME" ] || fail "versionName is '$version_name', not '$EXPECTED_VERSION_NAME'"
[ "$version_code" = "$EXPECTED_VERSION_CODE" ] || fail "versionCode is '$version_code', not '$EXPECTED_VERSION_CODE'"
if grep -q 'DEBUGGABLE' "$OUT/package.txt"; then fail "the installed app is debuggable"; fi
base_apk="$(timeout 30 adb shell pm path "$APPLICATION_ID" | tr -d '\r' | sed -n 's/^package://p' | grep 'base.apk' | head -1)"
splits="$(timeout 30 adb shell pm path "$APPLICATION_ID" | tr -d '\r' | grep -c '^package:' || true)"
timeout 120 adb pull "$base_apk" "$OUT/installed-base.apk" >/dev/null || { fail "could not pull $base_apk"; exit 1; }
tools="$(find "${ANDROID_HOME:-/usr/local/lib/android/sdk}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
"$tools/apksigner" verify --print-certs "$OUT/installed-base.apk" > "$OUT/installed-certs.txt"
installed_fp="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$OUT/installed-certs.txt" | head -1 | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')"
if [ "$installed_fp" != "$EXPECTED_FINGERPRINT" ]; then
    fail "the installed app's certificate is $installed_fp, not the key's $EXPECTED_FINGERPRINT"
else
    note "$APPLICATION_ID installed as $splits APK(s), signed with the key: certificate SHA-256 $installed_fp; version $version_name ($version_code); not debuggable"
fi

# 3. The shell client (the instrumented tests' APK, run with app_process as the shell user: one line to a destination).
step "the shell client"
timeout 60 adb push "$CLIENT_APK" "$CLIENT" >/dev/null
client() { timeout 40 adb shell "CLASSPATH=$CLIENT app_process /system/bin app.proxyflow.e2e.ShellClient $*" 2>&1 | tr -d '\r' | grep -v '^$' | tail -1 || true; }

# 4. The recording (screenrecord's longest, 180 s: the owner trims the Play video to its 90 s), and the app, used
#    through its screens.
step "onboarding"
if timeout 30 adb shell "rm -f /sdcard/pf-smoke.mp4; (screenrecord --time-limit 180 /sdcard/pf-smoke.mp4 </dev/null >/dev/null 2>&1 &)"; then RECORDING=1; fi
timeout 30 adb shell am start -W -n "$APPLICATION_ID/app.proxyflow.MainActivity" >/dev/null
$UI tap "Next"
$UI tap "Continue"
if [ "$API" -ge 33 ]; then $UI tap "Not now"; fi
$UI wait "Add a proxy to get started" 20
$UI screenshot "$OUT/1-home-empty.png"
step "add a proxy"
$UI tap "Add proxy"
$UI wait "Protocol" 20
$UI type "Server" "$(prop proxy.host)"
$UI type "Port" "$(prop socks.port)"
$UI tap "Proxy requires sign-in"
$UI type "Username" "$(prop socks.user)"
$UI type "Password" "$(prop socks.password)"
$UI screenshot "$OUT/2-add-proxy.png"
$UI tap "Save"
$UI wait "Connect" 20
step "connect"
$UI tap "Connect"
# ProxyFlow's own explainer of the VPN permission (board B9: "Allow a VPN connection", Continue), then Android's own
# consent dialog ("Connection request", OK): both always show on a fresh device.
$UI tap "Continue"
$UI tap "OK"
if $UI wait "Connected" 90; then
    note "Connected through the lab's SOCKS5 proxy, from the app's own screens"
else
    fail "the app did not reach Connected within 90 s"
fi
$UI screenshot "$OUT/3-connected.png"
step "a line through the tunnel"
reply="$(client tcp "$(prop destination.name)" "$(prop destination.port)" "PF11-$API" 10000)"
if [ "$reply" = "REPLY PROXYFLOW-DEST PF11-$API" ]; then
    note "a line to $(prop destination.name), reachable only through a proxy, came back through the tunnel"
else
    fail "the line through the proxy did not come back: ${reply:-no reply}"
fi
witness="$(client tcp "$(prop proxy.host)" "$(prop witness.port)" "PF11-witness-$API" 3000)"
case "$witness" in
    ERROR*) note "the witness was not reached ($witness)" ;;
    REPLY*) fail "the witness, which only a packet around the proxy reaches, answered: $witness" ;;
    *) fail "the witness probe gave no usable answer: ${witness:-nothing}" ;;
esac
step "disconnect"
$UI tap "Disconnect"
$UI wait "Not connected" 30 || fail "the app did not show Not connected after Disconnect"
$UI screenshot "$OUT/4-disconnected.png"
sleep 2

# 5. The lab's logs since the lab came up: the destination's connections equal Dante's tunnels to it, and are the line;
#    the witness heard nothing; the phone's uplink resolver was never asked for the destination's name.
step "the lab's logs"
sudo chmod -R a+rX /tmp/pf 2>/dev/null || true
destination="$(prop destination.name)"
connections="$(since destination.log | grep -c . || true)"
tunnels="$(since danted.log | grep -E "tcp/connect \[: .*${destination//./\\.}\.$(prop destination.port)\b" | grep -vc 'block(' || true)"
if [ "$connections" = 1 ] && [ "$tunnels" = 1 ]; then
    note "the destination saw 1 connection and Dante opened 1 tunnel to it: the line, through the proxy"
else
    fail "the destination saw $connections connection(s) and Dante opened $tunnels tunnel(s) to it; expected 1 and 1"
fi
heard="$(since witness.log | grep -c . || true)"
[ "$heard" = 0 ] || fail "the witness's log has $heard new line(s)"
if since dns-uplink.log | grep -Eq "query\[[A-Z]+\] ${destination//./\\.} "; then
    fail "the phone's uplink resolver was asked for $destination: a name looked up around the proxy"
fi
if [ "$STATUS" = 0 ]; then note "the lab's logs: 1 connection = 1 tunnel; the witness heard nothing; no lookup of $destination on the uplink"; fi
