package com.example.autoclick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.hypot

/**
 * Lớp phủ toàn màn hình, không nhận chạm, vẽ dây nối từ điểm bắt đầu (N)
 * tới điểm dừng (P) của mọi nút Kéo, có mũi tên chỉ hướng về P.
 * Toạ độ các đoạn là toạ độ cửa sổ (cùng hệ với WindowManager.LayoutParams.x/y).
 */
class SwipeLinesView(context: Context) : View(context) {

    /** Một đoạn dây: tâm điểm bắt đầu, tâm điểm dừng và bán kính của hai chấm. */
    class Seg(
        val sx: Float, val sy: Float, val ex: Float, val ey: Float,
        val startR: Float, val endR: Float
    )

    /** Nguồn dữ liệu: được gọi mỗi lần vẽ nên kéo chấm là dây đi theo ngay. */
    var segments: () -> List<Seg> = { emptyList() }

    private val d = resources.displayMetrics.density
    private val lineColor = ActionType.SWIPE.color

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(210, 255, 255, 255)
        strokeWidth = 7f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = lineColor
        strokeWidth = 3.5f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = lineColor
    }
    private val arrowHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(210, 255, 255, 255)
        strokeWidth = 3f * d
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (s in segments()) drawSeg(canvas, s)
    }

    private fun drawSeg(c: Canvas, s: Seg) {
        val dx = s.ex - s.sx
        val dy = s.ey - s.sy
        val len = hypot(dx, dy)
        // Hai chấm chồng lên nhau thì không có chỗ để vẽ dây
        if (len <= s.startR + s.endR + 2f * d) return
        val ux = dx / len
        val uy = dy / len

        // Dây đi từ mép chấm N tới mép chấm P (mũi tên chạm đúng mép P)
        val x0 = s.sx + ux * s.startR
        val y0 = s.sy + uy * s.startR
        val tipX = s.ex - ux * s.endR
        val tipY = s.ey - uy * s.endR

        val headLen = 16f * d
        val headHalf = 9f * d
        val visible = hypot(tipX - x0, tipY - y0)
        // Đoạn quá ngắn thì thu nhỏ đầu mũi tên cho vừa
        val k = if (visible < headLen * 1.6f) visible / (headLen * 1.6f) else 1f

        // Thân dây dừng ở đáy đầu mũi tên để nét dày không lòi ra ngoài đỉnh mũi tên
        val baseX = tipX - ux * headLen * k * 0.8f
        val baseY = tipY - uy * headLen * k * 0.8f
        c.drawLine(x0, y0, baseX, baseY, haloPaint)
        c.drawLine(x0, y0, baseX, baseY, linePaint)

        // Mũi tên ở đầu dây, chỉ vào chấm P
        drawArrow(c, tipX, tipY, ux, uy, headLen * k, headHalf * k)

        // Dây dài: thêm một mũi tên ở giữa để thấy rõ chiều kéo
        if (visible > 170f * d) {
            val mx = (x0 + tipX) / 2f + ux * 7f * d
            val my = (y0 + tipY) / 2f + uy * 7f * d
            drawArrow(c, mx, my, ux, uy, 14f * d, 8f * d)
        }
    }

    /** Tam giác có đỉnh tại (tx, ty), hướng theo (ux, uy). */
    private fun drawArrow(c: Canvas, tx: Float, ty: Float, ux: Float, uy: Float, len: Float, half: Float) {
        val bx = tx - ux * len
        val by = ty - uy * len
        val nx = -uy
        val ny = ux
        path.reset()
        path.moveTo(tx, ty)
        path.lineTo(bx + nx * half, by + ny * half)
        path.lineTo(bx - nx * half, by - ny * half)
        path.close()
        c.drawPath(path, arrowHalo)
        c.drawPath(path, fillPaint)
    }
}
