package com.example.lyricfloat // Verify this matches your exact app package

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder
import android.annotation.SuppressLint
import android.widget.RemoteViews
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

class FloatingLyricsService : Service() {

    companion object {
        var isRunning = false
    }

    data class LyricLine(val timeMs: Long, val text: String)

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var notificationManager: NotificationManager

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "lyrics_lock_screen_channel_V2"
    private var currentLyricIndex = -1

    private var parsedLyrics = listOf<LyricLine>()
    private var syncJob: Job? = null

    private var currentPositionMs = 0L
    private var isMusicPlaying = false
    private var lastUpdateTime = 0L
    private var currentTrackName = ""

    private val lyricsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val trackName = intent?.getStringExtra("track_name")
            val artistName = intent?.getStringExtra("artist_name")

            currentPositionMs = intent?.getLongExtra("position_ms", 0L) ?: 0L
            isMusicPlaying = intent?.getBooleanExtra("is_playing", false) ?: false
            lastUpdateTime = System.currentTimeMillis()

            if (trackName != null && ::floatingView.isInitialized) {
                if (trackName != currentTrackName) {
                    currentTrackName = trackName
                    syncJob?.cancel()
                    parsedLyrics = emptyList()
                    currentLyricIndex = -1 // Reset index for new song
                    setStatusText("Searching: $trackName...")
                    fetchLyrics(trackName, artistName ?: "")
                } else {
                    startSyncTimer()
                }
            }
        }
    }

    private val colorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyCustomColors()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Notification Manager & Channel
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lock Screen Lyrics",
            NotificationManager.IMPORTANCE_DEFAULT // Default is fine for Foreground Services
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        notificationManager.createNotificationChannel(channel)

        // 2. PROMOTE TO FOREGROUND SERVICE IMMEDIATELY
        val initialNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Dynamic Lyrics Active")
            .setContentText("Waiting for music...")
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Pin to lock screen
            .build()

        startForeground(NOTIFICATION_ID, initialNotification) // <--- CRITICAL FIX

        isRunning = true
        requestTileUpdate()

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater

            floatingView = inflater.inflate(R.layout.layout_floating_lyrics, null)

            // 2. Set static width mapping to 300dp
            val widthInPx = (300 * resources.displayMetrics.density).toInt()

            val params = WindowManager.LayoutParams(
                widthInPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

            // 3. Pin to Top-Center
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.x = 0
            params.y = 0

            windowManager.addView(floatingView, params)

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            var tapCount = 0
            var lastTapTime = 0L
            val TAP_TIMEOUT = 350L
            val TOUCH_SLOP = 10f

            floatingView.findViewById<View>(R.id.lyrics_container).setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = Math.abs(event.rawX - initialTouchX)
                        val dy = Math.abs(event.rawY - initialTouchY)

                        if (dx < TOUCH_SLOP && dy < TOUCH_SLOP) {
                            val currentTime = System.currentTimeMillis()

                            if (currentTime - lastTapTime < TAP_TIMEOUT) {
                                tapCount++
                            } else {
                                tapCount = 1
                            }
                            lastTapTime = currentTime

                            if (tapCount == 2) {
                                // --- NEW: Double tap to reset position ---
                                params.x = 0
                                params.y = 0 // Your starting Y coordinate
                                windowManager.updateViewLayout(floatingView, params)
                                Toast.makeText(this@FloatingLyricsService, "Position reset", Toast.LENGTH_SHORT).show()
                            } else if (tapCount == 3) {
                                // --- Triple tap to close ---
                                Toast.makeText(this@FloatingLyricsService, "Lyrics closed", Toast.LENGTH_SHORT).show()
                                stopSelf()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }

            val filter = IntentFilter("UPDATE_LYRICS")
            ContextCompat.registerReceiver(
                this,
                lyricsReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val colorFilter = IntentFilter("UPDATE_COLORS")
            ContextCompat.registerReceiver(
                this,
                colorReceiver,
                colorFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            // Apply colors instantly upon creation
            applyCustomColors()

            // Delayed query to ensure receiver is ready
            Handler(Looper.getMainLooper()).postDelayed({
                val requestIntent = Intent("REQUEST_CURRENT_TRACK")
                requestIntent.setPackage(packageName)
                sendBroadcast(requestIntent)
            }, 300)

        } catch (e: Exception) {
            Toast.makeText(this, "CRASH: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        isRunning = false
        requestTileUpdate()
        syncJob?.cancel()
        notificationManager.cancel(NOTIFICATION_ID) // Clear lock screen notification

        try {
            unregisterReceiver(colorReceiver)
        } catch (e: Exception) { }

        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }

    private fun requestTileUpdate() {
        try {
            TileService.requestListeningState(
                this,
                ComponentName(this, LyricsTileService::class.java)
            )
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun setStatusText(message: String) {
        if (::floatingView.isInitialized) {
            floatingView.findViewById<TextView>(R.id.tv_previous_lyric).text = ""
            floatingView.findViewById<TextView>(R.id.tv_current_lyric).text = message
            floatingView.findViewById<TextView>(R.id.tv_next_lyric).text = ""
        }
    }

    private fun fetchLyrics(title: String, artist: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val queryTitle = URLEncoder.encode(title, "UTF-8")
                val queryArtist = URLEncoder.encode(artist, "UTF-8")
                val url = "https://lrclib.net/api/search?track_name=$queryTitle&artist_name=$queryArtist"

                val response = URL(url).readText()
                val jsonArray = JSONArray(response)

                if (jsonArray.length() > 0) {
                    val firstResult = jsonArray.getJSONObject(0)
                    val syncedLyrics = firstResult.optString("syncedLyrics", "")

                    if (syncedLyrics.isNotEmpty()) {
                        val parsed = parseLrc(syncedLyrics)
                        withContext(Dispatchers.Main) {
                            parsedLyrics = parsed
                            startSyncTimer()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            parsedLyrics = emptyList()
                            val plainLyrics = firstResult.optString("plainLyrics", "")
                            val text = if (plainLyrics.isNotEmpty()) "No synced lyrics.\n\n$plainLyrics" else "Instrumental or no lyrics available."
                            setStatusText(text)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        parsedLyrics = emptyList()
                        setStatusText("No lyrics found for $title.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    parsedLyrics = emptyList()
                    setStatusText("Network error or API down.")
                }
            }
        }
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")

        lrc.lines().forEach { line ->
            val match = regex.find(line)
            if (match != null) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val milStr = match.groupValues[3]
                val mil = if (milStr.length == 2) milStr.toLong() * 10 else milStr.toLong()

                val timeInMs = (min * 60 * 1000) + (sec * 1000) + mil
                val text = match.groupValues[4].trim()

                if (text.isNotEmpty()) {
                    lines.add(LyricLine(timeInMs, text))
                }
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun startSyncTimer() {
        syncJob?.cancel()

        if (!isMusicPlaying || parsedLyrics.isEmpty()) return

        syncJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val timePassed = System.currentTimeMillis() - lastUpdateTime
                val estimatedPosition = currentPositionMs + timePassed

                val currentIndex = parsedLyrics.indexOfLast { it.timeMs <= estimatedPosition }

                if (currentIndex != -1 && ::floatingView.isInitialized) {
                    val prevText = if (currentIndex > 0) parsedLyrics[currentIndex - 1].text else ""
                    val currentText = parsedLyrics[currentIndex].text
                    val nextText = if (currentIndex < parsedLyrics.size - 1) parsedLyrics[currentIndex + 1].text else ""

                    floatingView.findViewById<TextView>(R.id.tv_previous_lyric).text = prevText
                    floatingView.findViewById<TextView>(R.id.tv_current_lyric).text = currentText
                    floatingView.findViewById<TextView>(R.id.tv_next_lyric).text = nextText

                    // Only push a lock screen update if the line actually moved
                    if (currentIndex != currentLyricIndex) {
                        currentLyricIndex = currentIndex
                        updateLockScreenNotification(prevText, currentText, nextText)
                    }
                }

                delay(100)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateLockScreenNotification(prev: String, current: String, next: String) {
        val customView = RemoteViews(packageName, R.layout.layout_notification_lyrics)

        customView.setTextViewText(R.id.notif_tv_prev, prev)
        customView.setTextViewText(R.id.notif_tv_current, current)
        customView.setTextViewText(R.id.notif_tv_next, next)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            // We ONLY set the standard view now, no BigContentView and no DecoratedStyle
            .setCustomContentView(customView)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun applyCustomColors() {
        if (!::floatingView.isInitialized) return

        val prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
        val bgColor = prefs.getString("bg_color", "#FF000000") ?: "#FF000000"
        val textColor = prefs.getString("text_color", "#FFFFFFFF") ?: "#FFFFFFFF"

        try {
            val parsedBgColor = Color.parseColor(bgColor)
            val parsedTextColor = Color.parseColor(textColor)

            // Create a 50% transparent version of the text color for the prev/next lines
            val semiTransparentTextColor = Color.argb(
                128,
                Color.red(parsedTextColor),
                Color.green(parsedTextColor),
                Color.blue(parsedTextColor)
            )

            // 1. Rebuild the rounded background with the new color
            val backgroundShape = GradientDrawable()
            backgroundShape.shape = GradientDrawable.RECTANGLE
            backgroundShape.cornerRadius = 32f // Keeps the rounded corners
            backgroundShape.setColor(parsedBgColor)

            floatingView.findViewById<View>(R.id.lyrics_container).background = backgroundShape

            // 2. Apply the text colors
            floatingView.findViewById<TextView>(R.id.tv_current_lyric).setTextColor(parsedTextColor)
            floatingView.findViewById<TextView>(R.id.tv_previous_lyric).setTextColor(semiTransparentTextColor)
            floatingView.findViewById<TextView>(R.id.tv_next_lyric).setTextColor(semiTransparentTextColor)

        } catch (e: Exception) {
            // Failsafe in case of a crash during parsing, defaults will remain
        }
    }
}