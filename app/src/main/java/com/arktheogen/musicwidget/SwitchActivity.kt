package com.arktheogen.musicwidget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible screen that handles app-switch taps from the widget.
 * It is an Activity (not a broadcast) because only a foreground screen may
 * launch another app on modern Android.
 */
class SwitchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionHub.ensureStarted(this)
        when (intent.getStringExtra(EXTRA_OP)) {
            OP_SELECT -> intent.getStringExtra(EXTRA_PKG)?.let { pkg ->
                SessionHub.select(this, pkg)
                toast(pkg)
            }
            OP_CYCLE -> toast(SessionHub.cycleMode(this))
            OP_OPEN -> SessionHub.openApp(this, intent.getStringExtra(EXTRA_PKG) ?: SessionHub.targetPackage())
        }
        finish()
    }

    private fun toast(mode: String) {
        val text = if (mode == SessionHub.AUTO) getString(R.string.following_auto)
        else getString(R.string.following_app, MusicApps.byPkg(mode)?.label ?: mode)
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_OP = "op"
        private const val EXTRA_PKG = "pkg"
        private const val OP_SELECT = "select"
        private const val OP_CYCLE = "cycle"
        private const val OP_OPEN = "open"

        private fun base(ctx: Context, op: String) = Intent(ctx, SwitchActivity::class.java)
            .putExtra(EXTRA_OP, op)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun selectIntent(ctx: Context, pkg: String) = base(ctx, OP_SELECT).putExtra(EXTRA_PKG, pkg)
        fun cycleIntent(ctx: Context) = base(ctx, OP_CYCLE)
        fun openIntent(ctx: Context, pkg: String?) = base(ctx, OP_OPEN).putExtra(EXTRA_PKG, pkg)
    }
}
