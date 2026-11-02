package com.arktheogen.musicwidget

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Shows the diagnostics report and runs handoff tests. Results stay until the screen is closed. */
class DiagnosticsActivity : Activity() {
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
        MusicApps.ALL.forEach { rows.addView(testRow(it)) }
    }

    override fun onResume() {
        super.onResume()
        refresh() // coming back from a music app: show its new state
    }

    private fun refresh() {
        probe = Diagnostics.report(this)
        render()
    }

    private fun render() {
        reportView.text = probe + "\n== Handoff tests ==" + testLog.ifEmpty { "\n(none yet)" }
    }

    private fun log(line: String) {
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

    private fun copy() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Music Widget diagnostics", reportView.text))
        Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
    }
}
