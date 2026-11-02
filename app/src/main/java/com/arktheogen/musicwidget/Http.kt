package com.arktheogen.musicwidget

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Tiny blocking HTTP GET for background threads (lyrics, song-link lookups). */
object Http {
    private const val USER_AGENT = "MusicWidget/0.2 (https://github.com/ARKTHEOGEN/Music-widget-android)"
    private const val TIMEOUT_MS = 8000

    fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
