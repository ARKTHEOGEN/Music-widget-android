package com.arktheogen.musicwidget

import android.content.Context
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Finds the exact Apple Music song for a pick (from YouTube Music), using Apple's public
 * iTunes Search (free, no account). Apple Music's own search, given loose words, sometimes
 * picks a different song, so we choose the song ourselves and hand Apple Music its ID.
 * Matches are saved, so a repeat pick needs no lookup.
 */
object AppleCatalog {
    data class Track(val id: Long, val title: String, val artist: String, val durationMs: Long)

    /** v3: v1 could save a remix, v2 a same-titled song by other singers. */
    private const val PREFS = "apple_matches_v3"

    /** Words that mark a different recording. A result with one the pick lacks is a different song. */
    private val VERSION_WORDS = Regex(
        """\b(remix|live|lofi|lo-fi|reprise|acoustic|unplugged|instrumental|karaoke|cover|slowed|reverb|sped up|mashup|extended|club mix)\b"""
    )

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

    /**
     * Title: same = 4, one starts with the other = 2, same sound with a different spelling = 1
     * ("Oh Senyoreeta" / "Sanyo Reetta"). One artist must match too: "Rasika Rasika" by other
     * singers is a different song. Not streamable, or a different version (see [VERSION_WORDS]): skipped.
     */
    private fun search(term: String, cc: String, title: String, artist: String): Track? {
        val json = JSONObject(Http.get("https://itunes.apple.com/search?term=${Http.enc(term)}&entity=song&limit=15&country=$cc"))
        val results = json.optJSONArray("results") ?: return null
        val wantTitle = core(title)
        val wantSound = sound(wantTitle)
        val wantArtists = artistTokens(artist)
        val wantLower = title.lowercase(Locale.ROOT)
        var best: Track? = null
        var bestScore = 0
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            if (!r.optBoolean("isStreamable", true)) continue
            val t = r.optString("trackName")
            val a = r.optString("artistName")
            val fullLower = (t + " " + r.optString("collectionName")).lowercase(Locale.ROOT)
            if (VERSION_WORDS.findAll(fullLower).any { !wantLower.contains(it.value) }) continue
            val gotTitle = core(t)
            val gotArtists = artistTokens(a)
            if (wantArtists.none { w -> gotArtists.any { g -> sameArtist(w, g) } }) continue
            val titleScore = when {
                gotTitle.isEmpty() || wantTitle.isEmpty() -> 0
                gotTitle == wantTitle -> 4
                gotTitle.startsWith(wantTitle) || wantTitle.startsWith(gotTitle) -> 2
                closeSpelling(wantSound, sound(gotTitle)) -> 1
                else -> 0
            }
            if (titleScore == 0) continue
            val score = titleScore
            if (score > bestScore) { // ties keep Apple's own order (relevance)
                bestScore = score
                best = Track(r.optLong("trackId"), t, a, r.optLong("trackTimeMillis"))
            }
        }
        return best?.takeIf { it.id > 0 }
    }

    /** "P. Unnikrishnan" vs "UnniKrishnan"; "S P Balasubramaniam" vs "S.P. Balasubrahmanyam"; "SPB" vs either. */
    private fun sameArtist(a: Artist, b: Artist) =
        a.joined.contains(b.joined) || b.joined.contains(a.joined) ||
            closeSpelling(sound(a.joined), sound(b.joined)) ||
            (a.joined.length <= 4 && a.joined == b.initials) || (b.joined.length <= 4 && b.joined == a.initials)

    private class Artist(val joined: String, val initials: String)

    /** Edit distance within 30% of the longer word, for words of 6+ letters. */
    private fun closeSpelling(a: String, b: String): Boolean {
        val longer = maxOf(a.length, b.length)
        return minOf(a.length, b.length) >= 6 && distance(a, b) <= longer * 3 / 10
    }

    /** Spelling-free form for comparing transliterations: no h, y → i, no doubled letters. */
    private fun sound(s: String): String {
        val sb = StringBuilder()
        for (c in s.replace("h", "").replace('y', 'i')) if (sb.lastOrNull() != c) sb.append(c)
        return sb.toString()
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Title without anything in brackets: "Minnalae (From \"May Madham\")" → "minnalae". */
    private fun core(title: String) = norm(title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), ""))

    /** "S. P. Balasubrahmanyam & Reena Bharadhwaj" → [spbalasubrahmanyam, reenabharadhwaj]; "SPB" → [spb]. */
    private fun artistTokens(artist: String) =
        artist.split(",", "&", " and ", " feat", " ft.")
            .map { name -> Artist(norm(name), name.split(' ', '.').filter { it.isNotBlank() }.joinToString("") { norm(it).take(1) }) }
            .filter { it.joined.length >= 3 }

    /** Lower case, accents removed ("Señorita" → "senorita"), letters and digits only. */
    private fun norm(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .filter { it.isLetterOrDigit() }

    private fun encode(t: Track) = listOf(t.id, t.title, t.artist, t.durationMs).joinToString("\t")

    private fun decode(s: String): Track? {
        val p = s.split("\t")
        if (p.size != 4) return null
        return Track(p[0].toLongOrNull() ?: return null, p[1], p[2], p[3].toLongOrNull() ?: 0L)
    }
}
