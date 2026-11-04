package app.proxyflow.core.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ProxyProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile (board B11, M9, ADR-0014), only Android can bind it (BIND_QUICK_SETTINGS_TILE). On while a
 * connection exists, off while apps use the normal network ([QuickTile]). A tap connects the profile Home shows, as
 * Home's Connect does, or disconnects; anything that needs Android's consent screen or a confirmation opens Home
 * instead. On a locked phone the tile names no profile and a tap asks to unlock first, so nobody can turn the VPN off
 * from the lock screen. A long press opens Home (MainActivity's QS_TILE_PREFERENCES filter).
 */
class ProxyFlowTileService : TileService() {
    private val scope = MainScope()
    private var listening: Job? = null

    private val runtime: VpnRuntime get() = (application as VpnRuntime.Provider).vpnRuntime

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            combine(runtime.state, runtime.shownProfile) { state, shown -> state to shown }.collect { (state, shown) -> show(state, shown) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::act) else act()
    }

    private fun act() {
        scope.launch {
            val state = runtime.state.value
            val shown = runtime.shownProfile.first()
            when (QuickTile.tap(state, hasProfile = shown != null, consentNeeded = runtime.consentIntent() != null)) {
                QuickTile.Tap.Connect -> shown?.let { runtime.connectChosen(it.id) }
                QuickTile.Tap.Disconnect -> runtime.controller.disconnect()
                QuickTile.Tap.OpenApp -> openApp()
            }
        }
    }

    private fun show(state: ConnectionState, shown: ProxyProfile?) {
        val tile = qsTile ?: return
        val content = QuickTile.content(state, shown?.displayName, isLocked)
        val line = when (content.line) {
            QuickTile.Line.Profile -> content.profileName.orEmpty()
            QuickTile.Line.Connected -> getString(R.string.tile_connected)
            QuickTile.Line.Connecting -> getString(R.string.tile_connecting)
            QuickTile.Line.Reconnecting -> getString(R.string.tile_reconnecting)
            QuickTile.Line.Offline -> getString(R.string.tile_offline)
            QuickTile.Line.Blocked -> getString(R.string.tile_blocked)
            QuickTile.Line.Off -> getString(R.string.tile_off)
            QuickTile.Line.AddProxy -> getString(R.string.tile_add_proxy)
        }
        tile.state = when (content.look) {
            QuickTile.Look.On -> Tile.STATE_ACTIVE
            QuickTile.Look.Off -> Tile.STATE_INACTIVE
            QuickTile.Look.Unavailable -> Tile.STATE_UNAVAILABLE
        }
        tile.label = getString(R.string.tile_label)
        tile.subtitle = line
        // TalkBack reads the tile's name and what it is doing, not only on or off.
        tile.contentDescription = getString(R.string.tile_description, line)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = line
        tile.updateTile()
    }

    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            startWithIntent(intent)
        }
    }

    // Android 13 and older take an Intent; 14 and later only a PendingIntent (the branch above).
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startWithIntent(intent: Intent) = startActivityAndCollapse(intent)
}
