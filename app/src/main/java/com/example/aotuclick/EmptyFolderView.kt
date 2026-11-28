// Lucian UX/UI Prompt #088 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#088
package com.example.autoclick

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.tan

/**
 * Thư mục rỗng nổi nhẹ (trạng thái "chưa có kịch bản").
 * - setHover(true): thư mục hé mở, giấy ló ra (tương ứng hover vào nút mẫu).
 * - setEating(true) + gulp(): nuốt tờ giấy bay vào.
 * - drop(): rơi xuống lại khi xoá hết.
 * Hình vẽ theo hệ toạ độ 168x128 của mẫu, các giá trị thời gian/đường cong giữ nguyên.
 */
class EmptyFolderView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val spring = PathInterpolator(.34f, 1.45f, .64f, 1f)
    private val ease = PathInterpolator(.2f, .8f, .2f, 1f)

    // ---- Hình dạng (toạ độ viewBox 168x128) ----
    private val backPath = Path().apply {
        moveTo(16f, 26f)
        arcTo(RectF(16f, 16f, 36f, 36f), 180f, 90f)
        lineTo(60f, 16f)
        arcTo(RectF(52f, 16f, 68f, 32f), -90f, 48f)
        lineTo(74f, 27f)
        lineTo(142f, 27f)
        arcTo(RectF(132f, 27f, 152f, 47f), -90f, 90f)
        lineTo(152f, 103f)
        arcTo(RectF(132f, 103f, 152f, 123f), 0f, 90f)
        lineTo(26f, 113f)
        arcTo(RectF(16f, 103f, 36f, 123f), 90f, 90f)
        close()
    }
    private val frontPath = Path().apply {
        moveTo(10f, 50f)
        arcTo(RectF(10f, 40f, 30f, 60f), 180f, 90f)
        lineTo(148f, 40f)
        arcTo(RectF(138f, 40f, 158f, 60f), -90f, 94.6f)
        lineTo(153f, 102.8f)
        arcTo(RectF(133f, 92f, 153f, 112f), 4.6f, 85.4f)
        lineTo(25f, 112f)
        arcTo(RectF(15f, 92f, 35f, 112f), 90f, 90f)
        close()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadowShader: RadialGradient? = null
    private var shadowShaderColor = 0

    // ---- Tư thế: [skewDeg, scaleY] / [dịch Y, góc xoay] ----
    private val frontPose = floatArrayOf(0f, 1f)
    private val aPose = floatArrayOf(0f, 0f)
    private val bPose = floatArrayOf(0f, 0f)
    private val frontTo = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(-7f, .86f), floatArrayOf(-12f, .74f))
    private val aTo = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(-10f, -5f), floatArrayOf(-14f, -7f))
    private val bTo = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(-6f, 4f), floatArrayOf(-10f, 6f))
    private val glides = HashMap<FloatArray, ValueAnimator>()
    private var hovering = false
    private var eating = false

    // ---- Nổi lơ lửng 5s, nuốt, rơi ----
    private var floatP = 0f
    private var floatAnim: ValueAnimator? = null
    private var gulpT = -1f
    private var gulpAnim: ValueAnimator? = null
    private var dropP = 1f
    private var dropAnim: ValueAnimator? = null

    fun setHover(h: Boolean) {
        hovering = h
        applyPose()
    }

    fun setEating(e: Boolean) {
        eating = e
        applyPose()
    }

    private fun applyPose() {
        val s = if (eating) 2 else if (hovering) 1 else 0
        glide(frontPose, frontTo[s], 0L)
        glide(aPose, aTo[s], 0L)
        glide(bPose, bTo[s], 40L)
    }

    private fun glide(cur: FloatArray, to: FloatArray, delay: Long) {
        glides[cur]?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            to.copyInto(cur)
            invalidate()
            return
        }
        val from = cur.copyOf()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 550L
        a.startDelay = delay
        a.interpolator = spring
        a.addUpdateListener {
            val t = it.animatedValue as Float
            for (i in cur.indices) cur[i] = from[i] + (to[i] - from[i]) * t
            invalidate()
        }
        glides[cur] = a
        a.start()
    }

    /** Nuốt: svg phồng rồi co lại (600ms). */
    fun gulp() {
        gulpAnim?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 600L
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { gulpT = it.animatedValue as Float; invalidate() }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                gulpT = -1f
                invalidate()
            }
        })
        gulpAnim = a
        a.start()
    }

    /** Rơi xuống lại (850ms, lò xo): từ trên cao -46, thu nhỏ .85, trong suốt. */
    fun drop() {
        dropAnim?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            dropP = 1f
            invalidate()
            return
        }
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 850L
        a.interpolator = spring
        a.addUpdateListener { dropP = it.animatedValue as Float; invalidate() }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                dropP = 1f
                invalidate()
            }
        })
        dropP = 0f
        dropAnim = a
        a.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ValueAnimator.areAnimatorsEnabled()) {
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 5000L
            a.repeatCount = ValueAnimator.INFINITE
            a.interpolator = LinearInterpolator()
            a.addUpdateListener { floatP = it.animatedValue as Float; invalidate() }
            floatAnim = a
            a.start()
        }
    }

    override fun onDetachedFromWindow() {
        floatAnim?.cancel()
        gulpAnim?.cancel()
        dropAnim?.cancel()
        glides.values.forEach { it.cancel() }
        glides.clear()
        super.onDetachedFromWindow()
    }

    /** 0 → 1 → 0 trong một chu kỳ, mỗi nửa chu kỳ theo ease-in-out. */
    private fun bump(): Float {
        val tri = if (floatP < .5f) floatP * 2f else (1f - floatP) * 2f
        return ((1.0 - cos(PI * tri)) / 2.0).toFloat()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Nội suy theo các mốc (offset, x, y), mỗi đoạn dùng đường cong ease. */
    private fun gulpScale(t: Float): FloatArray {
        val o = floatArrayOf(0f, .3f, .62f, 1f)
        val sx = floatArrayOf(1f, 1.08f, .97f, 1f)
        val sy = floatArrayOf(1f, .88f, 1.05f, 1f)
        var i = 0
        while (i < 2 && t > o[i + 1]) i++
        val u = ease.getInterpolation(((t - o[i]) / (o[i + 1] - o[i])).coerceIn(0f, 1f))
        return floatArrayOf(lerp(sx[i], sx[i + 1], u), lerp(sy[i], sy[i + 1], u))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ox = (width - 168f * d) / 2f
        val b = bump()

        // Bóng dưới thư mục: co lại và mờ đi khi thư mục nổi lên
        val ink = AppTheme.text
        if (shadowShader == null || shadowShaderColor != ink) {
            val c = Color.argb(41, Color.red(ink), Color.green(ink), Color.blue(ink))
            shadowShader = RadialGradient(0f, 0f, 50f * d, c, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            shadowShaderColor = ink
            shadowPaint.shader = shadowShader
        }
        shadowPaint.alpha = (255 * (1f - .4f * b)).toInt()
        canvas.save()
        canvas.translate(ox + 84f * d, 133f * d)
        canvas.scale(lerp(1f, .8f, b), .1f)
        canvas.drawCircle(0f, 0f, 50f * d, shadowPaint)
        canvas.restore()

        // Thư mục
        val floatY = -7f * d * b
        val alpha = if (dropP < 1f) dropP.coerceIn(0f, 1f) else 1f
        canvas.save()
        canvas.translate(ox, floatY)
        canvas.scale(d, d)
        if (alpha < 1f) canvas.saveLayerAlpha(-200f, -200f, 400f, 400f, (alpha * 255).toInt())

        // Biến đổi cấp svg (gốc 50% 92%)
        var gx = 1f
        var gy = 1f
        if (gulpT >= 0f) {
            val g = gulpScale(gulpT)
            gx = g[0]; gy = g[1]
        }
        val ds = if (dropP < 1f || dropP > 1f) lerp(.85f, 1f, dropP) else 1f
        val dty = if (dropP != 1f) -46f * (1f - dropP) else 0f
        canvas.translate(84f, 117.76f)
        canvas.translate(0f, dty)
        canvas.scale(gx * ds, gy * ds)
        canvas.translate(-84f, -117.76f)

        // Lưng thư mục
        fill.color = Color.parseColor("#0B9B70")
        canvas.drawPath(backPath, fill)

        // Giấy a
        paper(canvas, 76f, 98f, aPose) {
            fill.color = Color.parseColor("#EEF1F7")
            canvas.drawRoundRect(30f, 26f, 122f, 98f, 6f, 6f, fill)
            line.color = Color.parseColor("#C9D1DF")
            canvas.drawLine(42f, 40f, 90f, 40f, line)
            canvas.drawLine(42f, 50f, 104f, 50f, line)
            canvas.drawLine(42f, 60f, 78f, 60f, line)
        }
        // Giấy b (trễ 40ms)
        paper(canvas, 95f, 100f, bPose) {
            fill.color = Color.WHITE
            canvas.drawRoundRect(50f, 32f, 140f, 100f, 6f, 6f, fill)
            line.color = Color.parseColor("#D3DAE6")
            canvas.drawLine(62f, 46f, 114f, 46f, line)
            canvas.drawLine(62f, 56f, 126f, 56f, line)
            canvas.drawLine(62f, 66f, 92f, 66f, line)
        }

        // Mặt trước (nghiêng + xẹp khi mở)
        canvas.save()
        canvas.translate(84f, 112f)
        canvas.skew(tan(Math.toRadians(frontPose[0].toDouble())).toFloat(), 0f)
        canvas.scale(1f, frontPose[1])
        canvas.translate(-84f, -112f)
        fill.color = Color.parseColor("#16C08C")
        canvas.drawPath(frontPath, fill)
        line.color = Color.argb(89, 255, 255, 255)
        line.strokeWidth = 2f
        canvas.drawLine(22f, 47f, 146f, 47f, line)
        line.strokeWidth = 3f
        fill.color = Color.argb(97, 255, 255, 255)
        canvas.drawRoundRect(66f, 72f, 102f, 80f, 4f, 4f, fill)
        canvas.restore()

        if (alpha < 1f) canvas.restore()
        canvas.restore()
    }

    private fun paper(c: Canvas, ox: Float, oy: Float, pose: FloatArray, draw: () -> Unit) {
        c.save()
        c.translate(ox, oy)
        c.translate(0f, pose[0])
        c.rotate(pose[1])
        c.translate(-ox, -oy)
        draw()
        c.restore()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((168 * d).toInt(), (138 * d).toInt())
    }
}

/** Tờ giấy nhỏ bay từ nút mẫu vào thư mục (26x32dp, đường bay 760ms). */
class FlyPaperView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val bez = PathInterpolator(.45f, 0f, .25f, 1f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var color = Color.rgb(16, 185, 129)
    private var anim: ValueAnimator? = null

    init {
        visibility = GONE
        elevation = 4f * d
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, 5f * d)
            }
        }
        isClickable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((26 * d).toInt(), (32 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 5f * d
        canvas.save()
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW) }
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.color = Color.rgb(0xCB, 0xD3, 0xE1)
        var y = 12f
        while (y < 32f) {
            canvas.drawRect(5f * d, y * d, 21f * d, (y + 2f) * d, paint)
            y += 14f
        }
        paint.color = color
        canvas.drawRect(0f, 0f, w, 7f * d, paint)
        canvas.restore()
    }

    /**
     * Bay từ (x0, y0) tới (x0+dx, y0+dy), toạ độ tâm tờ giấy tính trong khung chứa.
     * Các mốc: 0 / .45 / .82 / 1 giống mẫu.
     */
    fun launch(x0: Float, y0: Float, dx: Float, dy: Float, c: Int, onEnd: () -> Unit) {
        color = c
        invalidate()
        anim?.cancel()
        val o = floatArrayOf(0f, .45f, .82f, 1f)
        // tx, ty, xoay, tỉ lệ, độ đục
        val k = arrayOf(
            floatArrayOf(0f, 0f, -10f, .5f, 0f),
            floatArrayOf(dx * .45f, dy * .45f - 70f * d, 12f, 1.1f, 1f),
            floatArrayOf(dx, dy, 0f, .8f, 1f),
            floatArrayOf(dx, dy + 26f * d, 0f, .6f, 0f)
        )
        visibility = VISIBLE
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 760L
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            val t = it.animatedValue as Float
            var i = 0
            while (i < 2 && t > o[i + 1]) i++
            val u = bez.getInterpolation(((t - o[i]) / (o[i + 1] - o[i])).coerceIn(0f, 1f))
            fun v(j: Int) = k[i][j] + (k[i + 1][j] - k[i][j]) * u
            translationX = x0 - 13f * d + v(0)
            translationY = y0 - 16f * d + v(1)
            rotation = v(2)
            scaleX = v(3)
            scaleY = v(3)
            alpha = v(4).coerceIn(0f, 1f)
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                visibility = GONE
                onEnd()
            }
        })
        anim = a
        a.start()
    }
}
