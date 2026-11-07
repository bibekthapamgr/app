package app.proxyflow.core.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ProxyProfile
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * The Quick Settings tile (board B11, M9, ADR-0014), only Android can bind it (BIND_QUICK_SETTINGS_TILE). On while
 * ProxyFlow connects or a connection exists, off otherwise ([QuickTile]). A tap connects the profile Home shows, as
 * Home's Connect does, or disconnects; anything that needs Android's consent screen or a confirmation opens Home
 * instead. On a locked phone the tile names no profile and a tap asks to unlock first, so nobody can turn the VPN off
 * from the lock screen. A long press opens Home (MainActivity's QS_TILE_PREFERENCES filter).
 */
class ProxyFlowTileService : TileService() {
    // A failure reading the profiles (storage, the Keystore) must not take down the process the VPN runs in.
    private val scope = MainScope() + CoroutineExceptionHandler { _, e -> Log.w(TAG, "tile: ${e.javaClass.simpleName}") }
    private var listening: Job? = null
    private var shown: Pair<ConnectionState, ProxyProfile?>? = null

    private val runtime: VpnRuntime get() = (application as VpnRuntime.Provider).vpnRuntime

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            combine(runtime.state, runtime.shownProfile) { state, profile -> state to profile }
                .catch { e -> Log.w(TAG, "tile: the profile can't be read (${e.javaClass.simpleName})") }
                .collect { (state, profile) ->
                    shown = state to profile
                    show(state, profile, isLocked)
                }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        // Android keeps showing the tile as last drawn, the lock screen too, until it listens again: drawn without a name.
        shown?.let { (state, profile) -> show(state, profile, locked = true) }
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
            val profile = runtime.shownProfile.first()
            // Read after the profile, which may wait on storage: what the tap acts on is the state as it is now.
            val state = runtime.state.value
            when (QuickTile.tap(state, hasProfile = profile != null, consentNeeded = runtime.consentIntent() != null)) {
                QuickTile.Tap.Connect -> profile?.let { runtime.connectChosen(it.id) }
                // Only while still Connected when the session gets to it: held traffic is left to Home's Stop.
                QuickTile.Tap.Disconnect -> runtime.controller.disconnectIfConnected()
                QuickTile.Tap.OpenApp -> openApp()
            }
        }
    }

    private fun show(state: ConnectionState, profile: ProxyProfile?, locked: Boolean) {
        val tile = qsTile ?: return
        val content = QuickTile.content(state, profile, locked)
        val shown = QuickTile.shown(content, words = { getString(wordsOf(it)) }, describe = { getString(R.string.tile_description, it) })
        tile.state = when (content.look) {
            QuickTile.Look.On -> Tile.STATE_ACTIVE
            // Never unavailable: Android delivers no tap to an unavailable tile, and "Add a proxy first" opens Home.
            QuickTile.Look.Off -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_label)
        tile.subtitle = shown.subtitle
        // TalkBack reads the tile's name and what it is doing, not only on or off.
        tile.contentDescription = shown.description
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = shown.stateDescription
        tile.updateTile()
    }

    private fun wordsOf(line: QuickTile.Line): Int = when (line) {
        // A profile's name is the content's own, not a resource (QuickTile.shown).
        QuickTile.Line.Profile, QuickTile.Line.Connected -> R.string.tile_connected
        QuickTile.Line.Connecting -> R.string.tile_connecting
        QuickTile.Line.Reconnecting -> R.string.tile_reconnecting
        QuickTile.Line.Offline -> R.string.tile_offline
        QuickTile.Line.Blocked -> R.string.tile_blocked
        QuickTile.Line.Off -> R.string.tile_off
        QuickTile.Line.AddProxy -> R.string.tile_add_proxy
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

    private companion object {
        const val TAG = "ProxyFlowTile"
    }
}
