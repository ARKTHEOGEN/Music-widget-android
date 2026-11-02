package com.arktheogen.musicwidget

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.view.animation.LinearInterpolator
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The pop-up player. Home-screen widgets accept only taps, so every swipe and
 * drag lives here:
 *  - swipe art left / right → next / previous (art follows your finger)
 *  - double-tap art → play / pause
 *  - tap art → lyrics on / off; swipe up → lyrics; swipe down → close
 *  - with lyrics showing, a sideways swipe still changes track
 *  - drag the bar → seek; with lyrics: tap a synced line → jump there, tap anywhere else → back to art
 */
class PlayerPanelActivity : Activity(), SessionHub.Listener {

    private lateinit var card: LinearLayout
    private lateinit var artFrame: ArtFrame
    private lateinit var art: ImageView
    private lateinit var lyricsScroll: ScrollView
    private lateinit var lyricsList: LinearLayout
    private lateinit var lyricsStatus: TextView
    private lateinit var infoScroll: ScrollView
    private lateinit var infoList: LinearLayout
    private var infoVisible = false
    private var infoKey: String? = null
    /** The audio rows, refreshed on every player change while the info shows (the format arrives after the song starts). */
    private var audioBox: LinearLayout? = null
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var badge: TextView

    private val ticker = Handler(Looper.getMainLooper())
    private var np: NowPlaying? = null
    private var shownArt: Bitmap? = null
    /** After a swipe: the song we swiped away from, and where the next art slides in from (0 = none). */
    private var swipeFromKey: String? = null
    private var slideInFrom = 0f
    private var userSeeking = false
    private var cardBottomPadding = 0

    private var lyricsVisible = false
    private var lyricsKey: String? = null
    /** Non-null while the lyrics area shows a message (loading / not found / error) instead of lines. */
    private var lyricsMessageRes: Int? = null
    private var syncedLines: List<Lyrics.Line> = emptyList()
    private var lineViews: List<TextView> = emptyList()
    private var currentLine = -1
    private var lastManualScroll = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_panel)
        bindViews()
        sizeCard()
        wireControls()
        SessionHub.ensureStarted(this)
        lyricsVisible = intent.getBooleanExtra(EXTRA_SHOW_LYRICS, false)
        card.post {
            card.translationY = card.height.toFloat()
            card.animate().translationY(0f).setDuration(220).start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_LYRICS, false)) showLyrics(true)
    }

    override fun onStart() {
        super.onStart()
        if (!SessionHub.hasAccess(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        SessionHub.addListener(this)
        onNowPlayingChanged()
        ticker.post(tick)
    }

    override fun onStop() {
        super.onStop()
        SessionHub.removeListener(this)
        ticker.removeCallbacks(tick)
        ticker.removeCallbacks(retryLyrics)
        // Closed mid-swipe: put the art back so it's there when the panel opens again.
        ticker.removeCallbacks(slideIn)
        slideInFrom = 0f
        swipeFromKey = null
        artFrame.animate().cancel()
        artFrame.translationX = 0f
        artFrame.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- setup

    private fun bindViews() {
        card = findViewById(R.id.card)
        artFrame = findViewById(R.id.art_frame)
        art = findViewById(R.id.art)
        lyricsScroll = findViewById(R.id.lyrics_scroll)
        lyricsList = findViewById(R.id.lyrics_list)
        lyricsStatus = findViewById(R.id.lyrics_status)
        infoScroll = findViewById(R.id.info_scroll)
        infoList = findViewById(R.id.info_list)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        seek = findViewById(R.id.seek)
        elapsed = findViewById(R.id.elapsed)
        duration = findViewById(R.id.duration)
        btnShuffle = findViewById(R.id.btn_shuffle)
        btnRepeat = findViewById(R.id.btn_repeat)
        btnPlay = findViewById(R.id.btn_play)
        badge = findViewById(R.id.badge)
    }

    /** Card: full width on phones, max 480dp on tablets. Art: square, never taller than 42% of screen. */
    private fun sizeCard() {
        val dm = resources.displayMetrics
        val cardWidth = min(dm.widthPixels, (480 * dm.density).toInt())
        card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply { width = cardWidth }
        val artSize = min(cardWidth - card.paddingLeft - card.paddingRight, (dm.heightPixels * 0.42f).toInt())
        artFrame.layoutParams = artFrame.layoutParams.apply { width = artSize; height = artSize }
        lyricsList.setPadding(lyricsList.paddingLeft, artSize / 3, lyricsList.paddingRight, artSize / 2)

        // Keep the card above the gesture/navigation bar (edge-to-edge on Android 15+).
        cardBottomPadding = card.paddingBottom
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom
            card.setPadding(card.paddingLeft, card.paddingTop, card.paddingRight, cardBottomPadding + bottom)
            insets
        }
    }

    private fun wireControls() {
        findViewById<View>(R.id.root).setOnClickListener { close() }
        findViewById<View>(R.id.btn_next).setOnClickListener { SessionHub.next() }
        findViewById<View>(R.id.btn_prev).setOnClickListener { SessionHub.previous() }
        btnShuffle.setOnClickListener { SessionHub.toggleShuffle() }
        btnRepeat.setOnClickListener { SessionHub.cycleRepeat() }
        findViewById<View>(R.id.btn_lyrics).setOnClickListener { showLyrics(!lyricsVisible) }
        findViewById<View>(R.id.btn_info).setOnClickListener { showInfo(!infoVisible) }
        // Tap anywhere on the info (rows aren't clickable, so taps reach the list) → back to the art.
        infoList.setOnClickListener { showInfo(false) }
        findViewById<View>(R.id.btn_open).setOnClickListener { SessionHub.openApp(this, np?.app?.pkg) }

        badge.setOnClickListener { Toast.makeText(this, Relay.toggle(this), Toast.LENGTH_SHORT).show() }
        badge.setOnLongClickListener { SessionHub.openApp(this, np?.app?.pkg) }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) elapsed.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                userSeeking = false
                SessionHub.seekTo(bar.progress.toLong())
            }
        })

        artFrame.touchFirst = ArtGestures()::handle
        // Tap anywhere around the lines (sides, gaps, empty space) → back to the art.
        lyricsScroll.isFillViewport = true
        lyricsList.setOnClickListener { showLyrics(false) }
        lyricsScroll.setOnTouchListener { _, _ ->
            lastManualScroll = SystemClock.uptimeMillis()
            false
        }
    }

    // ---------------------------------------------------------------- render

    override fun onNowPlayingChanged() {
        val state = SessionHub.nowPlaying()
        np = state

        if (state.hasSession) {
            title.text = state.title
            artist.text = state.artist
        } else {
            title.text = getString(R.string.nothing_playing)
            artist.text = state.app?.let { getString(R.string.tap_play_to_open, it.label) }
                ?: getString(R.string.open_a_music_app)
        }

        if (state.art !== shownArt || shownArt == null) {
            shownArt = state.art
            if (state.art != null) art.setImageBitmap(state.art) else art.setImageResource(R.drawable.art_placeholder)
            applyColors(Artwork.lookFor(this, state.art))
        }
        if (slideInFrom != 0f && state.trackKey != swipeFromKey && state.art != null) {
            ticker.removeCallbacks(slideIn)
            slideIn.run()
        }

        btnPlay.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = getString(if (state.isPlaying) R.string.cd_pause else R.string.cd_play)
        btnPlay.setOnClickListener {
            if (state.hasSession) SessionHub.playPause() else SessionHub.openApp(this, state.app?.pkg)
        }

        btnShuffle.alpha = alphaFor(state.shuffle)
        btnShuffle.isEnabled = state.shuffle != Toggle.UNAVAILABLE
        btnRepeat.setImageResource(if (state.repeat == Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        btnRepeat.alpha = alphaFor(state.repeat)
        btnRepeat.isEnabled = state.repeat != Repeat.UNAVAILABLE

        seek.max = state.durationMs.toInt().coerceAtLeast(1)
        seek.isEnabled = state.canSeek
        duration.text = if (state.durationMs > 0) formatTime(state.durationMs) else ""

        renderBadge(state)

        updateProgress()
        showLyrics(lyricsVisible) // reloads only if the track changed
        if (infoVisible) {
            loadInfo() // same
            renderAudio(state)
        }
    }

    /** Where the song plays, and whether YouTube Music is picking (tap = relay on/off). */
    private fun renderBadge(state: NowPlaying) {
        val app = state.app
        badge.visibility = if (app != null) View.VISIBLE else View.GONE
        if (app == null) return
        badge.text = if (state.relay) getString(R.string.badge_relay, app.label) else app.label
        badge.isSelected = state.relay
        badge.contentDescription = getString(if (state.relay) R.string.cd_relay_on else R.string.cd_relay_off)
        if (badge.tag != app.pkg) {
            badge.tag = app.pkg
            val size = (20 * resources.displayMetrics.density).toInt()
            val icon = MusicApps.icon(this, app.pkg)?.mutate()?.apply { setBounds(0, 0, size, size) }
            badge.setCompoundDrawablesRelative(icon, null, null, null)
        }
    }

    private fun applyColors(look: Artwork.Look) {
        card.background.mutate().setTint(look.background)
        val accent = ColorStateList.valueOf(look.accent)
        seek.progressTintList = accent
        seek.thumbTintList = accent
        btnPlay.backgroundTintList = accent
        btnPlay.imageTintList = ColorStateList.valueOf(look.onAccent)
    }

    private fun alphaFor(t: Toggle) = when (t) {
        Toggle.ON -> 1f
        Toggle.UNKNOWN -> 0.8f
        Toggle.OFF -> 0.45f
        Toggle.UNAVAILABLE -> 0.2f
    }

    private fun alphaFor(r: Repeat) = when (r) {
        Repeat.ALL, Repeat.ONE -> 1f
        Repeat.UNKNOWN -> 0.8f
        Repeat.OFF -> 0.45f
        Repeat.UNAVAILABLE -> 0.2f
    }

    /** Runs only while the panel is visible: 4×/s with synced lyrics showing, else 2×/s. */
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            val fast = lyricsVisible && syncedLines.isNotEmpty()
            ticker.postDelayed(this, if (fast) 250 else 500)
        }
    }

    private fun updateProgress() {
        val state = np ?: return
        val pos = state.livePositionMs()
        if (!userSeeking) {
            seek.progress = pos.toInt()
            elapsed.text = if (state.durationMs > 0) formatTime(pos) else ""
        }
        highlightLyric(pos)
    }

    // ---------------------------------------------------------------- lyrics

    private fun showLyrics(show: Boolean) {
        if (show && infoVisible) showInfo(false)
        lyricsVisible = show
        art.alpha = if (show || infoVisible) 0.15f else 1f
        if (show) loadLyrics()
        refreshLyricsViews()
    }

    /** One place decides what the lyrics area shows: nothing, a message, or the lines. */
    private fun refreshLyricsViews() {
        val message = lyricsMessageRes
        lyricsStatus.visibility = if (lyricsVisible && message != null) View.VISIBLE else View.GONE
        lyricsScroll.visibility = if (lyricsVisible && message == null) View.VISIBLE else View.GONE
    }

    /** LRCLIB was busy: try again while the lyrics stay open (stopped in onStop). */
    private val retryLyrics = Runnable { if (lyricsVisible && !isDestroyed) loadLyrics() }

    private fun loadLyrics() {
        ticker.removeCallbacks(retryLyrics)
        val state = np ?: return
        if (!state.hasSession || state.title.isBlank()) {
            lyricsKey = null
            lyricsMessage(R.string.lyrics_nothing)
            return
        }
        val key = state.trackKey
        if (key == lyricsKey) return
        lyricsKey = key
        lyricsMessage(R.string.lyrics_loading)
        Lyrics.load(this, key, state.title, state.artist, state.durationMs) { result ->
            if (key == lyricsKey && !isDestroyed) showLyricsResult(result)
        }
    }

    private fun lyricsMessage(res: Int) {
        syncedLines = emptyList()
        lineViews = emptyList()
        lyricsList.removeAllViews()
        lyricsStatus.setText(res)
        lyricsMessageRes = res
        refreshLyricsViews()
    }

    private fun showLyricsResult(result: Lyrics.Result) {
        when (result) {
            is Lyrics.Result.Synced -> {
                lyricsList.removeAllViews()
                syncedLines = result.lines
                lineViews = result.lines.map { line ->
                    lyricLine(line.text.ifEmpty { "♪" }).apply {
                        alpha = 0.45f
                        setOnClickListener { SessionHub.seekTo(line.timeMs) }
                    }.also { lyricsList.addView(it) }
                }
                currentLine = -1
                revealLyrics()
            }
            is Lyrics.Result.Plain -> {
                lyricsList.removeAllViews()
                syncedLines = emptyList()
                lineViews = emptyList()
                lyricsList.addView(lyricLine(result.text))
                revealLyrics()
            }
            Lyrics.Result.Instrumental -> lyricsMessage(R.string.lyrics_instrumental)
            Lyrics.Result.NotFound -> lyricsMessage(R.string.lyrics_not_found)
            Lyrics.Result.Busy -> {
                lyricsMessage(R.string.lyrics_busy)
                lyricsKey = null
                ticker.postDelayed(retryLyrics, BUSY_RETRY_MS)
            }
            Lyrics.Result.Failed -> {
                lyricsMessage(R.string.lyrics_failed)
                lyricsKey = null // allow retry on next tap
            }
        }
    }

    private fun revealLyrics() {
        lyricsMessageRes = null
        refreshLyricsViews()
        lyricsScroll.scrollTo(0, 0)
        np?.let { highlightLyric(it.livePositionMs()) }
    }

    /**
     * Only as wide as its text, so taps beside a line reach the list (→ back to the art).
     * Tap = back to the art; synced lines replace this with "jump there".
     */
    private fun lyricLine(text: String) = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        this.text = text
        setOnClickListener { showLyrics(false) }
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.DEFAULT_BOLD
        val pad = (6 * resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
    }

    /** Binary search for the line being sung, then centre it (unless the user is scrolling). */
    private fun highlightLyric(pos: Long) {
        if (!lyricsVisible || syncedLines.isEmpty()) return
        val target = pos + 250 // show the line a touch early
        var lo = 0
        var hi = syncedLines.lastIndex
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (syncedLines[mid].timeMs <= target) {
                idx = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (idx == currentLine) return
        lineViews.getOrNull(currentLine)?.alpha = 0.45f
        lineViews.getOrNull(idx)?.alpha = 1f
        currentLine = idx
        if (SystemClock.uptimeMillis() - lastManualScroll < 3000) return
        lineViews.getOrNull(idx)?.let { v ->
            val y = lyricsList.top + v.top - lyricsScroll.height / 2 + v.height / 2
            lyricsScroll.smoothScrollTo(0, max(0, y))
        }
    }

    // ---------------------------------------------------------------- info

    /** Song and artist details over the art, toggled like the lyrics (only one shows at a time). */
    private fun showInfo(show: Boolean) {
        if (show && lyricsVisible) showLyrics(false)
        infoVisible = show
        art.alpha = if (show || lyricsVisible) 0.15f else 1f
        infoScroll.visibility = if (show) View.VISIBLE else View.GONE
        if (show) loadInfo()
    }

    private fun loadInfo() {
        val state = np ?: return
        if (!state.hasSession || state.title.isBlank()) {
            infoKey = null
            infoMessage(getString(R.string.lyrics_nothing))
            return
        }
        val key = state.trackKey
        if (key == infoKey) return
        infoKey = key
        infoMessage(getString(R.string.info_loading))
        infoScroll.scrollTo(0, 0)
        SongInfo.load(this, state.title, state.artist) { info ->
            if (key != infoKey || isDestroyed) return@load
            if (info == null) {
                infoKey = null // try again next time
                infoMessage(getString(R.string.info_failed))
            } else {
                renderInfo(info, state)
            }
        }
    }

    private fun infoMessage(text: String) {
        audioBox = null
        infoList.removeAllViews()
        infoList.addView(infoText(text, 16f, bold = false, secondary = false))
    }

    private fun renderInfo(info: SongInfo.Info, state: NowPlaying) {
        infoList.removeAllViews()
        infoList.addView(infoHeading(R.string.info_song))
        listOf(
            R.string.info_album to info.album,
            R.string.info_released to info.year,
            R.string.info_genre to info.genre,
            R.string.info_track to info.track,
            R.string.info_length to state.durationMs.takeIf { it > 0 }?.let { formatTime(it) },
        ).forEach { (label, value) -> if (!value.isNullOrBlank()) infoList.addView(infoRow(getString(label), value)) }
        info.copyright?.let { infoList.addView(infoText(it, 12f, bold = false, secondary = true)) }
        infoList.addView(infoHeading(R.string.info_audio))
        audioBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { infoList.addView(it) }
        renderAudio(state)
        if (info.artists.isEmpty()) return
        infoList.addView(infoHeading(R.string.info_artists))
        info.artists.forEach { infoList.addView(artistRow(it)) }
    }

    /** Stream format (what the app reports) → Android's mixer → output, for the audio nerds. */
    private fun renderAudio(state: NowPlaying) {
        val box = audioBox ?: return
        val q = AudioQuality.read(this, state.app?.pkg)
        box.removeAllViews()
        val format = listOfNotNull(
            q.bitDepth?.let { "$it-bit" },
            q.sampleRate?.let { AudioQuality.khz(it) + " kHz" },
            q.channels?.let { if (it == 2) "stereo" else if (it == 1) "mono" else "$it ch" },
        ).joinToString(" / ")
        listOf(
            R.string.info_quality to q.tier,
            R.string.info_codec to q.codec,
            R.string.info_format to format.ifEmpty { null },
            R.string.info_bitrate to q.bitRate?.let { String.format(java.util.Locale.ROOT, "%,d kbps", it / 1000) },
            R.string.info_output to q.output,
            R.string.info_mixer to q.mixerRate?.let { rate ->
                when {
                    q.bitPerfect -> getString(R.string.info_bit_perfect)
                    q.resampled -> getString(R.string.info_resampled, AudioQuality.khz(q.sampleRate!!), AudioQuality.khz(rate))
                    else -> AudioQuality.khz(rate) + " kHz"
                }
            },
        ).forEach { (label, value) -> if (!value.isNullOrBlank()) box.addView(infoRow(getString(label), value)) }
        if (q.codec == null) box.addView(infoText(getString(R.string.info_no_format), 12f, bold = false, secondary = true))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun infoText(text: String, sp: Float, bold: Boolean, secondary: Boolean) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(if (secondary) getColor(R.color.text_secondary) else 0xFFFFFFFF.toInt())
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun infoHeading(res: Int) = infoText(getString(res).uppercase(), 12f, bold = true, secondary = true).apply {
        letterSpacing = 0.1f
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun infoRow(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(infoText(label, 14f, bold = false, secondary = true).apply {
            layoutParams = LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        addView(infoText(value, 14f, bold = false, secondary = false).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    private fun artistRow(a: SongInfo.Artist) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, dp(8))
        val size = dp(56)
        addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(12) }
            setBackgroundResource(R.drawable.photo_circle)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val photo = a.photo?.let { decodePhoto(it.path, size) }
            if (photo != null) setImageBitmap(photo) else {
                setImageResource(R.drawable.ic_music_note)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(infoText(a.name, 15f, bold = true, secondary = false))
            addView(infoText(a.about ?: getString(R.string.info_no_bio), 13f, bold = false, secondary = true).apply {
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        })
    }

    /** Decodes a saved photo no bigger than needed (photos are 250 px; shown at 56 dp). */
    private fun decodePhoto(path: String, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------------------------------------------------------------- gestures

    private fun close() {
        card.animate().translationY(card.height.toFloat()).setDuration(180).withEndAction { finish() }.start()
    }

    /**
     * Plain slide: the art leaves the way it was dragged, stays out of sight until the next
     * song's art has arrived (the app can take a few seconds), then slides in from the other side.
     */
    private fun swipeTrack(v: View, toNext: Boolean) {
        val out = if (toNext) -v.width.toFloat() else v.width.toFloat()
        val remaining = abs(out - v.translationX) / v.width
        swipeFromKey = np?.trackKey
        slideInFrom = -out
        v.animate().translationX(out).setInterpolator(LinearInterpolator())
            .setDuration((SLIDE_OUT_MS * remaining).toLong().coerceAtLeast(60)).withLayer()
            .withEndAction {
                v.visibility = View.INVISIBLE
                if (toNext) SessionHub.next() else SessionHub.previous()
                ticker.postDelayed(slideIn, SLIDE_IN_TIMEOUT_MS)
            }.start()
    }

    /** Next song's art is showing (or we waited long enough): bring it in. */
    private val slideIn = Runnable {
        if (slideInFrom == 0f) return@Runnable
        val v = artFrame
        v.translationX = slideInFrom
        slideInFrom = 0f
        swipeFromKey = null
        v.visibility = View.VISIBLE
        v.animate().translationX(0f).setInterpolator(DECELERATE).setDuration(SLIDE_IN_MS).withLayer().start()
    }

    private fun springBack(v: View) {
        v.animate().translationX(0f).setInterpolator(DECELERATE).setDuration(180).withLayer().start()
    }

    /** Horizontal drag moves the art with your finger; release past 25% width skips. */
    /**
     * Sees every touch on the art first (through [ArtFrame]).
     * Art or a lyrics message showing: all gestures are ours.
     * Lyric lines showing: a sideways drag is ours, and so is a pull down once the list is at its top
     * (closes the panel); scrolling and tapping a line stay with the list.
     */
    private inner class ArtGestures : GestureDetector.SimpleOnGestureListener() {
        private val detector = GestureDetector(this@PlayerPanelActivity, this)
        private val slop = ViewConfiguration.get(this@PlayerPanelActivity).scaledTouchSlop
        private val swipeDistance = 80 * resources.displayMetrics.density
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var scrolling = false
        private var pullingDown = false

        /** Returns true when the gesture is ours. */
        fun handle(e: MotionEvent): Boolean {
            // Lines or info: scrolling and taps belong to the list.
            val linesShowing = (lyricsVisible && lyricsMessageRes == null) || infoVisible
            if (!linesShowing) detector.onTouchEvent(e)
            val v = artFrame
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    scrolling = false
                    pullingDown = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !scrolling && !pullingDown) {
                        if (abs(dx) > slop && abs(dx) > abs(dy)) dragging = true
                        else if (abs(dy) > slop) {
                            // Vertical: never becomes a track swipe. Over lines, pulling down at the top closes.
                            if (linesShowing && dy > 0 && !lyricsScroll.canScrollVertically(-1)) pullingDown = true
                            else scrolling = true
                        }
                    }
                    if (dragging) v.translationX = dx
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    val up = e.actionMasked == MotionEvent.ACTION_UP
                    val wasDragging = dragging
                    val wasPulling = pullingDown
                    dragging = false
                    pullingDown = false
                    when {
                        wasDragging && up && abs(dx) > v.width * 0.25f -> swipeTrack(v, toNext = dx < 0)
                        wasDragging -> springBack(v)
                        wasPulling -> if (up && dy > swipeDistance) close()
                        linesShowing -> return false
                        up && abs(dy) > abs(dx) && dy > swipeDistance -> close()
                        up && abs(dy) > abs(dx) && -dy > swipeDistance -> showLyrics(true)
                    }
                    return true
                }
            }
            return dragging || pullingDown || !linesShowing
        }

        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            SessionHub.playPause()
            return true
        }

        // Toggles, so a "no lyrics" message can be tapped away.
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            showLyrics(!lyricsVisible)
            return true
        }
    }

    companion object {
        const val EXTRA_SHOW_LYRICS = "show_lyrics"
        private const val SLIDE_OUT_MS = 220L
        private const val SLIDE_IN_MS = 260L
        /** Longest wait for the next song's art before sliding in whatever is there. */
        private const val SLIDE_IN_TIMEOUT_MS = 6_000L
        private val DECELERATE = PathInterpolator(0f, 0f, 0.2f, 1f)
        private const val BUSY_RETRY_MS = 20_000L
    }
}
