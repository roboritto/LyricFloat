package com.example.lyricfloat

import android.content.Context
import java.io.File

object LyricsCache {

    private fun getFileName(title: String, artist: String): String {
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9]"), "_")
        val safeArtist = artist.replace(Regex("[^a-zA-Z0-9]"), "_")
        return "${safeArtist}_${safeTitle}.lrc"
    }

    fun saveLyrics(context: Context, title: String, artist: String, rawLyrics: String) {
        try {
            val file = File(context.cacheDir, getFileName(title, artist))
            file.writeText(rawLyrics)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getLyrics(context: Context, title: String, artist: String): String? {
        val file = File(context.cacheDir, getFileName(title, artist))
        if (file.exists()) {
            // Bump the timestamp to mark this file as recently used
            file.setLastModified(System.currentTimeMillis())
            return file.readText()
        }
        return null
    }

    // Deletes any cached lyrics that haven't been played in 'maxDays'
    fun cleanupOldCache(context: Context, maxDays: Int = 30) {
        try {
            val files = context.cacheDir.listFiles { _, name -> name.endsWith(".lrc") } ?: return

            // Calculate the cutoff timestamp in milliseconds
            val cutoffTime = System.currentTimeMillis() - (maxDays * 24L * 60L * 60L * 1000L)

            for (file in files) {
                if (file.lastModified() < cutoffTime) {
                    file.delete()
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}