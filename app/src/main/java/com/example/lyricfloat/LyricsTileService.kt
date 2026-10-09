package com.example.lyricfloat

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class LyricsTileService : TileService() {

    // This runs every time you pull down the control panel
    override fun onStartListening() {
        super.onStartListening()

        val tile = qsTile ?: return

        // Check the flag from our floating service
        if (FloatingLyricsService.isRunning) {
            tile.state = Tile.STATE_ACTIVE // Lights up blue
        } else {
            tile.state = Tile.STATE_INACTIVE // Dims to black/gray
        }

        tile.updateTile() // Apply the color change
    }

    // Triggered when the user taps the tile
    override fun onClick() {
        super.onClick()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please open Dynamic Lyrics to grant permissions first.", Toast.LENGTH_LONG).show()
            return
        }

        // Toggle the service based on its current state
        val serviceIntent = Intent(this, FloatingLyricsService::class.java)
        if (FloatingLyricsService.isRunning) {
            stopService(serviceIntent) // Turn it off
        } else {
            startForegroundService(serviceIntent) // <--- CHANGE THIS LINE
        }
    }
}