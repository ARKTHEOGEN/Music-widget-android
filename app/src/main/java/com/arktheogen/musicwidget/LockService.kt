package com.arktheogen.musicwidget

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service behind Side Panel:
 *  - the floating accessibility button opens the pop-up ([QuickPanelActivity]);
 *  - performs Android's own actions for the pop-up and widget: lock (like the power button, so
 *    fingerprint/face unlock still work), screenshot, home, back, recents, notifications, ...;
 *  - runs Side Panel's own auto brightness ([AutoBrightness]);
 *  - watches system settings (where Android records volume) so open screens stay in step.
 * It reads nothing on screen.
 */
class LockService : AccessibilityService() {

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = SidePanelWidget.renderAll(this@LockService)
    }

    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) = QuickPanelActivity.open(this@LockService)
    }

    override fun onServiceConnected() {
        instance = this
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback, main)
        AutoBrightness.attach(this)
        SidePanelWidget.renderAll(this, force = true) // lock button now locks instead of opening setup
    }

    override fun onDestroy() {
        AutoBrightness.detach(this)
        accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
        contentResolver.unregisterContentObserver(observer)
        instance = null
        SidePanelWidget.renderAll(this, force = true)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private var instance: LockService? = null

        /** False when the service isn't turned on (or Android refused). */
        fun lock(): Boolean = perform(GLOBAL_ACTION_LOCK_SCREEN)

        /**
         * Runs one of Android's GLOBAL_ACTION_s. [delayMs] lets the pop-up close first, so a
         * screenshot doesn't capture it and Home/Back act on the app underneath.
         */
        fun perform(action: Int, delayMs: Long = 0): Boolean {
            val s = instance ?: return false
            if (delayMs == 0L) return s.performGlobalAction(action)
            main.postDelayed({ instance?.performGlobalAction(action) }, delayMs)
            return true
        }

        /** Whether the owner has turned the service on in Accessibility settings. */
        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val me = ComponentName(ctx, LockService::class.java)
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            // Android may store the short form ("pkg/.LockService"); unflatten handles both.
            return on.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
