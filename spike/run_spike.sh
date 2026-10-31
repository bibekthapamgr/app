#!/usr/bin/env bash
# ProxyFlow M1 feasibility spike — one command:
#   build engine -> Go unit/hermetic tests (race) + short fuzzing ->
#   end-to-end namespace tests with packet captures -> evidence summary.
#
# Requirements: Linux, root (network namespaces + TUN), Go 1.27.3+,
#   apt: iproute2 tcpdump tshark iperf3 squid dante-server stunnel4 dnsutils
#        iputils-ping curl openssl
#   pip: pytest cryptography
# Optional: HEV_TAG=2.19.3 builds hev-socks5-tunnel for the comparison tests.
set -euo pipefail
cd "$(dirname "$0")"

RUN_ID="${SPIKE_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
export SPIKE_RUN_ID="$RUN_ID"
EVID="evidence/run-$RUN_ID"
mkdir -p bin "$EVID"
FUZZTIME="${FUZZTIME:-15s}"

echo "== 1/5 build engine"
(cd engine && go vet ./... && go build -trimpath -o ../bin/pfspike ./cmd/pfspike)
# Size evidence: stripped builds for Linux (like-for-like with hev) and for
# android/arm64 without cgo (the gomobile AAR itself needs the NDK).
(cd engine &&
  go build -trimpath -ldflags='-s -w' -o ../bin/pfspike-stripped ./cmd/pfspike &&
  CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -trimpath -ldflags='-s -w' -o ../bin/pfspike-android-arm64 ./cmd/pfspike)
{ go version
  for b in bin/pfspike bin/pfspike-stripped bin/pfspike-android-arm64 bin/hev-socks5-tunnel; do
    [ -e "$b" ] && printf '%10d bytes  %s  (%s)\n' "$(stat -c %s "$b")" "$b" "$(file -b "$b" | cut -d, -f1-2)"
  done; } > "$EVID/binary-sizes.txt"

echo "== 2/5 unit + hermetic engine tests (race detector)"
(cd engine && go test -race -count=1 -json ./...) > "$EVID/go-test.json" || {
  echo "Go tests failed"; grep '"Action":"fail"' "$EVID/go-test.json" | head; exit 1; }

if [ -n "${GO_TEST_REPEAT:-}" ]; then
  echo "== 2b/5 repeat the hermetic engine tests ${GO_TEST_REPEAT}x (race detector) for flake evidence"
  REP="$EVID/go-test-repeat.log" RAW="$EVID/.go-test-repeat.raw"
  echo "\$ go test -race -count=$GO_TEST_REPEAT -v ./engine/   (results per test, counted)" > "$REP"
  rc=0
  (cd engine && go test -race -count="$GO_TEST_REPEAT" -v ./engine/) > "$RAW" 2>&1 || rc=$?
  { grep -E '^ *--- (PASS|FAIL|SKIP)' "$RAW" | sed -E 's/^ *//; s/ \([0-9.]+s\)$//' | sort | uniq -c || true
    grep -E '^(ok|FAIL|panic)' "$RAW" || true; } >> "$REP"
  rm -f "$RAW"
  if [ "$rc" -ne 0 ]; then echo "repeated Go tests failed"; tail -20 "$REP"; exit 1; fi
fi

echo "== 3/5 fuzzing protocol parsers ($FUZZTIME each)"
(cd engine &&
  go test -run='^$' -fuzz='^FuzzReadSOCKS5Reply$' -fuzztime="$FUZZTIME" ./adapters/ &&
  go test -run='^$' -fuzz='^FuzzReadConnectResponse$' -fuzztime="$FUZZTIME" ./adapters/ &&
  go test -run='^$' -fuzz='^FuzzResponder$' -fuzztime="$FUZZTIME" ./fakedns/) | tee "$EVID/fuzz.log"

if [ -n "${HEV_TAG:-}" ] && [ ! -x bin/hev-socks5-tunnel ]; then
  echo "== optional: build hev-socks5-tunnel $HEV_TAG for comparison"
  tmp=$(mktemp -d)
  git clone -q --depth 1 --branch "$HEV_TAG" --recurse-submodules --shallow-submodules \
    https://github.com/heiher/hev-socks5-tunnel "$tmp/hev"
  if [ ! -e /proc/sys/net/ipv6 ]; then
    # Test-only patch: hev uses dual-stack AF_INET6 sockets, which fail when
    # the kernel is booted with ipv6.disable=1. Not needed on Android.
    PATCH="$(pwd)/hev/ipv4-only-test-patch.diff"
    (cd "$tmp/hev/src/core" && patch -p1 < "$PATCH")
  fi
  make -C "$tmp/hev" -j"$(nproc)" >/dev/null
  cp "$tmp/hev/bin/hev-socks5-tunnel" bin/
fi

echo "== 4/5 end-to-end tests (network namespaces, real proxies, packet captures)"
set +e
python3 -m pytest -q --junitxml="$EVID/junit.xml" tests/
rc=$?
set -e

echo "== 5/5 evidence summary"
python3 harness/report.py "$EVID" --go-tests "$EVID/go-test.json" --junit "$EVID/junit.xml"
echo "Evidence: $EVID/SUMMARY.md"
exit $rc
