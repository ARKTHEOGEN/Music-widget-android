package com.arktheogen.musicwidget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Frame around the panel's album art. [touchFirst] sees every touch before the children;
 * otherwise the lyrics list (whose lines are tappable) would swallow swipes.
 * Returning true takes the gesture over, and the children get a cancel.
 */
class ArtFrame(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    var touchFirst: ((MotionEvent) -> Boolean)? = null
    private var takenOver = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) takenOver = false
        if (touchFirst?.invoke(ev) != true) return super.dispatchTouchEvent(ev)
        if (!takenOver) {
            takenOver = true
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        return true
    }
}
