package com.arktheogen.musicwidget

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class MusicApp(val pkg: String, val label: String)

/** The apps the widget follows. Add a package here to support another player. */
object MusicApps {
    val ALL = listOf(
        MusicApp("com.apple.android.music", "Apple Music"),
        MusicApp("com.spotify.music", "Spotify"),
        MusicApp("com.google.android.apps.youtube.music", "YouTube Music"),
    )

    fun byPkg(pkg: String?): MusicApp? = ALL.firstOrNull { it.pkg == pkg }

    fun isSupported(pkg: String?): Boolean = byPkg(pkg) != null

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun installed(ctx: Context): List<MusicApp> = ALL.filter { isInstalled(ctx, it.pkg) }

    fun icon(ctx: Context, pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
