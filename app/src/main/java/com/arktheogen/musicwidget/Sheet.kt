package com.arktheogen.musicwidget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import kotlin.math.abs
import kotlin.math.max

/**
 * Bottom sheet that follows a downward drag and closes past a quarter of its height
 * (like the Music Widget pop-up). Drags that start on the volume slider stay with the slider.
 */
class Sheet @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    var onDismiss: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var onSlider = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                onSlider = sliderAt(this, e.rawX.toInt(), e.rawY.toInt())
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - downY
                if (!onSlider && dy > slop && dy > abs(e.rawX - downX)) return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
            }
            MotionEvent.ACTION_MOVE -> translationY = max(0f, e.rawY - downY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (translationY > height / 4f) onDismiss?.invoke()
                else animate().translationY(0f).setDuration(180).start()
            }
        }
        return true
    }

    private fun sliderAt(v: View, x: Int, y: Int): Boolean {
        if (v is SeekBar) {
            val r = Rect()
            return v.getGlobalVisibleRect(r) && r.contains(x, y)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) if (sliderAt(v.getChildAt(i), x, y)) return true
        return false
    }
}
