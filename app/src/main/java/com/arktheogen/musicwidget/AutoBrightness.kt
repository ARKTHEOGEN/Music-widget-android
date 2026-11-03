package com.arktheogen.musicwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.log10

/**
 * Side Panel's own auto brightness, because Android 17's is broken on the owner's Pixel:
 * the light sensor reports only when the light changes, and Android treats 5 s of quiet
 * as "no reading" and falls back to the manual level (measured 2026-11-03). Here, a quiet
 * sensor means the light hasn't changed, so the last reading stays valid.
 *
 *  - Runs inside [LockService], and listens to the sensor only while the screen is on.
 *  - Brightens after [BRIGHTEN_MS], dims only after [DIM_MS] (a passing shadow doesn't flicker it),
 *    and fades over [RAMP_STEPS] small steps.
 *  - The brightness slider sets a preference ("a bit brighter than auto"), saved as an offset.
 */
object AutoBrightness : SensorEventListener {
    private const val PREFS = "brightness"
    private const val KEY_ON = "own_auto"
    private const val KEY_OFFSET = "offset"
    private const val BRIGHTEN_MS = 400L
    private const val DIM_MS = 3_000L
    private const val RAMP_STEPS = 8
    private const val RAMP_STEP_MS = 40L
    /** Changes smaller than this (on the slider's 0..1 scale) are ignored, to avoid needless writes. */
    private const val MIN_CHANGE = 0.02f

    private val main = Handler(Looper.getMainLooper())
    private var service: Context? = null
    private var lux = -1f
    /** Slider position we last wrote; -1 = none yet since the screen came on. */
    private var shown = -1f
    private var screenOn = true
    private var listening = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(ctx: Context) = prefs(ctx).getBoolean(KEY_ON, false)

    /** False when it can't run: no "Modify system settings", or the Side Panel service is off. */
    fun setOn(ctx: Context, on: Boolean): Boolean {
        if (!Brightness.canChange(ctx) || (on && !LockService.isEnabled(ctx))) return false
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
        if (on) Brightness.setSystemAuto(ctx, false) // Android's own would fight ours
        shown = -1f
        update()
        return true
    }

    /** Called by [LockService] when it starts. */
    fun attach(ctx: Context) {
        service = ctx
        screenOn = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(screenReceiver, filter)
        update()
    }

    fun detach(ctx: Context) {
        stopListening()
        ctx.unregisterReceiver(screenReceiver)
        service = null
    }

    /**
     * The owner moved the slider while auto is on: remember how much brighter or dimmer than
     * the curve they like it, and apply it now.
     */
    fun setPreference(ctx: Context, position: Float) {
        if (lux < 0f) return
        val offset = (position - curve(lux)).coerceIn(-0.5f, 0.5f)
        prefs(ctx).edit().putFloat(KEY_OFFSET, offset).apply()
        main.removeCallbacks(apply)
        shown = position
        Brightness.setLevel(ctx, position)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            shown = -1f // screen on: set the level at once from the first reading, no fade
            update()
        }
    }

    private fun update() {
        val c = service ?: return
        val want = screenOn && isOn(c) && Brightness.canChange(c)
        if (want && !listening) {
            val sm = c.getSystemService(SensorManager::class.java)
            val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
            // An on-change sensor sends its current value right after registering.
            listening = sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL, main)
        } else if (!want && listening) {
            stopListening()
        }
    }

    private fun stopListening() {
        if (!listening) return
        service?.getSystemService(SensorManager::class.java)?.unregisterListener(this)
        listening = false
        main.removeCallbacks(apply)
        main.removeCallbacks(ramp)
    }

    override fun onSensorChanged(e: SensorEvent) {
        lux = e.values[0]
        val c = service ?: return
        val target = target(c)
        if (shown < 0f) return write(c, target)
        val diff = target - shown
        main.removeCallbacks(apply)
        if (abs(diff) < MIN_CHANGE) return
        main.postDelayed(apply, if (diff > 0) BRIGHTEN_MS else DIM_MS)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    /** The light stayed different long enough: fade to the new level. */
    private val apply = Runnable {
        val c = service ?: return@Runnable
        rampFrom = shown
        rampTo = target(c)
        rampStep = 0
        main.removeCallbacks(ramp)
        main.post(ramp)
    }

    private var rampFrom = 0f
    private var rampTo = 0f
    private var rampStep = 0

    private val ramp = object : Runnable {
        override fun run() {
            val c = service ?: return
            rampStep++
            write(c, rampFrom + (rampTo - rampFrom) * rampStep / RAMP_STEPS)
            if (rampStep < RAMP_STEPS) main.postDelayed(this, RAMP_STEP_MS)
        }
    }

    private fun write(c: Context, position: Float) {
        shown = position
        Brightness.setLevel(c, position)
    }

    private fun target(c: Context) = (curve(lux) + prefs(c).getFloat(KEY_OFFSET, 0f)).coerceIn(0.02f, 1f)

    /**
     * Lux → slider position (Android's perceptual scale): dark room ~0.15, office ~0.55,
     * bright indoor ~0.75, daylight ~0.95.
     */
    private fun curve(lux: Float) = (0.15f + 0.2f * log10(lux.coerceAtLeast(0f) + 1f)).coerceIn(0f, 1f)
}
