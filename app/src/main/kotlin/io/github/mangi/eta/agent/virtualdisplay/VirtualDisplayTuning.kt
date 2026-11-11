package io.github.mangi.eta.agent.virtualdisplay

import io.github.mangi.eta.config.Prefs
import kotlin.math.roundToInt

/**
 * 灵屏的可调参数：分辨率、帧率、闲置宽限时间。
 *
 * 全部落在 App 私有的 `eta_agent_preferences` 里（和灵屏其它开关同一份），
 * 读取点只有这里，[VirtualDisplayController] 与设置页都走同一个入口，避免两处默认值漂移。
 *
 * - 分辨率用「目标高度」表达：0 表示跟随物理屏；降低时 **densityDpi 同比缩小**，
 *   这样应用里的 dp 尺寸不变，只是像素变少（省一点合成与取帧开销），不会把界面挤变形。
 * - 帧率同时约束两件事：虚拟屏的 requestedRefreshRate，以及取帧后刷新预览的最小间隔。
 * - 宽限 0 表示「不自动拆屏」，灵屏会一直留到用户主动关或进程结束。
 */
internal object VirtualDisplayTuning {

    const val KEY_HEIGHT = "agent_virtual_display_height"
    const val KEY_FPS = "agent_virtual_display_fps"
    const val KEY_GRACE_MINUTES = "agent_virtual_display_grace_minutes"

    const val DEFAULT_HEIGHT = 0
    const val DEFAULT_FPS = 0
    const val DEFAULT_GRACE_MINUTES = 120

    val HEIGHT_OPTIONS = listOf(0, 1280, 1600, 1920, 2400)
    val FPS_OPTIONS = listOf(0, 15, 30, 60, 120)
    val GRACE_OPTIONS = listOf(0, 15, 30, 60, 120, 240)

    fun heightPx(): Int = Prefs.getAgentInt(KEY_HEIGHT, DEFAULT_HEIGHT)
    fun fps(): Int = Prefs.getAgentInt(KEY_FPS, DEFAULT_FPS)
    fun graceMinutes(): Int = Prefs.getAgentInt(KEY_GRACE_MINUTES, DEFAULT_GRACE_MINUTES)

    /** 闲置多久之后拆屏；<= 0 表示不自动拆。 */
    fun graceMillis(): Long = graceMinutes().let { if (it <= 0) 0L else it * 60_000L }

    /**
     * 把「目标高度」换算成实际建屏尺寸与 density。
     * 高度不比物理屏小（或配置为 0）时原样返回，不做无意义的放大。
     */
    fun scaledMetrics(physicalWidth: Int, physicalHeight: Int, physicalDensity: Int): Triple<Int, Int, Int> {
        val targetHeight = heightPx()
        if (targetHeight <= 0 || targetHeight >= physicalHeight) {
            return Triple(physicalWidth, physicalHeight, physicalDensity)
        }
        val ratio = targetHeight.toFloat() / physicalHeight.toFloat()
        val width = (physicalWidth * ratio).roundToInt().coerceAtLeast(320)
        val height = targetHeight.coerceAtLeast(320)
        // dp 尺寸保持不变：density 同比缩小（下限 120dpi 是 DisplayManager 能接受的范围）。
        val density = (physicalDensity * ratio).roundToInt().coerceIn(120, 640)
        return Triple(width, height, density)
    }

    /** 预览刷新最小间隔；null 表示沿用「看的时候 30ms、后台 160ms」的旧策略。 */
    fun frameIntervalMs(): Long? {
        val fps = fps()
        if (fps <= 0) return null
        return (1000L / fps).coerceAtLeast(16L)
    }

    /** 虚拟屏请求的刷新率；0 表示不显式设置。 */
    fun requestedRefreshRate(): Float = fps().toFloat().coerceIn(0f, 144f)

    fun heightLabel(value: Int): String = if (value <= 0) "跟随物理屏" else "${value}p"
    fun fpsLabel(value: Int): String = if (value <= 0) "默认" else "$value fps"
    fun graceLabel(value: Int): String = if (value <= 0) "不自动拆（常驻）" else "$value 分钟"
}
