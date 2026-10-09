package com.example.lyricfloat // Verify this matches your app package

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat

class MediaListenerService : NotificationListenerService() {

    private val requestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            checkCurrentlyPlaying()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter("REQUEST_CURRENT_TRACK")
        ContextCompat.registerReceiver(
            this,
            requestReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(requestReceiver)
    }

    private fun checkCurrentlyPlaying() {
        try {
            val activeNotifs = activeNotifications
            for (sbn in activeNotifs) {
                val extras = sbn.notification.extras
                if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

                    if (title != null) {
                        val intent = Intent("UPDATE_LYRICS")
                        intent.setPackage(packageName)
                        intent.putExtra("track_name", title)
                        intent.putExtra("artist_name", artist ?: "")

                        // Extract exact playback position
                        val token = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
                        if (token != null) {
                            val controller = MediaController(this@MediaListenerService, token)
                            val state = controller.playbackState

                            intent.putExtra("position_ms", state?.position ?: 0L)
                            intent.putExtra("is_playing", state?.state == PlaybackState.STATE_PLAYING)
                        }

                        sendBroadcast(intent)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore if it fails to fetch notifications
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras

        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

            if (title != null) {
                val intent = Intent("UPDATE_LYRICS")
                intent.setPackage(packageName)
                intent.putExtra("track_name", title)
                intent.putExtra("artist_name", artist ?: "")

                // Extract exact playback position
                val token = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
                if (token != null) {
                    val controller = MediaController(this@MediaListenerService, token)
                    val state = controller.playbackState

                    intent.putExtra("position_ms", state?.position ?: 0L)
                    intent.putExtra("is_playing", state?.state == PlaybackState.STATE_PLAYING)
                }

                sendBroadcast(intent)
            }
        }
    }
}