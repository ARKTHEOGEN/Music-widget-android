package com.arktheogen.musicwidget

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * The pop-up (from the accessibility button or the widget): a media volume slider and a grid of
 * one-tap actions, like AssistiveTouch. Swipe down, tap outside or press Back to close.
 */
class QuickPanelActivity : Activity() {
    private class Tile(val icon: Int, val label: Int, val run: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var sheet: Sheet
    private lateinit var slider: SeekBar
    private lateinit var mute: ImageButton
    private lateinit var brightness: SeekBar
    private lateinit var auto: ImageButton
    private lateinit var audio: AudioManager
    private var torchId: String? = null
    private var torchOn = false
    private var torchTile: View? = null
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleShortcut(intent)) return
        setContentView(R.layout.activity_quick)
        audio = getSystemService(AudioManager::class.java)
        sheet = findViewById(R.id.sheet)
        slider = findViewById(R.id.slider)
        mute = findViewById(R.id.btn_mute)
        brightness = findViewById(R.id.brightness)
        auto = findViewById(R.id.btn_auto)
        findViewById<View>(R.id.root).setOnClickListener { close() }
        sheet.setOnClickListener { } // taps on the sheet's empty space don't close it
        sheet.onDismiss = { close() }
        keepAboveNavigationBar()
        wireVolume()
        wireBrightness()
        torchId = try { Actions.torchCamera(this) } catch (e: CameraAccessException) { null }
        buildGrid()
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            sheet.animate().translationY(0f).setDuration(220).start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    /** The widget's QR button goes through here (only an activity may open another app). */
    private fun handleShortcut(i: Intent): Boolean {
        if (i.getStringExtra(EXTRA_DO) != DO_QR) return false
        Actions.openQrScanner(this)
        finishQuietly()
        return true
    }

    override fun onStart() {
        super.onStart()
        if (!::audio.isInitialized) return
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        torchId?.let { getSystemService(CameraManager::class.java).registerTorchCallback(torchCallback, main) }
        showVolume()
        showBrightness()
    }

    override fun onStop() {
        super.onStop()
        if (!::audio.isInitialized) return
        contentResolver.unregisterContentObserver(volumeObserver)
        torchId?.let { getSystemService(CameraManager::class.java).unregisterTorchCallback(torchCallback) }
        if (!closing) finishQuietly() // left by Home/another app: don't linger
    }

    @Deprecated("Back closes with the slide-down animation")
    override fun onBackPressed() = close()

    // ---------------------------------------------------------------- volume

    private val volumeObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            showVolume()
            showBrightness()
        }
    }

    private fun wireVolume() {
        slider.max = audio.getStreamMaxVolume(STREAM)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(STREAM, value, 0)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        mute.setOnClickListener { audio.adjustStreamVolume(STREAM, AudioManager.ADJUST_TOGGLE_MUTE, 0); showVolume() }
        findViewById<View>(R.id.btn_tray).setOnClickListener { closeThen { Actions.showVolumePanel(this) } }
    }

    private fun showVolume() {
        val muted = audio.isStreamMute(STREAM) || audio.getStreamVolume(STREAM) == 0
        slider.progress = if (audio.isStreamMute(STREAM)) 0 else audio.getStreamVolume(STREAM)
        mute.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_mute)
        mute.isSelected = muted
    }

    // ---------------------------------------------------------------- brightness

    private fun wireBrightness() {
        auto.setOnClickListener {
            when {
                !Brightness.canChange(this) -> needWriteSettings()
                !AutoBrightness.setOn(this, !AutoBrightness.isOn(this)) -> needService()
                else -> showBrightness()
            }
        }
        brightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val ctx = this@QuickPanelActivity
                when {
                    !Brightness.canChange(ctx) -> needWriteSettings()
                    AutoBrightness.isOn(ctx) -> AutoBrightness.setPreference(ctx, value / 1000f) // "brighter/dimmer than auto"
                    else -> Brightness.setLevel(ctx, value / 1000f)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private fun showBrightness() {
        val on = AutoBrightness.isOn(this)
        auto.isSelected = on
        auto.setImageResource(if (on) R.drawable.ic_brightness_auto else R.drawable.ic_brightness)
        auto.contentDescription = getString(if (on) R.string.cd_auto_on else R.string.cd_auto_off)
        // Android's own adaptive mode (if switched on in Settings) ignores the level: grey ours out then.
        val system = Brightness.isSystemAuto(this)
        brightness.isEnabled = !system
        brightness.alpha = if (system) 0.38f else 1f
        if (!brightness.isPressed) brightness.progress = (Brightness.level(this) * 1000).toInt()
    }

    /** Once only: the owner allows "Modify system settings" for Side Panel. */
    private fun needWriteSettings() {
        Toast.makeText(this, R.string.need_write_settings, Toast.LENGTH_LONG).show()
        closeThen { startActivity(Brightness.grantIntent(this)) }
    }

    // ---------------------------------------------------------------- actions

    private fun global(action: Int, delay: Long = CLOSE_MS) = closeThen {
        if (!LockService.perform(action, delay)) needService()
    }

    private fun buildGrid() {
        val tiles = listOf(
            Tile(R.drawable.ic_lock, R.string.t_lock) { global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) },
            Tile(R.drawable.ic_screenshot, R.string.t_screenshot) { global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, SCREENSHOT_MS) },
            Tile(R.drawable.ic_notifications, R.string.t_notifications) { global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) },
            Tile(R.drawable.ic_tune, R.string.t_quick_settings) { global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) },
            Tile(R.drawable.ic_home, R.string.t_home) { global(AccessibilityService.GLOBAL_ACTION_HOME) },
            Tile(R.drawable.ic_back, R.string.t_back) { global(AccessibilityService.GLOBAL_ACTION_BACK) },
            Tile(R.drawable.ic_recents, R.string.t_recents) { global(AccessibilityService.GLOBAL_ACTION_RECENTS) },
            Tile(R.drawable.ic_power, R.string.t_power) { global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) },
            Tile(R.drawable.ic_flashlight, R.string.t_torch) { toggleTorch() },
            Tile(R.drawable.ic_qr, R.string.t_qr) { closeThen { Actions.openQrScanner(this) } },
            Tile(R.drawable.ic_split, R.string.t_split) { global(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) },
            Tile(R.drawable.ic_apps, R.string.t_apps) { global(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS) },
        )
        val grid = findViewById<LinearLayout>(R.id.grid)
        tiles.chunked(COLUMNS).forEach { row ->
            grid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEach { addView(tileView(it)) }
            })
        }
    }

    private fun tileView(t: Tile) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setPadding(dp(4), dp(6), dp(4), dp(6))
        val icon = ImageView(context).apply {
            setImageResource(t.icon)
            setBackgroundResource(R.drawable.tile_bg)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
            contentDescription = getString(t.label)
            setOnClickListener { t.run() }
        }
        addView(icon)
        addView(TextView(context).apply {
            setText(t.label)
            setTextColor(getColor(R.color.text2))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(6), 0, 0)
        })
        if (t.icon == R.drawable.ic_flashlight) torchTile = icon
    }

    private fun toggleTorch() {
        val id = torchId ?: return Toast.makeText(this, R.string.no_torch, Toast.LENGTH_SHORT).show()
        try {
            getSystemService(CameraManager::class.java).setTorchMode(id, !torchOn)
        } catch (e: CameraAccessException) {
            Toast.makeText(this, R.string.torch_busy, Toast.LENGTH_SHORT).show() // camera in use
        }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchId) return
            torchOn = enabled
            torchTile?.isSelected = enabled
        }
    }

    private fun needService() {
        Toast.makeText(this, R.string.lock_off, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, SidePanelSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------------------------------------------------------------- open / close

    private fun close() = closeThen { }

    /** Slides the sheet away, then runs [then] (most actions need the pop-up gone first). */
    private fun closeThen(then: () -> Unit) {
        if (closing) return
        closing = true
        then()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(CLOSE_ANIM_MS).withEndAction { finishQuietly() }.start()
    }

    private fun finishQuietly() {
        closing = true
        finish()
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    /** Edge-to-edge (Android 15+): keep the sheet above the gesture bar. */
    private fun keepAboveNavigationBar() {
        val base = (sheet.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom
            sheet.layoutParams = (sheet.layoutParams as android.widget.FrameLayout.LayoutParams).apply { bottomMargin = base + bottom }
            insets
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val DO_QR = "qr"
        private const val EXTRA_DO = "do"
        private const val STREAM = AudioManager.STREAM_MUSIC
        private const val COLUMNS = 4
        private const val CLOSE_ANIM_MS = 180L
        /** Run actions after the sheet has gone. */
        private const val CLOSE_MS = 220L
        /** Screenshots wait for the dimmed background to clear too. */
        private const val SCREENSHOT_MS = 450L

        fun intent(ctx: Context, what: String?) = Intent(ctx, QuickPanelActivity::class.java).putExtra(EXTRA_DO, what)

        /** From the accessibility button (accessibility services may open screens from the background). */
        fun open(ctx: Context) = ctx.startActivity(intent(ctx, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
