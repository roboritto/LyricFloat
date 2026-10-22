package com.example.lyricfloat

import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.edit

class LyricsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        val prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
        val isBoxVisible = prefs.getBoolean("pref_floating_box", true)

        // Toggle the floating box preference
        prefs.edit { putBoolean("pref_floating_box", !isBoxVisible) }

        // Tell the background service to update its views
        sendBroadcast(Intent("UPDATE_DISPLAY_PREFS").apply { setPackage(packageName) })

        // Update the tile appearance
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
        val isBoxVisible = prefs.getBoolean("pref_floating_box", true)

        if (FloatingLyricsService.isRunning) {
            if (isBoxVisible) {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Float On"
                tile.subtitle = ""
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Float Off"
                tile.subtitle = ""
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Service Off"
            tile.subtitle = ""
        }

        tile.updateTile()
    }
}