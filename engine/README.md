# ProxyFlow packet engine (Go)

The engine that carries apps' TCP traffic through the user's SOCKS5 or HTTP
CONNECT proxy: gVisor's netstack on the VPN's TUN descriptor, fake-IP DNS, and
in-process proxy adapters with optional TLS to the proxy. Decisions:
ADR-0001 to ADR-0005 (`docs/adr/`). Behaviour, API and compatibility:
`docs/m4/protocol-compatibility.md`. Dependencies and licences:
`docs/m4/dependency-review.md`.

| Package | What it does |
|---|---|
| `adapters` | One tunnel per destination: protected TCP to the proxy, optional TLS (hostname checked here, chain trusted by the platform), SOCKS5 (RFC 1928/1929) or HTTP CONNECT (Basic). Stable error codes; credentials as wipeable bytes, never formatted. |
| `tunnel` | The netstack engine: TCP flows dialled through the proxy before the app's handshake completes (fail closed), DNS answered locally over UDP and TCP port 53 (any address, ADR-0011), TCP to the engine's own addresses refused, other UDP refused, pings never answered. Lifecycle, flow registry, limits, metrics (incl. proxy timeouts and failures of flows to names, for the app's diagnostics) and a bounded event queue. |
| `fakedns` | Fake-IP DNS: `A` queries answered from `198.18.0.0/15`, mapped back to hostnames on connect. |
| `pfengine` | The API exported to Kotlin with gomobile (`Start`, `Stop`, `Status`, `Stats`, `Flows`, `ResetFlows`, `Probe`, `SelfTest`). |
| `internal/proxytest` | Test-only fixtures: mock SOCKS5 and HTTP CONNECT proxies (scripted misbehaviour, TLS), an echo server, a test PKI, goroutine and descriptor leak checks. |

## Test

```sh
cd engine
go test -race ./...                       # unit, integration and lifecycle tests
go test -run '^$' -fuzz '^FuzzReadConnectResponse$' -fuzztime 30s ./adapters   # one fuzz target
```

Every test runs without root or network access: the netstack tests use a
socketpair and an in-process client stack; the proxies, the destination and
the certificates are created by the tests.

## Build the Android library

```sh
engine/build-aar.sh   # needs Go 1.27.3, a JDK and ANDROID_HOME; installs the pinned NDK if missing
```

Writes `engine/build/pfengine.aar` (armeabi-v7a, arm64-v8a, x86_64) and
`engine/build/pfengine-facts.txt`. The `:core:engine` Gradle module unpacks it;
without it, `:core:engine` tasks stop with a message saying so. CI builds it in
the `engine` job and passes it to the Android jobs.
