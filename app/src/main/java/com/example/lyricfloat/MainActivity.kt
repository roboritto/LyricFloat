package com.example.lyricfloat // Verify this matches your app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var switchStartService: SwitchCompat
    private lateinit var switchFloatingBox: SwitchCompat
    private lateinit var switchNotification: SwitchCompat
    private lateinit var switchLockScreen: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
        val seekDefaultLines = findViewById<SeekBar>(R.id.seek_default_lines)

        // 1. Core Controls
        val btnGrantAccess = findViewById<Button>(R.id.btn_notification_access)
        switchStartService = findViewById(R.id.switch_start_service)

        btnGrantAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        switchStartService.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!buttonView.isPressed) return@setOnCheckedChangeListener
            if (isChecked) {
                if (!Settings.canDrawOverlays(this) && prefs.getBoolean("pref_floating_box", true)) {
                    Toast.makeText(this, "Please grant Overlay Permission first!", Toast.LENGTH_SHORT).show()
                    switchStartService.isChecked = false
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                    return@setOnCheckedChangeListener
                }
                ContextCompat.startForegroundService(this, Intent(this, FloatingLyricsService::class.java))
            } else {
                stopService(Intent(this, FloatingLyricsService::class.java))
            }
        }

        // 2. Display Toggles
        switchFloatingBox = findViewById(R.id.switch_floating_box)
        switchNotification = findViewById(R.id.switch_notification)
        switchLockScreen = findViewById(R.id.switch_lock_screen)

        switchFloatingBox.isChecked = prefs.getBoolean("pref_floating_box", true)
        switchFloatingBox.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                prefs.edit().putBoolean("pref_floating_box", isChecked).apply()
                sendBroadcast(Intent("UPDATE_DISPLAY_PREFS").apply { setPackage(packageName) })
            }
        }

        switchNotification.isChecked = prefs.getBoolean("pref_notification", true)
        switchNotification.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                prefs.edit().putBoolean("pref_notification", isChecked).apply()

                // If disabling the main notification, force disable the lock screen
                if (!isChecked && switchLockScreen.isChecked) {
                    switchLockScreen.isChecked = false
                    prefs.edit().putBoolean("pref_lock_screen", false).apply()
                }
                sendBroadcast(Intent("UPDATE_DISPLAY_PREFS").apply { setPackage(packageName) })
            }
        }

        switchLockScreen.isChecked = prefs.getBoolean("pref_lock_screen", true)
        switchLockScreen.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                prefs.edit().putBoolean("pref_lock_screen", isChecked).apply()

                // If enabling the lock screen, force enable the main notification
                if (isChecked && !switchNotification.isChecked) {
                    switchNotification.isChecked = true
                    prefs.edit().putBoolean("pref_notification", true).apply()
                }
                sendBroadcast(Intent("UPDATE_DISPLAY_PREFS").apply { setPackage(packageName) })
            }
        }


        // 3. Theme & Colors (Unchanged)
        val etBgColor = findViewById<EditText>(R.id.et_bg_color)
        val etTextColor = findViewById<EditText>(R.id.et_text_color)
        val btnSave = findViewById<Button>(R.id.btn_save_colors)
        val previewContainer = findViewById<View>(R.id.preview_container)
        val previewPrev = findViewById<TextView>(R.id.preview_prev)
        val previewCurrent = findViewById<TextView>(R.id.preview_current)
        val previewNext = findViewById<TextView>(R.id.preview_next)
        val seekTransparency = findViewById<SeekBar>(R.id.seek_transparency)

        fun updatePreview() {
            try {
                val bgHex = etBgColor.text.toString().trim()
                val textHex = etTextColor.text.toString().trim()
                val parsedBgColor = Color.parseColor(bgHex)
                val parsedTextColor = Color.parseColor(textHex)

                val backgroundShape = GradientDrawable()
                backgroundShape.shape = GradientDrawable.RECTANGLE
                backgroundShape.cornerRadius = 32f
                backgroundShape.setColor(parsedBgColor)
                previewContainer.background = backgroundShape

                val semiTransparentText = Color.argb(128, Color.red(parsedTextColor), Color.green(parsedTextColor), Color.blue(parsedTextColor))
                previewCurrent.setTextColor(parsedTextColor)
                previewPrev.setTextColor(semiTransparentText)
                previewNext.setTextColor(semiTransparentText)
                seekTransparency.progress = Color.alpha(parsedBgColor)
            } catch (e: Exception) {}
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        }
        etBgColor.addTextChangedListener(textWatcher)
        etTextColor.addTextChangedListener(textWatcher)

        seekTransparency.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    try {
                        val currentColor = Color.parseColor(etBgColor.text.toString().trim())
                        val newColor = Color.argb(progress, Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                        etBgColor.setText(String.format("#%08X", -0x1 and newColor))
                    } catch (e: Exception) {}
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        etBgColor.setText(prefs.getString("bg_color", "#FF000000"))
        etTextColor.setText(prefs.getString("text_color", "#FFFFFFFF"))

        btnSave.setOnClickListener {
            try {
                Color.parseColor(etBgColor.text.toString().trim())
                Color.parseColor(etTextColor.text.toString().trim())
                prefs.edit().putString("bg_color", etBgColor.text.toString().trim()).putString("text_color", etTextColor.text.toString().trim()).apply()
                sendBroadcast(Intent("UPDATE_COLORS").apply { setPackage(packageName) })
                Toast.makeText(this, "Colors applied!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Invalid Hex Code", Toast.LENGTH_SHORT).show()
            }
        }

        // Load saved default lines (Default to progress 1, which equals 3 lines)
        val savedProgress = prefs.getInt("pref_lines_progress", 1)
        seekDefaultLines.progress = savedProgress

        seekDefaultLines.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val lines = (progress * 2) + 1 // Maps 0,1,2,3,4 to 1,3,5,7,9
                    prefs.edit()
                        .putInt("pref_lines_progress", progress)
                        .putInt("pref_default_lines", lines)
                        .apply()
                    Toast.makeText(this@MainActivity, "Default set to $lines lines", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn_theme_dark).setOnClickListener { etBgColor.setText("#FF000000"); etTextColor.setText("#FFFFFFFF"); btnSave.performClick() }
        findViewById<Button>(R.id.btn_theme_light).setOnClickListener { etBgColor.setText("#FFFFFFFF"); etTextColor.setText("#FF000000"); btnSave.performClick() }
        findViewById<Button>(R.id.btn_theme_green).setOnClickListener { etBgColor.setText("#FF191414"); etTextColor.setText("#FF1DB954"); btnSave.performClick() }
    }

    override fun onResume() {
        super.onResume()
        if (::switchStartService.isInitialized) {
            switchStartService.isChecked = FloatingLyricsService.isRunning
        }
    }
}