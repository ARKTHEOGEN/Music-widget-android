package com.arktheogen.musicwidget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible screen that handles widget taps that may open an app (the relay badge, play
 * with nothing running). It is an Activity (not a broadcast) because only a foreground
 * screen may launch another app on modern Android.
 */
class SwitchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionHub.ensureStarted(this)
        when (intent.getStringExtra(EXTRA_OP)) {
            OP_RELAY -> Toast.makeText(applicationContext, Relay.toggle(this), Toast.LENGTH_SHORT).show()
            OP_OPEN -> SessionHub.openApp(this, intent.getStringExtra(EXTRA_PKG) ?: SessionHub.targetPackage())
        }
        finish()
    }

    companion object {
        private const val EXTRA_OP = "op"
        private const val EXTRA_PKG = "pkg"
        private const val OP_RELAY = "relay"
        private const val OP_OPEN = "open"

        private fun base(ctx: Context, op: String) = Intent(ctx, SwitchActivity::class.java)
            .putExtra(EXTRA_OP, op)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun relayIntent(ctx: Context) = base(ctx, OP_RELAY)
        fun openIntent(ctx: Context, pkg: String?) = base(ctx, OP_OPEN).putExtra(EXTRA_PKG, pkg)
    }
}
