package com.example.autoclick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

class FloatingService : Service() {

    companion object {
        @Volatile var instance: FloatingService? = null
        private const val CHANNEL_ID = "nvpklick_running"
        private const val NOTI_ID = 1
        private const val ACTION_STOP_RUN = "com.example.autoclick.STOP_RUN"
        private const val ACTION_SHOW = "com.example.autoclick.SHOW_PANEL"
        private const val ACTION_QUIT = "com.example.autoclick.QUIT"
        // 999 giờ 59 phút 59 giây
        private const val MAX_SECONDS = 3_599_999L
    }

    /** Một nút trên màn hình: chấm bắt đầu (+ chấm kết thúc nếu là Kéo). */
    private class Item(
        val action: ClickAction,
        val view: TextView,
        val lp: WindowManager.LayoutParams,
        val endView: TextView?,
        val endLp: WindowManager.LayoutParams?
    )

    private lateinit var wm: WindowManager
    private var panelView: LinearLayout? = null
    private lateinit var panelLp: WindowManager.LayoutParams
    private lateinit var playBtn: IconView
    private lateinit var timerText: TextView
    private lateinit var bubbleView: IconView
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private val items = mutableListOf<Item>()

    private var running = false
    private var destroyed = false
    private var minimized = false

    // Bộ đếm thời gian chạy
    private val handler = Handler(Looper.getMainLooper())
    private var startTime = 0L
    private var elapsedMs = 0L
    private val ticker = object : Runnable {
        override fun run() {
            elapsedMs = SystemClock.elapsedRealtime() - startTime
            showTimer()
            if (running && !destroyed) handler.postDelayed(this, 200L)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val markerSize get() = dp(52)
    private val endSize get() = dp(40)

    private val colGreen = Color.rgb(46, 160, 67)
    private val colRed = Color.rgb(211, 47, 47)

    // Các thành phần của bảng nổi cần đổi màu khi đổi chế độ sáng/tối
    private var gripView: IconView? = null
    private val toolBtns = mutableListOf<IconView>()
    private var panelBgDrawable: GradientDrawable? = null
    private var timerBgDrawable: GradientDrawable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        ClickStore.ensureLoaded(this)
        when (intent?.action) {
            ACTION_STOP_RUN -> AutoClickService.instance?.stop()
            ACTION_SHOW -> expand()
            ACTION_QUIT -> stopSelf()
            else -> {
                if (panelView == null) {
                    setup()
                } else {
                    refresh()
                    applyAlpha()
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Đổi màu bảng nổi theo chế độ sáng/tối hiện tại của app. */
    fun applyTheme() {
        if (destroyed || panelView == null) return
        panelBgDrawable?.let {
            it.setColor(AppTheme.panelBg)
            it.setStroke(dp(1), AppTheme.panelStroke)
        }
        timerBgDrawable?.setColor(AppTheme.field)
        timerText.setTextColor(AppTheme.text)
        gripView?.let {
            it.tint = AppTheme.gripTint
            it.invalidate()
        }
        toolBtns.forEach { it.background = ovalBg(AppTheme.panelBtn) }
        panelView?.invalidate()
    }

    /** Cập nhật độ đậm của bảng nổi theo cài đặt. */
    fun applyAlpha() {
        panelView?.alpha = ClickStore.panelAlpha / 100f
    }

    // ---------- Foreground notification ----------

    private fun svcIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, FloatingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun notiAction(label: String, iconRes: Int, action: String, code: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, iconRes), label, svcIntent(action, code)
        ).build()

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nvp Klick", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val noti = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Nvp Klick")
            .setContentText("Bảng điều khiển nổi đang bật")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(notiAction("Dừng", android.R.drawable.ic_media_pause, ACTION_STOP_RUN, 1))
            .addAction(notiAction("Mở bảng", android.R.drawable.ic_menu_view, ACTION_SHOW, 2))
            .addAction(notiAction("Tắt", android.R.drawable.ic_menu_close_clear_cancel, ACTION_QUIT, 3))
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, noti)
        }
    }

    // ---------- Cửa sổ nổi ----------

    private fun overlayParams(w: Int, h: Int, px: Int, py: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = px
        lp.y = py
        return lp
    }

    private fun ovalBg(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        return d
    }

    private fun circleButton(kind: IconKind, bg: Int, sizeDp: Int, onClick: () -> Unit): IconView {
        val v = IconView(this, kind)
        v.background = ovalBg(bg)
        val lp = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        lp.setMargins(0, dp(3), 0, dp(3))
        v.layoutParams = lp
        v.setOnClickListener { onClick() }
        return v
    }

    private fun setup() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // Tay nắm kéo bảng
        val grip = IconView(this, IconKind.GRIP, AppTheme.gripTint)
        grip.layoutParams = LinearLayout.LayoutParams(dp(44), dp(26))
        gripView = grip

        playBtn = circleButton(IconKind.PLAY, colGreen, 50) { toggle() }

        // Ô hiển thị thời gian chạy: 000:00:00
        timerText = TextView(this)
        timerText.setTextColor(AppTheme.text)
        timerText.textSize = 10f
        timerText.typeface = Typeface.MONOSPACE
        timerText.gravity = Gravity.CENTER
        timerText.setPadding(dp(6), dp(2), dp(6), dp(2))
        val tbg = GradientDrawable()
        tbg.shape = GradientDrawable.RECTANGLE
        tbg.cornerRadius = dp(8).toFloat()
        tbg.setColor(AppTheme.field)
        timerText.background = tbg
        timerBgDrawable = tbg
        val tlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tlp.setMargins(0, dp(2), 0, dp(4))
        timerText.layoutParams = tlp
        showTimer()

        val addBtn = circleButton(IconKind.PLUS, AppTheme.panelBtn, 36) { addAction(ActionType.TAP) }
        val swipeBtn = circleButton(IconKind.SWIPE, AppTheme.panelBtn, 36) { addAction(ActionType.SWIPE) }
        val removeBtn = circleButton(IconKind.MINUS, AppTheme.panelBtn, 36) { removeLastAction() }
        val settingsBtn = circleButton(IconKind.GEAR, AppTheme.panelBtn, 36) { openSettings() }
        val minBtn = circleButton(IconKind.COLLAPSE, AppTheme.panelBtn, 36) { minimize() }
        val closeBtn = circleButton(IconKind.CLOSE, Color.rgb(150, 50, 50), 36) { stopSelf() }
        toolBtns.clear()
        toolBtns.addAll(listOf(addBtn, swipeBtn, removeBtn, settingsBtn, minBtn))

        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(30).toFloat()
        bg.setColor(AppTheme.panelBg)
        bg.setStroke(dp(1), AppTheme.panelStroke)
        panelBgDrawable = bg

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.setPadding(dp(6), dp(4), dp(6), dp(6))
        panel.background = bg
        panel.alpha = ClickStore.panelAlpha / 100f
        panel.addView(grip)
        panel.addView(playBtn)
        panel.addView(timerText)
        panel.addView(addBtn)
        panel.addView(swipeBtn)
        panel.addView(removeBtn)
        panel.addView(settingsBtn)
        panel.addView(minBtn)
        panel.addView(closeBtn)

        panelLp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(8), dp(100)
        )
        attachTouch(grip, panelLp, panel, null, null)
        wm.addView(panel, panelLp)
        panelView = panel

        // Bong bóng khi thu nhỏ: kéo để di chuyển, chạm để mở lại bảng
        bubbleView = IconView(this, IconKind.LOGO)
        bubbleView.background = ovalBg(colGreen)
        bubbleLp = overlayParams(dp(48), dp(48), dp(8), dp(100))
        attachTouch(bubbleView, bubbleLp, bubbleView, null, { expand() })

        rebuildMarkers()
    }

    // ---------- Thu nhỏ / mở rộng ----------

    private fun updateBubbleColor() {
        bubbleView.background = ovalBg(if (running) colRed else colGreen)
    }

    private fun minimize() {
        val p = panelView ?: return
        if (minimized) return
        minimized = true
        bubbleLp.x = panelLp.x
        bubbleLp.y = panelLp.y
        updateBubbleColor()
        try { wm.removeView(p) } catch (e: Exception) {}
        try { wm.addView(bubbleView, bubbleLp) } catch (e: Exception) {}
    }

    private fun expand() {
        val p = panelView ?: return
        if (!minimized) return
        minimized = false
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - p.width).coerceAtLeast(0)
        val maxY = (dm.heightPixels - p.height).coerceAtLeast(0)
        panelLp.x = bubbleLp.x.coerceIn(0, maxX)
        panelLp.y = bubbleLp.y.coerceIn(0, maxY)
        try { wm.removeView(bubbleView) } catch (e: Exception) {}
        try { wm.addView(p, panelLp) } catch (e: Exception) {}
    }

    // ---------- Bộ đếm thời gian ----------

    private fun showTimer() {
        val totalSec = (elapsedMs / 1000L).coerceIn(0L, MAX_SECONDS)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        val n = AutoClickService.instance?.tapCount ?: 0
        timerText.text = String.format(Locale.US, "%03d:%02d:%02d\n×%d", h, m, s, n)
    }

    // ---------- Các nút đánh số ----------

    /** Vẽ lại toàn bộ nút từ ClickStore (gọi sau khi sửa / xoá / đổi thứ tự). */
    fun refresh() {
        if (destroyed || panelView == null) return
        if (running) AutoClickService.instance?.stop()
        rebuildMarkers()
    }

    private fun clearMarkers() {
        items.forEach {
            try { wm.removeView(it.view) } catch (e: Exception) {}
            it.endView?.let { v -> try { wm.removeView(v) } catch (e: Exception) {} }
        }
        items.clear()
    }

    private fun rebuildMarkers() {
        clearMarkers()
        ClickStore.actions.forEachIndexed { i, a -> addMarker(i, a) }
    }

    private fun makeCircle(label: String, color: Int, size: Int): TextView {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)))
            setStroke(dp(3), color)
        }
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = if (size >= dp(50)) 20f else 14f
            gravity = Gravity.CENTER
            background = bg
        }
    }

    private fun addMarker(index: Int, a: ClickAction) {
        val n = (index + 1).toString()
        val half = markerSize / 2

        val view = makeCircle(n, a.type.color, markerSize)
        val lp = overlayParams(markerSize, markerSize, (a.x - half).toInt(), (a.y - half).toInt())
        attachTouch(view, lp, view,
            onMove = { x, y -> a.x = x + half.toFloat(); a.y = y + half.toFloat() },
            onClick = { openEditor(index) })
        if (running) lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.addView(view, lp)

        var endView: TextView? = null
        var endLp: WindowManager.LayoutParams? = null
        if (a.type == ActionType.SWIPE) {
            val eh = endSize / 2
            val ev = makeCircle("$n▸", Color.rgb(30, 136, 229), endSize)
            val elp = overlayParams(endSize, endSize, (a.x2 - eh).toInt(), (a.y2 - eh).toInt())
            attachTouch(ev, elp, ev,
                onMove = { x, y -> a.x2 = x + eh.toFloat(); a.y2 = y + eh.toFloat() },
                onClick = { openEditor(index) })
            if (running) elp.flags = elp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.addView(ev, elp)
            endView = ev
            endLp = elp
        }
        items.add(Item(a, view, lp, endView, endLp))
    }

    /** Thêm một nút mới: Chạm (TAP) hoặc Kéo (SWIPE, có chấm bắt đầu và chấm kết thúc). */
    private fun addAction(type: ActionType) {
        if (running) {
            Toast.makeText(this, "Hãy dừng trước khi thêm nút", Toast.LENGTH_SHORT).show()
            return
        }
        val dm = resources.displayMetrics
        val n = ClickStore.actions.size
        val minC = dp(30).toFloat()
        val maxX = (dm.widthPixels - dp(30)).toFloat()
        val maxY = (dm.heightPixels - dp(30)).toFloat()
        val x = (dm.widthPixels / 2f + (n % 6) * dp(14)).coerceIn(minC, maxX)
        val y = (dm.heightPixels / 2f + (n % 6) * dp(14)).coerceIn(minC, maxY)
        val x2 = if (x + dp(140) <= maxX) x + dp(140) else (x - dp(140)).coerceAtLeast(minC)
        val a = ClickAction(
            type = type,
            x = x, y = y,
            x2 = x2,
            y2 = y,
            delayMs = ClickStore.defaultDelay
        )
        ClickStore.actions.add(a)
        ClickStore.save(this)
        addMarker(ClickStore.actions.size - 1, a)
        if (type == ActionType.SWIPE) {
            Toast.makeText(this, "Kéo chấm xanh ▸ tới nơi cần thả", Toast.LENGTH_SHORT).show()
        }
    }

    /** Xoá nút cuối cùng trong danh sách. */
    private fun removeLastAction() {
        if (running) {
            Toast.makeText(this, "Hãy dừng trước khi xoá nút", Toast.LENGTH_SHORT).show()
            return
        }
        if (ClickStore.actions.isEmpty()) return
        ClickStore.actions.removeAt(ClickStore.actions.size - 1)
        ClickStore.save(this)
        rebuildMarkers()
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    private fun openEditor(index: Int) {
        if (running) return
        startActivity(
            Intent(this, EditActionActivity::class.java)
                .putExtra("index", index)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    /**
     * Kéo thả + bấm.
     * onMove  : cập nhật toạ độ khi kéo (nhận lp.x, lp.y)
     * onClick : gọi khi chạm mà không kéo
     */
    private fun attachTouch(
        touchView: View,
        lp: WindowManager.LayoutParams,
        target: View,
        onMove: ((Int, Int) -> Unit)?,
        onClick: (() -> Unit)?
    ) {
        var ix = 0
        var iy = 0
        var tx = 0f
        var ty = 0f
        var dragged = false
        val slop = dp(6)
        touchView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ix = lp.x; iy = lp.y
                    tx = e.rawX; ty = e.rawY
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (!dragged && (abs(dx) > slop || abs(dy) > slop)) dragged = true
                    if (dragged) {
                        lp.x = ix + dx.toInt()
                        lp.y = iy + dy.toInt()
                        try { wm.updateViewLayout(target, lp) } catch (ex: Exception) {}
                        onMove?.invoke(lp.x, lp.y)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) ClickStore.save(this) else onClick?.invoke()
                }
            }
            true
        }
    }

    // ---------- Chạy / dừng ----------

    private fun setRunningUi(r: Boolean) {
        val was = running
        running = r
        if (destroyed) return

        // Bộ đếm: bắt đầu từ 000:00:00 mỗi lần chạy, giữ nguyên số khi dừng
        handler.removeCallbacks(ticker)
        if (r) {
            if (!was) {
                startTime = SystemClock.elapsedRealtime()
                elapsedMs = 0L
            }
            handler.post(ticker)
        } else if (was) {
            elapsedMs = SystemClock.elapsedRealtime() - startTime
            showTimer()
        }

        playBtn.kind = if (r) IconKind.PAUSE else IconKind.PLAY
        playBtn.background = ovalBg(if (r) colRed else colGreen)
        playBtn.invalidate()
        updateBubbleColor()
        items.forEach { item ->
            setTouchable(item.view, item.lp, !r)
            val ev = item.endView
            val elp = item.endLp
            if (ev != null && elp != null) setTouchable(ev, elp, !r)
        }
    }

    private fun setTouchable(v: View, lp: WindowManager.LayoutParams, touchable: Boolean) {
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { wm.updateViewLayout(v, lp) } catch (e: Exception) {}
    }

    private fun toggle() {
        val svc = AutoClickService.instance
        if (svc == null) {
            Toast.makeText(this, "Hãy bật dịch vụ Trợ năng Nvp Klick trước", Toast.LENGTH_LONG).show()
            return
        }
        if (running) {
            svc.stop()          // callback sẽ cập nhật giao diện
            setRunningUi(false) // phòng trường hợp callback không được gọi
            return
        }
        if (ClickStore.actions.isEmpty()) {
            Toast.makeText(this, "Chưa có nút nào. Nhấn ＋ để thêm", Toast.LENGTH_SHORT).show()
            return
        }

        // Lấy toạ độ tâm thật trên màn hình của từng chấm
        val loc = IntArray(2)
        items.forEach { item ->
            item.view.getLocationOnScreen(loc)
            item.action.x = loc[0] + item.view.width / 2f
            item.action.y = loc[1] + item.view.height / 2f
            val ev = item.endView
            if (ev != null) {
                ev.getLocationOnScreen(loc)
                item.action.x2 = loc[0] + ev.width / 2f
                item.action.y2 = loc[1] + ev.height / 2f
            }
        }
        ClickStore.save(this)

        setRunningUi(true)
        svc.run(
            ClickStore.actions,
            ClickStore.loops,
            ClickStore.stopMinutes * 60_000L,
            ClickStore.stopTaps
        ) { setRunningUi(false) }
    }

    override fun onDestroy() {
        AutoClickService.instance?.stop()
        destroyed = true
        handler.removeCallbacks(ticker)
        instance = null
        clearMarkers()
        panelView?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        if (::bubbleView.isInitialized) {
            try { wm.removeView(bubbleView) } catch (e: Exception) {}
        }
        panelView = null
        super.onDestroy()
    }
}