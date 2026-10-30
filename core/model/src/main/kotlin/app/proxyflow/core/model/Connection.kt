package app.proxyflow.core.model

/**
 * Connection states from the M1 state model (docs/m1/03-user-journeys.md).
 * M2 only ever reports [Disconnected]; the VPN service (M5) drives the rest.
 */
enum class ConnectionStatus { Disconnected, Connecting, Connected, Reconnecting, Blocked, Offline }

/** One entry in the local event log (filled by the engine from M7). */
data class LogEntry(val id: Long, val timeMillis: Long, val level: Level, val code: String?, val message: String) {
    enum class Level { Info, Warning, Error }
}
