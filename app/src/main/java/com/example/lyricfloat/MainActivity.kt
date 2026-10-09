package com.example.lyricfloat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // Modern Android approach to handle the result of the settings screen
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Check if the user granted the permission after returning from settings
        if (Settings.canDrawOverlays(this)) {
            startFloatingService()
        } else {
            Toast.makeText(this, "Permission required to show floating lyrics.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStart = findViewById<Button>(R.id.btn_start_service)
        btnStart.setOnClickListener {
            checkPermissionAndStart()
        }

        // ADD THESE LINES:
        val btnNotifAccess = findViewById<Button>(R.id.btn_notification_access)
        btnNotifAccess.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }
    }

    private fun requestNotificationAccess() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun checkPermissionAndStart() {
        Toast.makeText(this, "Button clicked!", Toast.LENGTH_SHORT).show() // Add this line

        if (!Settings.canDrawOverlays(this)) {
            // User hasn't granted permission; launch the specific system settings page
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            // Permission already granted; start the service immediately
            startFloatingService()
        }
    }

    private fun startFloatingService() {
        // This will prove the app is actually calling the service
        Toast.makeText(this, "Starting Lyrics Service...", Toast.LENGTH_SHORT).show()

        val intent = Intent(this, FloatingLyricsService::class.java)
        startService(intent)
    }
}