# M4 — Dependency and licence review

**Milestone:** M4 · **Reviewed:** 31 Oct 2026 · Continues `docs/m1/08-dependency-review.md` (engine choice, M1) and the M2/M3 reports (Android libraries)

M4 adds the Go engine to the product. This review lists everything that ends up inside the engine library (`libgojni.so` and `classes.jar` in `engine/build/pfengine.aar`), the tools that build it, and how CI makes sure the shipped bytes come from exactly the reviewed and tested sources.

## 1. Inside the engine library

`go list -deps ./pfengine` (the bound package) pulls in these modules; nothing else is linked except gomobile's own bind runtime (§2). The module list printed from the built library itself (`go version -m libgojni.so`) is part of every CI run's AAR facts.

| Module | Version (pinned in `engine/go.mod`) | Licence | Used for |
|---|---|---|---|
| Go toolchain and standard library | 1.27.3 | BSD-3-Clause | runtime, `crypto/tls` (TLS to the proxy), `crypto/x509` (hostname matching), `net` |
| `gvisor.dev/gvisor` | `v0.0.0-20261029062900-94fcb330700e` (`go` branch) | Apache-2.0 (no NOTICE file) | the netstack: 44 packages under `pkg/tcpip`, `pkg/buffer`, `pkg/sync` and helpers. None of the sandbox (`runsc`) is linked |
| `golang.org/x/net` | v0.60.0 | BSD-3-Clause + PATENTS grant | `dns/dnsmessage` (fake DNS) |
| `golang.org/x/sys` | v0.48.0 | BSD-3-Clause + PATENTS grant | `unix`: socketpair (self-test), socket options |
| `golang.org/x/time` | v0.15.0 | BSD-3-Clause + PATENTS grant | indirect (gVisor: `rate`) |
| `golang.org/x/exp` | `v0.0.0-20250711185948-6ae5c78190dc` | BSD-3-Clause + PATENTS grant | indirect (gVisor) |
| `github.com/google/btree` | v1.1.2 | Apache-2.0 | indirect (gVisor) |

Unchanged from M1 except that the M4 code uses a smaller part of `x/sys` (the TUN ioctls of the spike's Linux harness are gone). The test-only fixtures (`engine/internal/proxytest`) use the standard library only and are not linked into the library.

## 2. gomobile

| Item | Version | Licence | Shipped? |
|---|---|---|---|
| `golang.org/x/mobile` (`gomobile`, `gobind`, bind runtime) | `v0.0.0-20260908204917-8b95e45f8d3e` (commit `8b95e45f8d3e`, 8 Sep 2026) | BSD-3-Clause | the bind runtime is: Go package `golang.org/x/mobile/bind/seq` in `libgojni.so`, Java classes `go.Seq`, `go.Universe`, `go.error` and the generated `app.proxyflow.engine.go.pfengine.*` in `classes.jar`. The commands are build tools |

Why this commit and not the newest (`1977704`, 11 Oct 2026): the newest asks for `golang.org/x/sys v0.49.0`, which minimum version selection would then put into the engine itself, untested. `8b95e45f8d3e` asks for `x/sys v0.48.0`, the engine's own version. `engine/build-aar.sh` adds x/mobile only for the build (on copies of `go.mod`/`go.sum`, restored afterwards) and **fails if adding it changes the version of any module the engine links**, so a future pin bump cannot silently change the engine. `gomobile init` is not used because it installs `gobind@latest`; both commands are installed at the pin instead.

## 3. Build tools (not shipped)

| Tool | Version | Licence | Notes |
|---|---|---|---|
| Android NDK | r28c (`28.2.13676358`), installed with `sdkmanager` when the runner lacks it | Apache-2.0 and others (LLVM) | clang/lld link `libgojni.so` against the system's `libc`, `libm`, `libdl` and `liblog` only. r28 links with 16 KB page alignment, which Google Play requires for 64-bit libraries of apps targeting Android 15+; the script also passes `-z max-page-size=16384` and checks every `LOAD` segment of the arm64-v8a and x86_64 libraries |
| `govulncheck` | `golang.org/x/vuln` v1.9.0 | BSD-3-Clause | CI, every run, inside `build-aar.sh` (§6) |
| `actions/setup-go` | v7.0.0, pinned to commit `b7ad1dad31e0` | MIT | CI |

## 4. Android side

| Library | Version | Licence | Where |
|---|---|---|---|
| `kotlinx-serialization-json` (and `-core`) | 1.9.0 (catalogue version since M2) | Apache-2.0 | `:core:engine`, the JSON boundary with the engine (`docs/m1/08` §3 planned it) |

No new test libraries: the instrumented tests use AndroidX Test and JUnit 4 from M2/M3, and build their own TLS certificates (an X.509 writer in the test sources) instead of adding a crypto library. `:core:engine` is not yet a dependency of the app (M5), so the release runtime classpath and APK are unchanged by M4.

## 5. Integrity

- **Go modules.** `go.sum` was produced from an offline mirror in M1. Every CI run downloads every module from `proxy.golang.org`, checks the module cache against `go.sum` (`go mod verify`) and that `go.mod`/`go.sum` are tidy (`go mod tidy -diff`), then **rebuilds `go.sum` from nothing**: with no hash to trust, the go command takes every hash from `sum.golang.org`, and the step fails unless the result is the committed file. That last part was added after the review: while a hash is in `go.sum`, the go command never asks the checksum database, so the first M4 runs had not compared `go.sum` with it, as this document then claimed. The rebuilt file was identical in the M4 runs, which closes the open M1 item "the public check has not run yet". The AAR is built only after this step passed.
- **The AAR.** Built in CI from the commit under test (`engine/build-aar.sh`, `-trimpath`, empty build id) once the module checks and the vulnerability scan passed; its size, SHA-256, per-ABI library sizes and segment alignment, and the linked module list are emitted as notices in every run (the acceptance report quotes one). Two runs of the same commit produced byte-identical AARs. The Android jobs use that artifact, never a committed binary.
- **GitHub Actions** stay pinned to commit SHAs.

## 6. Vulnerabilities

`govulncheck` runs on every CI run against the Go vulnerability database, inside `build-aar.sh` after gomobile's bind runtime has been added: over the engine (`./...`: the standard library of Go 1.27.3 and every module above) **and** `golang.org/x/mobile/bind` and `bind/seq`, which are in `go.mod` only during that build — so it scans exactly what the library links. A finding fails the AAR build, so no library with a known vulnerability reaches the Android jobs. (Until the review it ran as a separate step over `./...` only, which missed the bind runtime.) Result for the verified commit: see the acceptance report. gVisor's published CVEs (CVE-2024-10603, CVE-2024-10026) were fixed in 2024, long before the pinned commit (M1 review).

## 7. Licence obligations

- Apache-2.0 (gVisor, btree): reproduce the licence text in the app's open-source notices (screen G1, M9) and keep it with the distribution. Neither project ships a NOTICE file.
- BSD-3-Clause (Go, `x/*`, x/mobile): reproduce the copyright notice and licence text in the notices. The Go `PATENTS` files grant patent rights and add no obligation.
- All are compatible with whichever licence the owner picks for ProxyFlow (open decision D8); none is copyleft.

## 8. Update policy

As in M1 §5, with the M4 tooling: bump gVisor's `go` branch pin monthly or on an advisory, bump x/mobile only to a commit whose requirements do not move the engine's modules (the build script enforces it), bump the NDK deliberately (it decides the library's alignment and ABI baseline), and keep Go at the version in `go.mod` and the workflow. Every bump goes through the full engine job: race tests, fuzzing, govulncheck and the device tests.
