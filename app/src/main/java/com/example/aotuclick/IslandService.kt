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

        @Volatile var log: String = "chưa có lần chạy nào"

        fun report(ctx: Context): String {
            val svc = instance
            val v = svc?.island
            val sb = StringBuilder()
            sb.append("Máy: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            sb.append("Quyền hiển thị trên app khác: ").append(Settings.canDrawOverlays(ctx)).append("\n")
            sb.append("Dịch vụ Trợ năng đang chạy: ").append(AutoClickService.instance != null).append("\n")
            sb.append("islandOn (đã lưu): ").append(ClickStore.islandOn).append("\n")
            sb.append("IslandService đang chạy: ").append(svc != null).append("\n")
            sb.append("View đã gắn vào cửa sổ: ").append(v?.isAttachedToWindow).append("\n")
            sb.append("Kích thước view: ").append(v?.width).append("x").append(v?.height).append("\n")
            sb.append("Cửa sổ hiển thị: ").append(v?.windowVisibility == View.VISIBLE).append("\n")
            sb.append("Chế độ: ").append(svc?.modeName ?: "-").append("\n")
            sb.append("Nhật ký: ").append(log)
            return sb.toString()
        }
    }

    private var modeName = ""

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
        log = "onCreate"
        ClickStore.ensureLoaded(this)
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!Settings.canDrawOverlays(this) && AutoClickService.instance == null) {
            log = "dừng: thiếu quyền overlay và chưa bật Trợ năng"
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
            log = "dừng: islandOn=false khi onStartCommand"
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

    private fun buildParams(type: Int): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            dp(320), dp(48), type,
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
        return p
    }

    private fun tryAdd(ctx: Context, type: Int): Boolean {
        return try {
            val w = ctx.getSystemService(WINDOW_SERVICE) as WindowManager
            wm = w
            val v = DynamicIslandView(ctx)
            v.visibility = View.VISIBLE
            v.onExpandChanged = { expanded -> setTall(expanded) }
            val p = buildParams(type)
            w.addView(v, p)
            island = v
            lp = p
            true
        } catch (e: Throwable) {
            Log.e("IslandService", "addView failed type=$type", e)
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            log = "addView lỗi (type=$type): $lastError"
            false
        }
    }

    private var lastError = ""

    private fun addIsland() {
        // Ưu tiên lớp phủ Trợ năng (không bị MIUI/ColorOS... chặn), thiếu thì dùng lớp phủ thường
        val a11y = AutoClickService.instance
        var mode = ""
        if (a11y != null && tryAdd(a11y, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)) {
            mode = "Trợ năng"
        } else if (Settings.canDrawOverlays(this) &&
            tryAdd(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        ) {
            mode = "lớp phủ"
        }
        if (mode.isEmpty()) {
            Toast.makeText(
                this,
                "Không hiện được Dynamic Island. " + (if (lastError.isNotEmpty()) lastError else "Hãy bật dịch vụ Trợ năng của Nvp Klick."),
                Toast.LENGTH_LONG
            ).show()
            ClickStore.islandOn = false
            ClickStore.save(this)
            stopSelf()
            return
        }
        modeName = mode
        log = "addView OK ($mode)"
        Toast.makeText(this, "Dynamic Island đã bật ($mode)", Toast.LENGTH_SHORT).show()
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
        log = log + " | onDestroy"
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
