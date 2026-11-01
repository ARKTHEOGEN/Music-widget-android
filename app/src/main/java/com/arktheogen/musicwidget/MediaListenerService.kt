package com.arktheogen.musicwidget

import android.content.ComponentName
import android.service.notification.NotificationListenerService

/**
 * Exists only so Android grants us media-session access and keeps our process
 * alive. We never read notification contents.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        SessionHub.ensureStarted(this)
        WidgetRenderer.renderAll(this, force = true)
    }

    override fun onListenerDisconnected() {
        SessionHub.stop()
        requestRebind(ComponentName(this, MediaListenerService::class.java))
    }
}
