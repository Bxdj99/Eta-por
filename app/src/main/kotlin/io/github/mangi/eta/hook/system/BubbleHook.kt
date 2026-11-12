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

    private fun onNotificationPosted(sbn: StatusBarNotification) {
        val context = host ?: return
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
        val context = host ?: return
        val manager = window ?: return
        val view = card ?: createCard(context) ?: return
        val layoutParams = cardParams ?: return
        view.bind(entry.icon, entry.title, entry.text, queue.size, queue.size > 1)
        positionCard(manager, layoutParams, view)
        view.visibility = android.view.View.VISIBLE
        view.alpha = 1f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
        handler.removeCallbacks(hideCardRunnable)
        handler.postDelayed(hideCardRunnable, CARD_TIMEOUT_MS)
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

    /** 卡片贴在球的内侧：球靠右就右对齐，否则左对齐；优先放在球下方，放不下就放上方。 */
    private fun positionCard(
        manager: WindowManager,
        layoutParams: WindowManager.LayoutParams,
        view: BubbleCardLayer,
    ) {
        val ballParams = params ?: return
        val metrics = manager.currentWindowMetrics
        val density = view.resources.displayMetrics.density
        val gap = (8 * density).toInt()
        val screenWidth = metrics.bounds.width()
        val screenHeight = metrics.bounds.height()
        val ballCenterX = ballParams.x + ballParams.width / 2f
        val onRight = ballCenterX > screenWidth / 2f
        layoutParams.x = if (onRight) {
            screenWidth - layoutParams.width - gap
        } else {
            gap
        }
        val below = ballParams.y + ballParams.height + gap
        layoutParams.y = if (below + layoutParams.height <= screenHeight - gap) {
            below
        } else {
            (ballParams.y - layoutParams.height - gap).coerceAtLeast(gap)
        }
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
            x = metrics.bounds.width() - size - (16 * density).toInt()
            y = (metrics.bounds.height() * 0.35f).toInt()
            windowAnimations = 0
        }
        val view = BubbleBallLayer(context, false)
        view.setOnTouchListener { _, event -> handleTouch(event) }
        return runCatching {
            manager.addView(view, layoutParams)
            window = manager
            ball = view
            params = layoutParams
            view
        }.onFailure { logger?.warn("BubbleHook addView failed: ${it.javaClass.simpleName}") }.getOrNull()
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

    /** 松手结算：底部删除、靠边吸附、其余回弹。 */
    private fun settle(
        current: WindowManager.LayoutParams,
        view: BubbleBallLayer,
        manager: WindowManager,
        density: Float,
    ) {
        val metrics = manager.currentWindowMetrics
        val edge = EDGE_DP * density
        val bottom = metrics.bounds.height()
        if (current.y >= bottom - edge) {
            view.animate().alpha(0f).scaleX(0.38f).scaleY(0.38f)
                .translationY(76f * density).setDuration(240L)
                .withEndAction { removeBall() }
                .start()
            return
        }
        val center = current.x + current.width / 2f
        when {
            center <= edge -> dock(current, view, manager, -1, metrics.bounds.width())
            center >= metrics.bounds.width() - edge -> dock(current, view, manager, 1, metrics.bounds.width())
            else -> {
                current.x = current.x.coerceIn(0, metrics.bounds.width() - current.width)
                current.y = current.y.coerceIn(0, bottom - current.height)
                runCatching { manager.updateViewLayout(view, current) }
                view.alpha = 1f
            }
        }
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

    /** 下滑：关闭队头（对齐 dismissQueuedNotification）。 */
    private fun onSwipeDown() {
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
