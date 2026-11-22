package io.github.mangi.eta.agent.virtualdisplay

import android.view.Display
import java.util.Locale

/**
 * 虚拟屏与物理主屏的空间路由契约。
 *
 * 核心原则：
 * 1. 原地延续走物理主屏：目标应用已经处于物理主屏前台（用户正在注视）时，直接在主屏操作，避免抽走 Task 导致闪退回桌面。
 * 2. 系统全局界面走物理主屏：桌面、SystemUI、Eta 自身留在主屏。
 * 3. 跨应用委托默认走后台虚拟屏：从 Eta 聊天页发起，或在 A 应用唤醒但需打开后台 B 应用时，在虚拟屏静默执行。
 * 4. 临门一脚台前呈现：遇到收银台、安全窗口（FLAG_SECURE）或模型调用 present_to_user 时，原位迁移回物理主屏。
 */
internal object CanvasRoutingPolicy {

    const val VIRTUAL_DISPLAY_NAME = "EtaAgentVirtualDisplay"

    // DisplayManager 公开与内部受信任虚拟屏标志位（AOSP DisplayManager.java）
    const val FLAG_PUBLIC = 1 shl 0
    const val FLAG_PRESENTATION = 1 shl 1
    const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val FLAG_TRUSTED = 1 shl 10
    const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val FLAG_OWN_FOCUS = 1 shl 14
    const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    /** android.view.Display.getFlags() 返回的受信任标志位，用于检验虚拟屏是否真正取得系统级 TRUSTED 权限。 */
    const val DISPLAY_FLAG_TRUSTED = 1 shl 7
    const val DISPLAY_FLAG_OWN_FOCUS = 1 shl 11
    const val DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 12

    /** WindowManager IME 显示策略：禁止虚拟屏在主屏回退弹出或抢占软键盘。 */
    const val DISPLAY_IME_POLICY_LOCAL = 0
    const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1
    const val DISPLAY_IME_POLICY_HIDE = 2

    /**
     * 受信任后台交互画布所需的完整标志组合。
     *
     * 两个"看着合理其实是坑"的标志（本机 ColorOS / Android 16 实测）：
     * - 不要 FLAG_OWN_CONTENT_ONLY：AOSP 用它推导 `DisplayDeviceInfo.canHostTasks`，设上就是 false，
     *   于是任何 Activity/Task 都落不到这块屏 —— 表现就是"应用迁不进灵屏、只能退回主屏"。
     * - 不要 FLAG_OWN_FOCUS：独立焦点屏会让 AccessibilityManagerService 只枚举默认 DisplayContent，
     *   节点树永远是空的 —— 表现就是"截图有画面、observe_screen 没有节点"。
     *
     * 其余保留：PUBLIC + SUPPORTS_TOUCH + TRUSTED + OWN_DISPLAY_GROUP（独立 display group）
     * + ALWAYS_UNLOCKED（锁屏下仍可交互）+ STEAL_TOP_FOCUS_DISABLED（不抢主屏焦点）。
     * 不启用 FLAG_ROTATES_WITH_CONTENT（避免跳转旋转时重建 Surface），
     * 不启用 FLAG_DESTROY_CONTENT_ON_REMOVAL（避免释放时误杀应用进程）。
     */
    const val TRUSTED_INTERACTIVE_FLAGS: Int =
        FLAG_PUBLIC or
            FLAG_SUPPORTS_TOUCH or
            FLAG_TRUSTED or
            FLAG_OWN_DISPLAY_GROUP or
            FLAG_ALWAYS_UNLOCKED or
            FLAG_TOUCH_FEEDBACK_DISABLED or
            FLAG_STEAL_TOP_FOCUS_DISABLED

    // Android 13 尚无独立焦点与禁止抢占顶层焦点的组合能力，后台画布从 Android 14 起启用。
    fun supportsBackgroundDisplay(sdkInt: Int): Boolean = sdkInt >= 34

    /**
     * 只硬性要求 TRUSTED。OWN_FOCUS 已经不设（会瞎掉 a11y），
     * STEAL_TOP_FOCUS_DISABLED 在部分 ROM 上不单独回显，拿它当门槛会造成"刚建好就判不合格"的重建死循环。
     */
    fun hasRequiredDisplayFlags(flags: Int): Boolean =
        flags and DISPLAY_FLAG_TRUSTED == DISPLAY_FLAG_TRUSTED

    private val MAIN_DISPLAY_ONLY_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.miui.home",
        "com.oppo.launcher",
        "com.bbk.launcher2",
        "com.huawei.android.launcher",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
    )

    enum class CanvasTarget(val wireName: String) {
        MAIN_DISPLAY("main"),
        VIRTUAL_DISPLAY("virtual"),
    }

    enum class RequestedDisplay {
        AUTO,
        VIRTUAL,
        MAIN;

        companion object {
            fun parse(raw: String?): RequestedDisplay? = when (raw?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
                "", "auto" -> AUTO
                "virtual", "background" -> VIRTUAL
                "main", "foreground", "physical" -> MAIN
                else -> null
            }
        }
    }

    fun decideLaunchTarget(
        virtualDisplayEnabled: Boolean,
        backendAvailable: Boolean,
        requested: RequestedDisplay = RequestedDisplay.AUTO,
        targetPackage: String? = null,
        foregroundPackageOnMainDisplay: String? = null,
        ownPackage: String = "io.github.mangi.eta",
    ): CanvasTarget {
        // 只有用户显式要主屏，才允许落主屏。虚拟屏没开/后端不可用属于「能力缺失」，
        // 不能悄悄降级到主屏去打断用户——调用方应拿到明确的报错。
        if (requested == RequestedDisplay.MAIN) return CanvasTarget.MAIN_DISPLAY
        val normalizedTarget = targetPackage?.trim().orEmpty()
        if (normalizedTarget.isNotEmpty()) {
            // 自己（Eta）和少数能力受限的应用只能在主屏，这条保留：
            // 给 Eta 自己建虚拟屏没有意义。
            if (normalizedTarget == ownPackage || normalizedTarget in MAIN_DISPLAY_ONLY_PACKAGES) {
                return CanvasTarget.MAIN_DISPLAY
            }
            // 以前这里还有一条「目标正好在主屏前台就复用主屏」的短路，
            // 结果是：用户正看着谁，一个 launch_app 就把谁留在主屏——主观感受就是
            // 「说好虚拟屏，却一直在主屏跳」。AUTO 的语义是「尽量别打扰用户」，
            // 所以不再复用主屏，统一往虚拟屏走。
        }
        if (!virtualDisplayEnabled || !backendAvailable) {
            // 能力不可用：交给上层转成 VIRTUAL_DISPLAY_UNAVAILABLE 报错/弹卡，
            // 而不是静默主屏。
            return CanvasTarget.MAIN_DISPLAY
        }
        return when (requested) {
            RequestedDisplay.VIRTUAL,
            RequestedDisplay.AUTO -> CanvasTarget.VIRTUAL_DISPLAY
            RequestedDisplay.MAIN -> CanvasTarget.MAIN_DISPLAY
        }
    }

    fun isVirtualDisplay(displayId: Int): Boolean =
        displayId != Display.DEFAULT_DISPLAY && displayId > 0
}
