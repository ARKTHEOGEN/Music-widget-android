package com.arktheogen.musicwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/** Setup: turn on the lock service, add the widget. */
class SidePanelSetupActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var brightnessStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_side_setup)
        status = findViewById(R.id.lock_status)
        findViewById<Button>(R.id.btn_enable).setOnClickListener { openAccessibility() }
        findViewById<Button>(R.id.btn_app_info).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        brightnessStatus = findViewById(R.id.brightness_status)
        findViewById<Button>(R.id.btn_brightness).setOnClickListener { startActivity(Brightness.grantIntent(this)) }
        findViewById<Button>(R.id.btn_add).setOnClickListener { addWidget() }
    }

    override fun onResume() {
        super.onResume()
        val on = LockService.isEnabled(this)
        status.setText(if (on) R.string.lock_ready else R.string.lock_not_ready)
        brightnessStatus.setText(if (Brightness.canChange(this)) R.string.brightness_ready else R.string.brightness_not_ready)
        SidePanelWidget.renderAll(this, force = true)
    }

    /**
     * Android's Accessibility list; the owner taps "Side Panel lock button" there.
     * (The page for one service needs a permission only system apps hold.)
     */
    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, R.string.find_service, Toast.LENGTH_LONG).show()
    }

    private fun addWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(ComponentName(this, SidePanelWidget::class.java), null, null)
        else Toast.makeText(this, R.string.pin_not_supported, Toast.LENGTH_LONG).show()
    }
}
