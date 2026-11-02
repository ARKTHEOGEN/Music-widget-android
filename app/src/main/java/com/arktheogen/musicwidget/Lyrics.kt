package com.arktheogen.musicwidget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Lyrics from LRCLIB (free, no account). None of the music apps share lyrics,
 * so we look the song up by title + artist and pick the closest duration match.
 */
object Lyrics {
    data class Line(val timeMs: Long, val text: String)

    sealed interface Result {
        data class Synced(val lines: List<Line>) : Result
        data class Plain(val text: String) : Result
        data object Instrumental : Result
        data object NotFound : Result
        data object Failed : Result
    }

    private const val SEARCH_URL = "https://lrclib.net/api/search"
    private const val DURATION_TOLERANCE_S = 3.0

    private const val RETRY_DELAY_MS = 1500L

    private val cache = LruCache<String, Result>(32)
    /** Found lyrics also live on disk, so each song needs LRCLIB (often busy) only once. */
    @Volatile private var diskDir: File? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Calls [onResult] on the main thread. Results (except failures) are cached per track in memory;
     * found lyrics are also cached on disk. "Not found" stays in memory only, as LRCLIB keeps growing.
     */
    fun load(ctx: Context, key: String, title: String, artist: String, durationMs: Long, onResult: (Result) -> Unit) {
        cache.get(key)?.let { onResult(it); return }
        if (diskDir == null) diskDir = File(ctx.applicationContext.cacheDir, "lyrics")
        worker.execute {
            val fromDisk = readDisk(key)
            val result = fromDisk ?: fetchWithRetry(cleanTitle(title), cleanArtist(artist), durationMs / 1000.0)
            if (result != Result.Failed) cache.put(key, result)
            if (fromDisk == null) writeDisk(key, result)
            main.post { onResult(result) }
        }
    }

    /** LRCLIB often answers "busy" (HTTP 503); one retry after a short wait usually gets through. */
    private fun fetchWithRetry(title: String, artist: String, durationS: Double): Result {
        repeat(2) { attempt ->
            try {
                return fetch(title, artist, durationS)
            } catch (e: IOException) {
                if (attempt == 0) Thread.sleep(RETRY_DELAY_MS)
            } catch (e: JSONException) {
                return Result.Failed
            }
        }
        return Result.Failed
    }

    // ---------------------------------------------------------------- disk cache

    private fun diskFile(key: String): File? = diskDir?.let { dir ->
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        File(dir, hash)
    }

    /** File format: a type letter on the first line (S synced, P plain, I instrumental), then the body. */
    private fun readDisk(key: String): Result? {
        val file = diskFile(key)?.takeIf { it.exists() } ?: return null
        val text = try {
            file.readText()
        } catch (e: IOException) {
            return null
        }
        val body = text.substringAfter('\n', "")
        return when (text.substringBefore('\n')) {
            "S" -> Result.Synced(body.lineSequence().mapNotNull { row ->
                row.substringBefore('\t').toLongOrNull()?.let { Line(it, row.substringAfter('\t', "")) }
            }.toList())
            "P" -> Result.Plain(body)
            "I" -> Result.Instrumental
            else -> null
        }
    }

    private fun writeDisk(key: String, result: Result) {
        val text = when (result) {
            is Result.Synced -> "S\n" + result.lines.joinToString("\n") { "${it.timeMs}\t${it.text}" }
            is Result.Plain -> "P\n" + result.text
            Result.Instrumental -> "I"
            Result.NotFound, Result.Failed -> return
        }
        try {
            val file = diskFile(key) ?: return
            file.parentFile?.mkdirs()
            file.writeText(text)
        } catch (e: IOException) {
            // Cache only; the next view fetches again.
        }
    }

    private fun fetch(title: String, artist: String, durationS: Double): Result {
        val candidates = search("track_name=${enc(title)}&artist_name=${enc(artist)}")
            .ifEmpty { search("q=${enc("$title $artist")}") }
        val close = candidates.filter {
            durationS <= 0 || abs(it.optDouble("duration", 0.0) - durationS) <= DURATION_TOLERANCE_S
        }.ifEmpty { candidates }
        val best = close.firstOrNull { it.str("syncedLyrics") != null }
            ?: close.firstOrNull { it.str("plainLyrics") != null }
            ?: close.firstOrNull { it.optBoolean("instrumental") }
            ?: return Result.NotFound

        if (best.optBoolean("instrumental")) return Result.Instrumental
        best.str("syncedLyrics")?.let { lrc ->
            val lines = parseLrc(lrc)
            if (lines.isNotEmpty()) return Result.Synced(lines)
        }
        return best.str("plainLyrics")?.let { Result.Plain(it) } ?: Result.NotFound
    }

    private fun search(query: String): List<JSONObject> {
        val arr = JSONArray(Http.get("$SEARCH_URL?$query"))
        return List(arr.length()) { arr.getJSONObject(it) }
    }

    // ---------------------------------------------------------------- parsing

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")

    /** "[01:23.45] text" → Line(83450, "text"). Lines with several tags repeat. */
    fun parseLrc(lrc: String): List<Line> {
        val out = ArrayList<Line>()
        for (raw in lrc.lineSequence()) {
            val tags = TIME_TAG.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim()
            for (tag in tags) {
                val min = tag.groupValues[1].toLong()
                val sec = tag.groupValues[2].replace(':', '.').toDouble()
                out += Line(min * 60_000 + (sec * 1000).toLong(), text)
            }
        }
        out.sortBy { it.timeMs }
        return out
    }

    // ---------------------------------------------------------------- clean-up

    private val NOISE = Regex(
        """\s*[(\[][^)\]]*\b(official|video|lyrics?|audio|visuali[sz]er|remaster(ed)?|explicit|hd|4k|mv)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT = Regex("""\s*[(\[]?\s*\b(feat\.?|ft\.|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    private val DASH_SUFFIX = Regex("""\s+-\s+.*\b(remaster(ed)?|version|edit|live|mono|stereo)\b.*$""", RegexOption.IGNORE_CASE)

    /** "Song (Official Video) [feat. X]" → "Song". Falls back to the original if nothing is left. */
    fun cleanTitle(t: String): String =
        t.replace(NOISE, "").replace(FEAT, "").replace(DASH_SUFFIX, "").trim().ifEmpty { t }

    /** "A, B & C" / "A - Topic" → "A". */
    fun cleanArtist(a: String): String =
        a.removeSuffix(" - Topic").split(",", " & ", " feat", " ft.").first().trim().ifEmpty { a }

    private fun enc(s: String) = Http.enc(s)

    /** org.json returns the text "null" for JSON nulls; treat those and blanks as missing. */
    private fun JSONObject.str(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
