package io.github.mangi.eta.hook.system

import android.app.Application
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger

/**
 * 在 SystemUI 进程里挂悬浮球（原模块的做法：hook 进 SystemUI 自己画 View）。
 *
 * 这里只负责窗口生命周期；球体绘制在 [BubbleBallLayer]，参数照抄原模块 r0。
 * 之所以搬到 SystemUI：主进程的 overlay 窗口受后台限制、层级也不如 SystemUI 高，
 * 而 SystemUI 里创建的窗口天然在通知层之上，交互手感才和原模块一致。
 */
internal object BubbleHook {

    @Volatile
    private var window: WindowManager? = null

    @Volatile
    private var ball: BubbleBallLayer? = null

    @Volatile
    private var layoutParams: WindowManager.LayoutParams? = null

    fun install(hooks: HookRegistrar, classLoader: ClassLoader, logger: ModuleLogger) {
        val appClass = HookSupport.findClassOrNull(classLoader, "com.android.systemui.SystemUIApplication")
        if (appClass == null) {
            logger.warn("BubbleHook: SystemUIApplication not found")
            return
        }
        val onCreate = HookSupport.findMethod(appClass, "onCreate")
        if (onCreate == null) {
            logger.warn("BubbleHook: SystemUIApplication.onCreate not found")
            return
        }
        hooks.intercept(
            id = "systemui.bubble-ball",
            executable = onCreate,
            description = "SystemUIApplication.onCreate 之后挂悬浮球",
        ) { chain ->
            val result = chain.proceed()
            runCatching {
                val application = chain.thisObject as? Application ?: return@runCatching
                attach(application, logger)
            }.onFailure { logger.warn("BubbleHook attach failed: ${it.javaClass.simpleName}") }
            result
        }
    }

    private fun attach(context: Context, logger: ModuleLogger) {
        if (ball != null) return
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val size = BubbleBallLayer.sizePx(context)
        val density = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
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
            x = (context.resources.displayMetrics.widthPixels - size - (24 * density)).toInt()
            y = (420 * density).toInt()
            windowAnimations = 0
        }
        val view = BubbleBallLayer(context, false)
        runCatching {
            manager.addView(view, params)
            window = manager
            ball = view
            layoutParams = params
            logger.info("BubbleHook: ball attached")
        }.onFailure {
            logger.warn("BubbleHook addView failed: ${it.javaClass.simpleName}")
        }
    }

    /** 供后续接入通知时更新球上的图标与角标。 */
    fun updateBall(icon: android.graphics.drawable.Drawable?, count: Int) {
        ball?.bind(icon, count)
    }
}
