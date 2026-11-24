package com.callerx.app

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: shows whether the SIP account is registered and opens the
 * CallerX dial pad (or voicemail when the tile is long-pressed).
 */
class CallerXDialTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        try {
            val prefs = getSharedPreferences("callerx", MODE_PRIVATE)
            val online = prefs.getBoolean("status:online", false)
            val tile = qsTile ?: return
            tile.icon = Icon.createWithResource(this, R.drawable.ic_callerx_tile)
            tile.label = if (online) "CallerX · SIP online" else "CallerX · SIP offline"
            tile.state = if (online) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (online) "Tap to dial" else "Tap to open the dial pad"
            tile.updateTile()
        } catch (_: Exception) { }
    }

    override fun onClick() {
        super.onClick()
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("callerx_widget", "dialer")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(
                    this, 301, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) { }
    }
}
