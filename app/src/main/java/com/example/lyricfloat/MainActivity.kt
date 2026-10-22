package com.example.lyricfloat

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import androidx.core.content.edit
import androidx.core.graphics.toColorInt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private var floatBgHex = "#FF000000"
    private var floatTextHex = "#FFFFFFFF"

    private var widgetBgHex = "#FF000000"
    private var widgetTextHex = "#FFFFFFFF"

    // App Theme Variables
    private var appBgHex = "#000000"
    private var appCardHex = "#4B5E87"
    private var appTextHex = "#FFFFFF"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)

        // --- Permissions ---
        findViewById<Button>(R.id.btn_notification_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // --- Load & Apply App Theme Instantly ---
        appBgHex = prefs.getString("app_bg_color", "#000000") ?: "#000000"
        appCardHex = prefs.getString("app_card_color", "#4B5E87") ?: "#4B5E87"
        appTextHex = prefs.getString("app_text_color", "#FFFFFF") ?: "#FFFFFF"
        applyAppThemeColors()

        // --- 1. Display Toggles ---
        val switchService = findViewById<SwitchCompat>(R.id.switch_start_service)
        val switchFloating = findViewById<SwitchCompat>(R.id.switch_floating_box)
        val switchNotif = findViewById<SwitchCompat>(R.id.switch_notification)
        val switchLock = findViewById<SwitchCompat>(R.id.switch_lock_screen)
        val switchWidgetArt = findViewById<SwitchCompat>(R.id.switch_widget_album_art)

        switchService.isChecked = FloatingLyricsService.isRunning
        switchFloating.isChecked = prefs.getBoolean("pref_floating_box", true)
        switchNotif.isChecked = prefs.getBoolean("pref_notification", true)
        switchLock.isChecked = prefs.getBoolean("pref_lock_screen", true)
        switchWidgetArt.isChecked = prefs.getBoolean("pref_widget_album_art", true)

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

        val displayToggleListener = { _: View?, _: Boolean ->
            prefs.edit {
                putBoolean("pref_floating_box", switchFloating.isChecked)
                putBoolean("pref_notification", switchNotif.isChecked)
                putBoolean("pref_lock_screen", switchLock.isChecked)
                putBoolean("pref_widget_album_art", switchWidgetArt.isChecked)
            }
            sendBroadcast(Intent("UPDATE_DISPLAY_PREFS").apply { setPackage(packageName) })
        }

        switchFloating.setOnCheckedChangeListener(displayToggleListener)
        switchNotif.setOnCheckedChangeListener(displayToggleListener)
        switchLock.setOnCheckedChangeListener(displayToggleListener)
        switchWidgetArt.setOnCheckedChangeListener(displayToggleListener)

        // --- 2. Floating Box Customization ---
        val etFloatBg = findViewById<EditText>(R.id.et_float_bg_color)
        val etFloatText = findViewById<EditText>(R.id.et_float_text_color)
        val btnPickFloatBg = findViewById<Button>(R.id.btn_pick_float_bg_color)
        val btnPickFloatText = findViewById<Button>(R.id.btn_pick_float_text_color)
        val seekFloatAlpha = findViewById<SeekBar>(R.id.seek_float_transparency)
        val seekFloatLines = findViewById<SeekBar>(R.id.seek_float_lines)

        floatBgHex = prefs.getString("bg_color", "#FF000000") ?: "#FF000000"
        floatTextHex = prefs.getString("text_color", "#FFFFFFFF") ?: "#FFFFFFFF"
        etFloatBg.setText(floatBgHex)
        etFloatText.setText(floatTextHex)

        btnPickFloatBg.setOnClickListener {
            ColorPickerDialog.Builder(this).setTitle("Pick Floating Background").setPreferenceName("FloatBgPicker")
                .setPositiveButton("Select", ColorEnvelopeListener { envelope, _ ->
                    floatBgHex = "#" + envelope.hexCode
                    etFloatBg.setText(floatBgHex)
                    updateFloatPreview()
                }).setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }.attachAlphaSlideBar(false).attachBrightnessSlideBar(true).show()
        }

        btnPickFloatText.setOnClickListener {
            ColorPickerDialog.Builder(this).setTitle("Pick Floating Text Color").setPreferenceName("FloatTextPicker")
                .setPositiveButton("Select", ColorEnvelopeListener { envelope, _ ->
                    floatTextHex = "#" + envelope.hexCode
                    etFloatText.setText(floatTextHex)
                    updateFloatPreview()
                }).setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }.attachAlphaSlideBar(false).attachBrightnessSlideBar(true).show()
        }

        try { seekFloatAlpha.progress = Integer.parseInt(floatBgHex.substring(1, 3), 16) } catch (e: Exception) { seekFloatAlpha.progress = 255 }
        seekFloatAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val typedBg = etFloatBg.text.toString().trim()
                    if (typedBg.length >= 7) {
                        floatBgHex = "#" + String.format("%02X", progress) + typedBg.substring(typedBg.length - 6)
                        etFloatBg.setText(floatBgHex)
                        updateFloatPreview()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekFloatLines.progress = prefs.getInt("pref_default_lines", 3) - 1
        seekFloatLines.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { updateFloatPreview() }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn_apply_float).setOnClickListener {
            saveAndBroadcastFloatColors()
            Toast.makeText(this, "Floating settings applied!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_float_theme_dark).setOnClickListener {
            floatBgHex = "#FF000000"; floatTextHex = "#FFFFFFFF"; etFloatBg.setText(floatBgHex); etFloatText.setText(floatTextHex); seekFloatAlpha.progress = 255; updateFloatPreview()
        }
        findViewById<Button>(R.id.btn_float_theme_light).setOnClickListener {
            floatBgHex = "#FFFFFFFF"; floatTextHex = "#FF000000"; etFloatBg.setText(floatBgHex); etFloatText.setText(floatTextHex); seekFloatAlpha.progress = 255; updateFloatPreview()
        }
        findViewById<Button>(R.id.btn_float_theme_green).setOnClickListener {
            floatBgHex = "#FF1DB954"; floatTextHex = "#FFFFFFFF"; etFloatBg.setText(floatBgHex); etFloatText.setText(floatTextHex); seekFloatAlpha.progress = 255; updateFloatPreview()
        }

        // --- 3. Widget Customization ---
        val etWidgetBg = findViewById<EditText>(R.id.et_widget_bg_color)
        val etWidgetText = findViewById<EditText>(R.id.et_widget_text_color)
        val btnPickWidgetBg = findViewById<Button>(R.id.btn_pick_widget_bg_color)
        val btnPickWidgetText = findViewById<Button>(R.id.btn_pick_widget_text_color)
        val seekWidgetAlpha = findViewById<SeekBar>(R.id.seek_widget_transparency)
        val seekWidgetLines = findViewById<SeekBar>(R.id.seek_widget_lines)

        widgetBgHex = prefs.getString("widget_bg_color", "#FF000000") ?: "#FF000000"
        widgetTextHex = prefs.getString("widget_text_color", "#FFFFFFFF") ?: "#FFFFFFFF"
        etWidgetBg.setText(widgetBgHex)
        etWidgetText.setText(widgetTextHex)

        btnPickWidgetBg.setOnClickListener {
            ColorPickerDialog.Builder(this).setTitle("Pick Widget Background").setPreferenceName("WidgetBgPicker")
                .setPositiveButton("Select", ColorEnvelopeListener { envelope, _ ->
                    widgetBgHex = "#" + envelope.hexCode
                    etWidgetBg.setText(widgetBgHex)
                    updateWidgetPreview()
                }).setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }.attachAlphaSlideBar(false).attachBrightnessSlideBar(true).show()
        }

        btnPickWidgetText.setOnClickListener {
            ColorPickerDialog.Builder(this).setTitle("Pick Widget Text Color").setPreferenceName("WidgetTextPicker")
                .setPositiveButton("Select", ColorEnvelopeListener { envelope, _ ->
                    widgetTextHex = "#" + envelope.hexCode
                    etWidgetText.setText(widgetTextHex)
                    updateWidgetPreview()
                }).setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }.attachAlphaSlideBar(false).attachBrightnessSlideBar(true).show()
        }

        try { seekWidgetAlpha.progress = Integer.parseInt(widgetBgHex.substring(1, 3), 16) } catch (e: Exception) { seekWidgetAlpha.progress = 255 }
        seekWidgetAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val typedBg = etWidgetBg.text.toString().trim()
                    if (typedBg.length >= 7) {
                        widgetBgHex = "#" + String.format("%02X", progress) + typedBg.substring(typedBg.length - 6)
                        etWidgetBg.setText(widgetBgHex)
                        updateWidgetPreview()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekWidgetLines.progress = prefs.getInt("widget_default_lines", 5) - 1
        seekWidgetLines.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { updateWidgetPreview() }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn_apply_widget).setOnClickListener {
            saveAndBroadcastWidgetColors()
            Toast.makeText(this, "Widget settings applied!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_widget_theme_dark).setOnClickListener {
            widgetBgHex = "#FF000000"; widgetTextHex = "#FFFFFFFF"; etWidgetBg.setText(widgetBgHex); etWidgetText.setText(widgetTextHex); seekWidgetAlpha.progress = 255; updateWidgetPreview()
        }
        findViewById<Button>(R.id.btn_widget_theme_light).setOnClickListener {
            widgetBgHex = "#FFFFFFFF"; widgetTextHex = "#FF000000"; etWidgetBg.setText(widgetBgHex); etWidgetText.setText(widgetTextHex); seekWidgetAlpha.progress = 255; updateWidgetPreview()
        }
        // Fixed: Replaced Album with Green
        findViewById<Button>(R.id.btn_widget_theme_accent).setOnClickListener {
            prefs.edit { putBoolean("pref_widget_accent_color", true) }
            findViewById<SwitchCompat>(R.id.switch_widget_album_art).isChecked = false // Turn off the full image mode
            Toast.makeText(this, "Accent Color Preset Applied!", Toast.LENGTH_SHORT).show()
            sendBroadcast(Intent("UPDATE_DISPLAY_PREFS").apply { setPackage(packageName) })
        }

        // --- 4. App Theme Customization ---
        val etAppBg = findViewById<EditText>(R.id.et_app_bg_color)
        val etAppCard = findViewById<EditText>(R.id.et_app_card_color)
        val etAppText = findViewById<EditText>(R.id.et_app_text_color)

        etAppBg.setText(appBgHex); etAppCard.setText(appCardHex); etAppText.setText(appTextHex)

        findViewById<Button>(R.id.btn_pick_app_bg_color).setOnClickListener {
            ColorPickerDialog.Builder(this).setTitle("Pick App Background").setPreferenceName("AppBgPicker")
                .setPositiveButton("Select", ColorEnvelopeListener { envelope, _ -> appBgHex = "#" + envelope.hexCode; etAppBg.setText(appBgHex) })
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }.attachAlphaSlideBar(false).attachBrightnessSlideBar(true).show()
        }
        findViewById<Button>(R.id.btn_pick_app_card_color).setOnClickListener {
            ColorPickerDialog.Builder(this).setTitle("Pick Card Color").setPreferenceName("AppCardPicker")
                .setPositiveButton("Select", ColorEnvelopeListener { envelope, _ -> appCardHex = "#" + envelope.hexCode; etAppCard.setText(appCardHex) })
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }.attachAlphaSlideBar(false).attachBrightnessSlideBar(true).show()
        }
        findViewById<Button>(R.id.btn_pick_app_text_color).setOnClickListener {
            ColorPickerDialog.Builder(this).setTitle("Pick App Text Color").setPreferenceName("AppTextPicker")
                .setPositiveButton("Select", ColorEnvelopeListener { envelope, _ -> appTextHex = "#" + envelope.hexCode; etAppText.setText(appTextHex) })
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }.attachAlphaSlideBar(false).attachBrightnessSlideBar(true).show()
        }

        findViewById<Button>(R.id.btn_apply_app).setOnClickListener {
            appBgHex = etAppBg.text.toString().trim()
            appCardHex = etAppCard.text.toString().trim()
            appTextHex = etAppText.text.toString().trim()

            prefs.edit {
                putString("app_bg_color", appBgHex).putString("app_card_color", appCardHex)
                    .putString("app_text_color", appTextHex)
            }
            applyAppThemeColors()
            Toast.makeText(this, "App Theme applied!", Toast.LENGTH_SHORT).show()
        }

        // App Presets
        findViewById<Button>(R.id.btn_app_theme_default).setOnClickListener {
            appBgHex = "#000000"; appCardHex = "#4B5E87"; appTextHex = "#FFFFFF"; etAppBg.setText(appBgHex); etAppCard.setText(appCardHex); etAppText.setText(appTextHex)
        }
        findViewById<Button>(R.id.btn_app_theme_dark).setOnClickListener {
            appBgHex = "#000000"; appCardHex = "#222222"; appTextHex = "#FFFFFF"; etAppBg.setText(appBgHex); etAppCard.setText(appCardHex); etAppText.setText(appTextHex)
        }
        findViewById<Button>(R.id.btn_app_theme_light).setOnClickListener {
            appBgHex = "#F0F0F0"; appCardHex = "#FFFFFF"; appTextHex = "#000000"; etAppBg.setText(appBgHex); etAppCard.setText(appCardHex); etAppText.setText(appTextHex)
        }

        updateFloatPreview()
        updateWidgetPreview()
    }

    // --- Core Theming Logic ---
    private fun applyAppThemeColors() {
        try {
            val bg = appBgHex.toColorInt()
            val card = appCardHex.toColorInt()
            val text = appTextHex.toColorInt()

            // Calculate a contrasting text color for the rounded apply buttons so they are always readable
            val btnTextColor = if (1 - (0.299 * Color.red(card) + 0.587 * Color.green(card) + 0.114 * Color.blue(card)) / 255 >= 0.5) Color.WHITE else Color.BLACK

            // 1. Root & Cards
            findViewById<View>(R.id.main_scroll_view).setBackgroundColor(bg)
            val cards = listOf(R.id.card_1, R.id.card_2, R.id.card_3, R.id.card_4, R.id.card_5, R.id.card_6)
            cards.forEach { findViewById<androidx.cardview.widget.CardView>(it)?.setCardBackgroundColor(card) }

            // 2. Title & Buttons (Link title color to Card color to make it pop)
            findViewById<TextView>(R.id.main_title).setTextColor(card)
            val buttons = listOf(R.id.btn_notification_access, R.id.btn_apply_float, R.id.btn_apply_widget, R.id.btn_apply_app)
            buttons.forEach {
                findViewById<Button>(it)?.apply {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(card)
                    setTextColor(btnTextColor)
                }
            }

            // 3. Update all normal text labels and switches recursively
            tintViews(findViewById(R.id.main_layout), text)

        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun tintViews(view: View, color: Int) {
        // Protect the preview boxes from being overwritten by the App Text Color!
        if (view.id == R.id.preview_container_float || view.id == R.id.preview_container_widget) return
        // Protect the Main Apply buttons which we manually tint for contrast
        if (view.id in listOf(R.id.btn_notification_access, R.id.btn_apply_float, R.id.btn_apply_widget, R.id.btn_apply_app)) return

        if (view is SwitchCompat) {
            view.setTextColor(color)
        } else if (view is Button) {
            // Ignore generic Preset and Emoji picker buttons to preserve their default styles
            return
        } else if (view is TextView) {
            view.setTextColor(color)
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) tintViews(view.getChildAt(i), color)
        }
    }

    private fun saveAndBroadcastFloatColors() {
        floatBgHex = findViewById<EditText>(R.id.et_float_bg_color).text.toString().trim()
        floatTextHex = findViewById<EditText>(R.id.et_float_text_color).text.toString().trim()
        val lines = findViewById<SeekBar>(R.id.seek_float_lines).progress + 1
        prefs.edit { putBoolean("pref_widget_accent_color", false) }

        prefs.edit {
            putString("bg_color", floatBgHex).putString("text_color", floatTextHex)
                .putInt("pref_default_lines", lines)
        }
        sendBroadcast(Intent("UPDATE_COLORS").apply { setPackage(packageName) })
        sendBroadcast(Intent("UPDATE_DISPLAY_PREFS").apply { setPackage(packageName) })
    }

    private fun saveAndBroadcastWidgetColors() {
        widgetBgHex = findViewById<EditText>(R.id.et_widget_bg_color).text.toString().trim()
        widgetTextHex = findViewById<EditText>(R.id.et_widget_text_color).text.toString().trim()
        val lines = findViewById<SeekBar>(R.id.seek_widget_lines).progress + 1

        prefs.edit {
            putString("widget_bg_color", widgetBgHex).putString(
                "widget_text_color",
                widgetTextHex
            ).putInt("widget_default_lines", lines)
        }
        sendBroadcast(Intent("UPDATE_DISPLAY_PREFS").apply { setPackage(packageName) })
    }

    private fun updateFloatPreview() {
        try {
            val bg = floatBgHex.toColorInt()
            val text = floatTextHex.toColorInt()
            val fade1 = Color.argb(170, Color.red(text), Color.green(text), Color.blue(text))
            val fade2 = Color.argb(85, Color.red(text), Color.green(text), Color.blue(text))
            val lines = findViewById<SeekBar>(R.id.seek_float_lines).progress + 1

            val bgShape = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 32f * resources.displayMetrics.density; setColor(bg) }
            findViewById<View>(R.id.preview_container_float).background = bgShape

            findViewById<TextView>(R.id.preview_float_prev2).apply { setTextColor(fade2); visibility = if (lines >= 5) View.VISIBLE else View.GONE }
            findViewById<TextView>(R.id.preview_float_prev).apply { setTextColor(fade1); visibility = if (lines >= 3) View.VISIBLE else View.GONE }
            findViewById<TextView>(R.id.preview_float_current).apply { setTextColor(text); visibility = View.VISIBLE }
            findViewById<TextView>(R.id.preview_float_next).apply { setTextColor(fade1); visibility = if (lines >= 2) View.VISIBLE else View.GONE }
            findViewById<TextView>(R.id.preview_float_next2).apply { setTextColor(fade2); visibility = if (lines >= 4) View.VISIBLE else View.GONE }
        } catch (e: Exception) { }
    }

    private fun updateWidgetPreview() {
        try {
            val bg = widgetBgHex.toColorInt()
            val text = widgetTextHex.toColorInt()
            val fade1 = Color.argb(170, Color.red(text), Color.green(text), Color.blue(text))
            val fade2 = Color.argb(85, Color.red(text), Color.green(text), Color.blue(text))
            val lines = findViewById<SeekBar>(R.id.seek_widget_lines).progress + 1

            val bgShape = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 32f * resources.displayMetrics.density; setColor(bg) }
            findViewById<View>(R.id.preview_container_widget).background = bgShape

            findViewById<TextView>(R.id.preview_widget_prev2).apply { setTextColor(fade2); visibility = if (lines >= 5) View.VISIBLE else View.GONE }
            findViewById<TextView>(R.id.preview_widget_prev).apply { setTextColor(fade1); visibility = if (lines >= 3) View.VISIBLE else View.GONE }
            findViewById<TextView>(R.id.preview_widget_current).apply { setTextColor(text); visibility = View.VISIBLE }
            findViewById<TextView>(R.id.preview_widget_next).apply { setTextColor(fade1); visibility = if (lines >= 2) View.VISIBLE else View.GONE }
            findViewById<TextView>(R.id.preview_widget_next2).apply { setTextColor(fade2); visibility = if (lines >= 4) View.VISIBLE else View.GONE }
        } catch (e: Exception) { }
    }
}