package io.github.mangi.eta.hook.system

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 通知内容卡片，对应原模块的 C0：
 *
 * - 18dp 圆角、三色纵向渐变背景（深色 rgb(57,72,84)→(38,52,64)→(27,39,49)）
 * - 1dp 描边 argb(200,104,133,151)
 * - 内边距 10dp/7dp、elevation 8dp、图标 34dp + 右距 10dp
 * - 标题 10sp rgb(183,198,210)、正文 13sp rgb(241,245,248)、正文上间距 2dp
 * - 右上角小圆点表示队列里还有更早的通知
 *
 * 运行在 SystemUI 进程，仅用原生控件，不依赖 Eta 主进程的 UI 栈。
 */
@SuppressLint("ViewConstructor")
internal class BubbleCardLayer(context: Context, private val dark: Boolean) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val iconView = ImageView(context)
    private val titleView = TextView(context)
    private val bodyView = TextView(context)
    private var hasMore = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val hPad = (10 * density).toInt()
        val vPad = (7 * density).toInt()
        setPadding(hPad, vPad, hPad, vPad)
        elevation = 8 * density

        iconView.scaleType = ImageView.ScaleType.CENTER_CROP
        val iconParams = LayoutParams((34 * density).toInt(), (34 * density).toInt())
        iconParams.rightMargin = (10 * density).toInt()
        addView(iconView, iconParams)

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(if (dark) Color.rgb(183, 198, 210) else Color.rgb(80, 88, 96))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bodyView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (dark) Color.rgb(241, 245, 248) else Color.rgb(24, 28, 32))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        column.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(
            bodyView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * density).toInt()
            },
        )
        addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    fun bind(
        icon: Drawable?,
        title: String,
        text: String,
        badge: Int,
        more: Boolean,
    ) {
        hasMore = more
        iconView.setImageDrawable(null)
        if (icon != null) {
            iconView.setImageDrawable(icon)
            iconView.visibility = VISIBLE
        } else {
            iconView.visibility = GONE
        }
        titleView.text = if (badge > 1) "$title · $badge" else title
        bodyView.text = text
        background = buildBackground()
        invalidate()
    }

    private fun buildBackground(): GradientDrawable = GradientDrawable().apply {
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        if (dark) {
            colors = intArrayOf(Color.rgb(57, 72, 84), Color.rgb(38, 52, 64), Color.rgb(27, 39, 49))
            setStroke((1 * density).toInt(), Color.argb(200, 104, 133, 151))
        } else {
            colors = intArrayOf(Color.rgb(255, 255, 255), Color.rgb(235, 247, 252), Color.rgb(196, 222, 240))
            setStroke((1 * density).toInt(), Color.argb(210, 205, 225, 235))
        }
        cornerRadius = 18 * density
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (!hasMore) return
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = if (dark) Color.argb(160, 205, 231, 242) else Color.argb(160, 120, 160, 190)
        canvas.drawCircle(
            width - 7 * density,
            6 * density,
            2.4f * density,
            paint,
        )
    }

    companion object {
        fun heightPx(context: Context): Int = (70 * context.resources.displayMetrics.density).toInt()
    }
}
