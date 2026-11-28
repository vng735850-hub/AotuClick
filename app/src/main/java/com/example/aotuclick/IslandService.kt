package com.example.autoclick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.BatteryManager
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
            installCrashLogger(ctx)
            log = "đã gọi startForegroundService"
            try {
                val cn = ctx.startForegroundService(Intent(ctx, IslandService::class.java))
                log = "đã gọi startForegroundService, kết quả=" + (cn?.flattenToShortString() ?: "NULL (không tìm thấy dịch vụ)")
            } catch (e: Throwable) {
                log = "startForegroundService lỗi: $e"
            }
            // Nếu sau 2 giây dịch vụ vẫn chưa chạy thì tự vẽ island trực tiếp (đường vòng)
            Handler(Looper.getMainLooper()).postDelayed({
                if (instance == null && ClickStore.islandOn) startFallback(ctx)
            }, 2000L)
        }

        fun applyViewPrefs(v: DynamicIslandView) {
            val g = IslandPrefs.gestures
            v.applyGestures(
                g && IslandPrefs.swipeStopwatch,
                g && IslandPrefs.holdTimer,
                g && IslandPrefs.swipeShade,
                IslandPrefs.holdTiers(),
                g && IslandPrefs.swipeRotate,
                g && IslandPrefs.dblTapSingle,
                g && IslandPrefs.dblTapMulti
            )
            v.applyPrefs(IslandPrefs.alwaysShow, IslandPrefs.noticeSec * 1000L)
        }

        /** Gọi khi người dùng đổi cài đặt: áp dụng ngay kích thước, vị trí và các lựa chọn khác. */
        fun applySettings(ctx: Context) {
            IslandPrefs.init(ctx)
            val s = instance
            if (s != null) {
                s.applyLayout()
            } else if (fbView != null) {
                stopFallback()
                startFallback(ctx)
            }
            IslandBus.view?.let { applyViewPrefs(it) }
            IslandBus.refresh()
            IslandNotificationListener.instance?.pushMedia()
        }

        @Volatile private var fbView: DynamicIslandView? = null
        private var fbWm: WindowManager? = null
        private val fbHandler = Handler(Looper.getMainLooper())

        /** Đường vòng: gắn viên thuốc thẳng từ tiến trình app, không cần dịch vụ. */
        fun startFallback(ctx: Context) {
            val app = ctx.applicationContext
            if (fbView != null || instance != null) return
            IslandPrefs.init(app)
            IslandBus.appCtx = app
            if (!Settings.canDrawOverlays(app)) {
                log = "đường vòng: thiếu quyền overlay"
                return
            }
            try {
                val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val d = app.resources.displayMetrics.density
                val sc = IslandPrefs.scalePct / 100f
                val id = app.resources.getIdentifier("status_bar_height", "dimen", "android")
                val sb = if (id > 0) app.resources.getDimensionPixelSize(id) else (24 * d).toInt()
                val p = WindowManager.LayoutParams(
                    (240 * d * sc).toInt(), (48 * d * sc).toInt(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                p.y = sb + (2 * d).toInt() + (IslandPrefs.offsetDp * d).toInt()
                val v = DynamicIslandView(app)
                v.setSizeScale(sc)
                applyViewPrefs(v)
                v.onAction = { id2 -> IslandBus.onAction(app, id2) }
                v.onActiveChanged = { active ->
                    val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    p.flags = if (active) (p.flags and f.inv()) else (p.flags or f)
                    try { w.updateViewLayout(v, p) } catch (e: Throwable) { }
                }
                if (v.wantsTouch()) {
                    p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                }
                val shrink = Runnable {
                    p.width = (240 * d * sc).toInt()
                    p.height = (48 * d * sc).toInt()
                    try { w.updateViewLayout(v, p) } catch (e: Throwable) { }
                }
                v.onExpandChanged = { ex ->
                    fbHandler.removeCallbacks(shrink)
                    if (ex) {
                        p.width = (320 * d * sc).toInt()
                        p.height = (186 * d * sc).toInt()
                        try { w.updateViewLayout(v, p) } catch (e: Throwable) { }
                    } else {
                        fbHandler.postDelayed(shrink, 800L)
                    }
                }
                w.addView(v, p)
                fbWm = w
                fbView = v
                IslandBus.view = v
                IslandNotificationListener.instance?.pushMedia()
                IslandBus.refresh()
                log = "đường vòng: addView OK (dịch vụ không khởi động được)"
            } catch (e: Throwable) {
                log = "đường vòng lỗi: $e"
            }
        }

        fun stopFallback() {
            val v = fbView ?: return
            try { fbWm?.removeView(v) } catch (e: Throwable) { }
            fbView = null
            if (IslandBus.view === v) IslandBus.view = null
        }

        private const val DIAG_PREF = "island_diag"
        @Volatile private var appCtx: Context? = null

        /** Ghi lỗi crash vào bộ nhớ để xem lại sau khi tiến trình khởi động lại. */
        fun installCrashLogger(ctx: Context) {
            appCtx = ctx.applicationContext
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            if (prev is IslandCrashLogger) return
            Thread.setDefaultUncaughtExceptionHandler(IslandCrashLogger(ctx.applicationContext, prev))
        }

        fun stop(ctx: Context) {
            stopFallback()
            ctx.stopService(Intent(ctx, IslandService::class.java))
        }

        @Volatile var log: String = "chưa có lần chạy nào"
            set(v) {
                field = v
                try {
                    appCtx?.getSharedPreferences(DIAG_PREF, Context.MODE_PRIVATE)?.edit()?.putString("log", v)?.commit()
                } catch (e: Throwable) {
                }
            }

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
            val declared = try {
                ctx.packageManager.getServiceInfo(android.content.ComponentName(ctx, IslandService::class.java), 0)
                "có"
            } catch (e: Throwable) {
                "KHÔNG ($e)"
            }
            sb.append("Dịch vụ khai báo trong Manifest: ").append(declared).append("\n")
            sb.append("Đường vòng đang hiện: ").append(fbView != null).append("\n")
            sb.append("Chế độ: ").append(svc?.modeName ?: "-").append("\n")
            val pf = ctx.applicationContext.getSharedPreferences(DIAG_PREF, Context.MODE_PRIVATE)
            sb.append("Nhật ký: ").append(pf.getString("log", "(trống)")).append("\n")
            sb.append("Lỗi crash gần nhất: ").append(pf.getString("crash", "không có"))
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
    private fun scale(): Float = IslandPrefs.scalePct / 100f
    private fun sdp(v: Int): Int = (v * resources.displayMetrics.density * scale()).toInt()
    private fun yExtra(): Int = dp(IslandPrefs.offsetDp)
    private var isTall = false

    /** true khi island chạy bằng lớp phủ Trợ năng (được vẽ đè lên cả thanh trạng thái). */
    private var a11yMode = false

    private fun flush(): Boolean = IslandPrefs.flushTop && a11yMode

    /** Vị trí dọc: sát mép trên (lớp phủ Trợ năng) hoặc ngay dưới thanh trạng thái (lớp phủ thường). */
    private fun yPos(): Int = if (flush()) yExtra() else topOffset() + yExtra()

    /** Thu gọn: cửa sổ chỉ rộng hơn viên thuốc một chút để ít chắn thanh trạng thái bên dưới. */
    private fun collapsedW(): Int = sdp(240)
    private fun expandedW(): Int = sdp(320)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        IslandPrefs.init(this)
        IslandBus.appCtx = applicationContext
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
            collapsedW(), sdp(48), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = yPos()
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
            v.onAction = { id -> IslandBus.onAction(applicationContext, id) }
            v.onActiveChanged = { active -> setTouchable(active) }
            a11yMode = (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            v.setSizeScale(scale())
            v.setFlushTop(flush())
            applyViewPrefs(v)
            val p = buildParams(type)
            w.addView(v, p)
            island = v
            lp = p
            setTouchable(v.wantsTouch())
            IslandBus.view = v
            IslandNotificationListener.instance?.pushMedia()
            IslandBus.refresh()
            true
        } catch (e: Throwable) {
            Log.e("IslandService", "addView failed type=$type", e)
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            log = "addView lỗi (type=$type): $lastError"
            false
        }
    }

    private var lastError = ""

    /** Áp dụng lại kích thước và vị trí khi người dùng đổi cài đặt. */
    fun applyLayout() {
        val v = island ?: return
        val p = lp ?: return
        v.setSizeScale(scale())
        v.setFlushTop(flush())
        p.width = if (isTall) expandedW() else collapsedW()
        p.height = if (isTall) sdp(186) else sdp(48)
        p.y = yPos()
        try {
            wm.updateViewLayout(v, p)
        } catch (e: Throwable) {
        }
    }

    /** Bật nhận chạm khi viên thuốc có nội dung, tắt khi rảnh để không chặn thanh trạng thái bên dưới. */
    private fun setTouchable(on: Boolean) {
        val v = island ?: return
        val p = lp ?: return
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.flags = if (on) (p.flags and f.inv()) else (p.flags or f)
        try {
            wm.updateViewLayout(v, p)
        } catch (e: Throwable) {
        }
    }

    private fun batteryPct(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (l >= 0 && sc > 0) l * 100 / sc else 0
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val v = island ?: return
            if (!IslandPrefs.showBattery) return
            val pct = batteryPct()
            when (i?.action) {
                Intent.ACTION_POWER_CONNECTED -> v.showNotice("Pin", "Đang sạc", "$pct%")
                Intent.ACTION_POWER_DISCONNECTED -> v.showNotice("Pin", "Đã rút sạc", "$pct%")
                Intent.ACTION_BATTERY_LOW -> v.showNotice("Pin", "Pin yếu", "$pct%")
            }
        }
    }
    private var powerRegistered = false

    private var wasFull = false
    private var batRegistered = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i == null) return
            val v = island ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && sc > 0) level * 100 / sc else -1
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val full = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL
            val on = IslandPrefs.showBattery
            v.setBattery(pct, plugged && on, full && plugged)
            if (on && full && plugged && !wasFull) v.showNotice("Pin", "Pin đã đầy", "100%")
            wasFull = full && plugged
        }
    }

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
        try {
            val f = IntentFilter()
            f.addAction(Intent.ACTION_POWER_CONNECTED)
            f.addAction(Intent.ACTION_POWER_DISCONNECTED)
            f.addAction(Intent.ACTION_BATTERY_LOW)
            registerReceiver(powerReceiver, f)
            powerRegistered = true
        } catch (e: Throwable) {
        }
        try {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batRegistered = true
        } catch (e: Throwable) {
        }
        log = "addView OK ($mode)"
        Toast.makeText(this, "Dynamic Island đã bật ($mode)", Toast.LENGTH_SHORT).show()
        island?.showNotice("Nvp Klick", "Dynamic Island đã bật", "Sẵn sàng hiện cuộc gọi, nhạc, thông báo")
    }

    /** Mở rộng: cao ngay. Thu gọn: chờ hiệu ứng nảy xong rồi mới thu cửa sổ lại. */
    private fun setTall(tall: Boolean) {
        handler.removeCallbacks(shrink)
        if (tall) applyHeight(true) else handler.postDelayed(shrink, 800L)
    }

    private fun applyHeight(tall: Boolean) {
        val v = island ?: return
        val p = lp ?: return
        isTall = tall
        p.height = if (tall) sdp(186) else sdp(48)
        p.width = if (tall) expandedW() else collapsedW()
        try {
            wm.updateViewLayout(v, p)
        } catch (e: Exception) {
        }
    }

    /**
     * Gắn lại viên thuốc khi dịch vụ Trợ năng vừa bật hoặc vừa tắt: ưu tiên lớp phủ Trợ năng
     * (nằm sát mép trên, đè lên thanh trạng thái), không có thì dùng lớp phủ thường.
     */
    fun reattach() {
        val old = island ?: return
        try { wm.removeView(old) } catch (e: Throwable) { }
        if (IslandBus.view === old) IslandBus.view = null
        island = null
        lp = null
        isTall = false
        handler.removeCallbacks(shrink)
        val a11y = AutoClickService.instance
        var mode = ""
        if (a11y != null && tryAdd(a11y, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)) {
            mode = "Trợ năng"
        } else if (Settings.canDrawOverlays(this) &&
            tryAdd(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        ) {
            mode = "lớp phủ"
        }
        modeName = mode
        log = if (mode.isEmpty()) "gắn lại island thất bại: $lastError" else "gắn lại island OK ($mode)"
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = island ?: return
        val p = lp ?: return
        p.y = yPos()
        try {
            wm.updateViewLayout(v, p)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        if (powerRegistered) {
            try { unregisterReceiver(powerReceiver) } catch (e: Throwable) { }
            powerRegistered = false
        }
        if (batRegistered) {
            try { unregisterReceiver(batteryReceiver) } catch (e: Throwable) { }
            batRegistered = false
        }
        if (IslandBus.view === island) IslandBus.view = null
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

/** Bắt mọi lỗi làm sập tiến trình, lưu lại stack trace rồi chuyển tiếp cho trình xử lý gốc. */
private class IslandCrashLogger(
    private val app: Context,
    private val prev: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            app.getSharedPreferences("island_diag", Context.MODE_PRIVATE).edit()
                .putString("crash", sw.toString().take(1200)).commit()
        } catch (x: Throwable) {
        }
        prev?.uncaughtException(t, e)
    }
}
