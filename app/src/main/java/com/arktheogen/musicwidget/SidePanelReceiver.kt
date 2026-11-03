package com.arktheogen.musicwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Widget taps that need no screen: lock, auto brightness, the tap zone. */
class SidePanelReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            TAP -> if (TapZoneWidget.isDoubleTap() && !LockService.lock()) {
                Toast.makeText(ctx, R.string.lock_off, Toast.LENGTH_LONG).show()
            }
            LOCK -> if (!LockService.lock()) Toast.makeText(ctx, R.string.lock_off, Toast.LENGTH_LONG).show()
            BRIGHTNESS -> {
                if (!AutoBrightness.setOn(ctx, !AutoBrightness.isOn(ctx))) {
                    Toast.makeText(ctx, R.string.lock_off, Toast.LENGTH_LONG).show() // needs the service running
                }
                SidePanelWidget.renderAll(ctx)
            }
        }
    }

    companion object {
        const val LOCK = "com.arktheogen.musicwidget.LOCK"
        const val BRIGHTNESS = "com.arktheogen.musicwidget.BRIGHTNESS"
        const val TAP = "com.arktheogen.musicwidget.TAP"
    }
}
