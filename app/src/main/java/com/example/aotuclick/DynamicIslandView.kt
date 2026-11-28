// Lucian UX/UI Prompt #459 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#459
package com.example.autoclick

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

// Các đường cong chuyển động giữ nguyên từ mẫu
private val DI_EASE: Interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)          // --ux-ease
private val DI_SIZE: Interpolator = PathInterpolator(0.3f, 1.32f, 0.5f, 1f)         // rộng / cao
private val DI_RADIUS: Interpolator = PathInterpolator(0.3f, 1.2f, 0.5f, 1f)        // bo góc
private val DI_PRESS: Interpolator = PathInterpolator(0.34f, 1.6f, 0.5f, 1f)        // nhấn / nút
private val DI_LAYER: Interpolator = PathInterpolator(0.3f, 1.4f, 0.5f, 1f)         // lớp nội dung hiện ra
private val DI_AT: Interpolator = PathInterpolator(0.3f, 1.5f, 0.5f, 1f)            // nảy về sau khi vuốt
private val DI_EQ: Interpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)            // ease-in-out của thanh equalizer
private val DI_OUT: Interpolator = PathInterpolator(0f, 0f, 0.58f, 1f)              // ease-out của hiệu ứng lắc

/** Một giá trị chuyển động từ giá trị hiện tại tới đích (giống CSS transition, có trễ). */
private class DiTween(initial: Float, private val onChange: () -> Unit) {
    var value = initial
        private set
    private var anim: ValueAnimator? = null

    fun to(target: Float, durationMs: Long, interp: Interpolator, delayMs: Long, animate: Boolean) {
        anim?.cancel()
        anim = null
        if (!animate || value == target) {
            value = target
            onChange()
            return
        }
        val from = value
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = durationMs
        a.startDelay = delayMs
        a.interpolator = interp
        a.addUpdateListener {
            val f = it.animatedValue as Float
            value = from + (target - from) * f
            onChange()
        }
        anim = a
        a.start()
    }

    fun set(v: Float) {
        anim?.cancel()
        anim = null
        value = v
        onChange()
    }
}

/**
 * Dynamic Island (mẫu #459): một viên thuốc đen chứa hoạt động trực tiếp.
 * Chạm để mở rộng (nảy lò xo), vuốt ngang để đổi hoạt động: hẹn giờ → nhạc → cuộc gọi.
 * Viên thuốc luôn đen dù app ở chế độ sáng hay tối. Vẽ hoàn toàn bằng Canvas, chạy được từ Android 8.0 (API 26).
 * Chỉ nhận chạm trong vùng viên thuốc, chạm ra ngoài sẽ đi xuống view bên dưới.
 */
class DynamicIslandView(context: Context) : View(context) {

    private class Track(val title: String, val artist: String, val len: Int)
    private class Btn(val id: String, val cx: Float, val cy: Float, val hw: Float, val hh: Float)

    private val density = resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())

    // ---------- Dữ liệu và trạng thái ----------
    private val acts = arrayOf("timer", "music", "call")
    private val tracks = arrayOf(
        Track("Đêm Hè", "Hangyeol", 223),
        Track("Tiếng Sóng", "Yunseul", 196)
    )
    private var act = 0
    private var ex = false
    private var callOn = false
    private var tS = 272
    private var tRun = true
    private var mS = 72
    private var track = 0
    private var playing = true
    private var cS = 0

    // ---------- Chuyển động ----------
    private fun tw(initial: Float) = DiTween(initial) { invalidate() }

    private val wT = tw(206f)
    private val hT = tw(36f)
    private val rT = tw(18f)
    private val offX = tw(0f)       // độ lệch ngang khi vuốt
    private val press = tw(0f)      // nhấn cả viên thuốc
    private val btnPress = tw(0f)   // nhấn một nút bên trong
    private val ringT = tw(272f / 300f)
    private val progT = tw(72f / 223f)
    private var pressedId: String? = null
    private var nudgeAnim: ValueAnimator? = null

    private inner class Layer(val key: String, val w: Float, val h: Float) {
        val op = tw(0f)
        val sc = tw(0.9f)
        fun set(on: Boolean, animate: Boolean) {
            if (on) {
                op.to(1f, 300, DI_EASE, 130, animate)
                sc.to(1f, 500, DI_LAYER, 100, animate)
            } else {
                op.to(0f, 160, DI_EASE, 0, animate)
                sc.to(0.9f, 300, DI_EASE, 0, animate)
            }
        }
    }

    private val layers = listOf(
        Layer("tc", 206f, 36f), Layer("te", 316f, 84f),
        Layer("mc", 206f, 36f), Layer("me", 316f, 160f),
        Layer("cc", 206f, 36f), Layer("ce", 316f, 84f),
        Layer("oc", 206f, 36f), Layer("oe", 316f, 84f)
    )

    // ---------- Sơn ----------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
    private val tfBold: Typeface = Typeface.DEFAULT_BOLD
    private val tfReg: Typeface = Typeface.DEFAULT
    private val tfLight: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val rect = RectF()
    private val clipPath = Path()
    private val artShaders = HashMap<Int, Array<Shader>>()
    private val avShaders = HashMap<Int, Shader>()

    private val cOrange = Color.rgb(0xFF, 0x9F, 0x0A)
    private val cGreen = Color.rgb(0x30, 0xD1, 0x58)
    private val cRed = Color.rgb(0xFF, 0x45, 0x3A)
    private val cPink = Color.rgb(0xFF, 0x8F, 0xAB)

    private val phonePath = buildPhone()
    private val nextPath = buildNext()
    private val playPath = buildPlay()

    private var eqMs = 0L
    private var lastFrame = 0L
    private var kbIdx = -1

    // ---------- Biểu tượng (toạ độ 24x24 như mẫu) ----------
    private fun buildPhone(): Path {
        val p = Path()
        p.moveTo(6.6f, 10.8f)
        p.quadTo(8.87f, 15.13f, 13.2f, 17.4f)
        p.rLineTo(2.2f, -2.2f)
        p.rCubicTo(0.3f, -0.3f, 0.7f, -0.4f, 1f, -0.2f)
        p.rCubicTo(1.1f, 0.4f, 2.3f, 0.6f, 3.6f, 0.6f)
        p.rCubicTo(0.6f, 0f, 1f, 0.4f, 1f, 1f)
        p.rLineTo(0f, 3.4f)
        p.rCubicTo(0f, 0.6f, -0.4f, 1f, -1f, 1f)
        p.arcTo(RectF(3f, -13f, 37f, 21f), 90f, 90f)
        p.rCubicTo(0f, -0.6f, 0.4f, -1f, 1f, -1f)
        p.rLineTo(3.5f, 0f)
        p.rCubicTo(0.6f, 0f, 1f, 0.4f, 1f, 1f)
        p.rCubicTo(0f, 1.3f, 0.2f, 2.5f, 0.6f, 3.6f)
        p.rCubicTo(0.1f, 0.3f, 0f, 0.7f, -0.2f, 1f)
        p.close()
        return p
    }

    private fun buildNext(): Path {
        val p = Path()
        p.moveTo(3.5f, 6.6f)
        p.rLineTo(0f, 10.8f)
        p.rCubicTo(0f, 0.6f, 0.6f, 0.9f, 1.1f, 0.6f)
        p.rLineTo(7.4f, -5.2f)
        p.rLineTo(0f, 4.6f)
        p.rCubicTo(0f, 0.6f, 0.6f, 0.9f, 1.1f, 0.6f)
        p.rLineTo(7.6f, -5.4f)
        p.rCubicTo(0.4f, -0.3f, 0.4f, -0.9f, 0f, -1.2f)
        p.lineTo(13.1f, 6f)
        p.rCubicTo(-0.5f, -0.3f, -1.1f, 0f, -1.1f, 0.6f)
        p.rLineTo(0f, 4.6f)
        p.lineTo(4.6f, 6f)
        p.rCubicTo(-0.5f, -0.3f, -1.1f, 0f, -1.1f, 0.6f)
        p.close()
        return p
    }

    private fun buildPlay(): Path {
        val p = Path()
        p.moveTo(8f, 5.6f)
        p.rLineTo(0f, 12.8f)
        p.rCubicTo(0f, 0.6f, 0.6f, 0.9f, 1.1f, 0.6f)
        p.rLineTo(9.9f, -6.4f)
        p.rCubicTo(0.5f, -0.3f, 0.5f, -0.9f, 0f, -1.2f)
        p.lineTo(9.1f, 5f)
        p.rCubicTo(-0.5f, -0.3f, -1.1f, 0f, -1.1f, 0.6f)
        p.close()
        return p
    }

    // ---------- Tiện ích ----------
    private fun reduceMotion(): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) {
        false
    }

    private fun fmt(s: Int): String {
        val v = Math.max(0, s)
        return (v / 60).toString() + ":" + (if (v % 60 < 10) "0" else "") + (v % 60)
    }

    private fun key(): String {
        val a = acts[act]
        val c = when (a) {
            "timer" -> 't'
            "music" -> 'm'
            else -> if (callOn) 'o' else 'c'
        }
        return c.toString() + (if (ex) 'e' else 'c')
    }

    private fun label(): String {
        val a = acts[act]
        val s = when (a) {
            "timer" -> "Hẹn giờ " + fmt(tS)
            "music" -> "Nhạc, " + tracks[track].title
            else -> if (callOn) "Đang gọi với Mẹ, " + fmt(cS) else "Cuộc gọi đến từ Mẹ"
        }
        return s + (if (ex) ", đã mở rộng" else "")
    }

    private fun sizeOf(k: String): FloatArray {
        if (!ex) return floatArrayOf(206f, 36f, 18f)
        return if (k == "me") floatArrayOf(316f, 160f, 46f) else floatArrayOf(316f, 84f, 42f)
    }

    private fun btnLabel(id: String): String = when (id) {
        "pause" -> if (tRun) "Tạm dừng" else "Tiếp tục"
        "cancel" -> "Kết thúc hẹn giờ"
        "play" -> if (playing) "Tạm dừng" else "Phát"
        "prev" -> "Bài trước"
        "next" -> "Bài sau"
        "decline" -> "Từ chối"
        "accept" -> "Nghe máy"
        else -> "Kết thúc cuộc gọi"
    }

    private fun buttons(k: String): List<Btn> = when (k) {
        "te" -> listOf(Btn("pause", -118f, 42f, 24f, 24f), Btn("cancel", -60f, 42f, 24f, 24f))
        "me" -> listOf(Btn("prev", -72f, 122f, 28f, 22f), Btn("play", 0f, 122f, 28f, 22f), Btn("next", 72f, 122f, 28f, 22f))
        "ce" -> listOf(Btn("decline", 56f, 42f, 24f, 24f), Btn("accept", 114f, 42f, 24f, 24f))
        "oe" -> listOf(Btn("end", 114f, 42f, 24f, 24f))
        else -> emptyList()
    }

    private fun curButtons(): List<Btn> = if (ex) buttons(key()) else emptyList()

    // ---------- Điều khiển ----------
    private fun render(announce: Boolean, animate: Boolean) {
        val k = key()
        val sz = sizeOf(k)
        for (l in layers) l.set(l.key == k, animate)
        wT.to(sz[0], 580, DI_SIZE, 0, animate)
        hT.to(sz[1], 580, DI_SIZE, 0, animate)
        rT.to(sz[2], 580, DI_RADIUS, 0, animate)
        kbIdx = -1
        contentDescription = label()
        if (announce) announceForAccessibility(label())
        invalidate()
    }

    private fun setEx(v: Boolean) {
        ex = v
        render(true, !reduceMotion())
    }

    /** Thu gọn ngay không hiệu ứng (dùng khi tắt công cụ). */
    fun collapseNow() {
        ex = false
        render(false, false)
    }

    private fun nudge(dir: Int) {
        if (reduceMotion()) return
        nudgeAnim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 520
        a.interpolator = DI_OUT
        a.addUpdateListener {
            val p = it.animatedValue as Float
            val v = if (p < 0.55f) {
                14f + (-4f - 14f) * (p / 0.55f)
            } else {
                -4f * (1f - (p - 0.55f) / 0.45f)
            }
            offX.set(v * dir)
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                offX.set(0f)
            }
        })
        nudgeAnim = a
        a.start()
    }

    private fun go(dir: Int, quiet: Boolean) {
        act = (act + dir + acts.size) % acts.size
        ex = false
        render(true, !reduceMotion())
        if (!quiet) nudge(dir)
    }

    private fun setTrack(i: Int) {
        track = (i + tracks.size) % tracks.size
        mS = 0
        progT.set(0f)
        contentDescription = label()
        invalidate()
    }

    private fun doAct(id: String) {
        when (id) {
            "pause" -> tRun = !tRun
            "cancel" -> {
                tS = 300
                tRun = true
                ringT.set(1f)
                go(1, true)
            }
            "play" -> playing = !playing
            "prev" -> setTrack(track - 1)
            "next" -> setTrack(track + 1)
            "accept" -> {
                callOn = true
                cS = 0
                ex = false
                render(true, !reduceMotion())
            }
            "decline" -> {
                ex = false
                act = 0
                render(true, !reduceMotion())
            }
            "end" -> {
                callOn = false
                ex = false
                act = 0
                render(true, !reduceMotion())
            }
        }
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        setEx(!ex)
        return true
    }

    // ---------- Đồng hồ 1 giây ----------
    private val clock = object : Runnable {
        override fun run() {
            if (tRun) {
                tS -= 1
                if (tS < 0) tS = 300
            }
            if (playing) {
                mS += 1
                if (mS > tracks[track].len) mS = 0
            }
            if (callOn) cS += 1
            val animate = !reduceMotion()
            ringT.to(tS / 300f, 1000, LinearInterpolator(), 0, animate)
            progT.to(mS.toFloat() / tracks[track].len, 1000, LinearInterpolator(), 0, animate)
            contentDescription = label()
            invalidate()
            ui.postDelayed(this, 1000)
        }
    }
    private var ticking = false

    private fun updateClock() {
        val want = isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE
        if (want && !ticking) {
            ticking = true
            ui.postDelayed(clock, 1000)
        } else if (!want && ticking) {
            ticking = false
            ui.removeCallbacks(clock)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrame = 0L
        updateClock()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        nudgeAnim?.cancel()
        ticking = false
        ui.removeCallbacks(clock)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateClock()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateClock()
    }

    // ---------- Chạm ----------
    private val topPad = 4f
    private var downX = 0f
    private var downBtn: String? = null
    private var bodyDown = false
    private var dragDx = 0f
    private var pressOff = false

    private fun islandCx(): Float = width / density / 2f + offX.value

    private fun inIsland(x: Float, y: Float): Boolean {
        val cx = islandCx()
        return x >= cx - wT.value / 2f && x <= cx + wT.value / 2f && y >= topPad && y <= topPad + hT.value
    }

    private fun hit(x: Float, y: Float): Btn? {
        val lx = x - islandCx()
        val ly = y - topPad
        for (b in curButtons()) {
            if (Math.abs(lx - b.cx) <= b.hw && Math.abs(ly - b.cy) <= b.hh) return b
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x / density
        val y = e.y / density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inIsland(x, y)) return false
                downX = x
                dragDx = 0f
                pressOff = false
                nudgeAnim?.cancel()
                val b = hit(x, y)
                if (b != null) {
                    downBtn = b.id
                    bodyDown = false
                    pressedId = b.id
                    btnPress.to(1f, 100, DI_PRESS, 0, !reduceMotion())
                } else {
                    downBtn = null
                    bodyDown = true
                    press.to(1f, 120, DI_PRESS, 0, !reduceMotion())
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (bodyDown) {
                    dragDx = x - downX
                    val r = Math.signum(dragDx) * Math.min(48f, Math.abs(dragDx) * 0.4f)
                    offX.set(r)
                    if (Math.abs(dragDx) > 8f && !pressOff) {
                        pressOff = true
                        press.to(0f, 450, DI_PRESS, 0, !reduceMotion())
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = e.actionMasked == MotionEvent.ACTION_UP
                if (downBtn != null) {
                    val id = downBtn!!
                    downBtn = null
                    btnPress.to(0f, 400, DI_PRESS, 0, !reduceMotion())
                    val b = hit(x, y)
                    if (up && b != null && b.id == id) doAct(id)
                } else if (bodyDown) {
                    bodyDown = false
                    press.to(0f, 450, DI_PRESS, 0, !reduceMotion())
                    offX.to(0f, 550, DI_AT, 0, !reduceMotion())
                    val d = dragDx
                    if (up) {
                        if (Math.abs(d) > 36f) {
                            go(if (d < 0f) 1 else -1, true)
                        } else if (Math.abs(d) <= 8f) {
                            performClick()
                        }
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    // ---------- Bàn phím ----------
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { go(1, false); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { go(-1, false); return true }
            KeyEvent.KEYCODE_ESCAPE -> if (ex) { setEx(false); return true }
            KeyEvent.KEYCODE_TAB -> if (ex) {
                val bs = curButtons()
                val n = kbIdx + (if (event.isShiftPressed) -1 else 1)
                if (n >= 0 && n < bs.size) {
                    kbIdx = n
                    invalidate()
                    return true
                }
                kbIdx = -1
                invalidate()
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                val bs = curButtons()
                if (kbIdx in bs.indices) doAct(bs[kbIdx].id) else performClick()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) kbIdx = -1
        invalidate()
    }

    // ---------- Trợ năng (TalkBack) ----------
    private val a11yPrev = 0x7E000001
    private val a11yNext = 0x7E000002
    private val a11yBtn = 0x7E000010

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_CLICK, if (ex) "Thu gọn" else "Mở rộng"
            )
        )
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(a11yPrev, "Hoạt động trước"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(a11yNext, "Hoạt động sau"))
        val bs = curButtons()
        for (i in bs.indices) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(a11yBtn + i, btnLabel(bs[i].id)))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == a11yPrev) { go(-1, true); return true }
        if (action == a11yNext) { go(1, true); return true }
        val bs = curButtons()
        val i = action - a11yBtn
        if (i >= 0 && i < bs.size) { doAct(bs[i].id); return true }
        return super.performAccessibilityAction(action, arguments)
    }

    // ---------- Vẽ ----------
    private fun rgba(c: Int, a: Float): Int =
        Color.argb((Color.alpha(c) * a).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun drawText(
        c: Canvas, s: String, x: Float, cy: Float, size: Float, color: Int,
        align: Paint.Align, tf: Typeface
    ) {
        tp.textSize = size
        tp.color = color
        tp.textAlign = align
        tp.typeface = tf
        val fm = tp.fontMetrics
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2f, tp)
    }

    /** Vẽ nội dung trong hệ 24x24 đặt tâm tại (cx, cy) với kích thước size. */
    private inline fun icon24(c: Canvas, cx: Float, cy: Float, size: Float, rot: Float, body: () -> Unit) {
        c.save()
        c.translate(cx, cy)
        if (rot != 0f) c.rotate(rot)
        c.scale(size / 24f, size / 24f)
        c.translate(-12f, -12f)
        body()
        c.restore()
    }

    private fun drawPhone(c: Canvas, cx: Float, cy: Float, size: Float, color: Int, rot: Float) {
        fill.shader = null
        fill.color = color
        icon24(c, cx, cy, size, rot) { c.drawPath(phonePath, fill) }
    }

    private fun drawBar2(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 2.8f
        icon24(c, cx, cy, size, 0f) {
            c.drawLine(8.5f, 6f, 8.5f, 18f, stroke)
            c.drawLine(15.5f, 6f, 15.5f, 18f, stroke)
        }
    }

    private fun drawPlayTri(c: Canvas, cx: Float, cy: Float, size: Float, color: Int, flip: Boolean) {
        fill.shader = null
        fill.color = color
        c.save()
        if (flip) c.scale(-1f, 1f, cx, cy)
        icon24(c, cx, cy, size, 0f) { c.drawPath(playPath, fill) }
        c.restore()
    }

    private fun drawPauseFill(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        fill.shader = null
        fill.color = color
        icon24(c, cx, cy, size, 0f) {
            c.drawRect(7f, 5f, 10.2f, 19f, fill)
            c.drawRect(13.8f, 5f, 17f, 19f, fill)
        }
    }

    private fun drawSkip(c: Canvas, cx: Float, cy: Float, size: Float, color: Int, flip: Boolean) {
        fill.shader = null
        fill.color = color
        c.save()
        if (flip) c.scale(-1f, 1f, cx, cy)
        icon24(c, cx, cy, size, 0f) { c.drawPath(nextPath, fill) }
        c.restore()
    }

    private fun drawX(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 2.8f
        icon24(c, cx, cy, size, 0f) {
            c.drawLine(7.5f, 7.5f, 16.5f, 16.5f, stroke)
            c.drawLine(16.5f, 7.5f, 7.5f, 16.5f, stroke)
        }
    }

    /** Nút tròn 48 có hiệu ứng nhấn thu nhỏ .88. */
    private inline fun roundBtn(c: Canvas, id: String, cx: Float, cy: Float, bg: Int, icon: () -> Unit) {
        val s = if (pressedId == id) 1f - 0.12f * btnPress.value else 1f
        c.save()
        c.translate(cx, cy)
        c.scale(s, s)
        fill.shader = null
        fill.color = bg
        c.drawCircle(0f, 0f, 24f, fill)
        c.translate(-cx, -cy)
        icon()
        c.restore()
    }

    private fun kbRing(c: Canvas, b: Btn) {
        stroke.color = Color.WHITE
        stroke.strokeWidth = 2f
        if (b.hw == b.hh) {
            c.drawCircle(b.cx, b.cy, b.hw + 3f, stroke)
        } else {
            rect.set(b.cx - b.hw - 2f, b.cy - b.hh - 2f, b.cx + b.hw + 2f, b.cy + b.hh + 2f)
            c.drawRoundRect(rect, 12f, 12f, stroke)
        }
    }

    private fun drawArt(c: Canvas, x: Float, y: Float, s: Float, r: Float, alt: Boolean) {
        val k = s.toInt() * 2 + (if (alt) 1 else 0)
        val sh = artShaders.getOrPut(k) {
            val ang = Math.toRadians(if (alt) 170.0 else 160.0)
            val dx = Math.sin(ang).toFloat()
            val dy = (-Math.cos(ang)).toFloat()
            val len = s * (Math.abs(dx) + Math.abs(dy))
            val m = s / 2f
            val c1 = if (alt) intArrayOf(Color.rgb(0x7F, 0xE3, 0xF5), Color.rgb(0x2A, 0x8C, 0xF0), Color.rgb(0x1B, 0x3F, 0xA8))
            else intArrayOf(Color.rgb(0xFF, 0x9A, 0x62), Color.rgb(0xF2, 0x55, 0x7A), Color.rgb(0x6B, 0x4C, 0xF0))
            val lin = LinearGradient(
                m - dx * len / 2f, m - dy * len / 2f, m + dx * len / 2f, m + dy * len / 2f,
                c1, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            val hi = if (alt) Color.rgb(0xE9, 0xFB, 0xFF) else Color.rgb(0xFF, 0xE6, 0xA8)
            val px = if (alt) 0.30f else 0.70f
            val py = if (alt) 0.28f else 0.30f
            val full = if (alt) 0.12f else 0.13f
            val edge = if (alt) 0.14f else 0.15f
            val rad = edge * s * 1.1f
            val rg = RadialGradient(
                px * s, py * s, rad,
                intArrayOf(hi, hi, Color.argb(0, Color.red(hi), Color.green(hi), Color.blue(hi))),
                floatArrayOf(0f, full / edge, 1f), Shader.TileMode.CLAMP
            )
            arrayOf<Shader>(lin, rg)
        }
        c.save()
        c.translate(x, y)
        rect.set(0f, 0f, s, s)
        fill.shader = sh[0]
        c.drawRoundRect(rect, r, r, fill)
        fill.shader = sh[1]
        c.drawRoundRect(rect, r, r, fill)
        fill.shader = null
        stroke.color = Color.argb(31, 255, 255, 255)
        stroke.strokeWidth = 1f
        rect.set(0.5f, 0.5f, s - 0.5f, s - 0.5f)
        c.drawRoundRect(rect, r, r, stroke)
        c.restore()
    }

    private fun drawAvatar(c: Canvas, cx: Float, cy: Float, d: Float, textSize: Float) {
        val k = d.toInt()
        val sh = avShaders.getOrPut(k) {
            val ang = Math.toRadians(150.0)
            val dx = Math.sin(ang).toFloat()
            val dy = (-Math.cos(ang)).toFloat()
            val len = d * (Math.abs(dx) + Math.abs(dy))
            val m = d / 2f
            LinearGradient(
                m - dx * len / 2f, m - dy * len / 2f, m + dx * len / 2f, m + dy * len / 2f,
                Color.rgb(0xFF, 0xB4, 0x8C), Color.rgb(0xF2, 0x55, 0x7A), Shader.TileMode.CLAMP
            )
        }
        c.save()
        c.translate(cx - d / 2f, cy - d / 2f)
        fill.shader = sh
        c.drawCircle(d / 2f, d / 2f, d / 2f, fill)
        fill.shader = null
        c.restore()
        drawText(c, "M", cx, cy, textSize, Color.WHITE, Paint.Align.CENTER, tfBold)
    }

    /** Bốn thanh equalizer: mỗi thanh co giãn qua lại theo chu kỳ riêng, đứng yên khi tạm dừng. */
    private fun drawEq(c: Canvas, right: Float, cy: Float, h: Float) {
        val durs = floatArrayOf(900f, 620f, 780f, 550f)
        val delays = floatArrayOf(0f, -300f, -500f, -100f)
        val still = reduceMotion()
        fill.shader = null
        fill.color = cPink
        val left = right - 19.5f
        for (i in 0 until 4) {
            val sc = if (still) {
                1f
            } else {
                val x = ((eqMs + delays[i]) / durs[i]).let { v -> ((v % 2f) + 2f) % 2f }
                val tri = if (x <= 1f) x else 2f - x
                0.25f + 0.75f * DI_EQ.getInterpolation(tri)
            }
            val bx = left + i * 5.5f
            val bh = h * sc
            rect.set(bx, cy - bh / 2f, bx + 3f, cy + bh / 2f)
            c.drawRoundRect(rect, 2f, 2f, fill)
        }
    }

    private fun phoneAngle(): Float {
        if (reduceMotion()) return 0f
        val t = (SystemClock.uptimeMillis() % 1600L) / 16f   // 0..100 (%)
        val ks = floatArrayOf(0f, 5f, 10f, 15f, 20f, 25f, 30f, 40f, 100f)
        val vs = floatArrayOf(0f, -14f, 14f, -14f, 14f, -14f, 14f, 0f, 0f)
        for (i in 0 until ks.size - 1) {
            if (t >= ks[i] && t <= ks[i + 1]) {
                val f = (t - ks[i]) / (ks[i + 1] - ks[i])
                val e = f * f * (3f - 2f * f)
                return vs[i] + (vs[i + 1] - vs[i]) * e
            }
        }
        return 0f
    }

    private fun drawLayer(c: Canvas, k: String) {
        val white = Color.WHITE
        val white55 = Color.argb(140, 255, 255, 255)
        when (k) {
            "tc" -> {
                // Vòng đếm ngược + giờ
                val cx = -84f
                val cy = 18f
                val rr = 8.25f
                stroke.strokeWidth = 2.38f
                stroke.color = rgba(cOrange, 0.28f)
                c.drawCircle(cx, cy, rr, stroke)
                val sweep = 360f * ringT.value.coerceIn(0f, 1f)
                if (sweep > 0.5f) {
                    stroke.color = cOrange
                    rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
                    c.drawArc(rect, -90f, sweep, false, stroke)
                }
                drawText(c, fmt(tS), 89f, 18f, 15f, cOrange, Paint.Align.RIGHT, tfBold)
            }
            "te" -> {
                roundBtn(c, "pause", -118f, 42f, rgba(cOrange, 0.24f)) {
                    if (tRun) drawBar2(c, -118f, 42f, 22f, cOrange) else drawPlayTri(c, -118f, 42f, 22f, cOrange, false)
                }
                roundBtn(c, "cancel", -60f, 42f, Color.argb(43, 255, 255, 255)) {
                    drawX(c, -60f, 42f, 22f, white)
                }
                drawText(c, "Hẹn giờ", 138f, 22f, 12f, rgba(cOrange, 0.85f), Paint.Align.RIGHT, tfBold)
                drawText(c, fmt(tS), 138f, 49f, 38f, cOrange, Paint.Align.RIGHT, tfLight)
            }
            "mc" -> {
                drawArt(c, -95f, 6.5f, 23f, 6f, track == 1)
                drawEq(c, 89f, 18f, 16f)
            }
            "me" -> {
                drawArt(c, -138f, 16f, 54f, 13f, track == 1)
                val tr = tracks[track]
                val maxW = 106.5f - (-72f)
                tp.textSize = 15.5f
                tp.typeface = tfBold
                val title = TextUtils.ellipsize(tr.title, tp, maxW, TextUtils.TruncateAt.END).toString()
                drawText(c, title, -72f, 34.5f, 15.5f, white, Paint.Align.LEFT, tfBold)
                drawText(c, tr.artist, -72f, 53f, 13f, white55, Paint.Align.LEFT, tfReg)
                drawEq(c, 138f, 30f, 20f)
                // Thanh tiến độ
                drawText(c, fmt(mS), -138f, 89.5f, 11f, Color.argb(128, 255, 255, 255), Paint.Align.LEFT, tfReg)
                drawText(c, "-" + fmt(tr.len - mS), 138f, 89.5f, 11f, Color.argb(128, 255, 255, 255), Paint.Align.RIGHT, tfReg)
                fill.shader = null
                fill.color = Color.argb(51, 255, 255, 255)
                rect.set(-98f, 87f, 98f, 92f)
                c.drawRoundRect(rect, 2.5f, 2.5f, fill)
                val pw = 196f * progT.value.coerceIn(0f, 1f)
                if (pw > 0.5f) {
                    fill.color = Color.argb(224, 255, 255, 255)
                    rect.set(-98f, 87f, -98f + pw, 92f)
                    c.drawRoundRect(rect, 2.5f, 2.5f, fill)
                }
                // Nút điều khiển
                ctlBtn(c, "prev", -72f, 122f) { drawSkip(c, -72f, 122f, 24f, white, true) }
                ctlBtn(c, "play", 0f, 122f) {
                    if (playing) drawPauseFill(c, 0f, 122f, 28f, white) else drawPlayTri(c, 0f, 122f, 28f, white, false)
                }
                ctlBtn(c, "next", 72f, 122f) { drawSkip(c, 72f, 122f, 24f, white, false) }
            }
            "cc" -> {
                drawAvatar(c, -83.5f, 18f, 23f, 11f)
                drawPhone(c, 79f, 18f, 20f, cGreen, phoneAngle())
            }
            "ce" -> {
                drawAvatar(c, -118f, 42f, 48f, 19f)
                drawText(c, "di động", -82f, 31f, 12.5f, white55, Paint.Align.LEFT, tfReg)
                drawText(c, "Mẹ", -82f, 52f, 18f, white, Paint.Align.LEFT, tfBold)
                roundBtn(c, "decline", 56f, 42f, cRed) { drawPhone(c, 56f, 42f, 22f, white, 135f) }
                roundBtn(c, "accept", 114f, 42f, cGreen) { drawPhone(c, 114f, 42f, 22f, white, 0f) }
            }
            "oc" -> {
                drawPhone(c, -85f, 18f, 20f, cGreen, 0f)
                drawText(c, fmt(cS), 89f, 18f, 15f, cGreen, Paint.Align.RIGHT, tfBold)
            }
            "oe" -> {
                drawAvatar(c, -118f, 42f, 48f, 19f)
                drawText(c, "Mẹ", -82f, 33f, 18f, white, Paint.Align.LEFT, tfBold)
                drawText(c, fmt(cS), -82f, 53f, 13f, cGreen, Paint.Align.LEFT, tfBold)
                roundBtn(c, "end", 114f, 42f, cRed) { drawPhone(c, 114f, 42f, 22f, white, 135f) }
            }
        }
        // Viền focus bàn phím cho nút đang chọn
        if (isFocused && kbIdx >= 0) {
            val bs = buttons(k)
            if (kbIdx < bs.size && k == key()) kbRing(c, bs[kbIdx])
        }
    }

    /** Nút phát nhạc nhỏ: thu nhỏ .85 khi nhấn. */
    private inline fun ctlBtn(c: Canvas, id: String, cx: Float, cy: Float, icon: () -> Unit) {
        val s = if (pressedId == id) 1f - 0.15f * btnPress.value else 1f
        c.save()
        c.scale(s, s, cx, cy)
        icon()
        c.restore()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        if (lastFrame != 0L && playing) eqMs += Math.min(now - lastFrame, 64L)
        lastFrame = now

        canvas.save()
        canvas.scale(density, density)
        val vw = width / density
        val cx = vw / 2f + offX.value
        val w = Math.max(wT.value, 1f)
        val h = Math.max(hT.value, 1f)
        val r = Math.min(Math.max(rT.value, 0f), Math.min(w, h) / 2f)
        val s = 1f - 0.035f * press.value

        canvas.save()
        canvas.translate(cx, topPad)
        canvas.scale(s, s, 0f, h / 2f)

        val dark = AppTheme.dark

        // Bóng đổ phía dưới (0 14px 30px -14px)
        val shadowA = if (dark) 0.9f else 0.45f
        fill.shader = null
        fill.color = Color.argb((255 * shadowA / 4f).toInt(), 0, 0, 0)
        for (i in 1..4) {
            val g = i * 4f
            rect.set(-w / 2f + 14f - g, 28f - g, w / 2f - 14f + g, h + g)
            canvas.drawRoundRect(rect, r, r, fill)
        }

        // Viền mảnh quanh viên thuốc chỉ hiện ở nền tối
        if (dark) {
            stroke.color = Color.argb(33, 255, 255, 255)
            stroke.strokeWidth = 1f
            rect.set(-w / 2f - 0.5f, -0.5f, w / 2f + 0.5f, h + 0.5f)
            canvas.drawRoundRect(rect, r + 0.5f, r + 0.5f, stroke)
        }

        // Thân viên thuốc luôn đen
        fill.color = Color.BLACK
        rect.set(-w / 2f, 0f, w / 2f, h)
        canvas.drawRoundRect(rect, r, r, fill)

        // Nội dung các lớp, cắt theo viên thuốc (overflow: hidden)
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.clipPath(clipPath)
        for (l in layers) {
            val a = l.op.value
            if (a <= 0.003f) continue
            canvas.saveLayerAlpha(-180f, -10f, 180f, 190f, (a.coerceIn(0f, 1f) * 255f).toInt())
            canvas.scale(l.sc.value, l.sc.value, 0f, 0f)
            drawLayer(canvas, l.key)
            canvas.restore()
        }
        canvas.restore()

        // Viền focus của cả viên thuốc
        if (isFocused && kbIdx < 0) {
            stroke.color = if (dark) Color.rgb(0xB7, 0xA4, 0xFF) else Color.rgb(0x6A, 0x57, 0xF2)
            stroke.strokeWidth = 2f
            rect.set(-w / 2f - 3f, -3f, w / 2f + 3f, h + 3f)
            canvas.drawRoundRect(rect, r + 3f, r + 3f, stroke)
        }
        canvas.restore()
        canvas.restore()

        // Còn hiệu ứng liên tục (equalizer, chuông) thì tiếp tục vẽ
        val eqOn = playing && !reduceMotion() &&
            layers.any { (it.key == "mc" || it.key == "me") && it.op.value > 0.003f }
        val ringOn = !reduceMotion() && layers.any { it.key == "cc" && it.op.value > 0.003f }
        if (eqOn || ringOn) postInvalidateOnAnimation()
    }

    init {
        isClickable = true
        isFocusable = true
        render(false, false)
    }
}
