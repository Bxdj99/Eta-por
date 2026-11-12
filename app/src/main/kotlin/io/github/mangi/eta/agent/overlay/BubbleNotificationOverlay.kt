package io.github.mangi.eta.agent.overlay

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * 通知气泡（Bubble Notifications 的原生重实现）。
 *
 * 与原 LSPosed 模块的差别：模块 hook 进 SystemUI 改通知面板本身；这里走
 * [android.service.notification.NotificationListenerService] + 悬浮窗，
 * 只画气泡、不动系统 UI，因此不需要额外常驻进程（Eta 本来就在跑）。
 *
 * 触摸纪律：窗口尺寸紧贴气泡内容，透明区不铺满屏幕，避免吞掉下层触摸。
 */
internal object BubbleNotificationOverlay {

    private const val TAG = "EtaBubble"
    private const val MAX_BUBBLES = 3
    private const val AUTO_DISMISS_MS = 8_000L
    private const val WINDOW_WIDTH_DP = 316
    private const val BUBBLE_HEIGHT_DP = 66
    private const val BUBBLE_GAP_DP = 8
    private const val SWIPE_DISMISS_DP = 28
    private const val DRAG_HOLD_MS = 400L

    private val handler = Handler(Looper.getMainLooper())
    private val iconCache = HashMap<String, Drawable?>()

    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var view: BubbleStackView? = null
    private val bubbles = ArrayList<BubbleEntry>()
    private val dismissRunnable = Runnable { dismissAll(animate = true) }

    /** 通知监听回调入口；主线程执行，异常一律吞掉，绝不影响通知入库。 */
    fun onPosted(context: Context, sbn: StatusBarNotification) {
        runCatching {
            prime(context)
            debug(context, "onPosted pkg=${sbn.packageName} enabled=${enabled()} show=${shouldShow(sbn)}")
            if (!enabled()) return
            if (!shouldShow(sbn)) return
            val entry = buildEntry(context.applicationContext, sbn) ?: return
            debug(context, "queue ${entry.packageName} / ${entry.title}")
            handler.post { show(context.applicationContext, entry) }
        }.onFailure { debug(context, "onPosted failed: $it") }
    }

    private fun debug(context: Context, message: String) {
        runCatching {
            val file = java.io.File(context.filesDir, "bubble_debug.log")
            if (file.length() > 64 * 1024) file.delete()
            file.appendText("${System.currentTimeMillis()} $message\n")
        }
    }

    fun dismissAll(animate: Boolean = false) {
        handler.removeCallbacks(dismissRunnable)
        val target = view ?: run {
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
            for (index in 0 until array.length()) {
                add(array.optString(index))
            }
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
        if (view == null && !attach(context)) {
            debug(context, "attach failed for ${entry.packageName}")
            return
        }
        val stack = view ?: return
        bubbles.removeAll { it.key == entry.key }
        while (bubbles.size >= MAX_BUBBLES) bubbles.removeAt(0)
        bubbles.add(entry)
        stack.alpha = 1f
        stack.invalidate()
        updateWindowSize()
        scheduleDismiss()
        debug(context, "shown ${entry.packageName} count=${bubbles.size}")
    }

    private fun scheduleDismiss() {
        handler.removeCallbacks(dismissRunnable)
        handler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
    }

    private fun hostContext(fallback: Context): Context =
        AgentAccessibilityService.current() ?: fallback

    private fun attach(fallback: Context): Boolean {
        val context = hostContext(fallback)
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val density = context.resources.displayMetrics.density
        val width = (WINDOW_WIDTH_DP * density).toInt()
        val height = windowHeightPx(context)
        val layoutParams = WindowManager.LayoutParams(
            width,
            height,
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
        val stack = BubbleStackView(context, density).apply {
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
            view = stack
            updateWindowSize()
            true
        }.onFailure { android.util.Log.w(TAG, "addView failed", it) }.getOrDefault(false)
    }

    private fun detach() {
        val stack = view ?: return
        runCatching { windowManager?.removeView(stack) }
        view = null
        params = null
        windowManager = null
        bubbles.clear()
        handler.removeCallbacks(dismissRunnable)
    }

    private fun dismissAt(index: Int) {
        if (index !in bubbles.indices) return
        bubbles.removeAt(index)
        val stack = view ?: return
        if (bubbles.isEmpty()) {
            detach()
            return
        }
        stack.invalidate()
        updateWindowSize()
        scheduleDismiss()
    }

    private fun activateAt(index: Int) {
        val entry = bubbles.getOrNull(index) ?: return
        val context = hostContext(lastContext ?: return)
        dismissAt(index)
        val sent = runCatching { entry.contentIntent?.send() }.isSuccess && entry.contentIntent != null
        if (sent) return
        runCatching {
            val intent: Intent? = context.packageManager.getLaunchIntentForPackage(entry.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
            }
        }
    }

    private fun updateWindowSize() {
        val stack = view ?: return
        val current = params ?: return
        val manager = windowManager ?: return
        val height = windowHeightPx(stack.context)
        if (current.height == height) return
        current.height = height
        runCatching { manager.updateViewLayout(stack, current) }
    }

    private fun windowHeightPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        val count = bubbles.size.coerceAtLeast(1)
        val dpHeight = (BUBBLE_HEIGHT_DP * count) + (BUBBLE_GAP_DP * (count - 1)) + 8
        return (dpHeight * density).toInt()
    }

    private fun snapToEdge() {
        val current = params ?: return
        val stack = view ?: return
        val manager = windowManager ?: return
        val metrics = runCatching { manager.currentWindowMetrics }.getOrNull() ?: return
        val screenWidth = metrics.bounds.width()
        val screenHeight = metrics.bounds.height()
        val margin = (8 * stack.resources.displayMetrics.density).toInt()
        current.x = if (current.x + current.width / 2 < screenWidth / 2) margin else screenWidth - current.width - margin
        current.y = current.y.coerceIn(margin, (screenHeight - current.height - margin).coerceAtLeast(margin))
        runCatching { manager.updateViewLayout(stack, current) }
        lastContext?.let { context ->
            Prefs.localAgentPreferences()?.edit()
                ?.putString(Prefs.Keys.AGENT_BUBBLE_POSITION, "${current.x},${current.y}")
                ?.apply()
        }
    }

    private fun savedPosition(context: Context, width: Int): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = runCatching { manager?.currentWindowMetrics }.getOrNull()
        val screenWidth = metrics?.bounds?.width() ?: (1080)
        val screenHeight = metrics?.bounds?.height() ?: (2400)
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

    @Volatile
    private var lastContext: Context? = null

    /** 记录最近一次宿主 Context（attach 时需要拿到 application context 或无障碍 context）。 */
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

    /** 自绘气泡栈：一个窗口承载最多 3 个气泡，避免多窗口各自抢触摸。 */
    @SuppressLint("ViewConstructor")
    private class BubbleStackView(
        context: Context,
        private val density: Float,
    ) : View(context) {

        var onDismissAt: ((Int) -> Unit)? = null
        var onActivateAt: ((Int) -> Unit)? = null
        var onMoveWindow: ((Int, Int) -> Unit)? = null
        var onReleaseWindow: (() -> Unit)? = null

        private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2202124.toInt() }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = 0x26FFFFFF
        }
        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt()
            textSize = 11f * density
        }
        private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF5F6F8.toInt()
            textSize = 13.5f * density
        }
        private val rect = RectF()
        private val iconRect = RectF()

        private val bubbleHeight = BUBBLE_HEIGHT_DP * density
        private val gap = BUBBLE_GAP_DP * density
        private val radius = 20f * density
        private val padding = 14f * density
        private val iconSize = 34f * density

        private var downX = 0f
        private var downY = 0f
        private var activeIndex = -1
        private var dragging = false
        private var lastX = 0f
        private var lastY = 0f
        private val dragRunnable = Runnable {
            if (activeIndex >= 0) dragging = true
        }

        override fun onDraw(canvas: Canvas) {
            val entries = bubbles
            if (entries.isEmpty()) return
            entries.forEachIndexed { index, entry ->
                drawBubble(canvas, index, entry)
            }
        }

        private fun drawBubble(canvas: Canvas, index: Int, entry: BubbleEntry) {
            val top = index * (bubbleHeight + gap)
            val bottom = top + bubbleHeight
            rect.set(0f, top, width.toFloat(), bottom)
            canvas.drawRoundRect(rect, radius, radius, background)
            canvas.drawRoundRect(rect, radius, radius, border)

            val iconLeft = padding
            val iconTop = top + (bubbleHeight - iconSize) / 2f
            iconRect.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            val icon = entry.icon
            if (icon != null) {
                icon.setBounds(
                    iconRect.left.toInt(),
                    iconRect.top.toInt(),
                    iconRect.right.toInt(),
                    iconRect.bottom.toInt(),
                )
                icon.draw(canvas)
            }

            val textLeft = iconRect.right + 12f * density
            val textRight = width - padding - 8f * density
            val textWidth = (textRight - textLeft).coerceAtLeast(10f)
            val titleBaseline = top + 22f * density
            val bodyBaseline = top + 42f * density
            val titleText = TextUtils.ellipsize(entry.appLabel, titlePaint, textWidth, TextUtils.TruncateAt.END)
            val bodyText = TextUtils.ellipsize(entry.text.ifBlank { entry.title }, bodyPaint, textWidth, TextUtils.TruncateAt.END)
            canvas.drawText(titleText, 0, titleText.length, textLeft, titleBaseline, titlePaint)
            canvas.drawText(bodyText, 0, bodyText.length, textLeft, bodyBaseline, bodyPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val entries = bubbles
            if (entries.isEmpty()) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastX = event.rawX
                    lastY = event.rawY
                    dragging = false
                    activeIndex = indexAt(event.y)
                    if (activeIndex < 0) return false
                    postDelayed(dragRunnable, DRAG_HOLD_MS)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeIndex < 0) return false
                    if (dragging) {
                        val dx = (event.rawX - lastX).toInt()
                        val dy = (event.rawY - lastY).toInt()
                        lastX = event.rawX
                        lastY = event.rawY
                        if (dx != 0 || dy != 0) onMoveWindow?.invoke(dx, dy)
                        return true
                    }
                    val deltaY = event.y - downY
                    if (deltaY < -SWIPE_DISMISS_DP * density && kotlin.math.abs(event.x - downX) < width * 0.6f) {
                        removeCallbacks(dragRunnable)
                        onDismissAt?.invoke(activeIndex)
                        activeIndex = -1
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(dragRunnable)
                    if (activeIndex >= 0) {
                        if (dragging) {
                            onReleaseWindow?.invoke()
                        } else {
                            onActivateAt?.invoke(activeIndex)
                        }
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
            if (bubbles.isEmpty()) return -1
            val slot = (y / (bubbleHeight + gap)).toInt()
            return if (slot in bubbles.indices) slot else -1
        }
    }
}
