package io.github.mangi.eta.hook.system

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 长按球展开的通知栈（对应原模块的 C0017i0）。
 *
 * 结构：半透明遮罩上居中一列卡片，每张卡片 64dp 高，最多展示 8 条；
 * 点卡片 = 打开该应用并出栈，点遮罩 = 收起。
 */
@SuppressLint("ViewConstructor")
internal class BubbleStackLayer(
    context: Context,
    private val dark: Boolean,
    private val items: List<Item>,
    private val onPick: (Int) -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    data class Item(
        val title: String,
        val text: String,
        val icon: android.graphics.drawable.Drawable?,
    )

    private val density = resources.displayMetrics.density

    init {
        setBackgroundColor(Color.argb(140, 0, 0, 0))
        isClickable = true
        setOnClickListener { onDismiss() }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
        }
        val columnWidth = minOf((360 * density).toInt(), resources.displayMetrics.widthPixels - (40 * density).toInt())
        val wrapper = FrameLayout(context).apply {
            isClickable = true
            addView(
                column,
                LayoutParams(columnWidth, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }
        addView(wrapper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        items.take(8).forEachIndexed { index, item ->
            val row = buildRow(item)
            row.setOnClickListener { onPick(index) }
            column.addView(row, LinearLayout.LayoutParams(columnWidth, (64 * density).toInt()).apply {
                if (index > 0) topMargin = (6 * density).toInt()
            })
        }
    }

    private fun buildRow(item: Item): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (12 * density).toInt()
            setPadding(hPad, 0, hPad, 0)
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                if (dark) {
                    colors = intArrayOf(Color.rgb(57, 72, 84), Color.rgb(27, 39, 49))
                    setStroke((1 * density).toInt(), Color.argb(200, 104, 133, 151))
                } else {
                    colors = intArrayOf(Color.rgb(255, 255, 255), Color.rgb(196, 222, 240))
                    setStroke((1 * density).toInt(), Color.argb(210, 205, 225, 235))
                }
                cornerRadius = 18 * density
            }
        }
        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(item.icon)
            visibility = if (item.icon == null) View.GONE else View.VISIBLE
        }
        row.addView(iconView, LinearLayout.LayoutParams((34 * density).toInt(), (34 * density).toInt()).apply {
            rightMargin = (10 * density).toInt()
        })
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(context).apply {
            text = item.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(if (dark) Color.rgb(183, 198, 210) else Color.rgb(80, 88, 96))
            maxLines = 1
        }
        val bodyView = TextView(context).apply {
            text = item.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (dark) Color.rgb(241, 245, 248) else Color.rgb(24, 28, 32))
            maxLines = 1
        }
        column.addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(bodyView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (2 * density).toInt()
        })
        row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }
}
