package com.arktheogen.musicwidget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Screen brightness: Android's own adaptive mode and the manual level ([AutoBrightness] drives
 * the level when Side Panel's auto is on).
 * Changing them needs "Modify system settings", which the owner grants once in Settings.
 */
object Brightness {
    private const val MAX = 255

    fun canChange(ctx: Context) = Settings.System.canWrite(ctx)

    /** The Settings page where the owner allows "Modify system settings" for Side Panel. */
    fun grantIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android's own adaptive brightness (Settings → Display), which ignores the manual level. */
    fun isSystemAuto(ctx: Context) = Settings.System.getInt(
        ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
    ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    fun setSystemAuto(ctx: Context, on: Boolean): Boolean {
        if (!canChange(ctx)) return false
        val mode = if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        return Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
    }

    /**
     * Slider position 0..1, on the same curve as Android's own brightness slider (eyes see
     * brightness on a curve, so a straight 0..255 scale feels bright almost all the way).
     * Ignored by Android while its own adaptive brightness is on.
     */
    fun level(ctx: Context): Float {
        val raw = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, MAX / 2)
        return linearToGamma((raw - 1f) / (MAX - 1))
    }

    fun setLevel(ctx: Context, position: Float): Boolean {
        if (!canChange(ctx)) return false
        val raw = 1 + (gammaToLinear(position) * (MAX - 1)).toInt()
        return Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw.coerceIn(1, MAX))
    }

    // Android's slider curve (BrightnessUtils in the Android source).
    private const val R = 0.5f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f

    private fun gammaToLinear(v: Float): Float {
        val x = if (v <= R) (v / R) * (v / R) else exp((v - C) / A) + B
        return (x / 12f).coerceIn(0f, 1f)
    }

    private fun linearToGamma(v: Float): Float {
        val n = v.coerceIn(0f, 1f) * 12f
        return (if (n <= 1f) sqrt(n) * R else A * ln(n - B) + C).coerceIn(0f, 1f)
    }
}
