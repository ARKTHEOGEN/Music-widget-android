package com.arktheogen.musicwidget

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Details for the pop-up's info view, from free sources that need no account:
 *  - the song (album, year, genre, track number, label) from Apple's catalogue (iTunes lookup);
 *  - each artist's photo from Deezer and a short bio from Wikipedia.
 * Everything found is saved on disk; artists are saved by name, so a singer is looked up once
 * across all their songs. Failures are not saved, so they're tried again next time.
 */
object SongInfo {
    data class Artist(val name: String, val about: String?, val photo: File?)

    data class Info(
        val album: String?,
        val year: String?,
        val genre: String?,
        val track: String?,
        val copyright: String?,
        val artists: List<Artist>,
    )

    private const val MAX_ARTISTS = 6
    private const val BIO_CHARS = 200
    /** Words in a Wikipedia description that say the page is about a music person, not a namesake. */
    private val MUSIC_WORDS = Regex(
        """singer|musician|composer|lyricist|songwriter|rapper|vocalist|band|music director|playback|poet|instrumentalist|record producer"""
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** [onResult] runs on the main thread; null when nothing could be found or loaded. */
    fun load(ctx: Context, title: String, artist: String, onResult: (Info?) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val info = try {
                build(app, title, artist)
            } catch (e: IOException) {
                null
            } catch (e: JSONException) {
                null
            }
            main.post { onResult(info) }
        }
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "info").apply { mkdirs() }

    private fun build(ctx: Context, title: String, artist: String): Info {
        val songFile = File(dir(ctx), "song_" + sha1(title.lowercase(Locale.ROOT) + "|" + artist.lowercase(Locale.ROOT)) + ".json")
        val song = readJson(songFile) ?: lookupSong(ctx, title, artist)?.also { songFile.writeText(it.toString()) }
        // Apple lists every artist ("A.R. Rahman, Unni Menon, Chinmayi Sripada & ..."); YouTube Music often fewer.
        val names = splitArtists(song?.optString("artist")?.takeIf { it.isNotBlank() } ?: artist)
        return Info(
            album = song?.str("album"),
            year = song?.str("year"),
            genre = song?.str("genre"),
            track = song?.str("track"),
            copyright = song?.str("copyright"),
            artists = names.map { artistInfo(ctx, it) },
        )
    }

    /** One iTunes lookup returns the song and its album (for the label line). */
    private fun lookupSong(ctx: Context, title: String, artist: String): JSONObject? {
        val track = AppleCatalog.find(ctx, title, artist) ?: return null
        val cc = AppleCatalog.country(ctx)
        val first = JSONObject(Http.get("https://itunes.apple.com/lookup?id=${track.id}&country=$cc"))
            .optJSONArray("results")?.optJSONObject(0) ?: return null
        val collectionId = first.optLong("collectionId")
        val album = if (collectionId > 0) {
            JSONObject(Http.get("https://itunes.apple.com/lookup?id=$collectionId&country=$cc"))
                .optJSONArray("results")?.optJSONObject(0)
        } else null
        val n = first.optInt("trackNumber")
        val of = first.optInt("trackCount")
        return JSONObject()
            .put("artist", first.optString("artistName"))
            .put("album", first.optString("collectionName"))
            .put("year", first.optString("releaseDate").take(4))
            .put("genre", first.optString("primaryGenreName"))
            .put("track", if (n > 0 && of > 0) "$n of $of" else "")
            .put("copyright", album?.optString("copyright").orEmpty())
    }

    // ------------------------------------------------------------------ artists

    private fun artistInfo(ctx: Context, name: String): Artist {
        val file = File(dir(ctx), "artist_" + sha1(norm(name)) + ".json")
        readJson(file)?.let { saved ->
            val photo = saved.str("photo")?.let { File(dir(ctx), it) }?.takeIf { it.exists() }
            return Artist(name, saved.str("about"), photo)
        }
        var complete = true
        val photo = try {
            deezerPhoto(name)?.let { url -> download(url, File(dir(ctx), "photo_" + sha1(url) + ".jpg")) }
        } catch (e: IOException) {
            complete = false
            null
        } catch (e: JSONException) {
            null
        }
        val about = try {
            wikipediaBio(name)
        } catch (e: IOException) {
            complete = false
            null
        } catch (e: JSONException) {
            null
        }
        if (complete) {
            file.writeText(JSONObject().put("about", about.orEmpty()).put("photo", photo?.name.orEmpty()).toString())
        }
        return Artist(name, about, photo)
    }

    /** Deezer's photo for the artist whose name matches; null when Deezer has only its blank placeholder. */
    private fun deezerPhoto(name: String): String? {
        val data = JSONObject(Http.get("https://api.deezer.com/search/artist?q=${Http.enc(name)}&limit=5"))
            .optJSONArray("data") ?: return null
        val want = norm(name)
        for (i in 0 until data.length()) {
            val a = data.getJSONObject(i)
            val got = norm(a.optString("name"))
            if (got.isEmpty() || !(got == want || got.contains(want) || want.contains(got))) continue
            val url = a.optString("picture_medium") // 250×250: enough for a small round photo
            return url.takeIf { it.isNotBlank() && !it.contains("/artist//") }
        }
        return null
    }

    /**
     * First lines of the artist's Wikipedia summary. Tries "Name", then "Name (singer)" and
     * similar, and accepts a page only if it describes a music person (not a namesake).
     */
    private fun wikipediaBio(name: String): String? {
        for (page in listOf(name, "$name (singer)", "$name (musician)", "$name (composer)")) {
            val o = try {
                JSONObject(Http.get("https://en.wikipedia.org/api/rest_v1/page/summary/${wikiTitle(page)}?redirect=true"))
            } catch (e: HttpStatusException) {
                if (e.code == 404) continue else throw e
            }
            if (o.optString("type") == "disambiguation") continue
            val description = (o.optString("description") + " " + o.optString("extract").take(200)).lowercase(Locale.ROOT)
            if (!MUSIC_WORDS.containsMatchIn(description)) continue
            return shorten(o.optString("extract"))
        }
        return null
    }

    private fun wikiTitle(page: String) = Http.enc(page.replace(' ', '_'))

    /** Whole sentences up to [BIO_CHARS]. */
    private fun shorten(text: String): String? {
        if (text.isBlank()) return null
        if (text.length <= BIO_CHARS) return text
        val cut = text.lastIndexOf(". ", BIO_CHARS)
        return if (cut > 80) text.substring(0, cut + 1) else text.take(BIO_CHARS).trimEnd() + "…"
    }

    /** "A.R. Rahman, Unni Menon & Chinmayi feat. X" → [A.R. Rahman, Unni Menon, Chinmayi, X]. */
    private fun splitArtists(artist: String): List<String> =
        artist.removeSuffix(" - Topic")
            .split(Regex("""\s*(,|&| and | feat\.? | ft\.? | x )\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinctBy { norm(it) }
            .take(MAX_ARTISTS)

    // ------------------------------------------------------------------ helpers

    private fun download(url: String, to: File): File? {
        if (to.exists()) return to
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpStatusException(conn.responseCode)
            val tmp = File(to.path + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            return if (tmp.renameTo(to)) to else null
        } finally {
            conn.disconnect()
        }
    }

    private fun readJson(f: File): JSONObject? = try {
        if (f.exists()) JSONObject(f.readText()) else null
    } catch (e: JSONException) {
        null
    }

    private fun JSONObject.str(name: String): String? = optString(name).takeIf { it.isNotBlank() && it != "null" }

    private fun norm(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
