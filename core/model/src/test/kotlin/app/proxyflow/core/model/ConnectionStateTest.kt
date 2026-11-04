package app.proxyflow.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a switch or a reconnect does in each state (J11, M9): the screens word their questions by it. */
class ConnectionStateTest {
    @Test
    fun aSwitchHoldsTrafficOnceConnectedAndWhileAnAlwaysOnStartHoldsIt() {
        val holds = ConnectionStatus.entries.associateWith { ConnectionState(it, "p1").holdsTrafficOnSwitch }
        assertEquals(
            mapOf(
                ConnectionStatus.Disconnected to false,
                // A first connect: nothing is held yet, a switch only checks another proxy instead.
                ConnectionStatus.Connecting to false,
                ConnectionStatus.Connected to true,
                ConnectionStatus.Reconnecting to true,
                ConnectionStatus.Blocked to true,
                ConnectionStatus.Offline to true,
            ),
            holds,
        )
        assertEquals(
            "an Always-on start holding traffic (M8)",
            true,
            ConnectionState(ConnectionStatus.Connecting, "p1", held = true).holdsTrafficOnSwitch,
        )
        assertEquals("held means nothing without a connection", false, ConnectionState(held = true).holdsTrafficOnSwitch)
    }
}
