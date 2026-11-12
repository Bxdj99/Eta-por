package io.github.mangi.eta.hook.system

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import kotlin.math.abs
import java.util.ArrayDeque

/**
 * 在 SystemUI 进程里复刻原模块的悬浮球（对应 fake 的 r0 + BubbleNotificationHook 手势部分）。
 *
 * 行为对齐（读自 BubbleNotificationHook 与 r0）：
 * - 有通知才出现；球上显示应用图标 + 未读角标
 * - 点击 → 队列头出栈并打开对应应用（原模块走 openNotificationInFreeform，这里退化为 PendingIntent/启动应用）
 * - 下滑 → 队列头出栈，淡出；队列非空则继续显示下一条
 * - 上滑 → 切到队列里的下一条（原模块是 upSwipeIndex 循环，这里同样循环并更新球上图标/角标）
 * - 长按 450ms 后拖动 → 跟手移动；松手靠边 52dp 内吸附半隐，拖到底部 52dp 内删除
 * - 静止球：位置记忆
 *
 * 通知来源同原模块：hook SystemUI 的 NotificationListener.onNotificationPosted。
 */
internal object BubbleHook {

    private const val HOLD_MS = 450L
    private const val EDGE_DP = 52f
    private const val SWIPE_DP = 38f
    private const val CENTER_TAP_DP = 37f
    private const val CARD_TIMEOUT_MS = 8_000L
    /** 原模块 timeout_seconds（3–15 秒）的默认档，用于超时淡化。 */
    private const val BALL_DIM_DELAY_MS = 6_000L
    private val dimRunnable = Runnable {
        val view = ball ?: return@Runnable
        when {
            cfgBool(Prefs.Keys.AGENT_BUBBLE_AUTO_DISMISS, false) -> {
                // 对齐 bubble_auto_dismiss：超时直接缩小淡出并清空队列
                queue.clear()
                view.animate()
                    .alpha(0f)
                    .scaleX(0.6f)
                    .scaleY(0.6f)
                    .setDuration(200L)
                    .withEndAction { removeBall() }
                    .start()
            }
            cfgBool(Prefs.Keys.AGENT_BUBBLE_TIMEOUT_DIM, true) -> {
                // 对齐 bubble_timeout_dim：压到 0.35，触摸恢复
                view.animate().alpha(0.35f).setDuration(180L).start()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Entry>()
    private var swipeIndex = 0

    private var window: WindowManager? = null
    private var ball: BubbleBallLayer? = null
    private var params: WindowManager.LayoutParams? = null
    private var host: Context? = null
    private var logger: ModuleLogger? = null
    private var card: BubbleCardLayer? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private val hideCardRunnable = Runnable { hideCard() }

    // 手势状态
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longPressed = false
    /** 球当前是否吸附在左右边缘（供 edge_only 与卡片定位使用）。 */
    private var docked = false
    private var breathAnimator: android.animation.ValueAnimator? = null
    private val holdRunnable = Runnable {
        longPressed = true
        ball?.alpha = 1f
    }

    private data class Entry(
        val key: String,
        val packageName: String,
        val title: String,
        val text: String,
        val icon: Drawable?,
        val intent: PendingIntent?,
    )

    fun install(hooks: HookRegistrar, classLoader: ClassLoader, logger: ModuleLogger) {
        this.logger = logger
        val appClass = HookSupport.findClassOrNull(classLoader, "com.android.systemui.SystemUIApplication")
        val onCreate = appClass?.let { HookSupport.findMethod(it, "onCreate") }
        if (onCreate != null) {
            hooks.intercept(
                id = "systemui.bubble-host",
                executable = onCreate,
                description = "SystemUIApplication.onCreate 之后准备悬浮球宿主",
            ) { chain ->
                val result = chain.proceed()
                runCatching {
                    (chain.thisObject as? Application)?.let { attachHost(it) }
                }.onFailure { logger.warn("BubbleHook host failed: ${it.javaClass.simpleName}") }
                result
            }
        } else {
            logger.warn("BubbleHook: SystemUIApplication.onCreate not found")
        }

        // 通知来源：SystemUI 自己的 NotificationListener（与原模块同一入口）
        val listenerClass = HookSupport.findClassOrNull(
            classLoader,
            "com.android.systemui.statusbar.NotificationListener",
        )
        val onPosted = listenerClass?.let { HookSupport.findMethod(it, "onNotificationPosted", StatusBarNotification::class.java, android.service.notification.NotificationListenerService.RankingMap::class.java) }
        if (onPosted != null) {
            hooks.intercept(
                id = "systemui.bubble-notifications",
                executable = onPosted,
                description = "SystemUI NotificationListener.onNotificationPosted → 悬浮球队列",
            ) { chain ->
                val result = chain.proceed()
                runCatching {
                    (chain.args.getOrNull(0) as? StatusBarNotification)?.let { onNotificationPosted(it) }
                }
                result
            }
        } else {
            logger.warn("BubbleHook: NotificationListener.onNotificationPosted not found")
        }
    }

    private fun attachHost(application: Application) {
        if (host != null) return
        host = application
    }

    // ---- 配置读取（SystemUI 进程只能读框架下发的 remote prefs） ----

    private fun cfgBool(key: String, default: Boolean): Boolean =
        runCatching { Prefs.isEnabled(key) }.getOrDefault(default)

    private fun cfgInt(key: String, default: Int): Int =
        runCatching { Prefs.getRemoteInt(key, default) }.getOrDefault(default)

    private fun cfgString(key: String, default: String): String =
        runCatching { Prefs.getRemoteString(key, default) }.getOrDefault(default)

    private fun parseList(raw: String): Set<String> = runCatching {
        val array = org.json.JSONArray(raw)
        buildSet {
            for (index in 0 until array.length()) add(array.optString(index))
        }
    }.getOrDefault(emptySet())

    /** 黑白名单：白名单非空时只放行白名单，黑名单始终排除。 */
    private fun allowed(packageName: String): Boolean {
        val white = parseList(cfgString(Prefs.Keys.AGENT_BUBBLE_WHITELIST_JSON, "[]"))
        val black = parseList(cfgString(Prefs.Keys.AGENT_BUBBLE_BLACKLIST_JSON, "[]"))
        if (packageName in black) return false
        if (white.isNotEmpty() && packageName !in white) return false
        return true
    }

    private fun orientationKey(keyPortrait: String, keyLandscape: String): String {
        val context = host ?: return keyPortrait
        val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return if (landscape) keyLandscape else keyPortrait
    }

    private fun onNotificationPosted(sbn: StatusBarNotification) {
        val context = host ?: return
        if (!cfgBool(Prefs.Keys.AGENT_BUBBLE_NOTIFICATIONS, true)) return
        if (!allowed(sbn.packageName)) return
        val notification = sbn.notification ?: return
        val flags = notification.flags
        if (flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) return
        if (flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0) return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val icon = runCatching {
            notification.smallIcon?.loadDrawable(context.createPackageContext(sbn.packageName, 0))
                ?: context.packageManager.getApplicationIcon(sbn.packageName)
        }.getOrElse {
            runCatching { context.packageManager.getApplicationIcon(sbn.packageName) }.getOrNull()
        }
        val entry = Entry(
            key = sbn.key ?: sbn.packageName,
            packageName = sbn.packageName,
            title = title,
            text = text,
            icon = icon,
            intent = notification.contentIntent,
        )
        queue.removeAll { it.key == entry.key }
        queue.addLast(entry)
        swipeIndex = 0
        handler.post { refresh(showCard = true) }
    }

    /** 刷新球：没有条目就移除窗口，有条目就就地更新图标与角标；[showCard] 时同时弹内容卡片。 */
    private fun refresh(showCard: Boolean = false) {
        val context = host ?: return
        val current = queue.firstOrNull()
        if (current == null) {
            removeBall()
            hideCard()
            return
        }
        val view = ball ?: createBall(context) ?: return
        view.bind(current.icon, queue.size)
        view.visibility = android.view.View.VISIBLE
        // 对齐 r0 的超时淡化：静止一段时间后压到 0.35，触摸立刻恢复
        handler.removeCallbacks(dimRunnable)
        handler.postDelayed(dimRunnable, BALL_DIM_DELAY_MS)
        if (showCard) {
            showCard(current)
        } else {
            updateCardContent(current)
        }
    }

    private fun createCard(context: Context): BubbleCardLayer? {
        val manager = window ?: return null
        val density = context.resources.displayMetrics.density
        val metrics = manager.currentWindowMetrics
        val width = minOf((360 * density).toInt(), metrics.bounds.width() - (20 * density).toInt())
        val height = BubbleCardLayer.heightPx(context)
        val layoutParams = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
        }
        val view = BubbleCardLayer(context, false)
        view.setOnTouchListener { _, event -> handleCardTouch(event) }
        return runCatching {
            manager.addView(view, layoutParams)
            card = view
            cardParams = layoutParams
            view
        }.onFailure { logger?.warn("BubbleHook card addView failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private fun showCard(entry: Entry) {
        if (!cfgBool(Prefs.Keys.AGENT_BUBBLE_PREVIEW_ENABLED, true)) return
        if (cfgBool(Prefs.Keys.AGENT_BUBBLE_EDGE_ONLY, false) && !docked) return
        val context = host ?: return
        val manager = window ?: return
        val view = card ?: createCard(context) ?: return
        val layoutParams = cardParams ?: return
        view.bind(entry.icon, entry.title, entry.text, queue.size, queue.size > 1)
        applyCardSize(context, layoutParams)
        positionCard(manager, layoutParams, view)
        view.visibility = android.view.View.VISIBLE
        view.alpha = 1f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
        handler.removeCallbacks(hideCardRunnable)
        handler.postDelayed(hideCardRunnable, cardTimeoutMs(entry))
    }

    /** 卡片宽度：配置值（180–360dp）与会话自适应二选一，都不超过屏宽-20dp。 */
    private fun applyCardSize(context: Context, layoutParams: WindowManager.LayoutParams) {
        val manager = window ?: return
        val density = context.resources.displayMetrics.density
        val metrics = manager.currentWindowMetrics
        val maxWidth = minOf((360 * density).toInt(), metrics.bounds.width() - (20 * density).toInt())
        var width = ((cfgInt(Prefs.Keys.AGENT_BUBBLE_WIDTH_DP, 360).coerceIn(180, 360)) * density).toInt()
        width = minOf(width, maxWidth)
        if (cfgBool(Prefs.Keys.AGENT_BUBBLE_AUTO_WIDTH, false)) {
            val entry = queue.firstOrNull()
            if (entry != null) {
                val paint = android.graphics.Paint()
                paint.textSize = 13f * density
                val measured = maxOf(paint.measureText(entry.text), paint.measureText(entry.title))
                val padding = (34 + 10 + 20 + 16) * density
                width = (measured + padding).toInt().coerceIn((180 * density).toInt(), maxWidth)
            }
        }
        layoutParams.width = width
        layoutParams.height = BubbleCardLayer.heightPx(context)
    }

    /** 停留时长：配置 2–15 秒；开启动态时长时按文本长度加档（最多 +6 秒）。 */
    private fun cardTimeoutMs(entry: Entry): Long {
        val seconds = cfgInt(Prefs.Keys.AGENT_BUBBLE_TIMEOUT_SEC, 8).coerceIn(2, 15)
        if (!cfgBool(Prefs.Keys.AGENT_BUBBLE_DYNAMIC_TIMEOUT, true)) return seconds * 1000L
        val length = entry.text.length + entry.title.length
        val extra = (length / 20).coerceIn(0, 6)
        return (seconds + extra).coerceAtMost(20) * 1000L
    }

    private fun updateCardContent(entry: Entry) {
        val view = card ?: return
        view.bind(entry.icon, entry.title, entry.text, queue.size, queue.size > 1)
    }

    private fun hideCard() {
        handler.removeCallbacks(hideCardRunnable)
        val view = card ?: return
        view.animate().alpha(0f).setDuration(160L).withEndAction {
            view.visibility = android.view.View.GONE
            view.alpha = 1f
        }.start()
    }

    private fun removeCard() {
        handler.removeCallbacks(hideCardRunnable)
        val view = card ?: return
        runCatching { window?.removeView(view) }
        card = null
        cardParams = null
    }

    /**
     * 卡片定位，逐行照抄 C0.n()：
     *
     * 可用区 = 屏宽/高 去掉系统栏 inset 再各留 10dp；卡片默认贴在球旁边
     * （球在左半屏 → 卡片在球右侧，右半屏 → 卡片在球左侧，垂直居中对齐球心）；
     * 若与球相交，则依次尝试“上 / 下 / 右 / 左”四个候选位，取第一个不相交的。
     */
    private fun positionCard(
        manager: WindowManager,
        layoutParams: WindowManager.LayoutParams,
        view: BubbleCardLayer,
    ) {
        val ballParams = params ?: return
        val metrics = manager.currentWindowMetrics
        val density = view.resources.displayMetrics.density
        val margin = (10 * density).toInt()
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
        )
        val screenWidth = metrics.bounds.width()
        val screenHeight = metrics.bounds.height()
        val cardWidth = layoutParams.width
        val cardHeight = layoutParams.height

        val left = insets.left + margin
        val right = (screenWidth - insets.right - cardWidth - margin).coerceAtLeast(left)
        val top = insets.top + margin
        val bottom = (screenHeight - insets.bottom - cardHeight - margin).coerceAtLeast(top)

        val ballRect = Rect(
            ballParams.x,
            ballParams.y,
            ballParams.x + ballParams.width,
            ballParams.y + ballParams.height,
        )
        val ballCenterX = ballRect.centerX()
        val ballCenterY = ballRect.centerY()

        var x = if (ballCenterX < screenWidth / 2) {
            ballRect.right + margin
        } else {
            ballRect.left - cardWidth - margin
        }
        var y = ballCenterY - cardHeight / 2
        x = x.coerceIn(left, right)
        y = y.coerceIn(top, bottom)

        val occupied = Rect(x, y, x + cardWidth, y + cardHeight)
        if (Rect.intersects(occupied, ballRect)) {
            val centerY = (ballCenterY - cardHeight / 2).coerceIn(top, bottom)
            val candidates = listOf(
                (ballCenterX - cardWidth / 2).coerceIn(left, right) to (ballRect.top - cardHeight - margin),
                (ballCenterX - cardWidth / 2).coerceIn(left, right) to (ballRect.bottom + margin),
                (ballRect.right + margin).coerceIn(left, right) to centerY,
                (ballRect.left - cardWidth - margin).coerceIn(left, right) to centerY,
            )
            for ((cx, cy) in candidates) {
                val candidateY = cy.coerceIn(top, bottom)
                val rect = Rect(cx, candidateY, cx + cardWidth, candidateY + cardHeight)
                if (!Rect.intersects(rect, ballRect)) {
                    layoutParams.x = cx
                    layoutParams.y = candidateY
                    runCatching { manager.updateViewLayout(view, layoutParams) }
                    return
                }
            }
        }
        layoutParams.x = x
        layoutParams.y = y
        runCatching { manager.updateViewLayout(view, layoutParams) }
    }

    /** 卡片上的手势：下滑关闭、点击打开（对齐 C0 的预览交互）。 */
    private fun handleCardTouch(event: MotionEvent): Boolean {
        val view = card ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val density = view.resources.displayMetrics.density
                if (abs(dy) > 24f * density || abs(dx) > 24f * density) {
                    if (dy > 0) onSwipeDown() else onSwipeUp()
                } else {
                    onTap()
                }
                return true
            }
        }
        return false
    }

    private fun createBall(context: Context): BubbleBallLayer? {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val size = BubbleBallLayer.sizePx(context)
        val density = context.resources.displayMetrics.density
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
            )
            // 位置记忆按百分比（原模块 portrait_x/y_percent + dock_edge），横竖屏各存一份
            val xPercent = cfgInt(orientationKey(Prefs.Keys.AGENT_BUBBLE_X_PERCENT, Prefs.Keys.AGENT_BUBBLE_LAND_X_PERCENT), 100)
            val yPercent = cfgInt(orientationKey(Prefs.Keys.AGENT_BUBBLE_Y_PERCENT, Prefs.Keys.AGENT_BUBBLE_LAND_Y_PERCENT), 35)
            val dock = cfgInt(orientationKey(Prefs.Keys.AGENT_BUBBLE_DOCK_EDGE, Prefs.Keys.AGENT_BUBBLE_LAND_DOCK_EDGE), 0)
            val usableWidth = (metrics.bounds.width() - insets.left - insets.right - size).coerceAtLeast(1)
            val usableHeight = (metrics.bounds.height() - insets.top - insets.bottom - size).coerceAtLeast(1)
            x = insets.left + (xPercent * usableWidth / 100)
            y = insets.top + (yPercent * usableHeight / 100)
            if (dock < 0) x = -size / 2
            if (dock > 0) x = metrics.bounds.width() - size / 2
            windowAnimations = 0
        }
        val view = BubbleBallLayer(context, false)
        view.setOnTouchListener { _, event -> handleTouch(event) }
        return runCatching {
            manager.addView(view, layoutParams)
            window = manager
            ball = view
            params = layoutParams
            startBreathing(view)
            wakeScreenIfNeeded(context)
            view
        }.onFailure { logger?.warn("BubbleHook addView failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /** 对齐 decoration_continuous：球静止时做一次缓慢的呼吸缩放，关闭开关则保持静止。 */
    private fun startBreathing(view: BubbleBallLayer) {
        breathAnimator?.cancel()
        breathAnimator = null
        if (!cfgBool(Prefs.Keys.AGENT_BUBBLE_DECORATION, true)) return
        breathAnimator = android.animation.ValueAnimator.ofFloat(1f, 1.03f).apply {
            duration = 1600L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                view.scaleX = scale
                view.scaleY = scale
            }
            start()
        }
    }

    /** 对齐 screen_awake_prompt：灭屏时来通知先点亮屏幕几秒。 */
    private fun wakeScreenIfNeeded(context: Context) {
        if (!cfgBool(Prefs.Keys.AGENT_BUBBLE_WAKE_SCREEN, true)) return
        runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
            if (power.isInteractive) return
            val lock = power.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Eta:bubble-wake",
            )
            lock.acquire(3000L)
        }
    }

    private fun removeBall() {
        val view = ball ?: return
        runCatching { window?.removeView(view) }
        ball = null
        params = null
        removeCard()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val view = ball ?: return false
        val current = params ?: return false
        val manager = window ?: return false
        val density = view.resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = current.x
                startY = current.y
                dragging = false
                longPressed = false
                view.alpha = 1f
                handler.removeCallbacks(dimRunnable)
                handler.postDelayed(holdRunnable, HOLD_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging) {
                    if (abs(dx) * abs(dx) + abs(dy) * abs(dy) > (8f * density) * (8f * density)) {
                        // 只有长按之后移动才进入拖动；否则算滑动，交给 UP 判定
                        if (longPressed) dragging = true else handler.removeCallbacks(holdRunnable)
                    }
                }
                if (dragging) {
                    val metrics = manager.currentWindowMetrics
                    val half = current.width / 2
                    current.x = (startX + dx.toInt()).coerceIn(-half, metrics.bounds.width() - half)
                    current.y = (startY + dy.toInt()).coerceIn(0, metrics.bounds.height() - current.height)
                    runCatching { manager.updateViewLayout(view, current) }
                    // 卡片跟着球一起挪（对齐原模块的 onLayoutChangedAction）
                    val followCard = card
                    val followParams = cardParams
                    if (followCard != null && followParams != null && followCard.visibility == android.view.View.VISIBLE) {
                        positionCard(manager, followParams, followCard)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdRunnable)
                if (dragging) {
                    settle(current, view, manager, density)
                    dragging = false
                    longPressed = false
                    return true
                }
                if (longPressed) {
                    longPressed = false
                    toggleStack()
                    return true
                }
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val threshold = SWIPE_DP * density
                when {
                    abs(dy) > threshold && abs(dy) > abs(dx) -> {
                        if (dy < 0) onSwipeUp() else onSwipeDown()
                    }
                    abs(dx) > threshold -> onSwipeDown()
                    else -> {
                        val cx = view.width / 2f
                        val cy = view.height / 2f
                        val tx = event.x - cx
                        val ty = event.y - cy
                        val r = CENTER_TAP_DP * density
                        if (tx * tx + ty * ty <= r * r) onTap()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                if (dragging) settle(current, view, manager, density)
                dragging = false
                return true
            }
        }
        return false
    }

    /**
     * 松手结算，逐行照抄 r0.l()：
     * 1) y 先夹到 [8dp+topInset, 屏高-bottomInset-高度-12dp]
     * 2) 掉进底部 52dp 区 → 缩小淡出删除（260ms / DecelerateInterpolator(1.6) / scale 0.38 / 下移 76dp）
     * 3) 球心离左右边缘 ≤52dp → 吸附（b()：260ms DecelerateInterpolator(1.7)，alpha 0.5）
     * 4) 其余回弹到边界内，220ms alpha/scale 归位
     */
    private fun settle(
        current: WindowManager.LayoutParams,
        view: BubbleBallLayer,
        manager: WindowManager,
        density: Float,
    ) {
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
        )
        val screenWidth = metrics.bounds.width()
        val screenHeight = metrics.bounds.height()
        val margin = 8 * density
        val top = (insets.top + margin).toInt()
        val bottomLimit = (screenHeight - insets.bottom - current.height - (12 * density))
            .toInt()
            .coerceAtLeast(top)
        current.y = current.y.coerceIn(top, bottomLimit)
        val edge = EDGE_DP * density
        if (current.y >= bottomLimit - edge) {
            view.animate()
                .alpha(0f)
                .scaleX(0.38f)
                .scaleY(0.38f)
                .translationY(76f * density)
                .setDuration(260L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .withEndAction { removeBall() }
                .start()
            return
        }
        val center = current.x + current.width / 2f
        val isDocked = when {
            center <= edge -> {
                dock(current, view, manager, -1, screenWidth)
                true
            }
            center >= screenWidth - edge -> {
                dock(current, view, manager, 1, screenWidth)
                true
            }
            else -> false
        }
        docked = isDocked
        if (!isDocked) {
            current.x = current.x.coerceIn(
                (insets.left + margin).toInt(),
                (screenWidth - insets.right - current.width - margin).coerceAtLeast(0f).toInt(),
            )
            runCatching { manager.updateViewLayout(view, current) }
        }
        view.animate()
            .cancel()
        view.animate()
            .alpha(if (isDocked) 0.5f else 1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        // 吸附动画结束后让卡片跟随到球的最终位置
        handler.postDelayed({
            val followCard = card
            val followParams = cardParams
            if (followCard != null && followParams != null && followCard.visibility == android.view.View.VISIBLE) {
                positionCard(manager, followParams, followCard)
            }
        }, 300L)
    }

    private fun dock(
        current: WindowManager.LayoutParams,
        view: BubbleBallLayer,
        manager: WindowManager,
        side: Int,
        screenWidth: Int,
    ) {
        val target = if (side < 0) -current.width / 2 else screenWidth - current.width / 2
        val animator = android.animation.ValueAnimator.ofInt(current.x, target)
        animator.duration = 260L
        animator.interpolator = android.view.animation.DecelerateInterpolator(1.7f)
        animator.addUpdateListener { value ->
            current.x = value.animatedValue as Int
            runCatching { manager.updateViewLayout(view, current) }
        }
        animator.start()
        view.animate().alpha(0.5f).setDuration(180L).start()
    }

    /** 点击：出栈并打开应用（对齐 activateQueuedNotification）。 */
    private fun onTap() {
        val entry = queue.pollFirst() ?: return
        swipeIndex = 0
        openEntry(entry)
        refresh()
    }

    private var stack: android.view.View? = null

    /** 长按：展开通知栈（对齐 showExpandedStack）。再次长按或点遮罩收起。 */
    private fun toggleStack() {
        if (stack != null) {
            hideStack()
            return
        }
        if (queue.isEmpty()) return
        val context = host ?: return
        val manager = window ?: return
        val items = queue.map { BubbleStackLayer.Item(it.title, it.text, it.icon) }
        val view = BubbleStackLayer(
            context,
            false,
            items,
            onPick = { index ->
                val list = queue.toList()
                val entry = list.getOrNull(index)
                hideStack()
                if (entry != null) {
                    queue.remove(entry)
                    swipeIndex = 0
                    openEntry(entry)
                    refresh()
                }
            },
            onDismiss = { hideStack() },
        )
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        runCatching {
            manager.addView(view, layoutParams)
            stack = view
            hideCard()
            logger?.info("BubbleHook: stack expanded (${items.size})")
        }.onFailure { logger?.warn("BubbleHook stack failed: ${it.javaClass.simpleName}") }
    }

    private fun hideStack() {
        val view = stack ?: return
        runCatching { window?.removeView(view) }
        stack = null
    }

    private fun openEntry(entry: Entry) {
        val context = host ?: return
        var sent = false
        entry.intent?.let { pending -> sent = runCatching { pending.send() }.isSuccess }
        if (!sent) {
            runCatching {
                context.packageManager.getLaunchIntentForPackage(entry.packageName)?.apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(this)
                }
            }
        }
        logger?.info("BubbleHook: open ${entry.packageName} sent=$sent")
    }

    /** 下滑：关闭队头（对齐 dismissQueuedNotification），可选地清掉状态栏通知。 */
    private fun onSwipeDown() {
        if (cfgBool(Prefs.Keys.AGENT_BUBBLE_CLEAR_ON_SWIPE, false)) {
            runCatching {
                val manager = host?.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                manager?.cancelAll()
            }
        }
        queue.pollFirst()
        swipeIndex = 0
        val view = ball
        if (view != null) {
            view.animate().alpha(0f).scaleX(0.78f).scaleY(0.78f)
                .translationY(12f * view.resources.displayMetrics.density)
                .setDuration(200L)
                .withEndAction {
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.translationY = 0f
                    refresh(showCard = true)
                }
                .start()
        } else {
            refresh(showCard = true)
        }
    }

    /** 上滑：在队列里循环切到下一条（对齐 handleUpSwipe 的索引轮转），卡片内容跟着换。 */
    private fun onSwipeUp() {
        if (queue.size <= 1) return
        swipeIndex = (swipeIndex + 1) % queue.size
        val list = queue.toList()
        val entry = list.getOrNull(swipeIndex) ?: return
        ball?.bind(entry.icon, queue.size)
        updateCardContent(entry)
        logger?.info("BubbleHook: swipe up → ${entry.packageName} (${swipeIndex + 1}/${queue.size})")
    }
}
