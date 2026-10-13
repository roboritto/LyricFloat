package com.example.lyricfloat // Verify this matches your exact app package

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.RemoteViews
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

class FloatingLyricsService : Service() {

    companion object {
        var isRunning = false
    }

    data class LyricLine(val timeMs: Long, val text: String)

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var floatingParams: WindowManager.LayoutParams
    private lateinit var notificationManager: NotificationManager

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "lyrics_lock_screen_channel_V2"
    private var currentLyricIndex = -2
    private var isFloatingViewAttached = false

    // Absolute Scrolling & Freezing States
    private var manualScrollIndex = -1
    private var touchLockIndex = -1
    private var lastRenderedActiveIndex = -2
    private val scrollResetHandler = Handler(Looper.getMainLooper())
    private val scrollResetRunnable = Runnable { manualScrollIndex = -1 }

    private var parsedLyrics = listOf<LyricLine>()
    private var syncJob: Job? = null

    private var currentPositionMs = 0L
    private var isMusicPlaying = false
    private var lastUpdateTime = 0L
    private var currentTrackName = ""

    private val lyricsSources: List<LyricsProvider> = listOf(
        LrcLibProvider(),
        UnisonProvider(),
        YouTubeCaptionProvider()
    )

    private val lyricsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val trackName = intent?.getStringExtra("track_name")
            val artistName = intent?.getStringExtra("artist_name")

            currentPositionMs = intent?.getLongExtra("position_ms", 0L) ?: 0L
            isMusicPlaying = intent?.getBooleanExtra("is_playing", false) ?: false
            lastUpdateTime = System.currentTimeMillis()

            if (trackName != null) {
                if (trackName != currentTrackName) {
                    currentTrackName = trackName
                    syncJob?.cancel()
                    parsedLyrics = emptyList()
                    currentLyricIndex = -2
                    lastRenderedActiveIndex = -2
                    manualScrollIndex = -1
                    touchLockIndex = -1
                    setStatusText("Searching: $trackName...")
                    fetchLyrics(trackName, artistName ?: "")
                } else {
                    startSyncTimer()
                }
            }
        }
    }

    private val displayPrefsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyDisplayPreferences()
        }
    }

    private val colorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyCustomColors()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Lock Screen Lyrics", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)

        startForeground(NOTIFICATION_ID, buildNotification("", "Waiting for music...", ""))
        isRunning = true
        requestTileUpdate()

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            floatingView = inflater.inflate(R.layout.layout_floating_lyrics, null)

            floatingParams = WindowManager.LayoutParams(
                (300 * resources.displayMetrics.density).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 0
            }

            setupGestureControls()
            applyDisplayPreferences()
            applyCustomColors()

            ContextCompat.registerReceiver(this, lyricsReceiver, IntentFilter("UPDATE_LYRICS"), ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this, colorReceiver, IntentFilter("UPDATE_COLORS"), ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this, displayPrefsReceiver, IntentFilter("UPDATE_DISPLAY_PREFS"), ContextCompat.RECEIVER_NOT_EXPORTED)

            Handler(Looper.getMainLooper()).postDelayed({
                sendBroadcast(Intent("REQUEST_CURRENT_TRACK").apply { setPackage(packageName) })
            }, 300)

        } catch (e: Exception) {
            Toast.makeText(this, "CRASH: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyDisplayPreferences() {
        val prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
        val showFloating = prefs.getBoolean("pref_floating_box", true)

        if (showFloating && !isFloatingViewAttached) {
            try {
                windowManager.addView(floatingView, floatingParams)
                isFloatingViewAttached = true
            } catch (e: Exception) {}
        } else if (!showFloating && isFloatingViewAttached) {
            try {
                windowManager.removeView(floatingView)
                isFloatingViewAttached = false
            } catch (e: Exception) {}
        }

        if (parsedLyrics.isNotEmpty() && currentLyricIndex in parsedLyrics.indices) {
            val prev = if (currentLyricIndex > 0) parsedLyrics[currentLyricIndex - 1].text else ""
            val curr = parsedLyrics[currentLyricIndex].text
            val next = if (currentLyricIndex < parsedLyrics.size - 1) parsedLyrics[currentLyricIndex + 1].text else ""
            notificationManager.notify(NOTIFICATION_ID, buildNotification(prev, curr, next))
        }
    }

    private fun resetScrollTimer() {
        scrollResetHandler.removeCallbacks(scrollResetRunnable)
        scrollResetHandler.postDelayed(scrollResetRunnable, 4000)
    }

    private fun setupGestureControls() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        // 1. Dedicated Drag Handle
        val dragHandle = floatingView.findViewById<View>(R.id.dedicated_drag_handle)
        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingParams.x
                    initialY = floatingParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    floatingParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    floatingParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    if (isFloatingViewAttached) windowManager.updateViewLayout(floatingView, floatingParams)
                    true
                }
                else -> false
            }
        }

        // 2. Main Window Controls
        var lastTouchY = 0f
        var tapCount = 0
        var lastTapTime = 0L
        var isDraggingAllowed = false
        var hasScrolled = false

        val handler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable { isDraggingAllowed = true }

        floatingView.findViewById<View>(R.id.lyrics_container).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingParams.x
                    initialY = floatingParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    lastTouchY = event.rawY
                    hasScrolled = false

                    // FREEZE the viewport at the exact current line
                    touchLockIndex = Math.max(0, currentLyricIndex)
                    scrollResetHandler.removeCallbacks(scrollResetRunnable)

                    isDraggingAllowed = false
                    handler.postDelayed(longPressRunnable, 400)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)

                    if (!isDraggingAllowed && (dx > 15f || dy > 15f)) {
                        handler.removeCallbacks(longPressRunnable)
                    }

                    if (isDraggingAllowed && isFloatingViewAttached) {
                        floatingParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        floatingParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, floatingParams)
                    } else if (!isDraggingAllowed && parsedLyrics.isNotEmpty()) {
                        val deltaY = event.rawY - lastTouchY
                        val scrollThreshold = 60f

                        if (deltaY < -scrollThreshold) {
                            if (manualScrollIndex == -1) manualScrollIndex = touchLockIndex
                            val maxIndex = parsedLyrics.size - 1
                            if (manualScrollIndex < maxIndex) {
                                manualScrollIndex++
                                lastTouchY = event.rawY
                                hasScrolled = true
                            }
                        } else if (deltaY > scrollThreshold) {
                            if (manualScrollIndex == -1) manualScrollIndex = touchLockIndex
                            if (manualScrollIndex > 0) {
                                manualScrollIndex--
                                lastTouchY = event.rawY
                                hasScrolled = true
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    isDraggingAllowed = false
                    touchLockIndex = -1 // Release the holding lock

                    // Only start auto-reset if they actively have a scrolled index set
                    if (manualScrollIndex != -1) {
                        resetScrollTimer()
                    }

                    if (!hasScrolled && Math.abs(event.rawX - initialTouchX) < 10f && Math.abs(event.rawY - initialTouchY) < 10f) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastTapTime < 350L) tapCount++ else tapCount = 1
                        lastTapTime = currentTime

                        if (tapCount == 2) {
                            floatingParams.x = 0
                            floatingParams.y = 0
                            if (isFloatingViewAttached) windowManager.updateViewLayout(floatingView, floatingParams)
                            Toast.makeText(this@FloatingLyricsService, "Position reset", Toast.LENGTH_SHORT).show()
                        } else if (tapCount == 3) {
                            Toast.makeText(this@FloatingLyricsService, "Lyrics closed", Toast.LENGTH_SHORT).show()
                            stopSelf()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        requestTileUpdate()
        syncJob?.cancel()
        scrollResetHandler.removeCallbacks(scrollResetRunnable)
        notificationManager.cancel(NOTIFICATION_ID)
        try {
            unregisterReceiver(colorReceiver)
            unregisterReceiver(lyricsReceiver)
            unregisterReceiver(displayPrefsReceiver)
        } catch (e: Exception) { }
        if (isFloatingViewAttached) {
            windowManager.removeView(floatingView)
        }
    }

    private fun requestTileUpdate() {
        try { TileService.requestListeningState(this, ComponentName(this, LyricsTileService::class.java)) } catch (e: Exception) { }
    }

    private fun setStatusText(message: String) {
        if (isFloatingViewAttached) {
            floatingView.findViewById<TextView>(R.id.tv_dynamic_lyrics).text = message
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification("", message, ""))
    }

    private suspend fun getBestLyrics(trackName: String, artistName: String, onProgress: suspend (String) -> Unit): String? {
        return withContext(Dispatchers.IO) {
            for (provider in lyricsSources) {
                withContext(Dispatchers.Main) { onProgress(provider.name) }
                val lyrics = provider.fetchLyrics(trackName, artistName)
                if (!lyrics.isNullOrBlank()) return@withContext lyrics
            }
            return@withContext null
        }
    }

    private fun fetchLyrics(title: String, artist: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val finalLyrics = getBestLyrics(title, artist) { providerName -> setStatusText("Searching $providerName...") }
                if (!finalLyrics.isNullOrEmpty()) {
                    if (finalLyrics.contains("[00:")) {
                        val parsed = parseLrc(finalLyrics)
                        withContext(Dispatchers.Main) {
                            parsedLyrics = parsed
                            startSyncTimer()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            parsedLyrics = emptyList()
                            setStatusText("No synced lyrics.\n\n$finalLyrics")
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
                val text = match.groupValues[4].trim()
                if (text.isNotEmpty()) lines.add(LyricLine((min * 60 * 1000) + (sec * 1000) + mil, text))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun startSyncTimer() {
        syncJob?.cancel()
        if (!isMusicPlaying || parsedLyrics.isEmpty()) return

        syncJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val estimatedPosition = currentPositionMs + (System.currentTimeMillis() - lastUpdateTime)
                val currentIndex = parsedLyrics.indexOfLast { it.timeMs <= estimatedPosition }

                // 1. Determine what the viewport SHOULD be displaying right now
                val activeIndex = if (manualScrollIndex != -1) {
                    manualScrollIndex // Show what the user manually scrolled to
                } else if (touchLockIndex != -1) {
                    touchLockIndex // Freeze on the line where the user touched
                } else {
                    currentIndex // Show the actual live song progress
                }

                val indexChanged = (currentIndex != currentLyricIndex || currentLyricIndex == -2)
                val viewChanged = (activeIndex != lastRenderedActiveIndex)

                // 2. Only re-render if the song moved forward OR the user dragged the screen
                if (indexChanged || viewChanged) {
                    currentLyricIndex = currentIndex
                    lastRenderedActiveIndex = activeIndex

                    val prevText: String
                    val currentText: String
                    val nextText: String

                    if (currentIndex == -1) {
                        prevText = ""
                        currentText = "♫ $currentTrackName ♫"
                        nextText = if (parsedLyrics.isNotEmpty()) parsedLyrics[0].text else ""
                    } else {
                        prevText = if (currentIndex > 0) parsedLyrics[currentIndex - 1].text else ""
                        currentText = parsedLyrics[currentIndex].text
                        nextText = if (currentIndex < parsedLyrics.size - 1) parsedLyrics[currentIndex + 1].text else ""
                    }

                    // Update Floating Box
                    if (isFloatingViewAttached) {
                        if (activeIndex == -1) {
                            floatingView.findViewById<TextView>(R.id.tv_dynamic_lyrics).text = "$currentText\n$nextText"
                        } else {
                            val prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
                            val visibleLines = prefs.getInt("pref_default_lines", 3)
                            val halfOffset = visibleLines / 2

                            val builder = SpannableStringBuilder()
                            val parsedTextColor = Color.parseColor(prefs.getString("text_color", "#FFFFFFFF"))
                            val semiTransparentTextColor = Color.argb(128, Color.red(parsedTextColor), Color.green(parsedTextColor), Color.blue(parsedTextColor))

                            for (i in (activeIndex - halfOffset)..(activeIndex + halfOffset)) {
                                if (i in parsedLyrics.indices) {
                                    val start = builder.length
                                    val lineText = parsedLyrics[i].text + "\n"
                                    builder.append(lineText)

                                    // Highlight tracks the audio accurately even if the window is frozen
                                    val color = if (i == currentIndex) parsedTextColor else semiTransparentTextColor
                                    builder.setSpan(ForegroundColorSpan(color), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                } else {
                                    builder.append("\n")
                                }
                            }
                            floatingView.findViewById<TextView>(R.id.tv_dynamic_lyrics).text = builder.trimEnd()
                        }
                    }

                    // Update Notification Panel (Only if the actual song progressed)
                    if (indexChanged && manualScrollIndex == -1) {
                        notificationManager.notify(NOTIFICATION_ID, buildNotification(prevText, currentText, nextText))
                    }
                }
                delay(100)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildNotification(prev: String, current: String, next: String): android.app.Notification {
        val prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
        val showCustomNotif = prefs.getBoolean("pref_notification", true)
        val showOnLockScreen = prefs.getBoolean("pref_lock_screen", true)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(if (showOnLockScreen) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_SECRET)

        if (showCustomNotif) {
            val customView = RemoteViews(packageName, R.layout.layout_notification_lyrics)
            customView.setTextViewText(R.id.notif_tv_prev, prev)
            customView.setTextViewText(R.id.notif_tv_current, current)
            customView.setTextViewText(R.id.notif_tv_next, next)
            builder.setCustomContentView(customView)
        } else {
            builder.setContentTitle("Dynamic Lyrics Active")
            builder.setContentText(if (currentTrackName.isNotEmpty()) "Syncing: $currentTrackName" else "Running in background")
        }

        return builder.build()
    }

    private fun applyCustomColors() {
        if (!::floatingView.isInitialized) return
        val prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
        try {
            val parsedBgColor = Color.parseColor(prefs.getString("bg_color", "#FF000000") ?: "#FF000000")

            val backgroundShape = GradientDrawable()
            backgroundShape.shape = GradientDrawable.RECTANGLE
            backgroundShape.cornerRadius = 32f
            backgroundShape.setColor(parsedBgColor)

            floatingView.findViewById<View>(R.id.lyrics_container).background = backgroundShape
        } catch (e: Exception) { }
    }
}