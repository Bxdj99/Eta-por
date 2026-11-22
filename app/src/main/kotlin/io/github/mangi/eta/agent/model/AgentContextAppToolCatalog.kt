package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 上下文、应用入口与屏幕观察工具 schema。 */
internal object AgentContextAppToolCatalog {
    /** 用户裁决卡：不确定时让用户选，不要自己猜。 */
    /**
     * 把应用加入插件作用域（issue #234）。
     *
     * 两道门一起开：LSPosed 系统级作用域 + 插件应用级作用域，
     * 然后重启应用并验证宿主起来。用户只管说「加」，技术细节全在这。
     */
    fun appendPluginEnroll(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "plugin_enroll",
                description = "把目标应用加入进程内插件作用域（同时写 LSPosed 系统作用域和插件作用域，重启应用并验证宿主就绪）。" +
                    "调用前应当先用 ask_user 问过用户要不要加。" +
                    "返回 ok=true 表示宿主已就绪，之后可以用 app_plugin_call 直接调它的内部方法；" +
                    "ok=false 时 steps 会指出卡在哪一步。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "package",
                            JSONObject().put("type", "string").put("description", "目标应用包名"),
                        ),
                    )
                    .put("required", JSONArray().put("package")),
            ),
        )
    }

    /**
     * 进程内插件调用（issue #234）：对作用域内应用直接调它的内部能力。
     * 宿主 hook 在目标应用进程里起 loopback HTTP 服务，插件由 Agent 写 JS 热加载。
     */
    fun appendAppPluginCall(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "app_plugin_call",
                description = "【操作非 Eta 应用时的第一个动作】在目标应用进程内直接调它的内部能力（经 LSPosed 插件宿主）。" +
                    "点它、读它、抓数据、发文件、搜索之前，先调这个工具试一次，不要先 observe_screen 截图。" +
                    "用法：source 传 JS 源码，最后一句求值成一个对象 ({\"方法\":function(args){...}})，" +
                    "用 javaCall/javaField/javaNew/javaFind 调目标应用的类；method 填对象里的键名。" +
                    "返回 ok=true 就是拿到了结构化结果，不要再去截图。" +
                    "返回 PLUGIN_HOST_NOT_RUNNING / plugin_host_not_ready 才说明宿主没起：这时用 ask_user 弹卡问用户要不要加进作用域，" +
                    "用户同意就调 plugin_enroll，别自己默默退回截图。" +
                    "method_not_found 时先 javaFind 看真实方法名。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("package", JSONObject().put("type", "string").put("description", "目标应用包名"))
                            .put("plugin", JSONObject().put("type", "string").put("description", "插件 id（即 app-plugins/<包名>/ 下的 js 文件名，不含 .js）"))
                            .put("method", JSONObject().put("type", "string").put("description", "插件里 register 调用里定义的方法名"))
                            .put("action", JSONObject().put("type", "string")
                                .put("enum", JSONArray()
                                    .put("env").put("find").put("field")
                                    .put("call").put("activity").put("dump")
                                    .put("search").put("loaders").put("get")
                                    .put("scan").put("classes"))
                                .put("description",
                                    "预置动作，免写 JS：env(环境自检) / find(列类成员，args={cls,keyword}) / " +
                                        "field(读静态字段，args={cls,field}) / call(调静态方法，args={cls,method,args}) / " +
                                        "activity(拿当前Activity，args={members:true 附带方法表}) / " +
                                        "dump(列类成员，args={cls,keyword}) / " +
                                        "search(按关键词搜已加载类，args={keyword}，不解APK找混淆类的首选) / " +
                                        "loaders(查所有ClassLoader及某类能被哪个加载，args={cls}，动态模块应用必用) / " +
                                        "get(按路径读字段，args={obj,path}) / " +
                                        "scan(按类型扫堆找实例，args={cls,limit}，不知对象在哪时的终极手段) / " +
                                        "classes(按前缀列可见类，args={prefix})。" +
                                        "优先用它，比每次现写 source 快得多。"))
                            .put("args", JSONObject().put("type", "object").put("description", "传给插件方法/预置动作的参数对象"))
                            .put("source", JSONObject().put("type", "string").put("description",
                                "可选：直接传 JS 插件源码，宿主现场在目标应用进程内执行（推荐，绕开沙盒文件隔离）。" +
                                    "源码的最后一句必须求值成一个对象，其属性名即方法名：" +
                                    "({\"方法名\": function(args){ ... return {...}; }})。" +
                                    "可用全局：packageName、appContext、java(loadClass 反射)、log.d/w、json。")),
                    )
                    .put("required", JSONArray().put("package").put("plugin").put("method")),
            ),
        )
    }

    fun appendAskUser(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "ask_user",
                description = "不确定时向用户弹出提示卡让他选择，并把他的选择回灌到本轮对话。" +
                    "适用场景：两个信息来源矛盾、关键参数拿不准（比如键名/版本/数量）、操作有歧义、" +
                    "或下一步的走法需要用户拍板（比如「继续修改」还是「保持现状」）。" +
                    "必须给至少两个可区分的选项；用户选择后他的答案会以消息形式回来，按它继续，不要重复提问。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "question",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 60)
                                    .put("description", "一句话问题，显示在卡片的标题位置。"),
                            )
                            .put(
                                "body",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 500)
                                    .put("description", "背景说明：为什么不确定、两个来源各说了什么。用户看得懂才答得对。"),
                            )
                            .put(
                                "options",
                                JSONObject()
                                    .put("type", "array")
                                    .put("minItems", 2)
                                    .put("maxItems", 6)
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "object")
                                            .put(
                                                "properties",
                                                JSONObject()
                                                    .put(
                                                        "label",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put("maxLength", 24)
                                                            .put("description", "选项显示文字，如「按 144 处理」/「按 60 处理」/「我自己去看」。"),
                                                    )
                                                    .put(
                                                        "description",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put("maxLength", 100)
                                                            .put("description", "可选：这个选项的含义或后果。"),
                                                    ),
                                            )
                                            .put("required", JSONArray().put("label"))
                                            .put("additionalProperties", false),
                                    ),
                            ),
                    )
                    .put("required", JSONArray().put("question").put("options"))
                    .put("additionalProperties", false),
            ),
        )
    }

    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "get_current_context",
                    description = "获取手机当前时间、时区和最近系统位置；涉及现在、今天、明天或所在位置时调用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_apps",
                    description = "搜索手机上已安装的 Android 应用，返回应用名和包名。打开应用前如果不确定包名，先调用这个工具。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用名或包名片段，例如 QQ、微信、com.tencent")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否包含系统应用，默认 false")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回 1 到 20 个结果，默认 10")
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "launch_app",
                    description = "启动一个已安装 Android 应用。优先提供 package_name；只有应用名时允许模糊匹配，匹配多个会返回候选而不会启动。ok=true 表示启动请求已提交；launch_status=window_observed 才表示已在目标画布观察到应用窗口，unconfirmed 时先 observe_screen 确认，不要直接重复启动。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "精确 Android 包名，例如 com.tencent.mobileqq")
                                )
                                .put(
                                    "app_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用显示名，例如 QQ")
                                )
                                .put(
                                    "display",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("auto").put("virtual").put("main"))
                                        .put("default", "auto")
                                        .put(
                                            "description",
                                            "启动目标画布：auto（默认，跨应用任务优先在后台虚拟屏执行，不打断用户当前主屏）、virtual（显式在后台虚拟屏启动）、main（显式在物理主屏前台打开）。用户说的「虚拟屏」「后台虚拟屏」「灵屏」都指 virtual / auto，它是画布不是应用名，不要为它调用 search_apps。"
                                        )
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "present_to_user",
                    description = "将后台虚拟屏中已经准备就绪的应用画面无缝放大并移交到物理主屏前台，供用户直接查看、完成验证或确认付款。当任务在虚拟屏内已完成前置操作、到达收银台/二维码/结果页，或遇到安全窗口需要用户本人操作时调用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "reason",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选，向用户呈现画面的简短原因，例如“已到达支付确认页”")
                                )
                        )
                        .put("additionalProperties", false),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_uri",
                    description = "把一个确定有效的 URI 显式交给 Android 外部应用处理，例如 https、tel、geo 或应用 deep link。它不用于读取网页或网页交互。不要编造 URI。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "确定有效、可由系统处理的 URI")
                                )
                        )
                        .put("required", JSONArray().put("uri"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "observe_screen",
                    description = "取得手机操作权并观察当前屏幕；其他会话占用时由 Runtime 等待。默认只返回前台应用、屏幕尺寸、observation_id 与可见 UI 节点，不附截图。节点为空、目标无法唯一识别、界面以 Canvas/地图/图片/二维码等视觉内容为主，或任务依赖颜色、图像、空间布局时，显式设置 include_screenshot=true；补截图时保持 include_ui_tree=true，以同一次新观察刷新节点和 observation_id，禁止把新截图与旧节点混用。节点动作必须原样携带同一次观察的 observation_id；树被截断但节点语义仍有效时，优先把 max_nodes 提高到 120 后重试。手机操作阶段完成后调用 release_gui，让其他会话继续；释放或用户接管后必须重新观察才能操作。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "include_screenshot",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_SCREENSHOT)
                                        .put("description", "是否附加当前屏幕原图给模型，默认 false；仅在 UI 节点不足以完成任务时显式开启")
                                )
                                .put(
                                    "include_ui_tree",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_UI_TREE)
                                        .put("description", "是否返回 UI 节点列表，默认 true")
                                )
                                .put(
                                    "max_nodes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentScreenObservationContract.MIN_MAX_NODES)
                                        .put("maximum", AgentScreenObservationContract.MAX_MAX_NODES)
                                        .put("default", AgentScreenObservationContract.DEFAULT_MAX_NODES)
                                        .put("description", "最多返回 1 到 120 个 UI 节点，默认 60")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "release_gui",
                    description = "结束当前手机操作阶段并释放操作权，让其他会话使用手机。纯思考、网页、Shell 或文件工作不需要继续占用手机。释放后旧观察、节点与坐标全部失效；再次操作前先 observe_screen。没有持有操作权时调用也会成功。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                        .put("additionalProperties", false),
                )
            )
    }
}
