package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import org.json.JSONArray

/**
 * 模块配置中枢。
 *
 * - Hook 进程（system_server / SystemUI / Google / 系统助手等）在模块加载时调用
 *   [attachRemote]，缓存框架提供的只读 [SharedPreferences]；之后所有拦截回调用 [isEnabled]
 *   读取当前进程持有的 remote preferences。
 * - Eta Runtime 自己消费的开关保存在 App 私有配置中，不依赖 Xposed Service。
 * - Hook 消费的开关通过 [remotePreferencesForUi] 写入 RemotePreferences；
 *   XposedService 未就绪时不提供本地假 fallback。
 *
 * 基于 libxposed API 102 的 [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 * 与 service 102 的 [XposedService.getRemotePreferences]，两端共用同一 group。
 */
internal object Prefs {

    /** 远程配置组名，UI 写入与 Hook 读取必须一致。 */
    const val GROUP = "eta_prefs"

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    /** 所有功能开关 key。默认值按功能风险独立定义。 */
    object Keys {
        const val POWER_KEY_ASSISTANT_TARGET = "power_key_assistant_target"
        // 兼容旧版布尔协议；新 UI 不再写入，缺少三态配置时 true 仍表示 Gemini。
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        const val HOTWORD_SELF_HEAL = "hotword_self_heal"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val LOCKSCREEN_VOICE_COMMAND = "lockscreen_voice_command"
        const val SCREEN_ON_VOICE_COMMAND = "screen_on_voice_command"
        const val AGENT_CUSTOM_MODEL = "agent_custom_model"
        const val AGENT_REQUIRE_PREFIX = "agent_require_prefix"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_AUTO_COMPACTION_ENABLED = "agent_auto_compaction_enabled"
        const val AGENT_SUBAGENT_ENABLED = "agent_subagent_enabled"
        const val AGENT_VIRTUAL_DISPLAY_ENABLED = "agent_virtual_display_enabled"
        /** 灵屏使用期间是否点亮并保持屏幕（本机虚拟屏落在默认显示组，熄屏后无障碍就看不到窗口）。 */
        const val AGENT_VIRTUAL_DISPLAY_KEEP_AWAKE = "agent_virtual_display_keep_awake"
        /** 灵屏使用期间若处于锁屏，是否自动解除锁屏（锁屏后窗口对无障碍不可见）。 */
        const val AGENT_VIRTUAL_DISPLAY_DISMISS_KEYGUARD = "agent_virtual_display_dismiss_keyguard"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"
        const val AGENT_DISABLED_TOOLS_JSON = "agent_disabled_tools_json"

        /** 通知到达时是否在屏幕上弹出气泡（Bubble Notifications 的原生重实现）。 */
        const val AGENT_BUBBLE_NOTIFICATIONS = "agent_bubble_notifications"

        /** 气泡排除的应用包名，JSON 数组字符串。 */
        const val AGENT_BUBBLE_EXCLUDED_JSON = "agent_bubble_excluded_json"

        /** 气泡窗口位置记忆，格式 "x,y"（绝对像素，窗口左上角）。 */
        const val AGENT_BUBBLE_POSITION = "agent_bubble_position"

        /** 气泡宽度上限（dp），对应原模块 content_preview_width_dp。 */
        const val AGENT_BUBBLE_WIDTH_DP = "agent_bubble_width_dp"

        /** 气泡停留时长（秒），对应原模块 content_preview_timeout。 */
        const val AGENT_BUBBLE_TIMEOUT_SEC = "agent_bubble_timeout_sec"

        /** 气泡背景不透明度（百分比），对应原模块 content_preview_overlay_alpha。 */
        const val AGENT_BUBBLE_OPACITY_PCT = "agent_bubble_opacity_pct"

        // ---- 以下对应原模块 BubbleSettingsActivity 的完整开关集 ----

        /** 是否显示通知内容卡片（原 content_preview_enabled）。 */
        const val AGENT_BUBBLE_PREVIEW_ENABLED = "agent_bubble_preview_enabled"

        /** 卡片宽度随文字自适应（原 content_preview_auto_width）。 */
        const val AGENT_BUBBLE_AUTO_WIDTH = "agent_bubble_auto_width"

        /** 卡片停留时长随文字长度变化（原 content_preview_dynamic_timeout）。 */
        const val AGENT_BUBBLE_DYNAMIC_TIMEOUT = "agent_bubble_dynamic_timeout"

        /** 只有球贴边时才弹卡片（原 content_preview_edge_only）。 */
        const val AGENT_BUBBLE_EDGE_ONLY = "agent_bubble_edge_only"

        /** 卡片方位偏好：0 自动、1 上、2 下、3 左、4 右（原 content_preview_position）。 */
        const val AGENT_BUBBLE_PREVIEW_POSITION = "agent_bubble_preview_position"

        /** 静置超时后球变暗（原 bubble_timeout_dim）。 */
        const val AGENT_BUBBLE_TIMEOUT_DIM = "agent_bubble_timeout_dim"

        /** 静置超时后球自动消失（原 bubble_auto_dismiss）。 */
        const val AGENT_BUBBLE_AUTO_DISMISS = "agent_bubble_auto_dismiss"

        /** 球的装饰光晕连续动画（原 decoration_continuous）。 */
        const val AGENT_BUBBLE_DECORATION = "agent_bubble_decoration"

        /** 出现气泡时点亮屏幕（原 screen_awake_prompt）。 */
        const val AGENT_BUBBLE_WAKE_SCREEN = "agent_bubble_wake_screen"

        /** 状态栏文字滚动显示通知内容（原 status_scroll_enabled）。 */
        const val AGENT_BUBBLE_STATUS_SCROLL = "agent_bubble_status_scroll"

        /** 状态栏滚动轮数 1–5（原 status_scroll_rounds）。 */
        const val AGENT_BUBBLE_STATUS_ROUNDS = "agent_bubble_status_rounds"

        /** 下滑关闭时顺手清掉状态栏通知（原 clear_status_on_down_swipe）。 */
        const val AGENT_BUBBLE_CLEAR_ON_SWIPE = "agent_bubble_clear_on_swipe"

        /** 白名单 JSON（为空表示不启用白名单）。 */
        const val AGENT_BUBBLE_WHITELIST_JSON = "agent_bubble_whitelist_json"

        /** 黑名单 JSON。 */
        const val AGENT_BUBBLE_BLACKLIST_JSON = "agent_bubble_blacklist_json"

        /** 竖屏位置百分比与停靠边（原 portrait_*）。 */
        const val AGENT_BUBBLE_X_PERCENT = "agent_bubble_x_percent"
        const val AGENT_BUBBLE_Y_PERCENT = "agent_bubble_y_percent"
        const val AGENT_BUBBLE_DOCK_EDGE = "agent_bubble_dock_edge"

        /** 横屏位置百分比与停靠边（原 landscape_*）。 */
        const val AGENT_BUBBLE_LAND_X_PERCENT = "agent_bubble_land_x_percent"
        const val AGENT_BUBBLE_LAND_Y_PERCENT = "agent_bubble_land_y_percent"
        const val AGENT_BUBBLE_LAND_DOCK_EDGE = "agent_bubble_land_dock_edge"

        /** 全部布尔开关及其默认值。 */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to false,
            ASSISTANT_AUTO_CONFIG to false,
            HOTWORD_SELF_HEAL to false,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
            LOCKSCREEN_VOICE_COMMAND to false,
            SCREEN_ON_VOICE_COMMAND to false,
            AGENT_CUSTOM_MODEL to true,
            AGENT_REQUIRE_PREFIX to false,
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_THINKING_ENABLED to true,
            AGENT_AUTO_COMPACTION_ENABLED to true,
            AGENT_SUBAGENT_ENABLED to true,
            AGENT_VIRTUAL_DISPLAY_ENABLED to true,
            AGENT_VIRTUAL_DISPLAY_KEEP_AWAKE to true,
            AGENT_VIRTUAL_DISPLAY_DISMISS_KEYGUARD to true,
            AGENT_BUBBLE_NOTIFICATIONS to true,
            AGENT_BUBBLE_PREVIEW_ENABLED to true,
            AGENT_BUBBLE_AUTO_WIDTH to false,
            AGENT_BUBBLE_DYNAMIC_TIMEOUT to true,
            AGENT_BUBBLE_EDGE_ONLY to false,
            AGENT_BUBBLE_TIMEOUT_DIM to true,
            AGENT_BUBBLE_AUTO_DISMISS to false,
            AGENT_BUBBLE_DECORATION to true,
            AGENT_BUBBLE_WAKE_SCREEN to true,
            AGENT_BUBBLE_STATUS_SCROLL to true,
            AGENT_BUBBLE_CLEAR_ON_SWIPE to false,
        )

        /** 由 Eta Runtime 最终裁决、不要求 Xposed 框架在线的开关。 */
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_DEVICE_DIRECT_TOOLS,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            AGENT_THINKING_ENABLED,
            AGENT_AUTO_COMPACTION_ENABLED,
            AGENT_SUBAGENT_ENABLED,
            AGENT_VIRTUAL_DISPLAY_ENABLED,
            AGENT_VIRTUAL_DISPLAY_KEEP_AWAKE,
            AGENT_VIRTUAL_DISPLAY_DISMISS_KEYGUARD,
            AGENT_BUBBLE_NOTIFICATIONS,
            AGENT_BUBBLE_PREVIEW_ENABLED,
            AGENT_BUBBLE_AUTO_WIDTH,
            AGENT_BUBBLE_DYNAMIC_TIMEOUT,
            AGENT_BUBBLE_EDGE_ONLY,
            AGENT_BUBBLE_TIMEOUT_DIM,
            AGENT_BUBBLE_AUTO_DISMISS,
            AGENT_BUBBLE_DECORATION,
            AGENT_BUBBLE_WAKE_SCREEN,
            AGENT_BUBBLE_STATUS_SCROLL,
            AGENT_BUBBLE_CLEAR_ON_SWIPE,
        )
    }

    /** Hook 进程缓存的只读 remote preferences，由 ModuleMain 在 onModuleLoaded 注入。 */
    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** App 进程调用：初始化不依赖 Xposed Service 的 Agent 配置。 */
    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    ).also(::migrateLegacyGroupSwitchesIfNeeded)
                }
            }
        }
    }

    /** Hook 进程调用：缓存框架提供的只读 SharedPreferences。 */
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /** Hook 进程监听框架下发的配置变化；listener 必须由调用方在进程生命周期内强引用。 */
    fun registerRemoteListener(listener: SharedPreferences.OnSharedPreferenceChangeListener): Boolean {
        val preferences = remote ?: return false
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /**
     * 读取布尔开关。remote 不可用（框架未注入或调用失败）时回退各功能自己的默认值；
     * 默认值与设置页展示保持一致。
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
        return preferences?.getBoolean(key, default) ?: default
    }

    fun getString(key: String): String {
        return remote?.getString(key, "") ?: ""
    }

    /** 本地 Agent 配置里的整数项（灵屏分辨率/帧率/宽限这类可调参数）。 */
    fun getAgentInt(key: String, default: Int): Int =
        runCatching { localAgent?.getInt(key, default) ?: default }.getOrDefault(default)

    /** Hook 进程读取整型配置：SystemUI 里 localAgent 不初始化，只能读框架下发的 remote。 */
    fun getRemoteInt(key: String, default: Int): Int =
        runCatching { remote?.getInt(key, default) ?: default }.getOrDefault(default)

    /** Hook 进程读取字符串配置。 */
    fun getRemoteString(key: String, default: String): String =
        runCatching { remote?.getString(key, default) ?: default }.getOrDefault(default)

    /** UI 进程写入整型配置，并在 remote 可用时同步下发，让 Hook 侧立刻拿到。 */
    fun setBubbleInt(key: String, value: Int): Boolean {
        val localOk = runCatching { localAgent?.edit()?.putInt(key, value)?.commit() }.getOrDefault(false) == true
        runCatching { remote?.edit()?.putInt(key, value)?.commit() }
        return localOk
    }

    /** UI 进程写入字符串配置（黑白名单等），同步下发。 */
    fun setBubbleString(key: String, value: String): Boolean {
        val localOk = runCatching { localAgent?.edit()?.putString(key, value)?.commit() }.getOrDefault(false) == true
        runCatching { remote?.edit()?.putString(key, value)?.commit() }
        return localOk
    }

    /** 写整数项并同步落盘，返回值表示是否真的写成功。 */
    fun setAgentInt(key: String, value: Int): Boolean =
        runCatching { localAgent?.edit()?.putInt(key, value)?.commit() }.getOrDefault(false) == true

    fun powerAssistantTarget(): PowerAssistantTarget = powerAssistantTarget(remote)

    fun powerAssistantTarget(preferences: SharedPreferences?): PowerAssistantTarget {
        val persistedValue = runCatching {
            preferences?.getString(Keys.POWER_KEY_ASSISTANT_TARGET, null)
        }.getOrNull()
        val legacyDefault = Keys.BOOLEAN_DEFAULTS.getValue(Keys.POWER_KEY_TAKEOVER)
        val legacyTakeover = runCatching {
            preferences?.getBoolean(Keys.POWER_KEY_TAKEOVER, legacyDefault)
        }.getOrNull() ?: legacyDefault
        return PowerAssistantTarget.resolve(persistedValue, legacyTakeover)
    }

    /**
     * UI 进程获取可写的 RemotePreferences。
     *
     * [XposedService.getRemotePreferences] 的 commit 会同步等待 binder 提交到 LSPosed
     * 数据库，失败返回 false；service 未就绪时返回 null，让 UI 保持不可写。
     */
    fun remotePreferencesForUi(service: XposedService?): SharedPreferences? =
        runCatching { service?.getRemotePreferences(GROUP) }.getOrNull()

    /** Eta 设置页与 Runtime 使用的本地 Agent 配置，不依赖 LSPosed。 */
    fun localAgentPreferences(): SharedPreferences? = localAgent

    /**
     * 首次升级优先把已有 RemotePreferences 值迁入本地；之后本地值是事实源，并在框架
     * 可用时回写远端，让仍在目标进程中组装请求的 Hook 入口拿到一致的初始配置。
     */
    fun reconcileAgentPreferences(service: XposedService?) {
        val local = localAgent ?: return
        val remotePreferences = remotePreferencesForUi(service) ?: return
        val localEditor = local.edit()
        val remoteEditor = remotePreferences.edit()
        var updateLocal = false
        var updateRemote = false

        Keys.LOCAL_AGENT_KEYS.forEach { key ->
            val default = Keys.BOOLEAN_DEFAULTS.getValue(key)
            when {
                local.contains(key) -> {
                    remoteEditor.putBoolean(key, local.getBoolean(key, default))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putBoolean(key, remotePreferences.getBoolean(key, default))
                    updateLocal = true
                }
            }
        }
        // 气泡的数值/字符串参数：以 local 为准，同步下发 remote，SystemUI hook 侧才能读到
        listOf(
            Keys.AGENT_BUBBLE_WIDTH_DP to 360,
            Keys.AGENT_BUBBLE_TIMEOUT_SEC to 8,
            Keys.AGENT_BUBBLE_OPACITY_PCT to 100,
            Keys.AGENT_BUBBLE_STATUS_ROUNDS to 2,
            Keys.AGENT_BUBBLE_PREVIEW_POSITION to 0,
            Keys.AGENT_BUBBLE_X_PERCENT to 100,
            Keys.AGENT_BUBBLE_Y_PERCENT to 35,
            Keys.AGENT_BUBBLE_DOCK_EDGE to 0,
            Keys.AGENT_BUBBLE_LAND_X_PERCENT to 100,
            Keys.AGENT_BUBBLE_LAND_Y_PERCENT to 35,
            Keys.AGENT_BUBBLE_LAND_DOCK_EDGE to 0,
        ).forEach { (key, default) ->
            when {
                local.contains(key) -> {
                    remoteEditor.putInt(key, local.getInt(key, default))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putInt(key, remotePreferences.getInt(key, default))
                    updateLocal = true
                }
            }
        }
        listOf(
            Keys.AGENT_BUBBLE_WHITELIST_JSON to "[]",
            Keys.AGENT_BUBBLE_BLACKLIST_JSON to "[]",
            Keys.AGENT_BUBBLE_EXCLUDED_JSON to "[]",
        ).forEach { (key, default) ->
            when {
                local.contains(key) -> {
                    remoteEditor.putString(key, local.getString(key, default))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putString(key, remotePreferences.getString(key, default))
                    updateLocal = true
                }
            }
        }
        when {
            local.contains(Keys.AGENT_DISABLED_TOOLS_JSON) -> {
                remoteEditor.putString(
                    Keys.AGENT_DISABLED_TOOLS_JSON,
                    local.getString(Keys.AGENT_DISABLED_TOOLS_JSON, "[]"),
                )
                updateRemote = true
            }
            remotePreferences.contains(Keys.AGENT_DISABLED_TOOLS_JSON) -> {
                localEditor.putString(
                    Keys.AGENT_DISABLED_TOOLS_JSON,
                    remotePreferences.getString(Keys.AGENT_DISABLED_TOOLS_JSON, "[]"),
                )
                updateLocal = true
            }
        }
        if (updateLocal) localEditor.commit()
        if (updateRemote) runCatching { remoteEditor.commit() }
    }

    /** 将运行时工具名映射为工具列表中的主开关标识，确保同一能力的不同动作变体受同一开关约束。 */
    fun canonicalToolSwitchId(toolName: String): String = when (toolName) {
        "tap" -> "tap_area"
        "long_press_element" -> "long_press"
        "scroll_element" -> "scroll"
        "input_text", "replace_text", "clear_text", "paste_text" -> "type_text"
        "wait", "wait_for_package" -> "wait_for_text"
        "browser_read", "browser_interact", "browser_screenshot" -> "browser_use"
        "search_coloros_notes" -> "search_notes"
        "search_coloros_memories" -> "search_system_memories"
        else -> PersonalSearchTools.canonical(toolName)
    }

    fun disabledTools(preferences: SharedPreferences? = localAgent ?: remote): Set<String> {
        val raw = runCatching {
            preferences?.getString(Keys.AGENT_DISABLED_TOOLS_JSON, null)
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optString(index).trim()
                    if (item.isNotEmpty()) add(item)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun isToolEnabledInSet(toolName: String, disabled: Set<String>): Boolean {
        if (disabled.isEmpty()) return true
        val canonical = canonicalToolSwitchId(toolName)
        return toolName !in disabled && canonical !in disabled
    }

    fun isToolEnabled(
        toolName: String,
        preferences: SharedPreferences? = localAgent ?: remote,
    ): Boolean = isToolEnabledInSet(toolName, disabledTools(preferences))

    fun setToolEnabled(
        toolName: String,
        enabled: Boolean,
        service: XposedService? = null,
    ): Boolean = setToolsEnabled(listOf(toolName), enabled, service)

    fun setToolsEnabled(
        toolNames: Collection<String>,
        enabled: Boolean,
        service: XposedService? = null,
    ): Boolean {
        val local = localAgent ?: return false
        val updated = disabledTools(local).toMutableSet()
        toolNames.forEach { rawName ->
            val canonical = canonicalToolSwitchId(rawName)
            if (enabled) {
                updated -= rawName
                updated -= canonical
            } else {
                updated += canonical
            }
        }
        val encoded = JSONArray(updated.sorted()).toString()
        val editor = local.edit().putString(Keys.AGENT_DISABLED_TOOLS_JSON, encoded)
        LEGACY_TOOL_GROUP_KEYS.forEach { key ->
            if (local.contains(key) && !local.getBoolean(key, true)) {
                editor.putBoolean(key, true)
            }
        }
        val committed = runCatching { editor.commit() }.getOrDefault(false)
        if (committed && service != null) {
            reconcileAgentPreferences(service)
        }
        return committed
    }

    private fun migrateLegacyGroupSwitchesIfNeeded(local: SharedPreferences) {
        if (local.contains(Keys.AGENT_DISABLED_TOOLS_JSON)) return
        val disabledLegacyGroups = LEGACY_TOOL_GROUP_KEYS.filter { key ->
            local.contains(key) && !local.getBoolean(key, true)
        }
        if (disabledLegacyGroups.isEmpty()) return
        val migratedDisabled = linkedSetOf<String>()
        if (Keys.AGENT_BROWSER_TOOLS in disabledLegacyGroups) {
            migratedDisabled += listOf("web_search", "fetch_url", "browser_use")
        }
        if (Keys.AGENT_TERMINAL_TOOLS in disabledLegacyGroups) {
            migratedDisabled += AgentTerminalToolCatalog.names
            migratedDisabled += AgentFileToolCatalog.names
            migratedDisabled += "read_image"
        }
        if (Keys.AGENT_DEVICE_DIRECT_TOOLS in disabledLegacyGroups) {
            migratedDisabled += AgentPhoneToolCatalog.direct
            migratedDisabled += listOf(
                "set_alarm", "set_timer", "device_status", "inspect_app",
                "network_info", "top_memory_apps", "top_storage_apps",
                "media_control", "set_volume",
            )
        }
        if (Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS in disabledLegacyGroups) {
            migratedDisabled += PersonalSearchTools.names
            migratedDisabled += AgentPhoneToolCatalog.reads
            migratedDisabled += listOf(
                "read_personal_item", "summarize_bills", "get_setting", "wifi_credentials",
                "recent_notifications", "search_notification_history", "recent_app_activity",
                "app_usage_summary", "get_current_location", "get_device_environment",
                "list_alarms", "list_active_timers", "search_clipboard_history",
                "get_health_summary", "read_sms_code", "get_logcat", "search_media",
                "search_audio", "search_recordings", "search_files", "search_calendar_events",
                "search_contacts", "search_call_history", "search_messages", "search_downloads",
                "search_notes", "search_coloros_recordings", "search_recording_summaries",
                "search_system_memories", "search_saved_places", "search_personal_orders",
                "search_qq_chat_images", "search_wechat_chat_images",
            )
        }
        if (Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS in disabledLegacyGroups) {
            migratedDisabled += AgentPhoneToolCatalog.writes
            migratedDisabled += listOf("set_setting", "set_device_state", "app_state_control")
        }
        val editor = local.edit()
            .putString(Keys.AGENT_DISABLED_TOOLS_JSON, JSONArray(migratedDisabled.sorted()).toString())
        disabledLegacyGroups.forEach { key -> editor.putBoolean(key, true) }
        runCatching { editor.commit() }
    }

    private val LEGACY_TOOL_GROUP_KEYS = listOf(
        Keys.AGENT_TERMINAL_TOOLS,
        Keys.AGENT_BROWSER_TOOLS,
        Keys.AGENT_DEVICE_DIRECT_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
    )
}
