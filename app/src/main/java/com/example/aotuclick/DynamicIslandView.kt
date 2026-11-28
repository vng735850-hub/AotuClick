// Lucian UX/UI Prompt #459 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#459
package com.example.autoclick

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
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

    private class Btn(val id: String, val cx: Float, val cy: Float, val hw: Float, val hh: Float)

    private val baseDensity = resources.displayMetrics.density
    private var density = baseDensity
    private val ui = Handler(Looper.getMainLooper())

    // ---------- Dữ liệu và trạng thái ----------
    private val acts = arrayOf("timer", "music", "call", "note", "click", "prog", "nav", "charge", "idle")
    private val priority = arrayOf("call", "note", "click", "prog", "nav", "music", "timer", "charge", "idle")
    private var act = 0
    private var ex = false

    // Hẹn giờ
    private var timerOn = false
    private var tS = 0
    private var tTotal = 1
    private var tRun = true
    private var tCountUp = false
    private var tCount = 1
    // Nhạc
    private var musicOn = false
    private var mTitle = ""
    private var mArtist = ""
    private var mS = 0
    private var mLen = 0
    private var playing = false
    // Cuộc gọi: 0 không có, 1 đang đổ chuông, 2 đang nói chuyện
    private var callState = 0
    private var callName = ""
    private var cS = 0
    private val callOn: Boolean get() = callState == 2
    // Thông báo
    private var noteOn = false
    private var noteApp = ""
    private var noteTitle = ""
    private var noteText = ""
    private var noteReply = false
    // Auto-click
    private var clickOn = false
    private var clickCount = 0
    private var clickSec = 0
    private var clickPaused = false
    // Tiến trình tải xuống
    private var progOn = false
    private var progApp = ""
    private var progTitle = ""
    private var progPct = 0
    // Chỉ đường
    private var navOn = false
    private var navTitle = ""
    private var navText = ""
    // Pin và sạc
    private var batPct = -1
    private var charging = false
    private var batFull = false
    private var idleTime = ""
    private val clockFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    // Cài đặt
    var alwaysShow = true
    var noticeMs = 5000L
    private var lastActive = false
    private var lastTouch = false

    // ---------- Cử chỉ trên thanh island (chỉnh trong Cài đặt của app) ----------
    private var swipeSw = false          // vuốt trái → bấm giờ
    private var holdTm = false           // giữ → hẹn giờ
    private var shadeSw = false          // vuốt xuống → mở thanh thông báo
    private var tiers: List<IntArray> =
        listOf(intArrayOf(3, 1), intArrayOf(5, 2), intArrayOf(7, 5), intArrayOf(10, 10))  // giây giữ → phút
    private var rotSw = false            // vuốt phải → bật/tắt tự xoay màn hình
    private var dbl1 = false             // nhấn đúp → chế độ 1 mục tiêu
    private var dbl2 = false             // nhấn đúp 2 lần liên tục → chế độ nhiều mục tiêu
    private val gestureAny: Boolean get() = swipeSw || holdTm || shadeSw || rotSw || dbl1 || dbl2

    // Đếm số lần chạm nhanh liên tiếp: 1 = bấm thường, 2 = nhấn đúp, 4 = nhấn đúp 2 lần liên tục
    private val tapWindowMs = 320L
    private var tapCount = 0
    private val tapFire = Runnable {
        val n = tapCount
        tapCount = 0
        when {
            n == 1 -> performClick()
            n == 2 && dbl1 -> {
                buzz()
                onAction?.invoke("mode1")
            }
            n == 4 && dbl2 -> {
                buzz()
                onAction?.invoke("mode2")
            }
            else -> {
            }
        }
    }

    private fun registerTap() {
        if (!dbl1 && !dbl2) {
            performClick()
            return
        }
        tapCount++
        ui.removeCallbacks(tapFire)
        ui.postDelayed(tapFire, tapWindowMs)
    }

    private val holdShowMs = 700L        // giữ quá thời gian này mới hiện xem trước hẹn giờ
    private var holdStart = 0L
    private var holdActive = false
    private var holdIdx = -1
    private var holdMin = 0

    // ---------- Ảnh bìa nhạc thật ----------
    private var artSrc: Bitmap? = null
    private var artS: Bitmap? = null     // bản nhỏ (viên thuốc thu gọn)
    private var artL: Bitmap? = null     // bản lớn (viên thuốc mở rộng)
    private var artShS: BitmapShader? = null
    private var artShL: BitmapShader? = null
    private var accent = 0               // màu chủ đạo của ảnh bìa (0 = chưa có)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artMat = Matrix()
    private val noteCollapse = Runnable {
        if (noteOn && ex && acts[act] == "note") {
            ex = false
            render(false, !reduceMotion())
        }
    }
    private val noteHide = Runnable {
        noteOn = false
        afterChange(null)
    }

    /** Nút bấm trong viên thuốc gửi hành động ra ngoài: pause, cancel, play, prev, next, accept, decline, end, open... */
    var onAction: ((String) -> Unit)? = null

    /** Báo khi viên thuốc có / hết nội dung để cửa sổ chứa bật tắt khả năng nhận chạm. */
    var onActiveChanged: ((Boolean) -> Unit)? = null

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
        Layer("oc", 206f, 36f), Layer("oe", 316f, 84f),
        Layer("nc", 206f, 36f), Layer("ne", 316f, 84f),
        Layer("kc", 206f, 36f), Layer("ke", 316f, 84f),
        Layer("pc", 206f, 36f), Layer("pe", 316f, 84f),
        Layer("vc", 206f, 36f), Layer("ve", 316f, 84f),
        Layer("bc", 206f, 36f), Layer("be", 316f, 84f),
        Layer("ic", 206f, 36f), Layer("hc", 206f, 36f)
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
    private val cBlue = Color.rgb(0x4A, 0xA3, 0xFF)

    private val phonePath = buildPhone()
    private val nextPath = buildNext()
    private val playPath = buildPlay()

    /** Báo cho cửa sổ chứa (dịch vụ lớp phủ) biết viên thuốc đang mở rộng hay thu gọn. */
    var onExpandChanged: ((Boolean) -> Unit)? = null

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
        if (holdActive && !ex) return "hc"
        val a = acts[act]
        val c = when (a) {
            "timer" -> 't'
            "music" -> 'm'
            "note" -> 'n'
            "click" -> 'k'
            "prog" -> 'p'
            "nav" -> 'v'
            "charge" -> 'b'
            "idle" -> 'i'
            else -> if (callOn) 'o' else 'c'
        }
        if (c == 'i') return "ic"
        return c.toString() + (if (ex) 'e' else 'c')
    }

    private fun label(): String {
        val a = acts[act]
        val s = when (a) {
            "timer" -> timerLabel() + " " + fmt(tS)
            "music" -> "Nhạc, " + mTitle
            "note" -> "Thông báo, " + noteApp + ", " + noteTitle
            "click" -> "Auto-click đang chạy, " + clickCount + " lần"
            "prog" -> "Đang tải, " + progTitle + ", " + progPct + " phần trăm"
            "nav" -> "Chỉ đường, " + navTitle
            "charge" -> if (batFull) "Pin đã đầy" else "Đang sạc, " + batPct + " phần trăm"
            "idle" -> "Dynamic Island, " + idleTime
            else -> if (callOn) "Đang gọi với " + dispName() + ", " + fmt(cS) else "Cuộc gọi đến từ " + dispName()
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
        "dismiss" -> "Đóng thông báo"
        "reply" -> "Trả lời nhanh"
        "clickPause" -> if (clickPaused) "Chạy tiếp auto-click" else "Tạm dừng auto-click"
        "clickStop" -> "Dừng auto-click"
        "decline" -> "Từ chối"
        "accept" -> "Nghe máy"
        else -> "Kết thúc cuộc gọi"
    }

    private fun buttons(k: String): List<Btn> = when (k) {
        "te" -> listOf(Btn("pause", -118f, 42f, 24f, 24f), Btn("cancel", -60f, 42f, 24f, 24f))
        "me" -> listOf(Btn("prev", -72f, 122f, 28f, 22f), Btn("play", 0f, 122f, 28f, 22f), Btn("next", 72f, 122f, 28f, 22f))
        "ce" -> listOf(Btn("decline", 56f, 42f, 24f, 24f), Btn("accept", 114f, 42f, 24f, 24f))
        "oe" -> listOf(Btn("end", 114f, 42f, 24f, 24f))
        "ne" -> if (noteReply) listOf(Btn("reply", 66f, 42f, 24f, 24f), Btn("dismiss", 124f, 42f, 24f, 24f))
        else listOf(Btn("dismiss", 124f, 42f, 24f, 24f))
        "ke" -> listOf(Btn("clickPause", -118f, 42f, 24f, 24f), Btn("clickStop", -60f, 42f, 24f, 24f))
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
        onExpandChanged?.invoke(ex)
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
        for (i in 1..acts.size) {
            val c = (act + dir * i + acts.size * acts.size) % acts.size
            if (acts[c] != "idle" && isActive(acts[c])) {
                act = c
                break
            }
        }
        ex = false
        render(true, !reduceMotion())
        if (!quiet) nudge(dir)
    }


    private fun doAct(id: String) {
        when (id) {
            "pause" -> {
                tRun = !tRun
                onAction?.invoke(if (tRun) "timerResume" else "timerPause")
            }
            "cancel" -> {
                timerOn = false
                onAction?.invoke("timerCancel")
                afterChange(null)
            }
            "play" -> {
                playing = !playing
                onAction?.invoke("play")
            }
            "prev" -> onAction?.invoke("prev")
            "next" -> onAction?.invoke("next")
            "accept" -> onAction?.invoke("accept")
            "decline" -> {
                onAction?.invoke("decline")
                callState = 0
                afterChange(null)
            }
            "end" -> {
                onAction?.invoke("end")
                callState = 0
                afterChange(null)
            }
            "dismiss" -> {
                noteOn = false
                ui.removeCallbacks(noteCollapse)
                ui.removeCallbacks(noteHide)
                afterChange(null)
            }
            "reply" -> {
                onAction?.invoke("reply")
                noteOn = false
                ui.removeCallbacks(noteCollapse)
                ui.removeCallbacks(noteHide)
                afterChange(null)
            }
            "clickPause" -> {
                clickPaused = !clickPaused
                onAction?.invoke("clickPause")
            }
            "clickStop" -> onAction?.invoke("clickStop")
        }
        invalidate()
    }

    // ---------- Dữ liệu từ bên ngoài ----------
    private fun isActive(a: String): Boolean = when (a) {
        "timer" -> timerOn
        "music" -> musicOn
        "call" -> callState != 0
        "note" -> noteOn
        "click" -> clickOn
        "prog" -> progOn
        "nav" -> navOn
        "charge" -> charging
        else -> alwaysShow
    }

    private fun anyActive(): Boolean =
        timerOn || musicOn || callState != 0 || noteOn || clickOn || progOn || navOn || charging

    private fun timerLabel(): String =
        if (tCountUp) "Bấm giờ" else "Hẹn giờ" + (if (tCount > 1) " ×" + tCount else "")

    private fun dispName(): String = if (callName.isEmpty()) "Cuộc gọi" else callName

    private fun initialOf(s: String): String =
        if (s.isEmpty()) "?" else Character.toUpperCase(s[0]).toString()

    private fun mAlt(): Boolean = (mTitle.hashCode() and 1) == 1

    private fun ellip(s: String, size: Float, tf: Typeface, maxW: Float): String {
        tp.textSize = size
        tp.typeface = tf
        return TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString()
    }

    /** Chọn hoạt động đang hiển thị sau khi dữ liệu đổi. Ưu tiên: gọi, thông báo, nhạc, hẹn giờ. */
    private fun afterChange(prefer: String?) {
        var pf = prefer
        if (pf != null && pf != "call") {
            if (callState == 1) pf = null
            else if (noteOn && pf != "note") pf = null
        }
        if (pf != null && isActive(pf)) {
            val i = acts.indexOf(pf)
            if (i != act) {
                act = i
                ex = false
            }
        } else if (!isActive(acts[act]) || (acts[act] == "idle" && anyActive())) {
            for (a in priority) {
                if (isActive(a)) {
                    act = acts.indexOf(a)
                    ex = false
                    break
                }
            }
        }
        val active = anyActive()
        if (!active) ex = false
        render(false, !reduceMotion() && (active || alwaysShow))
        lastActive = active
        val touch = wantsTouch()
        if (touch != lastTouch) {
            lastTouch = touch
            onActiveChanged?.invoke(touch)
        }
        invalidate()
    }

    fun setTimer(total: Int, value: Int, running: Boolean, countUp: Boolean, count: Int) {
        val first = !timerOn
        timerOn = true
        tCountUp = countUp
        tCount = count
        tTotal = Math.max(1, total)
        tS = value
        tRun = running
        val target = if (countUp) (value % 60) / 60f else value / tTotal.toFloat()
        if (first) ringT.set(target) else ringT.to(target, 1000, LinearInterpolator(), 0, !reduceMotion())
        afterChange(if (first) "timer" else null)
    }

    fun focusTimer() {
        if (timerOn) afterChange("timer")
    }

    fun clearTimer() {
        timerOn = false
        afterChange(null)
    }

    fun setClick(count: Int, sec: Int, paused: Boolean) {
        val first = !clickOn
        clickOn = true
        clickCount = count
        clickSec = sec
        clickPaused = paused
        afterChange(if (first) "click" else null)
    }

    fun clearClick() {
        if (!clickOn) return
        clickOn = false
        afterChange(null)
    }

    fun setProgress(app: String, title: String, pct: Int) {
        val first = !progOn
        progOn = true
        progApp = app
        progTitle = title
        progPct = pct
        afterChange(if (first) "prog" else null)
    }

    fun clearProgress() {
        if (!progOn) return
        progOn = false
        afterChange(null)
    }

    fun setNav(title: String, text: String) {
        val first = !navOn
        navOn = true
        navTitle = title
        navText = text
        afterChange(if (first) "nav" else null)
    }

    fun clearNav() {
        if (!navOn) return
        navOn = false
        afterChange(null)
    }

    fun setBattery(pct: Int, isCharging: Boolean, isFull: Boolean) {
        batPct = pct
        batFull = isFull
        charging = isCharging
        afterChange(null)
    }

    fun applyPrefs(always: Boolean, noticeMillis: Long) {
        alwaysShow = always
        noticeMs = noticeMillis
        afterChange(null)
    }

    /** Nhận cài đặt cử chỉ. tiers: danh sách (giây giữ, số phút hẹn giờ) đã sắp tăng dần theo giây. */
    fun applyGestures(
        swipe: Boolean, hold: Boolean, shade: Boolean, list: List<IntArray>,
        rotate: Boolean = false, doubleTap: Boolean = false, doubleTap2: Boolean = false
    ) {
        swipeSw = swipe
        holdTm = hold
        shadeSw = shade
        rotSw = rotate
        dbl1 = doubleTap
        dbl2 = doubleTap2
        if (!doubleTap && !doubleTap2) {
            ui.removeCallbacks(tapFire)
            tapCount = 0
        }
        tiers = if (list.isEmpty()) listOf(intArrayOf(3, 1)) else list
    }

    /** Sát mép trên màn hình (không chừa khoảng trống phía trên viên thuốc). */
    fun setFlushTop(on: Boolean) {
        topPad = if (on) 0f else 4f
        invalidate()
    }

    /** Cửa sổ chứa viên thuốc có cần nhận chạm không (có nội dung, hoặc rảnh nhưng có cử chỉ). */
    fun wantsTouch(): Boolean = anyActive() || (gestureAny && alwaysShow)

    fun setSizeScale(f: Float) {
        density = baseDensity * f
        invalidate()
    }

    fun setMusic(title: String, artist: String, isPlaying: Boolean, posSec: Int, lenSec: Int) {
        val first = !musicOn
        musicOn = true
        mTitle = title
        mArtist = artist
        playing = isPlaying
        mS = Math.max(0, posSec)
        mLen = Math.max(0, lenSec)
        progT.set(if (mLen > 0) mS.toFloat() / mLen else 0f)
        afterChange(if (first && callState == 0 && !noteOn) "music" else null)
    }

    fun clearMusic() {
        if (!musicOn) return
        musicOn = false
        playing = false
        setMusicArt(null)
        afterChange(null)
    }

    /** Ảnh bìa thật của bài đang phát (null = dùng hình tạo sẵn). Gọi nhiều lần với cùng ảnh thì bỏ qua. */
    fun setMusicArt(src: Bitmap?) {
        if (src === artSrc) return
        artSrc = src
        artS = null
        artL = null
        artShS = null
        artShL = null
        if (src == null || src.isRecycled || src.width < 2 || src.height < 2) {
            invalidate()
            return
        }
        try {
            val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            if (soft != null) {
                val side = Math.min(soft.width, soft.height)
                val sq = Bitmap.createBitmap(soft, (soft.width - side) / 2, (soft.height - side) / 2, side, side)
                val big = Bitmap.createScaledBitmap(sq, 192, 192, true)
                val small = Bitmap.createScaledBitmap(sq, 96, 96, true)
                accent = dominantColor(small)
                artL = big
                artS = small
                artShL = BitmapShader(big, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                artShS = BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
        } catch (e: Throwable) {
            artS = null
            artL = null
            artShS = null
            artShL = null
        }
        invalidate()
    }

    /** Màu nổi bật nhất của ảnh: chia 12 nhóm màu, chuộng màu sặc sỡ và sáng, rồi làm sáng cho hợp nền đen. */
    private fun dominantColor(b: Bitmap): Int {
        val n = 24
        val sm = Bitmap.createScaledBitmap(b, n, n, true)
        val px = IntArray(n * n)
        sm.getPixels(px, 0, n, 0, 0, n, n)
        val hsv = FloatArray(3)
        val wsum = FloatArray(12)
        val rs = FloatArray(12)
        val gs = FloatArray(12)
        val bs = FloatArray(12)
        var ar = 0f
        var ag = 0f
        var ab = 0f
        var cnt = 0
        for (p in px) {
            if (Color.alpha(p) < 128) continue
            Color.colorToHSV(p, hsv)
            val w = hsv[1] * hsv[2] + 0.001f
            val i = (hsv[0] / 30f).toInt().coerceIn(0, 11)
            wsum[i] += w
            rs[i] += Color.red(p) * w
            gs[i] += Color.green(p) * w
            bs[i] += Color.blue(p) * w
            ar += Color.red(p)
            ag += Color.green(p)
            ab += Color.blue(p)
            cnt++
        }
        if (cnt == 0) return cPink
        var best = 0
        for (i in 1 until 12) {
            if (wsum[i] > wsum[best]) best = i
        }
        val useBucket = wsum[best] > 4f
        val r = if (useBucket) rs[best] / wsum[best] else ar / cnt
        val g = if (useBucket) gs[best] / wsum[best] else ag / cnt
        val bl = if (useBucket) bs[best] / wsum[best] else ab / cnt
        Color.colorToHSV(Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), bl.toInt().coerceIn(0, 255)), hsv)
        if (hsv[1] > 0.12f) hsv[1] = Math.max(hsv[1], 0.55f)
        hsv[2] = Math.max(hsv[2], 0.85f)
        return Color.HSVToColor(hsv)
    }

    private fun accentColor(): Int = if (artS != null && accent != 0) accent else cPink

    /** state: 0 hết gọi, 1 đang đổ chuông, 2 đang nói chuyện. name rỗng thì giữ tên cũ. */
    fun setCall(state: Int, name: String, sec: Int) {
        val prev = callState
        callState = state
        if (state == 0) {
            callName = ""
            afterChange(null)
            return
        }
        if (name.isNotEmpty()) callName = name
        cS = sec
        afterChange("call")
        if (state == 1 && prev != 1) {
            ex = true
            render(true, !reduceMotion())
        } else if (state == 2 && prev != 2) {
            ex = false
            render(false, !reduceMotion())
        }
    }

    fun showNotice(app: String, title: String, text: String, canReply: Boolean = false) {
        noteApp = app
        noteTitle = title
        noteText = text
        noteReply = canReply
        noteOn = true
        ui.removeCallbacks(noteCollapse)
        ui.removeCallbacks(noteHide)
        if (callState == 0) {
            afterChange("note")
            ex = true
            render(true, !reduceMotion())
        } else {
            afterChange(null)
        }
        ui.postDelayed(noteCollapse, noticeMs)
        ui.postDelayed(noteHide, noticeMs + 3000L)
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (acts[act] == "idle" && !ex) return true   // viên thuốc rảnh không có bản mở rộng
        if (ex && acts[act] == "note") {
            onAction?.invoke("open")
            noteOn = false
            ui.removeCallbacks(noteCollapse)
            ui.removeCallbacks(noteHide)
            afterChange(null)
            return true
        }
        setEx(!ex)
        return true
    }

    // ---------- Đồng hồ 1 giây ----------
    private val clock = object : Runnable {
        override fun run() {
            idleTime = clockFmt.format(java.util.Date())
            if (musicOn && playing) {
                mS += 1
                if (mLen > 0 && mS > mLen) mS = mLen
            }
            if (callState == 2) cS += 1
            val animate = !reduceMotion()
            progT.to(if (mLen > 0) mS.toFloat() / mLen else 0f, 1000, LinearInterpolator(), 0, animate)
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
        ui.removeCallbacks(holdTick)
        ui.removeCallbacks(tapFire)
        tapCount = 0
        holdActive = false
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
    private var topPad = 4f
    private var downX = 0f
    private var downBtn: String? = null
    private var bodyDown = false
    private var dragDx = 0f
    private var pressOff = false

    private fun islandCx(): Float = width / density / 2f + offX.value

    private fun inIsland(x: Float, y: Float): Boolean {
        if (!lastActive && !(gestureAny && alwaysShow)) return false
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

    private var downY = 0f
    private var dragDy = 0f

    /** Rung nhẹ báo đã nhận cử chỉ / đã đạt một mốc hẹn giờ. */
    private fun buzz() {
        try {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        } catch (e: Throwable) {
        }
    }

    private val holdTick = object : Runnable {
        override fun run() {
            if (!bodyDown || pressOff || ex || !holdTm) {
                stopHold()
                return
            }
            val el = SystemClock.uptimeMillis() - holdStart
            var idx = -1
            for (i in tiers.indices) {
                if (el >= tiers[i][0] * 1000L) idx = i
            }
            if (el >= holdShowMs && !holdActive) {
                holdActive = true
                buzz()
                render(false, !reduceMotion())
            }
            if (idx != holdIdx) {
                holdIdx = idx
                holdMin = if (idx >= 0) tiers[idx][1] else 0
                if (idx >= 0) buzz()
            }
            invalidate()
            ui.postDelayed(this, 80L)
        }
    }

    private fun startHold() {
        stopHold()
        if (holdTm && !ex && tiers.isNotEmpty()) {
            holdStart = SystemClock.uptimeMillis()
            holdIdx = -1
            holdMin = 0
            ui.postDelayed(holdTick, 120L)
        }
    }

    private fun stopHold() {
        ui.removeCallbacks(holdTick)
        val was = holdActive
        holdActive = false
        holdIdx = -1
        holdMin = 0
        if (was) render(false, !reduceMotion())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x / density
        val y = e.y / density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inIsland(x, y)) return false
                downX = x
                downY = y
                dragDx = 0f
                dragDy = 0f
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
                    startHold()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (bodyDown) {
                    dragDx = x - downX
                    dragDy = y - downY
                    // Thu gọn: cửa sổ chỉ chừa ~17dp mỗi bên nên giới hạn độ kéo
                    val lim = if (ex) 48f else 16f
                    val r = Math.signum(dragDx) * Math.min(lim, Math.abs(dragDx) * 0.4f)
                    offX.set(r)
                    if ((Math.abs(dragDx) > 8f || Math.abs(dragDy) > 10f) && !pressOff) {
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
                    val dv = dragDy
                    val shown = holdActive
                    val mins = holdMin
                    stopHold()
                    if (up) {
                        if (shown && !pressOff) {
                            // Thả tay sau khi giữ: đủ mốc thì hẹn giờ, chưa đủ mốc thì bỏ qua
                            if (mins > 0) {
                                buzz()
                                onAction?.invoke("timerSet:" + mins)
                            }
                        } else if (!ex && swipeSw && d < -36f && Math.abs(dv) < 30f) {
                            // Vuốt trái: tự bấm giờ
                            nudge(-1)
                            buzz()
                            onAction?.invoke("stopwatch")
                        } else if (!ex && rotSw && d > 36f && Math.abs(dv) < 30f) {
                            // Vuốt phải: bật/tắt tự xoay màn hình
                            nudge(1)
                            buzz()
                            onAction?.invoke("rotate")
                        } else if (!ex && shadeSw && dv > 40f && Math.abs(d) < 24f) {
                            // Vuốt xuống: mở thanh thông báo (island đang chắn vùng kéo thanh trạng thái)
                            buzz()
                            onAction?.invoke("shade")
                        } else if (Math.abs(d) > 36f) {
                            go(if (d < 0f) 1 else -1, true)
                        } else if (Math.abs(d) <= 8f && Math.abs(dv) <= 10f) {
                            registerTap()
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

    private fun drawRing(c: Canvas, cx: Float, cy: Float, r: Float, sw: Float, frac: Float, color: Int) {
        stroke.strokeWidth = sw
        stroke.color = rgba(color, 0.28f)
        c.drawCircle(cx, cy, r, stroke)
        val sweep = 360f * frac.coerceIn(0f, 1f)
        if (sweep > 0.5f) {
            stroke.color = color
            rect.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rect, -90f, sweep, false, stroke)
        }
    }

    private fun drawNavArrow(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 2.8f
        icon24(c, cx, cy, size, 0f) {
            c.drawLine(12f, 19f, 12f, 5f, stroke)
            c.drawLine(12f, 5f, 6f, 11f, stroke)
            c.drawLine(12f, 5f, 18f, 11f, stroke)
        }
    }

    private fun drawReply(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = 2.8f
        icon24(c, cx, cy, size, 0f) {
            c.drawLine(4f, 12f, 20f, 12f, stroke)
            c.drawLine(4f, 12f, 10f, 6f, stroke)
            c.drawLine(4f, 12f, 10f, 18f, stroke)
        }
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

    /** Vầng sáng mờ theo màu chủ đạo của ảnh bìa (chỉ khi có ảnh thật). */
    private fun drawGlow(c: Canvas, cx: Float, cy: Float, r: Float, step: Float) {
        if (artS == null) return
        fill.shader = null
        fill.color = rgba(accentColor(), 0.07f)
        for (i in 0 until 5) {
            c.drawCircle(cx, cy, r + i * step, fill)
        }
    }

    private fun drawRealArt(c: Canvas, x: Float, y: Float, s: Float, r: Float, bmp: Bitmap, sh: BitmapShader) {
        c.save()
        c.translate(x, y)
        rect.set(0f, 0f, s, s)
        artMat.reset()
        artMat.setScale(s / bmp.width, s / bmp.height)
        sh.setLocalMatrix(artMat)
        artPaint.shader = sh
        c.drawRoundRect(rect, r, r, artPaint)
        artPaint.shader = null
        // Viền theo màu chủ đạo
        stroke.color = rgba(accentColor(), 0.9f)
        stroke.strokeWidth = if (s > 40f) 1.6f else 1.2f
        rect.set(0.6f, 0.6f, s - 0.6f, s - 0.6f)
        c.drawRoundRect(rect, r, r, stroke)
        c.restore()
    }

    private fun drawArt(c: Canvas, x: Float, y: Float, s: Float, r: Float, alt: Boolean) {
        val big = s > 40f
        val bmp = if (big) artL else artS
        val bsh = if (big) artShL else artShS
        if (bmp != null && bsh != null) {
            drawRealArt(c, x, y, s, r, bmp, bsh)
            return
        }
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

    private fun drawAvatar(c: Canvas, cx: Float, cy: Float, d: Float, textSize: Float, initial: String = initialOf(callName)) {
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
        drawText(c, initial, cx, cy, textSize, Color.WHITE, Paint.Align.CENTER, tfBold)
    }

    /** Bốn thanh equalizer: mỗi thanh co giãn qua lại theo chu kỳ riêng, đứng yên khi tạm dừng. */
    private fun drawEq(c: Canvas, right: Float, cy: Float, h: Float) {
        val durs = floatArrayOf(900f, 620f, 780f, 550f)
        val delays = floatArrayOf(0f, -300f, -500f, -100f)
        val still = reduceMotion()
        fill.shader = null
        fill.color = accentColor()
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
                drawText(c, timerLabel(), 138f, 22f, 12f, rgba(cOrange, 0.85f), Paint.Align.RIGHT, tfBold)
                drawText(c, fmt(tS), 138f, 49f, 38f, cOrange, Paint.Align.RIGHT, tfLight)
            }
            "mc" -> {
                drawGlow(c, -83.5f, 18f, 12f, 3.5f)
                drawArt(c, -95f, 6.5f, 23f, 6f, mAlt())
                drawEq(c, 89f, 18f, 16f)
            }
            "me" -> {
                drawGlow(c, -111f, 43f, 28f, 9f)
                drawArt(c, -138f, 16f, 54f, 13f, mAlt())
                val maxW = 106.5f - (-72f)
                drawText(c, ellip(mTitle, 15.5f, tfBold, maxW), -72f, 34.5f, 15.5f, white, Paint.Align.LEFT, tfBold)
                drawText(c, ellip(mArtist, 13f, tfReg, maxW), -72f, 53f, 13f, white55, Paint.Align.LEFT, tfReg)
                drawEq(c, 138f, 30f, 20f)
                // Thanh tiến độ
                drawText(c, fmt(mS), -138f, 89.5f, 11f, Color.argb(128, 255, 255, 255), Paint.Align.LEFT, tfReg)
                drawText(c, (if (mLen > 0) "-" + fmt(mLen - mS) else ""), 138f, 89.5f, 11f, Color.argb(128, 255, 255, 255), Paint.Align.RIGHT, tfReg)
                fill.shader = null
                fill.color = Color.argb(51, 255, 255, 255)
                rect.set(-98f, 87f, 98f, 92f)
                c.drawRoundRect(rect, 2.5f, 2.5f, fill)
                val pw = 196f * progT.value.coerceIn(0f, 1f)
                if (pw > 0.5f) {
                    fill.color = if (artS != null) rgba(accentColor(), 0.95f) else Color.argb(224, 255, 255, 255)
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
                drawText(c, ellip(dispName(), 13f, tfBold, 105f), -66f, 18f, 13f, white, Paint.Align.LEFT, tfBold)
                drawPhone(c, 79f, 18f, 20f, cGreen, phoneAngle())
            }
            "ce" -> {
                drawAvatar(c, -118f, 42f, 48f, 19f)
                drawText(c, "Cuộc gọi đến", -82f, 31f, 12.5f, white55, Paint.Align.LEFT, tfReg)
                drawText(c, ellip(dispName(), 18f, tfBold, 112f), -82f, 52f, 18f, white, Paint.Align.LEFT, tfBold)
                roundBtn(c, "decline", 56f, 42f, cRed) { drawPhone(c, 56f, 42f, 22f, white, 135f) }
                roundBtn(c, "accept", 114f, 42f, cGreen) { drawPhone(c, 114f, 42f, 22f, white, 0f) }
            }
            "oc" -> {
                drawPhone(c, -85f, 18f, 20f, cGreen, 0f)
                drawText(c, ellip(dispName(), 13f, tfBold, 95f), -68f, 18f, 13f, white, Paint.Align.LEFT, tfBold)
                drawText(c, fmt(cS), 89f, 18f, 15f, cGreen, Paint.Align.RIGHT, tfBold)
            }
            "oe" -> {
                drawAvatar(c, -118f, 42f, 48f, 19f)
                drawText(c, ellip(dispName(), 18f, tfBold, 140f), -82f, 33f, 18f, white, Paint.Align.LEFT, tfBold)
                drawText(c, fmt(cS), -82f, 53f, 13f, cGreen, Paint.Align.LEFT, tfBold)
                roundBtn(c, "end", 114f, 42f, cRed) { drawPhone(c, 114f, 42f, 22f, white, 135f) }
            }
            "nc" -> {
                drawAvatar(c, -84f, 18f, 23f, 11f, initialOf(noteApp))
                drawText(c, ellip(if (noteTitle.isEmpty()) noteApp else noteTitle, 13f, tfBold, 150f), -68f, 18f, 13f, white, Paint.Align.LEFT, tfBold)
            }
            "ne" -> {
                drawAvatar(c, -118f, 42f, 48f, 19f, initialOf(noteApp))
                val tw = if (noteReply) 115f else 150f
                drawText(c, ellip(noteApp, 12f, tfReg, tw), -82f, 24f, 12f, white55, Paint.Align.LEFT, tfReg)
                drawText(c, ellip(noteTitle, 16f, tfBold, tw), -82f, 44f, 16f, white, Paint.Align.LEFT, tfBold)
                drawText(c, ellip(noteText, 13f, tfReg, tw), -82f, 64f, 13f, white55, Paint.Align.LEFT, tfReg)
                if (noteReply) {
                    roundBtn(c, "reply", 66f, 42f, Color.argb(43, 255, 255, 255)) { drawReply(c, 66f, 42f, 22f, white) }
                }
                roundBtn(c, "dismiss", 124f, 42f, Color.argb(43, 255, 255, 255)) { drawX(c, 124f, 42f, 22f, white) }
            }
            "kc" -> {
                drawPlayTri(c, -85f, 18f, 18f, cGreen, false)
                drawText(c, if (clickPaused) "Tạm dừng" else "Auto-click", -68f, 18f, 13f, white, Paint.Align.LEFT, tfBold)
                drawText(c, clickCount.toString(), 89f, 18f, 15f, cGreen, Paint.Align.RIGHT, tfBold)
            }
            "ke" -> {
                roundBtn(c, "clickPause", -118f, 42f, rgba(cGreen, 0.24f)) {
                    if (clickPaused) drawPlayTri(c, -118f, 42f, 22f, cGreen, false) else drawBar2(c, -118f, 42f, 22f, cGreen)
                }
                roundBtn(c, "clickStop", -60f, 42f, cRed) {
                    fill.shader = null
                    fill.color = white
                    rect.set(-67f, 35f, -53f, 49f)
                    c.drawRoundRect(rect, 3f, 3f, fill)
                }
                drawText(c, "Auto-click · " + fmt(clickSec), 138f, 22f, 12f, rgba(cGreen, 0.9f), Paint.Align.RIGHT, tfBold)
                drawText(c, clickCount.toString(), 138f, 49f, 38f, white, Paint.Align.RIGHT, tfLight)
            }
            "pc" -> {
                drawRing(c, -84f, 18f, 8.25f, 2.38f, progPct / 100f, cBlue)
                drawText(c, ellip(if (progTitle.isEmpty()) progApp else progTitle, 13f, tfBold, 105f), -68f, 18f, 13f, white, Paint.Align.LEFT, tfBold)
                drawText(c, progPct.toString() + "%", 89f, 18f, 15f, cBlue, Paint.Align.RIGHT, tfBold)
            }
            "pe" -> {
                drawRing(c, -118f, 42f, 18f, 4f, progPct / 100f, cBlue)
                drawText(c, progPct.toString(), -118f, 42f, 13f, white, Paint.Align.CENTER, tfBold)
                drawText(c, ellip(progApp, 12f, tfReg, 210f), -82f, 28f, 12f, white55, Paint.Align.LEFT, tfReg)
                drawText(c, ellip(progTitle, 16f, tfBold, 210f), -82f, 48f, 16f, white, Paint.Align.LEFT, tfBold)
                drawText(c, if (progPct >= 100) "Hoàn tất" else "Đang tải · " + progPct + "%", -82f, 66f, 13f, cBlue, Paint.Align.LEFT, tfBold)
            }
            "vc" -> {
                drawNavArrow(c, -84f, 18f, 20f, white)
                drawText(c, ellip(if (navTitle.isEmpty()) navText else navTitle, 13f, tfBold, 150f), -68f, 18f, 13f, white, Paint.Align.LEFT, tfBold)
            }
            "ve" -> {
                fill.shader = null
                fill.color = Color.argb(43, 255, 255, 255)
                c.drawCircle(-118f, 42f, 24f, fill)
                drawNavArrow(c, -118f, 42f, 26f, white)
                drawText(c, "Chỉ đường", -82f, 26f, 12f, white55, Paint.Align.LEFT, tfReg)
                drawText(c, ellip(navTitle, 17f, tfBold, 215f), -82f, 46f, 17f, white, Paint.Align.LEFT, tfBold)
                drawText(c, ellip(navText, 13f, tfReg, 215f), -82f, 66f, 13f, white55, Paint.Align.LEFT, tfReg)
            }
            "bc" -> {
                drawRing(c, -84f, 18f, 8.25f, 2.38f, Math.max(0, batPct) / 100f, cGreen)
                drawText(c, if (batFull) "Đã đầy" else "Đang sạc", -68f, 18f, 13f, white, Paint.Align.LEFT, tfBold)
                drawText(c, Math.max(0, batPct).toString() + "%", 89f, 18f, 15f, cGreen, Paint.Align.RIGHT, tfBold)
            }
            "be" -> {
                drawRing(c, -118f, 42f, 18f, 4f, Math.max(0, batPct) / 100f, cGreen)
                drawText(c, Math.max(0, batPct).toString(), -118f, 42f, 13f, white, Paint.Align.CENTER, tfBold)
                drawText(c, if (batFull) "Pin đã đầy" else "Đang sạc", -82f, 31f, 12.5f, white55, Paint.Align.LEFT, tfReg)
                drawText(c, Math.max(0, batPct).toString() + "%", -82f, 55f, 26f, cGreen, Paint.Align.LEFT, tfBold)
            }
            "hc" -> {
                // Xem trước hẹn giờ khi đang giữ: vòng tiến độ tới mốc kế + số phút đã đạt
                val el = if (holdActive) SystemClock.uptimeMillis() - holdStart else 0L
                var frac = 1f
                for (i in tiers.indices) {
                    val t = tiers[i][0] * 1000L
                    if (el < t) {
                        val p0 = if (i > 0) tiers[i - 1][0] * 1000L else 0L
                        frac = (el - p0).toFloat() / (t - p0).toFloat()
                        break
                    }
                }
                val cx = -84f
                val cy = 18f
                val rr = 8.25f
                stroke.strokeWidth = 2.38f
                stroke.color = rgba(cOrange, 0.28f)
                c.drawCircle(cx, cy, rr, stroke)
                val sweep = 360f * frac.coerceIn(0f, 1f)
                if (sweep > 0.5f) {
                    stroke.color = cOrange
                    rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
                    c.drawArc(rect, -90f, sweep, false, stroke)
                }
                val msg = if (holdMin > 0) "Hẹn giờ " + holdMin + " phút"
                else "Giữ " + tiers[0][0] + "s → " + tiers[0][1] + " phút"
                drawText(c, ellip(msg, 13f, tfBold, 112f), -68f, 18f, 13f, white, Paint.Align.LEFT, tfBold)
                drawText(c, (el / 1000L).toString() + "s", 89f, 18f, 15f, cOrange, Paint.Align.RIGHT, tfBold)
            }
            "ic" -> {
                val col = if (batPct in 0..19) cOrange else white
                drawRing(c, -84f, 18f, 8.25f, 2.38f, Math.max(0, batPct) / 100f, col)
                drawText(c, if (batPct >= 0) batPct.toString() + "%" else "", -68f, 18f, 12.5f, white55, Paint.Align.LEFT, tfReg)
                drawText(c, idleTime, 89f, 18f, 15f, white, Paint.Align.RIGHT, tfBold)
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
        if (!lastActive && !alwaysShow) return
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
        if (eqOn || ringOn || holdActive) postInvalidateOnAnimation()
    }

    init {
        isClickable = true
        isFocusable = true
        idleTime = clockFmt.format(java.util.Date())
        afterChange(null)
    }
}
