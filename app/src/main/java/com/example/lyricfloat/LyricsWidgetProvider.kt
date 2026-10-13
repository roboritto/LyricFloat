package com.example.lyricfloat // Verify this matches your app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

class LyricsWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == "UPDATE_WIDGET_LYRICS" || intent.action == "UPDATE_COLORS") {

            // 1. Fetch Custom Colors
            val prefs = context.getSharedPreferences("LyricsPrefs", Context.MODE_PRIVATE)
            val bgHex = prefs.getString("bg_color", "#FF000000") ?: "#FF000000"
            val textHex = prefs.getString("text_color", "#FFFFFFFF") ?: "#FFFFFFFF"

            var parsedBgColor = Color.BLACK
            var parsedTextColor = Color.WHITE
            try {
                parsedBgColor = Color.parseColor(bgHex)
                parsedTextColor = Color.parseColor(textHex)
            } catch (e: Exception) {}

            val semiTransparentTextColor = Color.argb(128, Color.red(parsedTextColor), Color.green(parsedTextColor), Color.blue(parsedTextColor))
            val veryTransparentTextColor = Color.argb(64, Color.red(parsedTextColor), Color.green(parsedTextColor), Color.blue(parsedTextColor))

            // 2. Fetch Text (If triggered by a color update, keep existing text if possible, or default)
            val prev2 = intent.getStringExtra("prev2") ?: ""
            val prev1 = intent.getStringExtra("prev1") ?: ""
            val current = intent.getStringExtra("current") ?: "Waiting for music..."
            val next1 = intent.getStringExtra("next1") ?: ""
            val next2 = intent.getStringExtra("next2") ?: ""

            // 3. Apply to Views
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, LyricsWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

            for (widgetId in allWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_lyrics)

                // Apply Text
                views.setTextViewText(R.id.widget_tv_prev2, prev2)
                views.setTextViewText(R.id.widget_tv_prev1, prev1)
                views.setTextViewText(R.id.widget_tv_current, current)
                views.setTextViewText(R.id.widget_tv_next1, next1)
                views.setTextViewText(R.id.widget_tv_next2, next2)

                // Apply Colors
                views.setInt(R.id.widget_background, "setColorFilter", parsedBgColor)
                views.setTextColor(R.id.widget_tv_prev2, veryTransparentTextColor)
                views.setTextColor(R.id.widget_tv_prev1, semiTransparentTextColor)
                views.setTextColor(R.id.widget_tv_current, parsedTextColor)
                views.setTextColor(R.id.widget_tv_next1, semiTransparentTextColor)
                views.setTextColor(R.id.widget_tv_next2, veryTransparentTextColor)

                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }
    }
}