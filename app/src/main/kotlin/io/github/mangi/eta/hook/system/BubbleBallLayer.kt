package io.github.mangi.eta.hook.system

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.View

/**
 * 悬浮球绘制层，参数逐项照抄原模块 r0.rOnDraw：
 *
 * - 半径 29dp，下方偏移 3dp、半径 +7dp 的径向阴影
 * - 主体径向渐变：中心偏 (7dp, 8dp)、半径 29×1.35
 * - 内圈 1dp 描边、27dp 裁剪的高光层（径向白 26% + 椭圆白描边 3.2dp + SweepGradient 外环 1.15dp）
 * - 图标 40dp、右上偏移 (22dp, -22dp) 的 12dp 亮角标
 *
 * 运行在 SystemUI 进程内（LSPosed 注入），所以不依赖 Eta 主进程的任何类。
 */
internal class BubbleBallLayer(context: Context, private val dark: Boolean) : View(context) {

    private val density = resources.displayMetrics.density
    private var icon: Drawable? = null
    private var count = 0
    private var prepared = false

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = if (dark) Color.argb(200, 184, 213, 229) else Color.argb(190, 255, 255, 255)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ovalStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        color = Color.argb(34, 255, 255, 255)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.15f * density
    }
    private val badgeShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.argb(215, 255, 255, 255)
    }
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = if (dark) Color.rgb(238, 246, 250) else Color.rgb(37, 48, 58)
    }
    private val clipPath = Path()

    fun bind(drawable: Drawable?, badgeCount: Int) {
        icon = drawable
        count = badgeCount
        prepared = false
        invalidate()
    }

    private fun dp(value: Float): Float = value * density

    private fun prepare() {
        if (prepared || width == 0) return
        prepared = true
        val cx = width / 2f
        val cy = height / 2f
        val radius = dp(29f)
        shadowPaint.shader = RadialGradient(
            cx, cy + dp(3f), radius + dp(7f),
            if (dark) Color.argb(75, 0, 0, 0) else Color.argb(44, 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        bodyPaint.shader = RadialGradient(
            cx - dp(7f), cy - dp(8f), radius * 1.35f,
            if (dark) Color.argb(125, 133, 171, 194) else Color.argb(122, 255, 255, 255),
            if (dark) Color.argb(90, 45, 65, 79) else Color.argb(68, 226, 234, 242),
            Shader.TileMode.CLAMP,
        )
        highlightPaint.shader = RadialGradient(
            cx - dp(9f), cy - dp(11f), radius * 1.28f,
            Color.argb(66, 255, 255, 255),
            Color.argb(18, 176, 216, 238),
            Shader.TileMode.CLAMP,
        )
        ringPaint.shader = SweepGradient(
            cx,
            cy,
            intArrayOf(
                Color.argb(if (dark) 110 else 135, 255, 255, 255),
                Color.argb(if (dark) 72 else 92, 170, 220, 242),
                Color.argb(if (dark) 58 else 78, 225, 192, 220),
                Color.argb(if (dark) 110 else 135, 255, 255, 255),
            ),
            null,
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        prepare()
        val cx = width / 2f
        val cy = height / 2f
        val radius = dp(29f)

        canvas.drawCircle(cx, cy + dp(3f), radius + dp(7f), shadowPaint)
        canvas.drawCircle(cx, cy, radius, bodyPaint)
        canvas.drawCircle(cx, cy, radius - dp(1f), innerStroke)

        val save = canvas.save()
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius - dp(2f), Path.Direction.CW)
        canvas.clipPath(clipPath)
        canvas.translate(-dp(3.2f), -dp(2.4f))
        canvas.scale(1.13f, 0.91f, cx, cy)
        canvas.drawCircle(cx, cy, radius, highlightPaint)
        canvas.drawOval(
            cx - radius * 0.78f,
            cy - radius * 0.68f,
            cx + radius * 0.82f,
            cy + radius * 0.72f,
            ovalStroke,
        )
        canvas.drawCircle(cx, cy, radius - dp(0.7f), ringPaint)
        canvas.restoreToCount(save)

        icon?.let { drawable ->
            val half = dp(20f)
            drawable.setBounds(
                (cx - half).toInt(),
                (cy - half).toInt(),
                (cx + half).toInt(),
                (cy + half).toInt(),
            )
            drawable.draw(canvas)
        }

        if (count > 0) {
            val bx = cx + dp(22f)
            val by = cy - dp(22f)
            val badgeRadius = dp(12f)
            badgeShadow.shader = RadialGradient(
                bx, by + dp(1.5f), badgeRadius + dp(4f),
                Color.argb(70, 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            badgeFill.shader = RadialGradient(
                bx - dp(3f), by - dp(4f), badgeRadius * 1.45f,
                Color.argb(245, 255, 255, 255),
                Color.argb(220, 212, 235, 247),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(bx, by + dp(1.5f), badgeRadius + dp(4f), badgeShadow)
            canvas.drawCircle(bx, by, badgeRadius, badgeFill)
            canvas.drawCircle(bx, by, badgeRadius - dp(0.8f), badgeStroke)

            val label = if (count > 99) "99+" else count.toString()
            badgeText.textSize = when {
                label.length >= 3 -> dp(8f)
                label.length == 2 -> dp(9f)
                else -> dp(10f)
            }
            val baseline = by - (badgeText.descent() + badgeText.ascent()) / 2f
            canvas.drawText(label, bx, baseline, badgeText)
        }
    }

    companion object {
        /** 供外部测量用：View 的边长，球半径 29dp + 角标与阴影空间。 */
        fun sizePx(context: Context): Int = (76 * context.resources.displayMetrics.density).toInt()

        fun boundsInset(context: Context): Rect {
            val inset = (12 * context.resources.displayMetrics.density).toInt()
            return Rect(inset, inset, inset, inset)
        }
    }
}
