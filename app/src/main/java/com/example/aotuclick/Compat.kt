package com.example.autoclick

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager

/** Hàm tương thích để app chạy được từ Android 5.0 (API 21) trở lên. */
object Compat {
    /** Android 7.0+ mới có API cử chỉ của Trợ năng (dispatchGesture). */
    val canGesture: Boolean get() = Build.SDK_INT >= 24

    fun canDrawOverlays(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(ctx) else true

    fun overlayIntent(ctx: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 23) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        } else null

    @Suppress("DEPRECATION")
    val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    val immutableFlag: Int
        get() = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    fun startForegroundSvc(ctx: Context, i: Intent) {
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun batteryOk(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}
