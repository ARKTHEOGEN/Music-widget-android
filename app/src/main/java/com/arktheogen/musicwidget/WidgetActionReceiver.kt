package com.arktheogen.musicwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives button taps from the widget and forwards them to the music app. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        SessionHub.ensureStarted(ctx)
        when (intent.action) {
            PLAY_PAUSE -> SessionHub.playPause()
            NEXT -> SessionHub.next()
            PREVIOUS -> SessionHub.previous()
            SHUFFLE -> SessionHub.toggleShuffle()
            REPEAT -> SessionHub.cycleRepeat()
        }
    }

    companion object {
        const val PLAY_PAUSE = "com.arktheogen.musicwidget.PLAY_PAUSE"
        const val NEXT = "com.arktheogen.musicwidget.NEXT"
        const val PREVIOUS = "com.arktheogen.musicwidget.PREVIOUS"
        const val SHUFFLE = "com.arktheogen.musicwidget.SHUFFLE"
        const val REPEAT = "com.arktheogen.musicwidget.REPEAT"
    }
}
