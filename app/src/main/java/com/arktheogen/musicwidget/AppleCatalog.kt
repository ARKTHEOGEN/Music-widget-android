package com.arktheogen.musicwidget

import android.content.Context
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.util.Locale

/**
 * Finds the exact Apple Music song for a pick (from YouTube Music), using Apple's public
 * iTunes Search (free, no account). Apple Music's own search, given loose words, sometimes
 * picks a different song, so we choose the song ourselves and hand Apple Music its ID.
 * Matches are saved, so a repeat pick needs no lookup.
 */
object AppleCatalog {
    data class Track(val id: Long, val title: String, val artist: String, val durationMs: Long)

    private const val PREFS = "apple_matches"

    /** The SIM network's country (where the Apple account most likely is); the phone's language region as fallback. */
    fun country(ctx: Context): String {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val iso = tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
        return (iso ?: Locale.getDefault().country.ifBlank { "US" }).lowercase(Locale.ROOT)
    }

    /** Background thread only (network). Null when Apple Music doesn't have the song. */
    fun find(ctx: Context, title: String, artist: String): Track? {
        val key = norm(title) + "|" + norm(artist)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(key, null)?.let { saved -> decode(saved)?.let { return it } }

        val cc = country(ctx)
        val cleanT = Lyrics.cleanTitle(title)
        val best = search("$cleanT ${Lyrics.cleanArtist(artist)}", cc, title, artist)
            ?: search(cleanT, cc, title, artist)
            ?: return null
        prefs.edit().putString(key, encode(best)).apply()
        return best
    }

    private fun search(term: String, cc: String, title: String, artist: String): Track? {
        val json = JSONObject(Http.get("https://itunes.apple.com/search?term=${Http.enc(term)}&entity=song&limit=15&country=$cc"))
        val results = json.optJSONArray("results") ?: return null
        val wantTitle = core(title)
        val wantArtists = artistTokens(artist)
        var best: Track? = null
        var bestScore = 0
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val t = r.optString("trackName")
            val a = r.optString("artistName")
            val gotTitle = core(t)
            val titleScore = when {
                gotTitle.isEmpty() || wantTitle.isEmpty() -> 0
                gotTitle == wantTitle -> 4
                gotTitle.startsWith(wantTitle) || wantTitle.startsWith(gotTitle) -> 2
                else -> 0
            }
            if (titleScore == 0) continue
            val gotArtist = norm(a)
            val artistScore = if (wantArtists.any { gotArtist.contains(it) }) 2 else 0
            val score = titleScore + artistScore
            if (score > bestScore) { // ties keep Apple's own order (relevance)
                bestScore = score
                best = Track(r.optLong("trackId"), t, a, r.optLong("trackTimeMillis"))
            }
        }
        return best?.takeIf { it.id > 0 }
    }

    /** Title without anything in brackets: "Minnalae (From \"May Madham\")" → "minnalae". */
    private fun core(title: String) = norm(title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), ""))

    /** "S. P. Balasubrahmanyam & Reena Bharadhwaj" → [spbalasubrahmanyam, reenabharadhwaj]; "SPB" → [spb]. */
    private fun artistTokens(artist: String) =
        artist.split(",", "&", " and ", " feat", " ft.").map { norm(it) }.filter { it.length >= 3 }

    private fun norm(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun encode(t: Track) = listOf(t.id, t.title, t.artist, t.durationMs).joinToString("\t")

    private fun decode(s: String): Track? {
        val p = s.split("\t")
        if (p.size != 4) return null
        return Track(p[0].toLongOrNull() ?: return null, p[1], p[2], p[3].toLongOrNull() ?: 0L)
    }
}
