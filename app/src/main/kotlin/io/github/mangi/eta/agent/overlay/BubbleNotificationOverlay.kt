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
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.min

/**
 * 通知气泡（Bubble Notifications 的原生重实现）。
 *
 * 视觉参数照抄原模块 C0：18dp 圆角、三色纵向渐变、1dp 描边 + 1.05dp 高光、
 * 8dp elevation、10dp/7dp 内边距、34dp 图标 + 10dp 右距、10sp 标题 / 13sp 正文、
 * 宽度 = min(配置宽度, 屏宽 - 20dp)，三角裁剪展开动画。
 *
 * 手势：上滑看上一条（通知栈）、下滑关闭当前、长按收成悬浮球、点击打开应用；
 * 球态下点击展开、拖动移动位置。
 */
internal object BubbleNotificationOverlay {

    private const val MAX_HISTORY = 10
    private const val BUBBLE_GAP_DP = 8
    private const val SWIPE_THRESHOLD_DP = 24
    private const val HOLD_MS = 400L
    /** 球体直径 58dp（原模块半径 29dp）；窗口留出角标与阴影的空间。 */
    private const val BALL_SIZE_DP = 58
    private const val BALL_VIEW_SIZE_DP = 76

    // 照抄原模块 C0 的尺寸（dp/sp）
    private const val MAX_WIDTH_DP = 360
    private const val MIN_WIDTH_DP = 180
    private const val SIDE_MARGIN_DP = 20
    private const val CORNER_RADIUS_DP = 18
    private const val CONTENT_PADDING_H_DP = 10
    private const val CONTENT_PADDING_V_DP = 7
    private const val ICON_SIZE_DP = 34
    private const val ICON_MARGIN_DP = 10
    private const val TITLE_SP = 10f
    private const val BODY_SP = 13f
    private const val BODY_TOP_MARGIN_DP = 2
    private const val ELEVATION_DP = 8
    /** 卡片窗口高度：两行文字 + 上下内边距，原模块是精确 measure，这里给固定档避免 WRAP_CONTENT 测成 0。 */
    private const val CARD_HEIGHT_DP = 70

    private val handler = Handler(Looper.getMainLooper())
    private val iconCache = HashMap<String, Drawable?>()

    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var stage: BubbleStage? = null

    /** 通知栈：上滑看更早的一条，下滑关闭当前。 */
    private val history = ArrayList<BubbleEntry>()
    private var currentIndex = -1
    private var collapsed = false

    /** 超时后不是消失，而是收成悬浮球常驻，点球可重新展开。 */
    private val dismissRunnable = Runnable { collapseToBall() }

    /** 通知监听回调入口；异常一律吞掉，绝不影响通知入库。 */
    fun onPosted(context: Context, sbn: StatusBarNotification) {
        runCatching {
            prime(context)
            val switchOn = enabled()
            val visible = shouldShow(sbn)
            debug(context, "onPosted ${sbn.packageName} switch=$switchOn pass=$visible")
            if (!switchOn) return
            if (!visible) return
            val entry = buildEntry(context.applicationContext, sbn)
            if (entry == null) {
                debug(context, "entry null ${sbn.packageName}")
                return
            }
            handler.post { show(context.applicationContext, entry) }
        }.onFailure { debug(context, "onPosted failed: $it") }
    }

    fun dismissAll(animate: Boolean = false) {
        handler.removeCallbacks(dismissRunnable)
        val target = stage ?: run {
            history.clear()
            currentIndex = -1
            return
        }
        if (animate && !collapsed) {
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
        if (stage == null && !attach(context)) {
            debug(context, "attach failed for ${entry.packageName}")
            return
        }
        val host = stage ?: return
        history.removeAll { it.key == entry.key }
        while (history.size >= MAX_HISTORY) history.removeAt(0)
        history.add(entry)
        currentIndex = history.size - 1
        if (collapsed) {
            // 收到新通知时从球展开
            collapsed = false
        }
        renderCurrent(animate = true)
        scheduleDismiss()
        debug(context, "shown ${entry.packageName} count=${history.size} stage=${stage != null}")
    }

    private fun scheduleDismiss() {
        handler.removeCallbacks(dismissRunnable)
        val seconds = runCatching {
            Prefs.getAgentInt(Prefs.Keys.AGENT_BUBBLE_TIMEOUT_SEC, 8)
        }.getOrDefault(8).coerceIn(3, 30)
        handler.postDelayed(dismissRunnable, seconds * 1000L)
    }

    /** 渲染当前栈位置的那一条；[animate] 时播放展开动画。 */
    private fun renderCurrent(animate: Boolean) {
        val host = stage ?: return
        val entry = history.getOrNull(currentIndex)
        if (entry == null) {
            detach()
            return
        }
        collapsed = false
        host.showCard(entry, currentIndex > 0, history.size, animate)
        resizeWindow(cardWidthPx(host.context), dp(host.context, CARD_HEIGHT_DP))
    }

    private fun collapseToBall() {
        val host = stage ?: return
        val entry = history.getOrNull(currentIndex) ?: return
        collapsed = true
        host.showBall(entry, history.size)
        resizeWindow(dp(host.context, BALL_VIEW_SIZE_DP), dp(host.context, BALL_VIEW_SIZE_DP))
        handler.removeCallbacks(dismissRunnable)
    }

    private fun expandFromBall() {
        if (!collapsed) return
        collapsed = false
        renderCurrent(animate = true)
        scheduleDismiss()
    }

    private fun moveBy(dx: Int, dy: Int) {
        val current = params ?: return
        current.x += dx
        current.y += dy
        val stack = stage ?: return
        val manager = windowManager ?: return
        runCatching { manager.updateViewLayout(stack, current) }
    }

    private fun snapToEdge() {
        val current = params ?: return
        val stack = stage ?: return
        val manager = windowManager ?: return
        val metrics = runCatching { manager.currentWindowMetrics }.getOrNull() ?: return
        val screenWidth = metrics.bounds.width()
        val screenHeight = metrics.bounds.height()
        val margin = dp(stack.context, 8)
        current.x = if (current.x + current.width / 2 < screenWidth / 2) margin else screenWidth - current.width - margin
        current.y = current.y.coerceIn(margin, (screenHeight - stack.height - margin).coerceAtLeast(margin))
        runCatching { manager.updateViewLayout(stack, current) }
        Prefs.localAgentPreferences()?.edit()
            ?.putString(Prefs.Keys.AGENT_BUBBLE_POSITION, "${current.x},${current.y}")
            ?.apply()
    }

    private fun resizeWindow(width: Int, height: Int) {
        val current = params ?: return
        val stack = stage ?: return
        val manager = windowManager ?: return
        if (current.width == width && current.height == height) return
        current.width = width
        current.height = height
        runCatching { manager.updateViewLayout(stack, current) }
    }

    private fun activateCurrent() {
        val entry = history.getOrNull(currentIndex) ?: return
        val context = lastContext ?: stage?.context ?: return
        val pending = entry.contentIntent
        debug(context, "tap ${entry.packageName} intent=${pending != null}")
        if (pending != null) {
            val result = runCatching { pending.send() }
            debug(context, "pending.send ok=${result.isSuccess} err=${result.exceptionOrNull()?.javaClass?.simpleName}")
            if (result.isSuccess) {
                dismissCurrent()
                return
            }
        }
        runCatching {
            val intent: Intent? = context.packageManager.getLaunchIntentForPackage(entry.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                debug(context, "launchIntent started ${entry.packageName}")
            } else {
                debug(context, "no launch intent ${entry.packageName}")
            }
        }.onFailure { debug(context, "startActivity failed: $it") }
        dismissCurrent()
    }

    /** 下滑：关闭当前一条，若栈里还有更早的就继续显示。 */
    private fun dismissCurrent() {
        if (currentIndex !in history.indices) {
            dismissAll()
            return
        }
        history.removeAt(currentIndex)
        if (history.isEmpty()) {
            currentIndex = -1
            detach()
            return
        }
        if (currentIndex >= history.size) currentIndex = history.size - 1
        renderCurrent(animate = true)
        scheduleDismiss()
    }

    /** 上滑：切到栈里更早的一条。 */
    private fun showPrevious() {
        if (currentIndex <= 0) return
        currentIndex -= 1
        renderCurrent(animate = true)
        scheduleDismiss()
    }

    private fun hostContext(fallback: Context): Context =
        AgentAccessibilityService.current() ?: fallback

    private fun attach(fallback: Context): Boolean {
        val context = hostContext(fallback)
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val width = cardWidthPx(context)
        val layoutParams = WindowManager.LayoutParams(
            width,
            dp(context, CARD_HEIGHT_DP),
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
        val host = BubbleStage(context, darkMode(context)).apply {
            onTap = { activateCurrent() }
            onSwipeUp = { showPrevious() }
            onSwipeDown = { dismissCurrent() }
            onHold = { collapseToBall() }
            onBallTap = { expandFromBall() }
            onDrag = { dx, dy -> moveBy(dx, dy) }
            onDragEnd = { snapToEdge() }
        }
        return runCatching {
            manager.addView(host, layoutParams)
            windowManager = manager
            params = layoutParams
            stage = host
            true
        }.onFailure { debug(context, "addView failed: $it") }.getOrDefault(false)
    }

    private fun detach() {
        val host = stage ?: return
        runCatching { windowManager?.removeView(host) }
        stage = null
        params = null
        windowManager = null
        history.clear()
        currentIndex = -1
        collapsed = false
        handler.removeCallbacks(dismissRunnable)
    }

    private fun cardWidthPx(context: Context): Int {
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
        val available = screenWidth - horizontalInset - dp(context, SIDE_MARGIN_DP)
        return min((configuredDp * density).toInt(), available).coerceAtLeast(dp(context, MIN_WIDTH_DP))
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

    private fun darkMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun debug(context: Context, message: String) {
        runCatching {
            val file = java.io.File(context.filesDir, "bubble_debug.log")
            if (file.length() > 64 * 1024) file.delete()
            file.appendText("${System.currentTimeMillis()} $message\n")
        }
    }

    @Volatile
    private var lastContext: Context? = null

    fun prime(context: Context) {
        lastContext = context.applicationContext
    }

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
     * 承载卡片与悬浮球两种形态的容器，负责全部手势。
     *
     * 展开态：上滑看上一条、下滑关闭、长按收成球、点击打开应用。
     * 球态：点击展开、拖动移动位置。
     */
    @SuppressLint("ViewConstructor")
    private class BubbleStage(context: Context, private val dark: Boolean) : FrameLayout(context) {

        var onTap: (() -> Unit)? = null
        var onSwipeUp: (() -> Unit)? = null
        var onSwipeDown: (() -> Unit)? = null
        var onHold: (() -> Unit)? = null
        var onBallTap: (() -> Unit)? = null
        var onDrag: ((Int, Int) -> Unit)? = null
        var onDragEnd: (() -> Unit)? = null

        private val density = resources.displayMetrics.density
        private var cardView: BubbleCard? = null
        private var ballView: View? = null

        private var downX = 0f
        private var downY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var dragging = false
        private var held = false
        private var swiped = false
        private var ballMode = false
        /** 长按刚收起成球时，抬手不应立刻又展开回卡片。 */
        private var justCollapsed = false

        private val holdRunnable = Runnable {
            held = true
            if (!ballMode) {
                justCollapsed = true
                onHold?.invoke()
            }
        }

        fun showCard(entry: BubbleEntry, hasPrevious: Boolean, count: Int, animate: Boolean) {
            ballMode = false
            removeAllViews()
            val card = BubbleCard(context, dark, hasPrevious, count)
            card.bind(entry, dark)
            addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            cardView = card
            ballView = null
            alpha = 1f
            if (animate) revealCard(card)
        }

        fun showBall(entry: BubbleEntry, count: Int) {
            ballMode = true
            removeAllViews()
            val size = dp(BALL_VIEW_SIZE_DP)
            val ball = BubbleBallView(context, dark).apply {
                bind(entry.icon, count)
            }
            addView(ball, LayoutParams(size, size))
            cardView = null
            ballView = ball
        }

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

        private fun dp(value: Int): Int = (value * density).toInt()

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    held = false
                    swiped = false
                    justCollapsed = false
                    postDelayed(holdRunnable, HOLD_MS)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    if (ballMode) {
                        if (!dragging && (abs(dx) > dp(4) || abs(dy) > dp(4))) dragging = true
                        if (dragging) {
                            onDrag?.invoke(dx.toInt(), dy.toInt())
                            lastRawX = event.rawX
                            lastRawY = event.rawY
                        }
                        return true
                    }
                    if (!swiped) {
                        val totalDy = event.y - downY
                        val totalDx = event.x - downX
                        if (abs(totalDy) > dp(SWIPE_THRESHOLD_DP) && abs(totalDx) < width * 0.6f) {
                            swiped = true
                            removeCallbacks(holdRunnable)
                            if (totalDy < 0) onSwipeUp?.invoke() else onSwipeDown?.invoke()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(holdRunnable)
                    if (ballMode) {
                        if (dragging) {
                            onDragEnd?.invoke()
                        } else if (!justCollapsed) {
                            onBallTap?.invoke()
                        }
                    } else if (!swiped && !held) {
                        onTap?.invoke()
                    }
                    dragging = false
                    held = false
                    swiped = false
                    justCollapsed = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(holdRunnable)
                    if (dragging) onDragEnd?.invoke()
                    dragging = false
                    held = false
                    swiped = false
                    return true
                }
            }
            return false
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    /**
     * 气泡卡片：渐变背景 + 边缘扫入裁剪 + 1.05dp 高光描边，照抄原模块 C0。
     * 右上角用一个小圆点表示"还有更早的通知"。
     */
    private class BubbleCard(
        context: Context,
        private val dark: Boolean,
        private val hasPrevious: Boolean,
        private val count: Int,
    ) : LinearLayout(context) {

        var reveal = 1f
            set(value) {
                field = value
                invalidate()
            }
        private var cornerRadiusPx = 0f
        private var dockEdge = 1

        private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.05f * resources.displayMetrics.density
        }
        private val clipPath = Path()
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val density = resources.displayMetrics.density
            val hPad = (CONTENT_PADDING_H_DP * density).toInt()
            val vPad = (CONTENT_PADDING_V_DP * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            elevation = ELEVATION_DP * density
            isClickable = true
            cornerRadiusPx = CORNER_RADIUS_DP * density
        }

        fun bind(entry: BubbleEntry, dark: Boolean) {
            removeAllViews()
            val density = resources.displayMetrics.density
            val background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                if (dark) {
                    colors = intArrayOf(Color.rgb(57, 72, 84), Color.rgb(38, 52, 64), Color.rgb(27, 39, 49))
                } else {
                    colors = intArrayOf(Color.rgb(255, 255, 255), Color.rgb(235, 247, 252), Color.rgb(196, 222, 240))
                }
                cornerRadius = CORNER_RADIUS_DP * density
                if (dark) {
                    setStroke((1 * density).toInt(), Color.argb(200, 104, 133, 151))
                } else {
                    setStroke((1 * density).toInt(), Color.argb(210, 205, 225, 235))
                }
                val opacity = runCatching {
                    Prefs.getAgentInt(Prefs.Keys.AGENT_BUBBLE_OPACITY_PCT, 100)
                }.getOrDefault(100).coerceIn(40, 100)
                alpha = opacity * 255 / 100
            }
            this.background = background

            entry.icon?.let { icon ->
                val imageView = ImageView(context)
                val cloned = runCatching { icon.constantState?.newDrawable()?.mutate() ?: icon }.getOrDefault(icon)
                cloned.alpha = 255
                imageView.setImageDrawable(cloned)
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                val params = LayoutParams((ICON_SIZE_DP * density).toInt(), (ICON_SIZE_DP * density).toInt())
                params.rightMargin = (ICON_MARGIN_DP * density).toInt()
                addView(imageView, params)
            }

            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleView = TextView(context).apply {
                text = if (count > 1) "${entry.appLabel} · ${entry.title}" else entry.appLabel
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
            column.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            column.addView(
                bodyView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (BODY_TOP_MARGIN_DP * density).toInt()
                },
            )
            addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

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
            if (hasPrevious) {
                dotPaint.color = if (dark) Color.argb(160, 205, 231, 242) else Color.argb(160, 120, 160, 190)
                canvas.drawCircle(width - 7 * density, 6 * density, 2.4f * density, dotPaint)
            }
            canvas.restoreToCount(save)
        }
    }

    /**
     * 悬浮球：照抄原模块 r0 的绘制。
     *
     * 半径 29dp；下方偏移 3dp 的径向阴影；中心偏 (7dp, 8dp)、半径 29×1.35 的径向渐变主体；
     * 27dp 内裁剪的高光层（径向白 26%、椭圆白描边 3.2dp、SweepGradient 外环 1.15dp）；
     * 40dp 图标；右上偏移 (22dp, -22dp) 的 12dp 亮角标（径向白 + 描边 + 加粗数字）。
     */
    private class BubbleBallView(context: Context, private val dark: Boolean) : View(context) {

        private val density = resources.displayMetrics.density
        private var icon: Drawable? = null
        private var count = 0

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
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            color = if (dark) Color.rgb(238, 246, 250) else Color.rgb(37, 48, 58)
        }
        private val clipPath = Path()
        private var prepared = false

        fun bind(drawable: Drawable?, badgeCount: Int) {
            icon = runCatching { drawable?.constantState?.newDrawable()?.mutate() ?: drawable }.getOrNull()
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
            shadowPaint.shader = android.graphics.RadialGradient(
                cx, cy + dp(3f), radius + dp(7f),
                if (dark) Color.argb(75, 0, 0, 0) else Color.argb(44, 0, 0, 0),
                Color.TRANSPARENT,
                android.graphics.Shader.TileMode.CLAMP,
            )
            bodyPaint.shader = android.graphics.RadialGradient(
                cx - dp(7f), cy - dp(8f), radius * 1.35f,
                if (dark) Color.argb(125, 133, 171, 194) else Color.argb(122, 255, 255, 255),
                if (dark) Color.argb(90, 45, 65, 79) else Color.argb(68, 226, 234, 242),
                android.graphics.Shader.TileMode.CLAMP,
            )
            highlightPaint.shader = android.graphics.RadialGradient(
                cx - dp(9f), cy - dp(11f), radius * 1.28f,
                Color.argb(66, 255, 255, 255), Color.argb(18, 176, 216, 238),
                android.graphics.Shader.TileMode.CLAMP,
            )
            ringPaint.shader = android.graphics.SweepGradient(
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
                badgeShadow.shader = android.graphics.RadialGradient(
                    bx, by + dp(1.5f), badgeRadius + dp(4f),
                    Color.argb(70, 0, 0, 0),
                    Color.TRANSPARENT,
                    android.graphics.Shader.TileMode.CLAMP,
                )
                badgeFill.shader = android.graphics.RadialGradient(
                    bx - dp(3f), by - dp(4f), badgeRadius * 1.45f,
                    Color.argb(245, 255, 255, 255),
                    Color.argb(220, 212, 235, 247),
                    android.graphics.Shader.TileMode.CLAMP,
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
    }
}
