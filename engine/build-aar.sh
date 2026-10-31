#!/usr/bin/env bash
# Builds engine/build/pfengine.aar, the packet engine for Android, with
# gomobile bind: Java classes in app.proxyflow.engine.go.pfengine and
# libgojni.so for armeabi-v7a, arm64-v8a and x86_64 (the emulators).
#
# Needs Go 1.27.3, a JDK and the Android SDK (ANDROID_HOME); the pinned NDK is
# installed with sdkmanager when missing. CI runs this in the engine job and
# hands the AAR to the build and device jobs; developers run it once before
# building :core:engine. Also writes build/pfengine-facts.txt (sizes, hashes,
# ELF alignment, the exact modules inside) for the acceptance evidence.
#
# PF_GOVULNCHECK=1 (CI) also runs govulncheck over everything the library
# links, gomobile's bind runtime included, which is in go.mod only during this
# build; a known vulnerability then fails the build. It needs the network.
set -euo pipefail
cd "$(dirname "$0")"

# golang.org/x/mobile, pinned (gomobile, gobind and the bind runtime inside the
# AAR). This commit is the newest whose go.mod asks for no newer golang.org/x/sys
# than the engine uses, so adding it cannot move the engine's own dependencies
# (checked below). docs/m4/dependency-review.md.
MOBILE_VERSION=v0.0.0-20260908204917-8b95e45f8d3e
# NDK r28c: links shared libraries with 16 KB page alignment, which Google Play
# requires for 64-bit libraries of apps targeting Android 15+.
NDK_VERSION=28.2.13676358
ANDROID_API=29 # minSdk (ADR-0007)
GOVULNCHECK_VERSION=v1.9.0
JAVA_PACKAGE=app.proxyflow.engine.go

step() { echo "== build-aar: $*"; }

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ] || [ ! -d "$ANDROID_HOME" ]; then
  echo "build-aar.sh: set ANDROID_HOME to the Android SDK" >&2
  exit 1
fi
export ANDROID_HOME

# x/mobile/bind is needed only for this build: work on copies of go.mod and
# go.sum and put the originals back afterwards.
work="$(mktemp -d)"
cp go.mod go.sum "$work/"
restore() { cp "$work/go.mod" go.mod; cp "$work/go.sum" go.sum; rm -rf "$work"; }
trap restore EXIT

if [ ! -x "$ANDROID_HOME/ndk/$NDK_VERSION/ndk-build" ]; then
  step "installing NDK $NDK_VERSION"
  sdkmanager="$(command -v sdkmanager || true)"
  [ -n "$sdkmanager" ] || sdkmanager="$(ls -d "$ANDROID_HOME"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | sort | tail -1)"
  [ -n "$sdkmanager" ] || { echo "build-aar.sh: NDK $NDK_VERSION missing and no sdkmanager found" >&2; exit 1; }
  # yes ends with SIGPIPE when sdkmanager exits; only sdkmanager's status counts.
  if ! (yes || true) | "$sdkmanager" --install "ndk;$NDK_VERSION" > "$work/sdkmanager.log" 2>&1; then
    tail -n 30 "$work/sdkmanager.log" >&2
    echo "build-aar.sh: sdkmanager could not install ndk;$NDK_VERSION" >&2
    exit 1
  fi
fi
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
export CGO_LDFLAGS="-Wl,-z,max-page-size=16384" # explicit, whatever the NDK's default
step "NDK $ANDROID_NDK_HOME, $(go version)"

engine_modules() { go list -deps -f '{{with .Module}}{{.Path}} {{.Version}}{{end}}' ./pfengine | sort -u; }
engine_modules > "$work/modules-before.txt"

step "installing gomobile and gobind $MOBILE_VERSION"
GOBIN="$work/bin" go install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION" "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"
export PATH="$work/bin:$PATH"
step "adding golang.org/x/mobile/bind for this build"
go get "golang.org/x/mobile/bind@$MOBILE_VERSION"

engine_modules > "$work/modules-after.txt"
if ! diff -u "$work/modules-before.txt" "$work/modules-after.txt"; then
  echo "build-aar.sh: adding golang.org/x/mobile changed the engine's dependencies; pin another x/mobile commit" >&2
  exit 1
fi

if [ "${PF_GOVULNCHECK:-0}" = 1 ]; then
  step "govulncheck $GOVULNCHECK_VERSION: the engine, its dependencies and the bind runtime"
  go run "golang.org/x/vuln/cmd/govulncheck@$GOVULNCHECK_VERSION" ./... golang.org/x/mobile/bind golang.org/x/mobile/bind/seq
fi

mkdir -p build
rm -f build/pfengine.aar build/pfengine-sources.jar
step "gomobile bind"
gomobile bind -target=android/arm,android/arm64,android/amd64 -androidapi "$ANDROID_API" \
  -javapkg "$JAVA_PACKAGE" -trimpath -ldflags="-s -w -buildid=" -o build/pfengine.aar ./pfengine

# Facts and checks on what was built.
step "checking the AAR"
readelf="$(ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-readelf | head -1)"
unpacked="$work/aar"
unzip -q build/pfengine.aar -d "$unpacked"
facts=build/pfengine-facts.txt
{
  echo "AAR build/pfengine.aar $(stat -c %s build/pfengine.aar) bytes sha256 $(sha256sum build/pfengine.aar | cut -d' ' -f1)"
  echo "BUILT WITH $(go version), gomobile $MOBILE_VERSION, NDK $NDK_VERSION, -androidapi $ANDROID_API"
  for abi in armeabi-v7a arm64-v8a x86_64; do
    so="$unpacked/jni/$abi/libgojni.so"
    if [ ! -f "$so" ]; then
      echo "MISSING jni/$abi/libgojni.so"
      continue
    fi
    aligns="$("$readelf" -lW "$so" | awk '$1 == "LOAD" {print $NF}' | sort -u | tr '\n' ' ')"
    echo "SO $abi $(stat -c %s "$so") bytes, LOAD alignment $aligns"
  done
  echo "CLASSES $(unzip -l "$unpacked/classes.jar" | awk '/\.class$/ {print $4}' | grep -c .) in classes.jar ($(unzip -l "$unpacked/classes.jar" | awk '/pfengine\/[A-Za-z]+\.class$/ {print $4}' | sed 's#.*/##' | tr '\n' ' '))"
  echo "MODULES linked into libgojni.so (go version -m):"
  go version -m "$unpacked/jni/arm64-v8a/libgojni.so" | awk '$1 == "dep" || $1 == "=>" {print "  " $2 " " $3}'
} | tee "$facts"

# Fail on a missing ABI or a 64-bit library aligned below 16 KB (0x4000).
if grep -q '^MISSING' "$facts"; then
  echo "build-aar.sh: an ABI is missing from the AAR" >&2
  exit 1
fi
for abi in arm64-v8a x86_64; do
  for a in $("$readelf" -lW "$unpacked/jni/$abi/libgojni.so" | awk '$1 == "LOAD" {print $NF}'); do
    if [ $((a)) -lt 16384 ]; then
      echo "build-aar.sh: jni/$abi/libgojni.so has a LOAD segment aligned to $a, below 16 KB" >&2
      exit 1
    fi
  done
done
echo "build-aar.sh: wrote build/pfengine.aar"
