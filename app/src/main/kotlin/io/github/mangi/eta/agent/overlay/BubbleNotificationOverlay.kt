package io.github.mangi.eta.agent.overlay

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import kotlin.math.min

/**
 * 通知气泡（Bubble Notifications 的原生重实现）。
 *
 * 视觉参数照抄原 LSPosed 模块（io.github.freeform.bubble 1.9.9 的 C0 类）：
 * 18dp 圆角、三色纵向渐变背景、1dp 描边、8dp elevation、10dp/7dp 内边距、
 * 34dp 图标 + 10dp 右距、10sp 标题（次级色）、13sp 正文（主色）、正文 2dp 上间距、
 * 宽度 = min(360dp, 屏宽 - 20dp) 且不小于 180dp，高度下限 58dp。
 *
 * 与原模块的差别仅在承载方式：模块 hook 进 SystemUI 改通知面板；这里走
 * NotificationListenerService + 悬浮窗，只画气泡、不动系统 UI，不新增常驻进程。
 */
internal object BubbleNotificationOverlay {

    private const val TAG = "EtaBubble"
    private const val MAX_BUBBLES = 3
    private const val AUTO_DISMISS_MS = 8_000L
    private const val BUBBLE_GAP_DP = 8
    private const val SWIPE_DISMISS_DP = 24
    private const val DRAG_HOLD_MS = 400L

    // 照抄原模块 C0 的尺寸（dp/sp）
    private const val MAX_WIDTH_DP = 360
    private const val MIN_WIDTH_DP = 180
    private const val SIDE_MARGIN_DP = 20
    private const val MIN_HEIGHT_DP = 58
    private const val CORNER_RADIUS_DP = 18
    private const val CONTENT_PADDING_H_DP = 10
    private const val CONTENT_PADDING_V_DP = 7
    private const val ICON_SIZE_DP = 34
    private const val ICON_MARGIN_DP = 10
    private const val TITLE_SP = 10f
    private const val BODY_SP = 13f
    private const val BODY_TOP_MARGIN_DP = 2
    private const val ELEVATION_DP = 8

    private val handler = Handler(Looper.getMainLooper())
    private val iconCache = HashMap<String, Drawable?>()

    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var container: BubbleContainer? = null
    private val bubbles = ArrayList<BubbleEntry>()
    private val dismissRunnable = Runnable { dismissAll(animate = true) }

    /** 通知监听回调入口；主线程执行，异常一律吞掉，绝不影响通知入库。 */
    fun onPosted(context: Context, sbn: StatusBarNotification) {
        runCatching {
            prime(context)
            if (!enabled()) return
            if (!shouldShow(sbn)) return
            val entry = buildEntry(context.applicationContext, sbn) ?: return
            handler.post { show(context.applicationContext, entry) }
        }.onFailure { debug(context, "onPosted failed: $it") }
    }

    fun dismissAll(animate: Boolean = false) {
        handler.removeCallbacks(dismissRunnable)
        val target = container ?: run {
            bubbles.clear()
            return
        }
        if (animate && bubbles.isNotEmpty()) {
            target.animate().alpha(0f).setDuration(160L).withEndAction { detach() }.start()
        } else {
            detach()
        }
    }

    private fun enabled(): Boolean = runCatching {
        Prefs.isEnabled(Prefs.Keys.AGENT_BUBBLE_NOTIFICATIONS)
    }.getOrDefault(true)

    private fun excludedPackages(): Set<String> = runCatching {
        val raw = Prefs.localAgentPreferences()?.getString(Prefs.Keys.AGENT_BUBBLE_EXCLUDED_JSON, "[]")
            ?: "[]"
        val array = JSONArray(raw)
        buildSet {
            for (index in 0 until array.length()) add(array.optString(index))
        }
    }.getOrDefault(emptySet())

    private fun shouldShow(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == "io.github.mangi.eta") return false
        if (sbn.packageName == "android") return false
        if (sbn.packageName in excludedPackages()) return false
        val notification = sbn.notification ?: return false
        val flags = notification.flags
        if (flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) return false
        if (flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        val extras = notification.extras ?: return false
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        return title.isNotBlank() || text.isNotBlank()
    }

    private fun buildEntry(context: Context, sbn: StatusBarNotification): BubbleEntry? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val appLabel = runCatching {
            val info = context.packageManager.getApplicationInfo(sbn.packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)
        return BubbleEntry(
            key = sbn.key ?: "${sbn.packageName}#${sbn.postTime}",
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title.ifBlank { appLabel },
            text = text,
            icon = loadIcon(context, sbn.packageName),
            contentIntent = notification.contentIntent,
            postedAt = sbn.postTime,
        )
    }

    private fun loadIcon(context: Context, packageName: String): Drawable? =
        iconCache.getOrPut(packageName) {
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        }

    private fun show(context: Context, entry: BubbleEntry) {
        if (container == null && !attach(context)) {
            debug(context, "attach failed for ${entry.packageName}")
            return
        }
        val stack = container ?: return
        bubbles.removeAll { it.key == entry.key }
        while (bubbles.size >= MAX_BUBBLES) bubbles.removeAt(0)
        bubbles.add(entry)
        stack.alpha = 1f
        stack.render(bubbles, animateLast = true)
        resizeWindow()
        scheduleDismiss()
    }

    private fun scheduleDismiss() {
        handler.removeCallbacks(dismissRunnable)
        val seconds = runCatching {
            Prefs.getAgentInt(Prefs.Keys.AGENT_BUBBLE_TIMEOUT_SEC, 8)
        }.getOrDefault(8).coerceIn(3, 30)
        handler.postDelayed(dismissRunnable, seconds * 1000L)
    }

    private fun hostContext(fallback: Context): Context =
        AgentAccessibilityService.current() ?: fallback

    private fun attach(fallback: Context): Boolean {
        val context = hostContext(fallback)
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val layoutParams = WindowManager.LayoutParams(
            bubbleWidthPx(context),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = savedPosition(context, width)
            x = saved.first
            y = saved.second
            windowAnimations = 0
        }
        val stack = BubbleContainer(context).apply {
            onDismissAt = { index -> dismissAt(index) }
            onActivateAt = { index -> activateAt(index) }
            onMoveWindow = { dx, dy ->
                val current = params
                if (current != null) {
                    current.x += dx
                    current.y += dy
                    runCatching { windowManager?.updateViewLayout(this, current) }
                }
            }
            onReleaseWindow = { snapToEdge() }
        }
        return runCatching {
            manager.addView(stack, layoutParams)
            windowManager = manager
            params = layoutParams
            container = stack
            true
        }.onFailure { debug(context, "addView failed: $it") }.getOrDefault(false)
    }

    private fun detach() {
        val stack = container ?: return
        runCatching { windowManager?.removeView(stack) }
        container = null
        params = null
        windowManager = null
        bubbles.clear()
        handler.removeCallbacks(dismissRunnable)
    }

    private fun dismissAt(index: Int) {
        if (index !in bubbles.indices) return
        bubbles.removeAt(index)
        val stack = container ?: return
        if (bubbles.isEmpty()) {
            detach()
            return
        }
        stack.render(bubbles)
        resizeWindow()
        scheduleDismiss()
    }

    private fun activateAt(index: Int) {
        val entry = bubbles.getOrNull(index) ?: return
        val context = hostContext(lastContext ?: return)
        dismissAt(index)
        val pending = entry.contentIntent
        if (pending != null) {
            val sent = runCatching { pending.send() }.isSuccess
            if (sent) return
        }
        runCatching {
            val intent: Intent? = context.packageManager.getLaunchIntentForPackage(entry.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
            }
        }
    }

    private fun resizeWindow() {
        val stack = container ?: return
        val current = params ?: return
        val manager = windowManager ?: return
        val width = bubbleWidthPx(stack.context)
        if (current.width == width) return
        current.width = width
        runCatching { manager.updateViewLayout(stack, current) }
    }

    /** 原模块公式：宽度 = min(配置宽度, 屏宽 - inset - 20dp)，且不小于 180dp。 */
    private fun bubbleWidthPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = runCatching { manager?.currentWindowMetrics }.getOrNull()
        val screenWidth = metrics?.bounds?.width() ?: 1080
        val insets = metrics?.windowInsets?.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
        )
        val horizontalInset = (insets?.left ?: 0) + (insets?.right ?: 0)
        val configuredDp = runCatching {
            Prefs.getAgentInt(Prefs.Keys.AGENT_BUBBLE_WIDTH_DP, MAX_WIDTH_DP)
        }.getOrDefault(MAX_WIDTH_DP).coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)
        val maxWidth = (configuredDp * density).toInt()
        val available = (screenWidth - horizontalInset - (SIDE_MARGIN_DP * density).toInt())
        val minWidth = (MIN_WIDTH_DP * density).toInt()
        return min(maxWidth, available).coerceAtLeast(minWidth)
    }

    private fun snapToEdge() {
        val current = params ?: return
        val stack = container ?: return
        val manager = windowManager ?: return
        val metrics = runCatching { manager.currentWindowMetrics }.getOrNull() ?: return
        val screenWidth = metrics.bounds.width()
        val screenHeight = metrics.bounds.height()
        val margin = (8 * stack.resources.displayMetrics.density).toInt()
        current.x = if (current.x + current.width / 2 < screenWidth / 2) margin else screenWidth - current.width - margin
        current.y = current.y.coerceIn(margin, (screenHeight - stack.height - margin).coerceAtLeast(margin))
        runCatching { manager.updateViewLayout(stack, current) }
        Prefs.localAgentPreferences()?.edit()
            ?.putString(Prefs.Keys.AGENT_BUBBLE_POSITION, "${current.x},${current.y}")
            ?.apply()
    }

    private fun savedPosition(context: Context, width: Int): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = runCatching { manager?.currentWindowMetrics }.getOrNull()
        val screenWidth = metrics?.bounds?.width() ?: 1080
        val screenHeight = metrics?.bounds?.height() ?: 2400
        val margin = (8 * density).toInt()
        val defaultY = (120 * density).toInt()
        val raw = Prefs.localAgentPreferences()?.getString(Prefs.Keys.AGENT_BUBBLE_POSITION, null)
        val parsed = raw?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }
        return if (parsed != null) {
            val x = parsed[0].coerceIn(margin, (screenWidth - width - margin).coerceAtLeast(margin))
            val y = parsed[1].coerceIn(margin, (screenHeight - 200 - margin).coerceAtLeast(margin))
            x to y
        } else {
            (screenWidth - width - margin) to defaultY
        }
    }

    private fun overlayWindowType(): Int =
        if (AgentAccessibilityService.current() != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

    private fun debug(context: Context, message: String) {
        runCatching {
            val file = java.io.File(context.filesDir, "bubble_debug.log")
            if (file.length() > 64 * 1024) file.delete()
            file.appendText("${System.currentTimeMillis()} $message\n")
        }
    }

    @Volatile
    private var lastContext: Context? = null

    internal data class BubbleEntry(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val icon: Drawable?,
        val contentIntent: PendingIntent?,
        val postedAt: Long,
    )

    /**
     * 气泡容器：垂直排列最多 [MAX_BUBBLES] 张卡片。
     * 卡片本身照抄原模块的横向 LinearLayout 结构（图标 + 标题/正文两行）。
     */
    @SuppressLint("ViewConstructor")
    private class BubbleContainer(context: Context) : LinearLayout(context) {

        var onDismissAt: ((Int) -> Unit)? = null
        var onActivateAt: ((Int) -> Unit)? = null
        var onMoveWindow: ((Int, Int) -> Unit)? = null
        var onReleaseWindow: (() -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        private var downX = 0f
        private var downY = 0f
        private var activeIndex = -1
        private var dragging = false
        private var lastRawX = 0f
        private var lastRawY = 0f
        private val dragRunnable = Runnable {
            if (activeIndex >= 0) dragging = true
        }

        init {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        fun render(entries: List<BubbleEntry>, animateLast: Boolean = false) {
            removeAllViews()
            entries.forEachIndexed { index, entry ->
                addView(buildCard(entry), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) topMargin = dp(BUBBLE_GAP_DP)
                })
            }
            if (animateLast) (getChildAt(childCount - 1) as? BubbleCard)?.let(::revealCard)
        }

        /** 照抄原模块的展开动画：330ms，三角形裁剪从边缘扫入 + 从 0.6 放大到 1。 */
        private fun revealCard(card: BubbleCard) {
            card.reveal = 0f
            card.scaleX = 0.6f
            card.scaleY = 0.6f
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 330L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    card.reveal = progress
                    card.scaleX = 0.6f + 0.4f * progress
                    card.scaleY = 0.6f + 0.4f * progress
                }
                start()
            }
        }

        private fun buildCard(entry: BubbleEntry): BubbleCard {
            val card = BubbleCard(context, dark)
            card.orientation = HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.setPadding(dp(CONTENT_PADDING_H_DP), dp(CONTENT_PADDING_V_DP), dp(CONTENT_PADDING_H_DP), dp(CONTENT_PADDING_V_DP))
            card.elevation = dp(ELEVATION_DP).toFloat()
            card.isClickable = true
            card.cornerRadiusPx = dp(CORNER_RADIUS_DP).toFloat()

            val background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                if (dark) {
                    colors = intArrayOf(Color.rgb(57, 72, 84), Color.rgb(38, 52, 64), Color.rgb(27, 39, 49))
                } else {
                    colors = intArrayOf(Color.rgb(255, 255, 255), Color.rgb(235, 247, 252), Color.rgb(196, 222, 240))
                }
                cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
                if (dark) {
                    setStroke(dp(1), Color.argb(200, 104, 133, 151))
                } else {
                    setStroke(dp(1), Color.argb(210, 205, 225, 235))
                }
                val opacity = runCatching {
                    Prefs.getAgentInt(Prefs.Keys.AGENT_BUBBLE_OPACITY_PCT, 100)
                }.getOrDefault(100).coerceIn(40, 100)
                alpha = opacity * 255 / 100
            }
            card.background = background

            entry.icon?.let { icon ->
                val imageView = ImageView(context)
                val cloned = runCatching { icon.constantState?.newDrawable()?.mutate() ?: icon }.getOrDefault(icon)
                cloned.alpha = 255
                imageView.setImageDrawable(cloned)
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                val params = LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP))
                params.rightMargin = dp(ICON_MARGIN_DP)
                card.addView(imageView, params)
            }

            val textColumn = LinearLayout(context)
            textColumn.orientation = VERTICAL
            textColumn.gravity = Gravity.CENTER_VERTICAL

            val titleView = TextView(context).apply {
                text = entry.appLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
                setTextColor(if (dark) Color.rgb(183, 198, 210) else Color.rgb(80, 88, 96))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val bodyView = TextView(context).apply {
                text = entry.text.ifBlank { entry.title }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
                setTextColor(if (dark) Color.rgb(241, 245, 248) else Color.rgb(24, 28, 32))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            textColumn.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            textColumn.addView(bodyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(BODY_TOP_MARGIN_DP)
            })
            card.addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            return card
        }

        private fun dp(value: Int): Int = (value * density).toInt()

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (childCount == 0) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    activeIndex = indexAt(event.y)
                    if (activeIndex < 0) return false
                    postDelayed(dragRunnable, DRAG_HOLD_MS)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeIndex < 0) return false
                    if (dragging) {
                        val dx = (event.rawX - lastRawX).toInt()
                        val dy = (event.rawY - lastRawY).toInt()
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        if (dx != 0 || dy != 0) onMoveWindow?.invoke(dx, dy)
                        return true
                    }
                    val deltaY = event.y - downY
                    if (deltaY < -dp(SWIPE_DISMISS_DP) && kotlin.math.abs(event.x - downX) < width * 0.6f) {
                        removeCallbacks(dragRunnable)
                        onDismissAt?.invoke(activeIndex)
                        activeIndex = -1
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(dragRunnable)
                    if (activeIndex >= 0) {
                        if (dragging) onReleaseWindow?.invoke() else onActivateAt?.invoke(activeIndex)
                    }
                    dragging = false
                    activeIndex = -1
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(dragRunnable)
                    if (dragging) onReleaseWindow?.invoke()
                    dragging = false
                    activeIndex = -1
                    return true
                }
            }
            return false
        }

        private fun indexAt(y: Float): Int {
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (y >= child.top && y <= child.bottom) return index
            }
            return -1
        }
    }

    /**
     * 气泡卡片：渐变背景 + 边缘扫入裁剪 + 1.05dp 高光描边。
     * 裁剪与描边逻辑照抄原模块 C0.dispatchDraw（三角形 clipPath + 圆角内缩描边）。
     */
    private class BubbleCard(context: Context, private val dark: Boolean) : LinearLayout(context) {

        var reveal = 1f
            set(value) {
                field = value
                invalidate()
            }
        var cornerRadiusPx = 0f
        /** 吸附边：0 左、1 右、2 上、3 下，决定扫入方向。 */
        var dockEdge = 1

        private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.05f * resources.displayMetrics.density
        }
        private val clipPath = Path()

        override fun dispatchDraw(canvas: Canvas) {
            val save = canvas.save()
            if (reveal < 0.999f && width > 1 && height > 1) {
                val w = width.toFloat()
                val hgt = height.toFloat()
                val half = reveal * 0.5f
                clipPath.reset()
                when (dockEdge) {
                    0 -> {
                        clipPath.moveTo(0f, 0f)
                        clipPath.lineTo(0f, hgt)
                        clipPath.lineTo(w - hgt * half, hgt / 2f)
                    }
                    1 -> {
                        clipPath.moveTo(w, 0f)
                        clipPath.lineTo(w, hgt)
                        clipPath.lineTo(hgt * half, hgt / 2f)
                    }
                    2 -> {
                        clipPath.moveTo(0f, 0f)
                        clipPath.lineTo(w, 0f)
                        clipPath.lineTo(w / 2f, hgt - w * half)
                    }
                    else -> {
                        clipPath.moveTo(0f, hgt)
                        clipPath.lineTo(w / 2f, w * half)
                        clipPath.lineTo(w, hgt)
                    }
                }
                clipPath.close()
                canvas.clipPath(clipPath)
            }
            super.dispatchDraw(canvas)
            val density = resources.displayMetrics.density
            if (width > 24 * density && height > 20 * density) {
                highlight.color = if (dark) Color.argb(86, 205, 231, 242) else Color.argb(108, 255, 255, 255)
                val inset = highlight.strokeWidth
                val radius = (cornerRadiusPx - inset).coerceAtLeast(1f)
                canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, highlight)
            }
            canvas.restoreToCount(save)
        }
    }

    /** 记录最近一次宿主 Context（保持与旧调用点兼容）。 */
    fun prime(context: Context) {
        lastContext = context.applicationContext
    }
}
