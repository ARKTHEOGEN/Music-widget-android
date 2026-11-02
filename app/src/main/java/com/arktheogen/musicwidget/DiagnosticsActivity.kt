package com.arktheogen.musicwidget

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Shows the diagnostics report and runs handoff tests. Results stay until the screen is closed.
 * Every report and test line also goes to logcat: adb logcat -s MusicWidgetDiag
 */
class DiagnosticsActivity : Activity() {
    private companion object { const val TAG = "MusicWidgetDiag" }

    private lateinit var reportView: TextView
    private val testLog = StringBuilder()
    private var probe = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        reportView = findViewById(R.id.report)
        findViewById<View>(R.id.btn_refresh).setOnClickListener { refresh() }
        findViewById<View>(R.id.btn_copy).setOnClickListener { copy() }
        val rows = findViewById<LinearLayout>(R.id.test_rows)
        val installed = MusicApps.installed(this)
        installed.forEach { rows.addView(testRow(it)) }
        if (installed.any { it.pkg == "com.apple.android.music" } &&
            installed.any { it.pkg == "com.google.android.apps.youtube.music" }) rows.addView(relayRows())
    }

    override fun onResume() {
        super.onResume()
        refresh() // coming back from a music app: show its new state
        Relay.setUi { show(it) }
    }

    override fun onPause() {
        super.onPause()
        Relay.setUi(null) // the relay keeps logging to logcat
    }

    private fun refresh() {
        probe = Diagnostics.report(this)
        // One logcat entry per line: logcat cuts single entries at about 4 KB.
        probe.lineSequence().forEach { Log.i(TAG, "report: $it") }
        render()
    }

    private fun render() {
        reportView.text = probe + "\n== Handoff tests ==" + testLog.ifEmpty { "\n(none yet)" }
    }

    private fun log(line: String) {
        Log.i(TAG, "test: $line")
        show(line)
    }

    /** On screen only (the relay writes its own logcat lines). */
    private fun show(line: String) {
        testLog.append('\n').append(line)
        render()
    }

    private fun testRow(app: MusicApp) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = getString(R.string.diag_to_app, app.label)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(Button(context).apply {
            setText(R.string.diag_search)
            setOnClickListener { Diagnostics.runHandoff(this@DiagnosticsActivity, app, Diagnostics.Method.SEARCH) { log(it) } }
        })
        addView(Button(context).apply {
            setText(R.string.diag_link)
            setOnClickListener { Diagnostics.runHandoff(this@DiagnosticsActivity, app, Diagnostics.Method.LINK) { log(it) } }
        })
    }

    /** YouTube Music picks, Apple Music plays. Keeps running when this screen is closed. */
    private fun relayRows() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { setText(R.string.diag_relay) })
        addView(buttonRow(
            R.string.diag_start to { Relay.start(this@DiagnosticsActivity, fastTest = false) },
            R.string.diag_fast to { Relay.start(this@DiagnosticsActivity, fastTest = true) },
            R.string.diag_stop to { Relay.stop("stopped by you") },
        ))
        addView(buttonRow(
            R.string.cd_previous to { Relay.previous() },
            R.string.cd_next to { Relay.next() },
        ))
    }

    private fun buttonRow(vararg buttons: Pair<Int, () -> Unit>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { (label, action) ->
            addView(Button(context).apply {
                setText(label)
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun copy() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Music Widget diagnostics", reportView.text))
        Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
    }
}
