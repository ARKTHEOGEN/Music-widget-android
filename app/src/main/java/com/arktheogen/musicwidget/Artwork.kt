package com.arktheogen.musicwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

/** Album-art colours ("Material You" from the cover). Computed once per cover. */
object Artwork {
    data class Look(
        /** Small copy for the widget (keeps the widget update small). */
        val widgetBitmap: Bitmap?,
        val background: Int,
        val accent: Int,
        val onAccent: Int,
    )

    private const val WIDGET_ART_PX = 256
    private var lastSource: Bitmap? = null
    private var lastLook: Look? = null

    fun lookFor(ctx: Context, art: Bitmap?): Look {
        lastLook?.let { if (art === lastSource) return it }
        val small = art?.let { scale(it) }
        val palette = small?.let { Palette.from(it).maximumColorCount(16).generate() }

        val base = palette?.let { it.darkVibrantSwatch ?: it.darkMutedSwatch ?: it.dominantSwatch }?.rgb
            ?: ctx.getColor(android.R.color.system_accent1_800)
        // Darken until white text is readable on it.
        var bg = ColorUtils.blendARGB(base, Color.BLACK, 0.3f)
        repeat(4) { if (ColorUtils.calculateLuminance(bg) > 0.12) bg = ColorUtils.blendARGB(bg, Color.BLACK, 0.25f) }

        val accent = palette?.let { it.lightVibrantSwatch ?: it.vibrantSwatch ?: it.lightMutedSwatch }?.rgb
            ?: ctx.getColor(android.R.color.system_accent1_200)
        val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.4) bg else Color.WHITE

        return Look(small, bg, accent, onAccent).also {
            lastSource = art
            lastLook = it
        }
    }

    private fun scale(src: Bitmap): Bitmap {
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val longest = maxOf(soft.width, soft.height)
        if (longest <= WIDGET_ART_PX) return soft
        val f = WIDGET_ART_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(soft, (soft.width * f).toInt().coerceAtLeast(1), (soft.height * f).toInt().coerceAtLeast(1), true)
    }
}
