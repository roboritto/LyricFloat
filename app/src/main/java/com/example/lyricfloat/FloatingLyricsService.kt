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
import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
    private var isResizing = false
    private var isFloatingViewAttached = false
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

            // NEW: Force the widget to redraw with the new colors immediately
            val widgetIntent = Intent(this@FloatingLyricsService, LyricsWidgetProvider::class.java).apply {
                action = "UPDATE_COLORS"
            }
            sendBroadcast(widgetIntent)
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
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                x = 0
                y = 0
            }

            // --- 6-Dot Drag Handle Logic ---
            val dragHandle = floatingView.findViewById<android.widget.ImageView>(R.id.drag_handle)
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            dragHandle.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initialX = floatingParams.x
                        initialY = floatingParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        floatingParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        floatingParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, floatingParams)
                        true
                    }
                    else -> false
                }
            }

            // --- Dynamic Line Expansion Handle ---
            val expandHandle = floatingView.findViewById<android.widget.ImageView>(R.id.resize_handle)
            var initialLines = 3
            var expandInitialTouchY = 0f
            val dragThresholdPx = 100f // Lowered back down for smoother response

            expandHandle.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        isResizing = true // Block background widget/notification sync

                        val prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
                        initialLines = prefs.getInt("pref_default_lines", 3)
                        expandInitialTouchY = event.rawY
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val deltaY = event.rawY - expandInitialTouchY
                        val lineOffset = (deltaY / dragThresholdPx).toInt()

                        var newLines = initialLines + lineOffset
                        newLines = Math.max(1, Math.min(newLines, 7))

                        val prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
                        if (prefs.getInt("pref_default_lines", 3) != newLines) {
                            prefs.edit().putInt("pref_default_lines", newLines).apply()
                            applyDisplayPreferences() // This will now ONLY update the local UI
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        isResizing = false // Re-enable background sync

                        // Push one final update to sync the widget and notification
                        applyDisplayPreferences()
                        sendBroadcast(android.content.Intent("UPDATE_DISPLAY_PREFS").apply { setPackage(packageName) })
                        true
                    }
                    else -> false
                }
            }

            // Setup scrolling and taps
            setupGestureControls()
            applyDisplayPreferences()
            applyCustomColors()

            androidx.core.content.ContextCompat.registerReceiver(this, lyricsReceiver, android.content.IntentFilter("UPDATE_LYRICS"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            androidx.core.content.ContextCompat.registerReceiver(this, colorReceiver, android.content.IntentFilter("UPDATE_COLORS"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            androidx.core.content.ContextCompat.registerReceiver(this, displayPrefsReceiver, android.content.IntentFilter("UPDATE_DISPLAY_PREFS"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                sendBroadcast(android.content.Intent("REQUEST_CURRENT_TRACK").apply { setPackage(packageName) })
            }, 300)

        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "CRASH: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
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

            // Only update system UI if we aren't currently dragging the resize handle
            if (!isResizing) {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(prev, curr, next))

                val wPrev2 = if (currentLyricIndex > 1) parsedLyrics[currentLyricIndex - 2].text else ""
                val wNext2 = if (currentLyricIndex < parsedLyrics.size - 2) parsedLyrics[currentLyricIndex + 2].text else ""
                updateHomeScreenWidget(wPrev2, prev, curr, next, wNext2)
            }
        } else {
            // Do the same for the fallback empty state
            if (!isResizing) {
                val titleText = if (currentTrackName.isNotEmpty()) "♫ $currentTrackName ♫" else "Waiting for music..."
                updateHomeScreenWidget("", "", titleText, "", "")
            }
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
                    touchLockIndex = -1

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
                            // Hide the box instead of killing the service
                            val prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("pref_floating_box", false).apply()
                            sendBroadcast(Intent("UPDATE_DISPLAY_PREFS").apply { setPackage(packageName) })
                            Toast.makeText(this@FloatingLyricsService, "Floating Box Hidden", Toast.LENGTH_SHORT).show()
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

        // Reset the widget when the service dies
        updateHomeScreenWidget("", "", "Lyric Float is off", "", "")

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

    private fun updateHomeScreenWidget(prev2: String, prev1: String, current: String, next1: String, next2: String) {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val thisWidget = ComponentName(this, LyricsWidgetProvider::class.java)
        val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

        if (allWidgetIds.isEmpty()) return

        val views = RemoteViews(packageName, R.layout.widget_lyrics)
        views.setTextViewText(R.id.widget_tv_prev2, prev2)
        views.setTextViewText(R.id.widget_tv_prev1, prev1)
        views.setTextViewText(R.id.widget_tv_current, current)
        views.setTextViewText(R.id.widget_tv_next1, next1)
        views.setTextViewText(R.id.widget_tv_next2, next2)

        val prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
        val useAlbumArt = prefs.getBoolean("pref_widget_album_art", true)

        if (useAlbumArt && currentAlbumArt != null) {
            views.setImageViewBitmap(R.id.widget_background, currentAlbumArt)
            views.setInt(R.id.widget_background, "setColorFilter", Color.parseColor("#99000000"))
        } else {
            val parsedBgColor = Color.parseColor(prefs.getString("bg_color", "#FF000000") ?: "#FF000000")
            views.setImageViewBitmap(R.id.widget_background, null)
            views.setInt(R.id.widget_background, "setColorFilter", parsedBgColor)
        }

        appWidgetManager.updateAppWidget(thisWidget, views)
    }

    private fun setStatusText(message: String) {
        if (isFloatingViewAttached) {
            floatingView.findViewById<TextView>(R.id.tv_dynamic_lyrics).text = message
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification("", message, ""))
        updateHomeScreenWidget("", "", message, "", "")
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

                        // NEW: Fetch the album art before starting the timer
                        currentAlbumArt = fetchAlbumArt(title, artist)

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

                    // ALWAYS update the Home Screen Widget with LIVE lyrics (ignoring manual scrolls)
                    if (indexChanged) {
                        val wPrev2 = if (currentIndex > 1) parsedLyrics[currentIndex - 2].text else ""
                        val wPrev1 = prevText // Reuse the already calculated string
                        val wCurr = currentText // Reuse the already calculated string
                        val wNext1 = nextText // Reuse the already calculated string
                        val wNext2 = if (currentIndex >= -1 && currentIndex < parsedLyrics.size - 2) parsedLyrics[currentIndex + 2].text else ""

                        updateHomeScreenWidget(wPrev2, wPrev1, wCurr, wNext1, wNext2)
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

    // Add this near your other state variables (like currentLyricIndex)
    private var currentAlbumArt: Bitmap? = null

    // Add this helper function inside the class
    private suspend fun fetchAlbumArt(track: String, artist: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val query = URLEncoder.encode("$track $artist", "UTF-8")
                val url = "https://itunes.apple.com/search?term=$query&entity=song&limit=1"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)

                    if (json.getInt("resultCount") > 0) {
                        val item = json.getJSONArray("results").getJSONObject(0)
                        // The API returns a 100x100 thumbnail by default. We replace the string to request a crisp 600x600 image instead.
                        val artworkUrl = item.getString("artworkUrl100").replace("100x100bb", "600x600bb")

                        val imageConn = URL(artworkUrl).openConnection() as HttpURLConnection
                        val rawBitmap = BitmapFactory.decodeStream(imageConn.inputStream)

                        // Apply a 64-pixel radius to the corners
                        return@withContext getRoundedCropBitmap(rawBitmap, 48f)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            null
        }
    }

    private fun getRoundedCropBitmap(bitmap: Bitmap, cornerRadius: Float): Bitmap {
        // Force the image into a wide 2:1 aspect ratio to fit the 4x2 widget
        val targetWidth = 800
        val targetHeight = 400

        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)

        // Calculate how much to scale and shift the image to perfectly center it
        val scale = Math.max(targetWidth.toFloat() / bitmap.width, targetHeight.toFloat() / bitmap.height)
        val scaledWidth = scale * bitmap.width
        val scaledHeight = scale * bitmap.height
        val left = (targetWidth - scaledWidth) / 2f
        val top = (targetHeight - scaledHeight) / 2f

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
        }

        // Draw the rounded mask
        val targetRect = android.graphics.RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawRoundRect(targetRect, cornerRadius, cornerRadius, paint)

        // Draw the album art inside the mask
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        val matrix = android.graphics.Matrix().apply {
            postScale(scale, scale)
            postTranslate(left, top)
        }
        canvas.drawBitmap(bitmap, matrix, paint)

        return output
    }
}