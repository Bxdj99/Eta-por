package io.github.mangi.eta.hook.system

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/**
 * 状态栏滚动通知（对应原模块 status_scroll）。
 *
 * 原模块是 hook 状态栏视图把内容塞进去；这里不改 SystemUI 布局，
 * 在状态栏高度位置叠一条半透明滚动条，用平移动画代替 marquee
 * （overlay 窗口拿不到焦点，marquee 不会动）。
 */
@SuppressLint("ViewConstructor")
internal class StatusTickerLayer(context: Context, private val dark: Boolean) : TextView(context) {

    init {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setHorizontallyScrolling(true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(if (dark) Color.rgb(238, 246, 250) else Color.rgb(37, 48, 58))
        gravity = Gravity.CENTER_VERTICAL
        val density = resources.displayMetrics.density
        setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            if (dark) {
                setColor(Color.argb(235, 27, 39, 49))
                setStroke((1 * density).toInt(), Color.argb(120, 104, 133, 151))
            } else {
                setColor(Color.argb(240, 245, 250, 255))
                setStroke((1 * density).toInt(), Color.argb(120, 205, 225, 235))
            }
        }
    }

    /** 内容宽度超过可视宽度时往返平移 [rounds] 轮，走完回调。 */
    fun startScroll(rounds: Int, onDone: () -> Unit) {
        val textWidth = paint.measureText(text?.toString().orEmpty())
        val visible = width.toFloat()
        if (textWidth <= visible || rounds <= 0) {
            onDone()
            return
        }
        val distance = textWidth - visible + 24f * resources.displayMetrics.density
        val animator = android.animation.ValueAnimator.ofFloat(0f, -distance)
        animator.duration = ((distance / 60f) * 16.67f).toLong().coerceIn(1200L, 6000L)
        animator.repeatMode = android.animation.ValueAnimator.REVERSE
        animator.repeatCount = rounds * 2 - 1
        animator.addUpdateListener { translationX = it.animatedValue as Float }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                translationX = 0f
                onDone()
            }
        })
        animator.start()
    }
}
