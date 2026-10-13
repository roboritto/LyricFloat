package com.example.lyricfloat // Ensure this matches your package name

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import android.widget.EditText

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    // Track the current hex strings so the live preview can use them
    private var currentBgHex = "#FF000000"
    private var currentTextHex = "#FFFFFFFF"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)

        // --- Permissions ---
        findViewById<Button>(R.id.btn_notification_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // --- 1. Main Service Toggle ---
        val switchService = findViewById<SwitchCompat>(R.id.switch_start_service)
        switchService.isChecked = FloatingLyricsService.isRunning

        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    switchService.isChecked = false
                } else {
                    startForegroundService(Intent(this, FloatingLyricsService::class.java))
                }
            } else {
                stopService(Intent(this, FloatingLyricsService::class.java))
            }
        }

        // --- 2. Display Toggles ---
        val switchFloating = findViewById<SwitchCompat>(R.id.switch_floating_box)
        val switchNotif = findViewById<SwitchCompat>(R.id.switch_notification)
        val switchLock = findViewById<SwitchCompat>(R.id.switch_lock_screen)
        val switchWidgetArt = findViewById<SwitchCompat>(R.id.switch_widget_album_art)

        switchFloating.isChecked = prefs.getBoolean("pref_floating_box", true)
        switchNotif.isChecked = prefs.getBoolean("pref_notification", true)
        switchLock.isChecked = prefs.getBoolean("pref_lock_screen", true)
        switchWidgetArt.isChecked = prefs.getBoolean("pref_widget_album_art", true)

        val displayToggleListener = { _: View?, _: Boolean ->
            prefs.edit().apply {
                putBoolean("pref_floating_box", switchFloating.isChecked)
                putBoolean("pref_notification", switchNotif.isChecked)
                putBoolean("pref_lock_screen", switchLock.isChecked)
                putBoolean("pref_widget_album_art", switchWidgetArt.isChecked)
            }.apply()
            sendBroadcast(Intent("UPDATE_DISPLAY_PREFS").apply { setPackage(packageName) })
        }

        switchFloating.setOnCheckedChangeListener(displayToggleListener)
        switchNotif.setOnCheckedChangeListener(displayToggleListener)
        switchLock.setOnCheckedChangeListener(displayToggleListener)
        switchWidgetArt.setOnCheckedChangeListener(displayToggleListener)

        // --- 3. Color Customization (Manual & Visual Picker) ---
        val etBgColor = findViewById<EditText>(R.id.et_bg_color)
        val etTextColor = findViewById<EditText>(R.id.et_text_color)
        val btnPickBgColor = findViewById<Button>(R.id.btn_pick_bg_color)
        val btnPickTextColor = findViewById<Button>(R.id.btn_pick_text_color)
        val btnSaveColors = findViewById<Button>(R.id.btn_save_colors)

        currentBgHex = prefs.getString("bg_color", "#FF000000") ?: "#FF000000"
        currentTextHex = prefs.getString("text_color", "#FFFFFFFF") ?: "#FFFFFFFF"
        etBgColor.setText(currentBgHex)
        etTextColor.setText(currentTextHex)

        btnPickBgColor.setOnClickListener {
            ColorPickerDialog.Builder(this)
                .setTitle("Pick Background Color")
                .setPreferenceName("BgColorPickerDialog")
                .setPositiveButton("Select", ColorEnvelopeListener { envelope, _ ->
                    currentBgHex = "#" + envelope.hexCode
                    etBgColor.setText(currentBgHex)
                    updateLivePreview()
                })
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .attachAlphaSlideBar(false)
                .attachBrightnessSlideBar(true)
                .show()
        }

        btnPickTextColor.setOnClickListener {
            ColorPickerDialog.Builder(this)
                .setTitle("Pick Text Color")
                .setPreferenceName("TextColorPickerDialog")
                .setPositiveButton("Select", ColorEnvelopeListener { envelope, _ ->
                    currentTextHex = "#" + envelope.hexCode
                    etTextColor.setText(currentTextHex)
                    updateLivePreview()
                })
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .attachAlphaSlideBar(false)
                .attachBrightnessSlideBar(true)
                .show()
        }

        btnSaveColors.setOnClickListener {
            // Grab whatever is currently typed in the fields
            currentBgHex = etBgColor.text.toString().trim()
            currentTextHex = etTextColor.text.toString().trim()

            // Validate and update the preview instantly
            updateLivePreview()

            prefs.edit().apply {
                putString("bg_color", currentBgHex)
                putString("text_color", currentTextHex)
            }.apply()

            sendBroadcast(Intent("UPDATE_COLORS").apply { setPackage(packageName) })
            Toast.makeText(this, "Colors Applied!", Toast.LENGTH_SHORT).show()
        }

        // --- 4. Sliders (Transparency & Visible Lines) ---
        val seekTransparency = findViewById<SeekBar>(R.id.seek_transparency)
        val seekLines = findViewById<SeekBar>(R.id.seek_default_lines)

        try {
            val currentAlpha = Integer.parseInt(currentBgHex.substring(1, 3), 16)
            seekTransparency.progress = currentAlpha
        } catch (e: Exception) {
            seekTransparency.progress = 255
        }

        seekTransparency.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val typedBg = etBgColor.text.toString().trim()
                    if (typedBg.length >= 7) {
                        val alphaHex = String.format("%02X", progress)
                        // Inject the new alpha into the currently typed hex string
                        currentBgHex = "#" + alphaHex + typedBg.substring(typedBg.length - 6)
                        etBgColor.setText(currentBgHex)
                        updateLivePreview()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putString("bg_color", currentBgHex).apply()
                sendBroadcast(Intent("UPDATE_COLORS").apply { setPackage(packageName) })
            }
        })

        seekLines.progress = prefs.getInt("pref_default_lines", 3)
        seekLines.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val lines = Math.max(1, seekBar?.progress ?: 3)
                prefs.edit().putInt("pref_default_lines", lines).apply()
                Toast.makeText(this@MainActivity, "Lines set to $lines", Toast.LENGTH_SHORT).show()
            }
        })

        // --- 5. Preset Themes ---
        val btnDark = findViewById<Button>(R.id.btn_theme_dark)
        val btnLight = findViewById<Button>(R.id.btn_theme_light)
        val btnGreen = findViewById<Button>(R.id.btn_theme_green)

        val applyPreset = { bg: String, text: String ->
            currentBgHex = bg
            currentTextHex = text
            etBgColor.setText(currentBgHex)
            etTextColor.setText(currentTextHex)

            try { seekTransparency.progress = Integer.parseInt(bg.substring(1, 3), 16) } catch (e: Exception) {}

            prefs.edit().apply {
                putString("bg_color", currentBgHex)
                putString("text_color", currentTextHex)
            }.apply()

            updateLivePreview()
            sendBroadcast(Intent("UPDATE_COLORS").apply { setPackage(packageName) })
        }

        btnDark.setOnClickListener { applyPreset("#FF000000", "#FFFFFFFF") }
        btnLight.setOnClickListener { applyPreset("#FFFFFFFF", "#FF000000") }
        btnGreen.setOnClickListener { applyPreset("#FF1DB954", "#FFFFFFFF") }

        // Initial UI Draw
        updateLivePreview()
    }

    /**
     * Instantly updates the preview box to match the selected hex strings and transparencies
     */
    private fun updateLivePreview() {
        try {
            val parsedBgColor = Color.parseColor(currentBgHex)
            val parsedTextColor = Color.parseColor(currentTextHex)

            // Calculate a 50% faded version of the text color for the inactive lines
            val semiTransparentTextColor = Color.argb(128, Color.red(parsedTextColor), Color.green(parsedTextColor), Color.blue(parsedTextColor))

            // Apply to Preview Background (matching the 32dp corner radius of the actual floating box)
            val backgroundShape = GradientDrawable()
            backgroundShape.shape = GradientDrawable.RECTANGLE
            backgroundShape.cornerRadius = 32f * resources.displayMetrics.density
            backgroundShape.setColor(parsedBgColor)
            findViewById<View>(R.id.preview_container).background = backgroundShape

            // Apply to Preview Text
            findViewById<TextView>(R.id.preview_prev).setTextColor(semiTransparentTextColor)
            findViewById<TextView>(R.id.preview_current).setTextColor(parsedTextColor)
            findViewById<TextView>(R.id.preview_next).setTextColor(semiTransparentTextColor)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}