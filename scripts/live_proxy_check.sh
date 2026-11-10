#!/usr/bin/env bash
# The live proxy check: the signed release (the universal APK a phone outside Play installs — the same bytes), on a
# clean emulator, used the way a person uses it with a proxy of their own on the real internet — no lab: onboarding,
# the proxy added from the form (its protocol, address, port and sign-in from the environment, never from a command
# line of this script's own beyond the moment the form is typed into, as the release smoke does), Test connection,
# Connect, ProxyFlow's explainer and Android's consent, Connected, then from the shell (outside the app, inside the VPN,
# as every other app is): an HTTPS page and a plain-HTTP page fetched the way a browser fetches them, each from a site
# that answers with the address it saw — which must be the proxy's side, never the runner's own — the phone's
# Logs screen, Disconnect; then the emulator's own capture of its uplink read by scripts/live_proxy_evidence.py:
# while Connected, nothing left the phone for anywhere but the proxy.
# What it proves: this proxy works with this build as shipped, and the phone's traffic went through it and nowhere
# else. What it cannot prove: anything about the owner's phone, their network or their provider's other servers.
# The proxy's address, port, username and password are masked in the job's log (the workflow adds the masks before
# this script runs); the screenshots kept are of screens that never show them (the form is not photographed).
# Usage: scripts/live_proxy_check.sh <universal.apk> <api> <out dir>
#   with PF_LIVE_PROXY_PROTOCOL (HTTP|SOCKS5), PF_LIVE_PROXY_HOST, PF_LIVE_PROXY_PORT, PF_LIVE_PROXY_USERNAME,
#   PF_LIVE_PROXY_PASSWORD (empty username: no sign-in), EXPECTED_FINGERPRINT, EXPECTED_VERSION_NAME,
#   EXPECTED_VERSION_CODE, APPLICATION_ID, CLIENT_APK (the instrumented tests' APK, for the shell client only),
#   UPLINK_PCAP (the emulator's -tcpdump file) and RUNNER_PUBLIC_IP (what the runner itself looks like from outside,
#   so a page that shows the runner's address is seen as the leak it would be).
set -euo pipefail

APK=$1
API=$2
OUT=$3
mkdir -p "$OUT"
UI="python3 $(dirname "$0")/release/ui.py"
EVIDENCE="python3 $(dirname "$0")/live_proxy_evidence.py"

STATUS=0
STEP="setup"
fail() { echo "::error title=Live proxy check (API $API)::$1"; STATUS=1; }
note() { echo "::notice title=Live proxy check (API $API)::$1"; }
step() { STEP="$1"; echo "--- $1"; }
CLIENT=/data/local/tmp/pf-client.apk
CONNECTED_AT=""
DISCONNECTED_AT=""

cleanup() {
    local code=$?
    trap - ERR EXIT
    if [ "$code" -ne 0 ] && [ "$STATUS" -eq 0 ]; then
        echo "::error title=Live proxy check (API $API)::stopped at: $STEP (exit $code)"
        STATUS=1
    fi
    if [ "$code" -ne 0 ]; then
        $UI screenshot "$OUT/failed-at-$(tr -c 'A-Za-z0-9' '-' <<<"$STEP").png" 2>/dev/null || true
        echo "the screen showed: $($UI texts 2>/dev/null | tr '\n' '|' | cut -c1-600)"
    fi
    timeout 60 adb uninstall "$APPLICATION_ID" >/dev/null 2>&1 || true
    timeout 30 adb shell rm -f "$CLIENT" /sdcard/pf-ui.xml >/dev/null 2>&1 || true
    exit "$STATUS"
}
trap cleanup EXIT
trap 'exit 1' ERR

[ -n "${PF_LIVE_PROXY_HOST:-}" ] && [ -n "${PF_LIVE_PROXY_PORT:-}" ] || { fail "no proxy given (PF_LIVE_PROXY_HOST/PORT)"; exit 1; }
PROTOCOL="${PF_LIVE_PROXY_PROTOCOL:-HTTP}"
case "$PROTOCOL" in HTTP|SOCKS5) ;; *) fail "PF_LIVE_PROXY_PROTOCOL must be HTTP or SOCKS5, not '$PROTOCOL'"; exit 1 ;; esac

# 1. The universal APK installed (what a phone outside Play installs; the same signed bytes).
step "install"
timeout 180 adb install -r "$APK" > "$OUT/install.txt" 2>&1 || { fail "adb install failed: $(tail -2 "$OUT/install.txt" | tr '\n' ' ')"; exit 1; }

# 2. What was installed: the package, the certificate, debuggable, version — as the release smoke checks them.
step "the installed package"
packages="$(timeout 30 adb shell pm list packages | tr -d '\r')"
grep -qE "^package:${APPLICATION_ID}[[:space:]]*$" <<<"$packages" || { fail "no package $APPLICATION_ID is installed"; exit 1; }
timeout 60 adb shell dumpsys package "$APPLICATION_ID" | tr -d '\r' > "$OUT/package.txt" || { fail "dumpsys package failed"; exit 1; }
version_name="$(sed -n 's/^ *versionName=//p' "$OUT/package.txt" | head -1)"
version_code="$(sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' "$OUT/package.txt" | head -1)"
[ "$version_name" = "$EXPECTED_VERSION_NAME" ] || fail "versionName is '$version_name', not '$EXPECTED_VERSION_NAME'"
[ "$version_code" = "$EXPECTED_VERSION_CODE" ] || fail "versionCode is '$version_code', not '$EXPECTED_VERSION_CODE'"
if grep -q 'DEBUGGABLE' "$OUT/package.txt"; then fail "the installed app is debuggable (dumpsys)"; fi
base_apk="$(timeout 30 adb shell pm path "$APPLICATION_ID" | tr -d '\r' | sed -n 's/^package://p' | grep 'base.apk' | head -1)"
timeout 120 adb pull "$base_apk" "$OUT/installed-base.apk" >/dev/null || { fail "could not pull $base_apk"; exit 1; }
tools="$(find "${ANDROID_HOME:-/usr/local/lib/android/sdk}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
"$tools/apksigner" verify --print-certs "$OUT/installed-base.apk" > "$OUT/installed-certs.txt"
digests="$(grep -oE 'certificate SHA-256 digest: [0-9a-f]{64}' "$OUT/installed-certs.txt" | grep -oE '[0-9a-f]{64}' | sort -u || true)"
[ "$(printf '%s' "$digests" | grep -c . || true)" = 1 ] || fail "the installed app has $(printf '%s' "$digests" | grep -c . || true) signing certificates, not one"
installed_fp="$(printf '%s' "$digests" | head -1 | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')"
if [ "$installed_fp" != "$EXPECTED_FINGERPRINT" ]; then
    fail "the installed app's certificate is $installed_fp, not the upload key's $EXPECTED_FINGERPRINT"
else
    note "$APPLICATION_ID $version_name ($version_code) installed from the universal APK, signed with the upload key (certificate SHA-256 $installed_fp), not debuggable"
fi
rm -f "$OUT/installed-base.apk"

# 3. The shell client (the instrumented tests' APK, run with app_process as the shell user).
step "the shell client"
timeout 60 adb push "$CLIENT_APK" "$CLIENT" >/dev/null
client() { timeout 60 adb shell "CLASSPATH=$CLIENT app_process /system/bin app.proxyflow.e2e.ShellClient $*" 2>&1 | tr -d '\r' | grep -v '^$' | tail -1 || true; }

# The phone's uplink: mobile data, with Wi-Fi off. From API 30 on the emulator's Wi-Fi is a network device of its own,
# which the emulator's own capture (-tcpdump) does not record (M10-89; scripts/e2e_device.sh): with Wi-Fi up the phone
# prefers it and the capture would see nothing of the tunnel. Wi-Fi is switched off (its automatic re-enabling and
# scanning too) and mobile data waited for; if Wi-Fi will not stay off, the run says so and the capture's verdict is
# not trusted (the evidence step then warns instead of passing).
step "the uplink"
UPLINK_CAPTURED=1
timeout 30 adb shell settings put global wifi_wakeup_enabled 0 >/dev/null 2>&1 || true
timeout 30 adb shell settings put global wifi_scan_always_enabled 0 >/dev/null 2>&1 || true
wifi_down() { ! timeout 15 adb shell 'ip -o -4 addr show wlan0 2>/dev/null; true' 2>&1 | grep -q inet; }
mobile_up() { timeout 30 adb shell 'ip -o -4 addr show' 2>&1 | tr -d '\r' | grep -Eq '^[0-9]+: (eth[0-9]+|radio[0-9]+|rmnet[0-9]+) +inet '; }
for _ in 1 2 3; do
    timeout 30 adb shell svc wifi disable >/dev/null 2>&1 || true
    for _ in $(seq 1 15); do wifi_down && break; sleep 1; done
    sleep 5
    wifi_down && break
done
for _ in $(seq 1 30); do mobile_up && break; sleep 2; done
if wifi_down && mobile_up; then
    note "the phone's uplink is mobile data, Wi-Fi off: the device the emulator's own capture records"
else
    UPLINK_CAPTURED=0
    echo "::warning title=Live proxy check (API $API)::the uplink could not be made mobile data alone (Wi-Fi down: $(wifi_down && echo yes || echo no); mobile data up: $(mobile_up && echo yes || echo no)); the emulator's capture may not record Wi-Fi"
fi
echo "addresses: $(timeout 30 adb shell 'ip -o -4 addr show' 2>&1 | tr -d '\r' | awk '{print $2, $4}' | tr '\n' ' ')"

# Before the app: what the internet sees of this phone with ProxyFlow off — the runner's own address.
before="$(client https api.ipify.org 443 /?format=text 15000)"
before_ip="$(sed -n 's/.*BODY \([0-9.]*\).*/\1/p' <<<"$before")"
if [ -n "$before_ip" ]; then
    note "with ProxyFlow off, api.ipify.org saw the phone as the runner's own address"
    RUNNER_PUBLIC_IP="${RUNNER_PUBLIC_IP:-$before_ip}"
else
    echo "::warning title=Live proxy check (API $API)::could not read the phone's address with ProxyFlow off: $before"
fi

# 4. The app, through its screens.
step "onboarding"
timeout 30 adb shell am start -W -n "$APPLICATION_ID/app.proxyflow.MainActivity" >/dev/null
$UI tap "Next"
$UI tap "Continue"
if [ "$API" -ge 33 ]; then $UI tap "Not now"; fi
$UI wait "Add a proxy to get started" 20
$UI screenshot "$OUT/1-home-empty.png"
step "add the proxy"
$UI tap "Add proxy"
$UI wait "Protocol" 20
$UI type "Name (optional)" "My-proxy"
$UI tap "$PROTOCOL"
$UI type "Server" "$PF_LIVE_PROXY_HOST"
$UI type "Port" "$PF_LIVE_PROXY_PORT"
if [ -n "${PF_LIVE_PROXY_USERNAME:-}" ]; then
    $UI tap "Proxy requires sign-in"
    $UI type "Username" "$PF_LIVE_PROXY_USERNAME"
    $UI type "Password" "$PF_LIVE_PROXY_PASSWORD"
fi
# No screenshot of the form: it shows the address and the username.
step "test connection"
$UI tap "Test connection"
# The result is a banner on the form (no screenshot: the form keeps itself out of screenshots, and shows the address).
if $UI wait-scroll "Proxy works" 60; then
    note "Test connection: $($UI texts | grep -m1 'opened a test connection' || echo 'Proxy works')"
else
    fail "Test connection did not say Proxy works within 60 s; the screen: $($UI texts | grep -v -F -e "$PF_LIVE_PROXY_HOST" -e "${PF_LIVE_PROXY_USERNAME:-@@none@@}" | tr '\n' '|' | cut -c1-400)"
fi
$UI tap "Save"
$UI wait "Connect" 20
step "connect"
$UI tap "Connect"
$UI wait "Allow a VPN connection" 20
$UI screenshot "$OUT/3-vpn-explainer.png"
$UI tap "Continue"
$UI wait "OK" 20
$UI screenshot "$OUT/4-android-consent.png"
$UI tap "OK"
if $UI wait "Connected" 90; then
    CONNECTED_AT="$(date +%s)"
    note "Connected through the owner's $PROTOCOL proxy, from the app's own screens"
else
    fail "the app did not reach Connected within 90 s; the screen: $($UI texts | tr '\n' '|' | cut -c1-400)"
    $UI screenshot "$OUT/5-not-connected.png"
    exit 1
fi
sleep 3
$UI screenshot "$OUT/5-connected.png"

# 5. Through the tunnel, from the shell (an app outside ProxyFlow, inside the VPN): an HTTPS page and a plain page
#    from sites that answer with the address they saw, and one ordinary site.
step "pages through the tunnel"
after="$(client https api.ipify.org 443 /?format=text 20000)"
after_ip="$(sed -n 's/.*BODY \([0-9.]*\).*/\1/p' <<<"$after")"
echo "https: ${after%%BODY*}"
if [ -z "$after_ip" ]; then
    fail "the HTTPS page through the tunnel did not come back: $after"
elif [ -n "${RUNNER_PUBLIC_IP:-}" ] && [ "$after_ip" = "$RUNNER_PUBLIC_IP" ]; then
    fail "api.ipify.org (HTTPS) saw the runner's own address while Connected: the page went around the proxy"
elif [ "$after_ip" = "$PF_LIVE_PROXY_HOST" ]; then
    note "api.ipify.org (HTTPS, through the tunnel) saw the proxy's own address — not the runner's"
else
    note "api.ipify.org (HTTPS, through the tunnel) saw an address that is neither the runner's nor the proxy's own: the provider's exit side (not printed)"
fi
plain="$(client http api.ipify.org 80 /?format=text 20000)"
plain_ip="$(sed -n 's/.*BODY \([0-9.]*\).*/\1/p' <<<"$plain")"
if [ -z "$plain_ip" ]; then
    fail "the plain-HTTP page through the tunnel did not come back: $plain"
elif [ -n "${RUNNER_PUBLIC_IP:-}" ] && [ "$plain_ip" = "$RUNNER_PUBLIC_IP" ]; then
    fail "api.ipify.org (plain HTTP) saw the runner's own address while Connected"
elif [ "$plain_ip" = "$after_ip" ]; then
    note "api.ipify.org (plain HTTP, through the tunnel) saw the same address as over HTTPS"
else
    note "api.ipify.org (plain HTTP, through the tunnel) saw another address than over HTTPS (a provider with several exits; neither printed)"
fi
site="$(client https www.google.com 443 /generate_204 20000)"
case "$site" in
    *"HTTP 204"*) note "www.google.com/generate_204 over HTTPS through the tunnel: 204, certificate checked by the phone's own trust store (${site%% LEAF*})" ;;
    *) fail "www.google.com/generate_204 through the tunnel: $site" ;;
esac
dns="$(client resolve example.com)"
case "$dns" in ADDRESSES*) note "a name looked up while Connected was answered ($dns)" ;; *) echo "::warning title=Live proxy check (API $API)::the lookup of example.com while Connected: $dns" ;; esac

# 6. The app's own screens while connected: Home's counts and the Logs screen (never addresses or names).
step "the app's screens"
sleep 2
$UI screenshot "$OUT/6-connected-traffic.png"
home="$($UI texts | tr '\n' '|' | cut -c1-500)"
echo "Home showed: $home"
$UI tap "Logs"
$UI wait "Logs" 20
$UI screenshot "$OUT/7-logs.png"
logs="$($UI texts | tr '\n' '|' | cut -c1-800)"
echo "Logs showed: $logs"
for secret in "$PF_LIVE_PROXY_HOST" "${PF_LIVE_PROXY_USERNAME:-}" "${PF_LIVE_PROXY_PASSWORD:-}"; do
    if [ -n "$secret" ] && grep -qF -- "$secret" <<<"$logs"; then fail "the Logs screen shows the proxy's address, username or password"; fi
done
$UI tap "Home"
$UI wait-scroll "Disconnect" 20

step "disconnect"
$UI tap "Disconnect"
DISCONNECTED_AT="$(date +%s)"
$UI wait "Not connected" 30 || fail "the app did not show Not connected after Disconnect"
$UI screenshot "$OUT/8-disconnected.png"
sleep 2
# With ProxyFlow off again the phone is itself again: the runner's address.
again="$(client https api.ipify.org 443 /?format=text 15000)"
again_ip="$(sed -n 's/.*BODY \([0-9.]*\).*/\1/p' <<<"$again")"
if [ -n "$again_ip" ] && [ -n "$after_ip" ] && [ "$again_ip" = "$after_ip" ]; then
    fail "after Disconnect, api.ipify.org still saw the proxy's side: the tunnel did not end"
elif [ -n "$again_ip" ]; then
    note "after Disconnect, api.ipify.org saw the phone as the runner's own address again"
fi
sleep 3

# 7. The uplink capture: while Connected, nothing left the phone for anywhere but the proxy.
step "the uplink capture"
if [ -n "${UPLINK_PCAP:-}" ] && [ -s "$UPLINK_PCAP" ]; then
    sudo chmod a+r "$UPLINK_PCAP" 2>/dev/null || true
    if $EVIDENCE "$UPLINK_PCAP" "$PF_LIVE_PROXY_HOST" "$PF_LIVE_PROXY_PORT" "$CONNECTED_AT" "$DISCONNECTED_AT" | tee "$OUT/uplink-evidence.txt"; then
        note "the uplink capture: $(tail -1 "$OUT/uplink-evidence.txt")"
    elif [ "$UPLINK_CAPTURED" = 0 ] && grep -q 'never reached the proxy' "$OUT/uplink-evidence.txt"; then
        echo "::warning title=Live proxy check (API $API)::the emulator's capture saw nothing of the tunnel (the phone was on Wi-Fi, which it does not record): the leak check could not be made on this level"
    else
        fail "the uplink capture: $(grep -m1 'LEAK\|never' "$OUT/uplink-evidence.txt" || tail -1 "$OUT/uplink-evidence.txt")"
    fi
else
    echo "::warning title=Live proxy check (API $API)::no uplink capture to read (UPLINK_PCAP)"
fi
