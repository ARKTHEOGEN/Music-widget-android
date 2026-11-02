package com.arktheogen.musicwidget

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The pop-up player. Home-screen widgets accept only taps, so every swipe and
 * drag lives here:
 *  - swipe art left / right → next / previous (art follows your finger)
 *  - double-tap art → play / pause
 *  - tap or swipe up on art → lyrics; swipe down → close
 *  - drag the bar → seek; tap a lyric line → jump there
 */
class PlayerPanelActivity : Activity(), SessionHub.Listener {

    private lateinit var card: LinearLayout
    private lateinit var artFrame: FrameLayout
    private lateinit var art: ImageView
    private lateinit var lyricsScroll: ScrollView
    private lateinit var lyricsList: LinearLayout
    private lateinit var lyricsStatus: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var chipAuto: TextView
    private lateinit var chips: List<ImageView>

    private val ticker = Handler(Looper.getMainLooper())
    private var np: NowPlaying? = null
    private var shownArt: Bitmap? = null
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
    }

    // ---------------------------------------------------------------- setup

    private fun bindViews() {
        card = findViewById(R.id.card)
        artFrame = findViewById(R.id.art_frame)
        art = findViewById(R.id.art)
        lyricsScroll = findViewById(R.id.lyrics_scroll)
        lyricsList = findViewById(R.id.lyrics_list)
        lyricsStatus = findViewById(R.id.lyrics_status)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        seek = findViewById(R.id.seek)
        elapsed = findViewById(R.id.elapsed)
        duration = findViewById(R.id.duration)
        btnShuffle = findViewById(R.id.btn_shuffle)
        btnRepeat = findViewById(R.id.btn_repeat)
        btnPlay = findViewById(R.id.btn_play)
        chipAuto = findViewById(R.id.chip_auto)
        chips = listOf(findViewById(R.id.chip_0), findViewById(R.id.chip_1), findViewById(R.id.chip_2))
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
        findViewById<View>(R.id.btn_open).setOnClickListener { SessionHub.openApp(this, np?.app?.pkg) }

        chipAuto.setOnClickListener { SessionHub.select(this, SessionHub.AUTO) }
        MusicApps.ALL.forEachIndexed { i, app ->
            val chip = chips[i]
            val icon = MusicApps.icon(this, app.pkg) // null = not installed: hide its chip
            chip.visibility = if (icon != null) View.VISIBLE else View.GONE
            chip.setImageDrawable(icon)
            chip.contentDescription = app.label
            chip.setOnClickListener { SessionHub.select(this, app.pkg) }
            chip.setOnLongClickListener { SessionHub.openApp(this, app.pkg) }
        }

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

        artFrame.setOnTouchListener(ArtGestures())
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

        chipAuto.isSelected = state.mode == SessionHub.AUTO
        MusicApps.ALL.forEachIndexed { i, app -> chips[i].isSelected = state.mode == app.pkg }

        updateProgress()
        showLyrics(lyricsVisible) // reloads only if the track changed
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
        lyricsVisible = show
        art.alpha = if (show) 0.15f else 1f
        if (show) loadLyrics()
        refreshLyricsViews()
    }

    /** One place decides what the lyrics area shows: nothing, a message, or the lines. */
    private fun refreshLyricsViews() {
        val message = lyricsMessageRes
        lyricsStatus.visibility = if (lyricsVisible && message != null) View.VISIBLE else View.GONE
        lyricsScroll.visibility = if (lyricsVisible && message == null) View.VISIBLE else View.GONE
    }

    private fun loadLyrics() {
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
        Lyrics.load(key, state.title, state.artist, state.durationMs) { result ->
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

    private fun lyricLine(text: String) = TextView(this).apply {
        this.text = text
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

    // ---------------------------------------------------------------- gestures

    private fun close() {
        card.animate().translationY(card.height.toFloat()).setDuration(180).withEndAction { finish() }.start()
    }

    private fun swipeTrack(v: View, toNext: Boolean) {
        val out = if (toNext) -v.width.toFloat() else v.width.toFloat()
        v.animate().translationX(out).alpha(0f).rotation(0f).setDuration(150).withEndAction {
            if (toNext) SessionHub.next() else SessionHub.previous()
            v.translationX = -out
            v.animate().translationX(0f).alpha(1f).setDuration(200).start()
        }.start()
    }

    private fun springBack(v: View) {
        v.animate().translationX(0f).alpha(1f).rotation(0f).setDuration(180).start()
    }

    /** Horizontal drag moves the art with your finger; release past 25% width skips. */
    private inner class ArtGestures : View.OnTouchListener, GestureDetector.SimpleOnGestureListener() {
        private val detector = GestureDetector(this@PlayerPanelActivity, this)
        private val slop = ViewConfiguration.get(this@PlayerPanelActivity).scaledTouchSlop
        private val swipeDistance = 80 * resources.displayMetrics.density
        private var downX = 0f
        private var downY = 0f
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (lyricsVisible) return false // let the lyrics list scroll
            detector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(dy)) dragging = true
                    if (dragging) {
                        v.translationX = dx
                        v.rotation = dx / v.width * 6f
                        v.alpha = 1f - min(0.6f, abs(dx) / v.width)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    val up = e.actionMasked == MotionEvent.ACTION_UP
                    when {
                        dragging && up && abs(dx) > v.width * 0.25f -> swipeTrack(v, toNext = dx < 0)
                        dragging -> springBack(v)
                        up && abs(dy) > abs(dx) && dy > swipeDistance -> close()
                        up && abs(dy) > abs(dx) && -dy > swipeDistance -> showLyrics(true)
                    }
                    dragging = false
                }
            }
            return true
        }

        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            SessionHub.playPause()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            showLyrics(true)
            return true
        }
    }

    companion object {
        const val EXTRA_SHOW_LYRICS = "show_lyrics"
    }
}
