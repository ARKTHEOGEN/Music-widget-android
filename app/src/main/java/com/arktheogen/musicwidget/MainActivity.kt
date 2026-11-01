package com.arktheogen.musicwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast

/** Setup screen: permissions, phone-maker tips, and a shortcut to add the widget. */
class MainActivity : Activity() {
    private lateinit var accessStatus: TextView
    private lateinit var appsStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        accessStatus = findViewById(R.id.access_status)
        appsStatus = findViewById(R.id.apps_status)

        findViewById<View>(R.id.btn_access).setOnClickListener { openListenerSettings() }
        findViewById<View>(R.id.btn_app_info).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        findViewById<View>(R.id.btn_add_widget).setOnClickListener { pinWidget() }
        findViewById<View>(R.id.btn_open_player).setOnClickListener {
            startActivity(Intent(this, PlayerPanelActivity::class.java))
        }
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        if (listOf("xiaomi", "redmi", "poco").any { it in brand }) {
            findViewById<View>(R.id.xiaomi_section).visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = SessionHub.hasAccess(this)
        accessStatus.setText(if (granted) R.string.access_granted else R.string.access_missing)
        appsStatus.text = MusicApps.ALL.joinToString("\n") { app ->
            getString(if (MusicApps.isInstalled(this, app.pkg)) R.string.app_installed else R.string.app_missing, app.label)
        }
        SessionHub.ensureStarted(this)
        WidgetRenderer.renderAll(this, force = true)
    }

    private fun openListenerSettings() {
        val component = ComponentName(this, MediaListenerService::class.java)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        try {
            startActivity(detail)
        } catch (e: ActivityNotFoundException) {
            // Some phone makers (e.g. Xiaomi) don't ship the per-app screen.
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun pinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, PlayerWidgetProvider::class.java), null, null)
        } else {
            Toast.makeText(this, R.string.pin_not_supported, Toast.LENGTH_LONG).show()
        }
    }
}
