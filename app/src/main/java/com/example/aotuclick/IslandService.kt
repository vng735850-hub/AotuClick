package com.example.autoclick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast

/**
 * Dynamic Island nổi trên mọi app (lớp phủ toàn hệ thống), đặt ngay dưới thanh trạng thái.
 * Dùng TYPE_APPLICATION_OVERLAY nên chạy được từ Android 8.0 (API 26), cần quyền "Hiển thị trên ứng dụng khác".
 * Cửa sổ chỉ cao bằng viên thuốc khi thu gọn, cao hơn khi mở rộng để không chặn thao tác của app bên dưới.
 */
class IslandService : Service() {

    companion object {
        @Volatile var instance: IslandService? = null
        private const val CHANNEL_ID = "nvpklick_island"
        private const val NOTI_ID = 3
        private const val ACTION_QUIT = "com.example.autoclick.ISLAND_QUIT"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, IslandService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, IslandService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private var island: DynamicIslandView? = null
    private var lp: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val shrink = Runnable { applyHeight(false) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ClickStore.ensureLoaded(this)
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!Settings.canDrawOverlays(this)) {
            ClickStore.islandOn = false
            ClickStore.save(this)
            Toast.makeText(this, "Chưa có quyền hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        addIsland()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            ClickStore.islandOn = false
            ClickStore.save(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ClickStore.islandOn) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nvp Klick - Dynamic Island", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val quit = PendingIntent.getService(
            this, 11, Intent(this, IslandService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val noti = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Nvp Klick")
            .setContentText("Dynamic Island đang bật")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Tắt", quit
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, noti)
        }
    }

    /** Chiều cao vùng trên cùng cần tránh (thanh trạng thái / tai thỏ), tính từ mép trên màn hình. */
    private fun topOffset(): Int {
        var top = 0
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                top = wm.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                    ).top
            } catch (e: Exception) {
                top = 0
            }
        }
        if (top <= 0) {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            top = if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
        }
        return top + dp(2)
    }

    private fun addIsland() {
        val v = DynamicIslandView(this)
        v.visibility = View.VISIBLE
        v.onExpandChanged = { expanded -> setTall(expanded) }
        val p = WindowManager.LayoutParams(
            dp(320), dp(48),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = topOffset()
        if (Build.VERSION.SDK_INT >= 30) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        island = v
        lp = p
        try {
            wm.addView(v, p)
        } catch (e: Throwable) {
            Log.e("IslandService", "addView failed", e)
            island = null
            Toast.makeText(this, "Không hiện được Dynamic Island: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            ClickStore.islandOn = false
            ClickStore.save(this)
            stopSelf()
            return
        }
        // Kiểm tra sau 1 giây: nếu hệ thống (MIUI/ColorOS...) chặn thì báo cho người dùng
        handler.postDelayed({
            val iv = island
            if (iv != null && !iv.isAttachedToWindow) {
                Toast.makeText(
                    this,
                    "Máy đang chặn lớp phủ. Vào Cài đặt > Ứng dụng > Nvp Klick, bật \"Hiển thị cửa sổ bật lên khi chạy nền\" và cho chạy nền.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, 1000L)
    }

    /** Mở rộng: cao ngay. Thu gọn: chờ hiệu ứng nảy xong rồi mới thu cửa sổ lại. */
    private fun setTall(tall: Boolean) {
        handler.removeCallbacks(shrink)
        if (tall) applyHeight(true) else handler.postDelayed(shrink, 800L)
    }

    private fun applyHeight(tall: Boolean) {
        val v = island ?: return
        val p = lp ?: return
        p.height = if (tall) dp(186) else dp(48)
        try {
            wm.updateViewLayout(v, p)
        } catch (e: Exception) {
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = island ?: return
        val p = lp ?: return
        p.y = topOffset()
        try {
            wm.updateViewLayout(v, p)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(shrink)
        island?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        island = null
        instance = null
        super.onDestroy()
    }
}
