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
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class FloatingService : Service() {

    companion object {
        @Volatile var instance: FloatingService? = null
        private const val CHANNEL_ID = "nvpklick_running"
        private const val NOTI_ID = 1
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
    private val items = mutableListOf<Item>()

    private var running = false
    private var destroyed = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val markerSize get() = dp(52)
    private val endSize get() = dp(40)

    private val colGreen = Color.rgb(46, 160, 67)
    private val colRed = Color.rgb(211, 47, 47)
    private val colBtn = Color.rgb(45, 55, 72)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        ClickStore.ensureLoaded(this)
        if (panelView == null) {
            setup()
        } else {
            refresh()
            applyAlpha()
        }
        return START_NOT_STICKY
    }

    /** Cập nhật độ đậm của bảng nổi theo cài đặt. */
    fun applyAlpha() {
        panelView?.alpha = ClickStore.panelAlpha / 100f
    }

    // ---------- Foreground notification ----------

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
        lp.setMargins(0, dp(4), 0, dp(4))
        v.layoutParams = lp
        v.setOnClickListener { onClick() }
        return v
    }

    private fun setup() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // Tay nắm kéo bảng
        val grip = IconView(this, IconKind.GRIP, Color.argb(210, 190, 196, 210))
        grip.layoutParams = LinearLayout.LayoutParams(dp(44), dp(30))

        playBtn = circleButton(IconKind.PLAY, colGreen, 50) { toggle() }
        val addBtn = circleButton(IconKind.PLUS, colBtn, 40) { addAction() }
        val removeBtn = circleButton(IconKind.MINUS, colBtn, 40) { removeLastAction() }
        val settingsBtn = circleButton(IconKind.GEAR, colBtn, 40) { openSettings() }
        val closeBtn = circleButton(IconKind.CLOSE, Color.rgb(150, 50, 50), 40) { stopSelf() }

        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(30).toFloat()
        bg.setColor(Color.argb(240, 24, 27, 34))
        bg.setStroke(dp(1), Color.argb(90, 255, 255, 255))

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.setPadding(dp(6), dp(4), dp(6), dp(6))
        panel.background = bg
        panel.alpha = ClickStore.panelAlpha / 100f
        panel.addView(grip)
        panel.addView(playBtn)
        panel.addView(addBtn)
        panel.addView(removeBtn)
        panel.addView(settingsBtn)
        panel.addView(closeBtn)

        panelLp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(8), dp(120)
        )
        attachTouch(grip, panelLp, panel, null, null)
        wm.addView(panel, panelLp)
        panelView = panel

        rebuildMarkers()
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

    private fun addAction() {
        if (running) {
            Toast.makeText(this, "Hãy dừng trước khi thêm nút", Toast.LENGTH_SHORT).show()
            return
        }
        val dm = resources.displayMetrics
        val n = ClickStore.actions.size
        val x = (dm.widthPixels / 2f + (n % 6) * dp(14)).coerceIn(dp(30).toFloat(), (dm.widthPixels - dp(30)).toFloat())
        val y = (dm.heightPixels / 2f + (n % 6) * dp(14)).coerceIn(dp(30).toFloat(), (dm.heightPixels - dp(30)).toFloat())
        val a = ClickAction(
            type = ActionType.TAP,
            x = x, y = y,
            x2 = (x + dp(140)).coerceAtMost((dm.widthPixels - dp(30)).toFloat()),
            y2 = y,
            delayMs = ClickStore.defaultDelay
        )
        ClickStore.actions.add(a)
        ClickStore.save(this)
        addMarker(ClickStore.actions.size - 1, a)
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
        running = r
        if (destroyed) return
        playBtn.kind = if (r) IconKind.PAUSE else IconKind.PLAY
        playBtn.background = ovalBg(if (r) colRed else colGreen)
        playBtn.invalidate()
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
        svc.run(ClickStore.actions, ClickStore.loops) { setRunningUi(false) }
    }

    override fun onDestroy() {
        AutoClickService.instance?.stop()
        destroyed = true
        instance = null
        clearMarkers()
        panelView?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        panelView = null
        super.onDestroy()
    }
}