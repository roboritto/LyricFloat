package com.example.lyricfloat // Ensure this matches your project's package name

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

interface LyricsProvider {
    val name: String // Added property to identify the provider in the UI
    suspend fun fetchLyrics(trackName: String, artistName: String): String?
}

class LrcLibProvider : LyricsProvider {
    override val name = "LRCLib"

    override suspend fun fetchLyrics(trackName: String, artistName: String): String? {
        return try {
            val encodedTrack = URLEncoder.encode(trackName, "UTF-8")
            val encodedArtist = URLEncoder.encode(artistName, "UTF-8")
            val urlString = "https://lrclib.net/api/get?track_name=$encodedTrack&artist_name=$encodedArtist"

            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)
                if (jsonObject.has("syncedLyrics") && !jsonObject.isNull("syncedLyrics")) {
                    return jsonObject.getString("syncedLyrics")
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}

class UnisonProvider : LyricsProvider {
    override val name = "Unison"

    override suspend fun fetchLyrics(trackName: String, artistName: String): String? {
        return try {
            val encodedTrack = URLEncoder.encode(trackName, "UTF-8")
            val encodedArtist = URLEncoder.encode(artistName, "UTF-8")
            val urlString = "https://unison.betterlyrics.org/lyrics?song=$encodedTrack&artist=$encodedArtist"

            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)
                if (jsonObject.has("lyrics") && !jsonObject.isNull("lyrics")) {
                    return jsonObject.getString("lyrics")
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}

class YouTubeCaptionProvider : LyricsProvider {
    override val name = "YouTube"

    override suspend fun fetchLyrics(trackName: String, artistName: String): String? {
        return try {
            val query = URLEncoder.encode("$trackName $artistName audio", "UTF-8")
            val searchUrl = "https://pipedapi.kavin.rocks/search?q=$query&filter=all"

            val searchConn = URL(searchUrl).openConnection() as HttpURLConnection
            searchConn.requestMethod = "GET"
            searchConn.connectTimeout = 5000

            if (searchConn.responseCode != 200) return null

            val searchResponse = searchConn.inputStream.bufferedReader().use { it.readText() }
            val searchJson = JSONObject(searchResponse)
            val items = searchJson.getJSONArray("items")

            if (items.length() == 0) return null

            val videoUrl = items.getJSONObject(0).getString("url")
            val videoId = videoUrl.replace("/watch?v=", "")

            val streamUrl = "https://pipedapi.kavin.rocks/streams/$videoId"
            val streamConn = URL(streamUrl).openConnection() as HttpURLConnection
            streamConn.requestMethod = "GET"

            if (streamConn.responseCode != 200) return null

            val streamResponse = streamConn.inputStream.bufferedReader().use { it.readText() }
            val streamJson = JSONObject(streamResponse)
            val subtitles = streamJson.getJSONArray("subtitles")

            if (subtitles.length() == 0) return null

            var subUrl = ""
            for (i in 0 until subtitles.length()) {
                val sub = subtitles.getJSONObject(i)
                val subName = sub.getString("name").lowercase()
                if (subName.contains("english") || subName.contains("en")) {
                    subUrl = sub.getString("url")
                    break
                }
            }
            if (subUrl.isEmpty()) {
                subUrl = subtitles.getJSONObject(0).getString("url")
            }

            val subDataConn = URL(subUrl).openConnection() as HttpURLConnection
            val vttText = subDataConn.inputStream.bufferedReader().use { it.readText() }

            return convertVttToLrc(vttText)

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun convertVttToLrc(vtt: String): String {
        val lrcBuilder = StringBuilder()
        var currentTimestamp = ""

        vtt.lines().forEach { line ->
            if (line.contains("-->")) {
                val startTime = line.substringBefore(" -->").trim()
                val parts = startTime.split(":")
                if (parts.size >= 2) {
                    val min = if (parts.size == 3) parts[1] else parts[0]
                    val secAndMs = if (parts.size == 3) parts[2] else parts[1]
                    val secParts = secAndMs.split(".")
                    val sec = secParts[0]
                    val ms = if (secParts.size > 1) secParts[1].take(2) else "00"
                    currentTimestamp = "[$min:$sec.$ms]"
                }
            } else if (line.isNotBlank() && !line.startsWith("WEBVTT") && currentTimestamp.isNotEmpty()) {
                val cleanText = line.replace(Regex("<[^>]*>"), "")
                lrcBuilder.append(currentTimestamp).append(cleanText).append("\n")
                currentTimestamp = ""
            }
        }
        return lrcBuilder.toString()
    }
}