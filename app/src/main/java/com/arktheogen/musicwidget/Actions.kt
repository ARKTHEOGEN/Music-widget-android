package com.arktheogen.musicwidget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings

/** Actions shared by the widget and the pop-up. */
object Actions {
    /** The Pixel's system QR scanner (the one behind the Quick Settings tile), as found on the owner's phone. */
    private val SYSTEM_QR = ComponentName("com.google.android.gms", "com.google.android.gms.mlkit.barcode.ui.PlatformBarcodeScanningActivityProxy")

    /** Android's own volume panel (the "volume tray"), without changing the volume. */
    fun showVolumePanel(ctx: Context) {
        ctx.getSystemService(AudioManager::class.java)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    }

    /** System QR scanner; Google Lens if that isn't there. */
    fun openQrScanner(activity: Activity) {
        val component = qrComponent(activity)
        val tries = listOfNotNull(
            Intent().setComponent(component),
            activity.packageManager.getLaunchIntentForPackage("com.google.ar.lens"),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("googleapp://lens")),
        )
        for (intent in tries) {
            try {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        android.widget.Toast.makeText(activity, R.string.no_qr, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** The scanner the system is set to use (it can change with Play services updates). */
    private fun qrComponent(ctx: Context): ComponentName = try {
        Settings.Secure.getString(ctx.contentResolver, "show_qr_code_scanner_setting")
            ?.let { ComponentName.unflattenFromString(it) } ?: SYSTEM_QR
    } catch (e: SecurityException) {
        SYSTEM_QR // hidden setting not readable for this app
    }

    /** The back camera with a flash, or null. */
    fun torchCamera(ctx: Context): String? {
        val cm = ctx.getSystemService(CameraManager::class.java)
        return cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }
}
