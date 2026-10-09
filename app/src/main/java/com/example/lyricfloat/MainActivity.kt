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

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ==========================================
        // 1. ORIGINAL PERMISSION & START BUTTONS
        // ==========================================
        // (Make sure these IDs match your activity_main.xml)
        val btnGrantAccess = findViewById<Button>(R.id.btn_notification_access) // Check your ID
        val btnStartService = findViewById<Button>(R.id.btn_start_service) // Check your ID

        btnGrantAccess.setOnClickListener {
            // Opens the Android settings page to grant Notification Access
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        btnStartService.setOnClickListener {
            // Starts the foreground service
            val serviceIntent = Intent(this, FloatingLyricsService::class.java)
            startForegroundService(serviceIntent)
        }

        // ==========================================
        // 2. COLOR CUSTOMIZATION & LIVE PREVIEW
        // ==========================================
        val prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
        val etBgColor = findViewById<EditText>(R.id.et_bg_color)
        val etTextColor = findViewById<EditText>(R.id.et_text_color)
        val btnSave = findViewById<Button>(R.id.btn_save_colors)

        val previewContainer = findViewById<View>(R.id.preview_container)
        val previewPrev = findViewById<TextView>(R.id.preview_prev)
        val previewCurrent = findViewById<TextView>(R.id.preview_current)
        val previewNext = findViewById<TextView>(R.id.preview_next)
        val seekTransparency = findViewById<SeekBar>(R.id.seek_transparency)

        // Helper function to update the Live Preview UI instantly
        fun updatePreview() {
            try {
                val bgHex = etBgColor.text.toString().trim()
                val textHex = etTextColor.text.toString().trim()

                val parsedBgColor = Color.parseColor(bgHex)
                val parsedTextColor = Color.parseColor(textHex)

                // Update Background with rounded corners
                val backgroundShape = GradientDrawable()
                backgroundShape.shape = GradientDrawable.RECTANGLE
                backgroundShape.cornerRadius = 32f
                backgroundShape.setColor(parsedBgColor)
                previewContainer.background = backgroundShape

                // Update Text Colors (Calculate 50% transparency for prev/next)
                val semiTransparentText = Color.argb(
                    128, Color.red(parsedTextColor), Color.green(parsedTextColor), Color.blue(parsedTextColor)
                )

                previewCurrent.setTextColor(parsedTextColor)
                previewPrev.setTextColor(semiTransparentText)
                previewNext.setTextColor(semiTransparentText)

                // Sync the slider position to match the currently typed hex alpha
                seekTransparency.progress = Color.alpha(parsedBgColor)

            } catch (e: Exception) {
                // Ignore incomplete typing like "#FF" before it becomes a valid color
            }
        }

        // Add TextWatchers so typing updates the preview immediately
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        }
        etBgColor.addTextChangedListener(textWatcher)
        etTextColor.addTextChangedListener(textWatcher)

        // Handle the Transparency Slider
        seekTransparency.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    try {
                        // Keep the current RGB, but swap out the Alpha (Transparency)
                        val currentColor = Color.parseColor(etBgColor.text.toString().trim())
                        val newColor = Color.argb(
                            progress, Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor)
                        )
                        // Update the EditText with the new Hex, which auto-triggers the TextWatcher
                        val hexString = String.format("#%08X", -0x1 and newColor)
                        etBgColor.setText(hexString)
                    } catch (e: Exception) {}
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Load existing colors from SharedPreferences
        etBgColor.setText(prefs.getString("bg_color", "#FF000000"))
        etTextColor.setText(prefs.getString("text_color", "#FFFFFFFF"))

        // Save Button Logic
        btnSave.setOnClickListener {
            val bgHex = etBgColor.text.toString().trim()
            val textHex = etTextColor.text.toString().trim()

            try {
                Color.parseColor(bgHex)
                Color.parseColor(textHex)

                prefs.edit()
                    .putString("bg_color", bgHex)
                    .putString("text_color", textHex)
                    .apply()

                val updateIntent = Intent("UPDATE_COLORS")
                updateIntent.setPackage(packageName)
                sendBroadcast(updateIntent)

                Toast.makeText(this, "Colors applied!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Invalid Hex Code. Use format #AARRGGBB", Toast.LENGTH_SHORT).show()
            }
        }

        // ==========================================
        // 3. PRESET THEMES
        // ==========================================
        val btnThemeDark = findViewById<Button>(R.id.btn_theme_dark)
        val btnThemeLight = findViewById<Button>(R.id.btn_theme_light)
        val btnThemeGreen = findViewById<Button>(R.id.btn_theme_green)

        // Helper function to apply the hex codes and trigger the save button
        fun applyPreset(bgHex: String, textHex: String) {
            etBgColor.setText(bgHex)
            etTextColor.setText(textHex)
            btnSave.performClick() // Programmatically clicks your Apply button
        }

        // Dark Theme (Solid Black BG, White Text)
        btnThemeDark.setOnClickListener {
            applyPreset("#FF000000", "#FFFFFFFF")
        }

        // Light Theme (Solid White BG, Black Text)
        btnThemeLight.setOnClickListener {
            applyPreset("#FFFFFFFF", "#FF000000")
        }

        // Spotify Green Theme (Dark Gray BG, Bright Green Text)
        btnThemeGreen.setOnClickListener {
            applyPreset("#FF191414", "#FF1DB954")
        }
    }
}