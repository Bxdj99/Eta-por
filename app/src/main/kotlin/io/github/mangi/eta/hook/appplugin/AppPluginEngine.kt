package io.github.mangi.eta.hook.appplugin

import android.content.Context
import dalvik.system.PathClassLoader
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 进程内插件引擎（issue #234）。
 *
 * 在目标应用进程内运行。Agent 把 JS 插件写到
 * `<moduleDir>/app-plugins/<package>/<pluginId>.js`，这里负责：
 *  1. 加载脚本（文件 mtime 变了就热重载）；
 *  2. 把「应用内部能力」注册成处理器（handler），由插件里的
 *     `register({ "方法名": function(args) {...} })` 声明；
 *  3. 提供给插件的安全 API：context（应用 Context）、
 *     java（Rhino 的 Java 桥，能直接调用应用类）、log。
 *
 * 安全边界：插件能访问目标应用的一切（本来就是它的进程），
 * 但没有独立权限提升——它能做的都是该应用自己能做的。
 */
internal object AppPluginEngine {

    private lateinit var logger: ModuleLogger
    private var appContext: Context? = null
    private var appClassLoader: ClassLoader? = null

    /** pluginId → 已编译脚本（带 mtime，用于热重载） */
    private data class Loaded(val scriptFile: File, val mtime: Long, val handlers: Map<String, (JSONObject) -> JSONObject>)
    private val loaded = ConcurrentHashMap<String, Loaded>()

    /** 目标应用主线程（Looper.getMainLooper）。 */
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * 把任务派发到**目标应用主线程**执行。
     *
     * 为什么必须这样：微信这类应用的网络层（NetScene.doScene、回调队列）依赖
     * 主线程 Looper 调度。早先在独立线程池里直接反射构造 scene 对象，
     * 能构造、能调方法，但拿不到登录态、进不了发送队列——消息永远不会真的发出去。
     * 只有和宿主应用自己的调用同线程，行为才和它自己发消息一致。
     */
    /**
     * 只读探测类方法（扫堆 / 读字段 / 列类）不需要主线程 Looper，
     * 放主线程反而会把 UI 卡死并触发 plugin_timeout（实测宿主被锁死要重启应用）。
     * 这类调用走独立工作线程，并给更长超时。
     */
    private fun runOnWorkerThread(task: () -> JSONObject, timeoutMs: Long = 60_000): JSONObject {
        val future = java.util.concurrent.FutureTask { task() }
        executor.execute(future)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (exception: Exception) {
            // 软失败：future.cancel(true) 只是打断标志，反射调用里的长循环不响应中断，
            // 线程仍会占着单线程 executor。这里换掉整个 executor，
            // 让后续调用拿到新线程，宿主不至于永久锁死（实测原实现会把进程拖死）。
            future.cancel(true)
            resetExecutor()
            error(
                "TIMEOUT",
                "操作超时（${timeoutMs}ms）：扫堆规模太大时请调小 maxScan 或指定更精确的类名",
            )
        }
    }

    /** 丢弃已卡死的执行线程，换一条新的。旧线程作为守护线程自然退出。 */
    @Synchronized
    private fun resetExecutor() {
        runCatching { executor.shutdownNow() }
        // 单线程 → 单线程，直接换引用（executor 声明为 var）。
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "eta-plugin-js").apply { isDaemon = true }
        }
        logger.warn("plugin executor reset: 上一个执行线程超时未返回")
    }

    private fun runOnMainThread(task: () -> JSONObject): JSONObject {
        // 已经主线程：直接跑，避免自己等自己死锁。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return task()
        val holder = java.util.concurrent.atomic.AtomicReference<JSONObject?>()
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            try {
                holder.set(task())
            } catch (throwable: Throwable) {
                holder.set(error("plugin_error", throwable.message?.take(200) ?: "main thread error"))
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
            holder.get() ?: error("plugin_null_result")
        } else {
            // 软失败：主线程任务没回来就放弃等待，不再无限期占住调用方。
            error("TIMEOUT", "主线程执行超时（15s）")
        }
    }

    /** 这些方法名是纯读取探测，不需要（也不该占）主线程。 */
    private fun isProbeMethod(method: String): Boolean =
        method in setOf(
            "env", "find", "field", "dump", "search", "loaders", "classes",
            "get", "scan", "activity",
        )

    /** 脚本执行线程池：Rhino 非线程安全，串行执行避免上下文打架。 */
    @Volatile
    private var executor: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "eta-plugin-js").apply { isDaemon = true }
        }

    fun init(context: Context, classLoader: ClassLoader, moduleLogger: ModuleLogger) {
        // applicationContext 在 Application 尚未完成 attach 时可能为 null，
        // 直接用调用方给的 context 兜底——它至少是个可用的 Context 实例。
        appContext = context.applicationContext ?: context
        appClassLoader = classLoader
        logger = moduleLogger
        logger.info("AppPluginEngine init pkg=${context.packageName} ctx=${appContext != null}")
    }

    /** 插件目录：<moduleDir>/app-plugins/<packageName>/ */
    fun pluginsDir(moduleDir: File, packageName: String): File =
        File(moduleDir, packageName).apply { mkdirs() }

    /**
     * 执行一次插件方法。
     * @return 处理器返回的 JSON；插件或方法不存在时返回 ok=false 的错误回执。
     */
    fun invoke(pluginId: String, method: String, argsJson: JSONObject): JSONObject {
        val context = appContext ?: return error("plugin_host_not_ready")
        val classLoader = appClassLoader ?: return error("plugin_host_not_ready")
        // 探测类方法（扫堆/列类/读字段）走工作线程：它们纯计算、不碰 Looper，
        // 放主线程会卡死 UI 并触发超时锁死宿主。
        // 业务类方法（发消息、点按钮）仍走主线程——那才需要应用自己的调度上下文。
        val work: () -> JSONObject = {
            try {
                val handlers = ensureLoaded(context, classLoader, pluginId)
                if (handlers == null) {
                    error("plugin_not_found", "pluginId=$pluginId")
                } else {
                    val handler = handlers[method]
                    if (handler == null) {
                        error("method_not_found", "plugin=$pluginId method=$method")
                    } else {
                        handler(argsJson)
                    }
                }
            } catch (throwable: Throwable) {
                logger.warn("plugin invoke failed: ${throwable.safeLogType()} ${throwable.message?.take(160)}")
                error("plugin_error", throwable.message?.take(300) ?: throwable.javaClass.simpleName)
            }
        }
        return if (isProbeMethod(method)) {
            runOnWorkerThread(task = work)
        } else {
            runOnMainThread(task = work)
        }
    }

    /**
     * 替换 Rhino 的消息提供者。
     *
     * 为什么必须做：Rhino 的 ScriptRuntime 静态初始化会调
     * ResourceBundle.getBundle("org.mozilla.javascript.resources.Messages")，
     * 而这些 .properties 只在 APK 根目录、不在 dex 里——LSPosed 只把 dex 注入目标进程，
     * 资源包找不到就抛 MissingResourceException，脚本连编译都进不去（实测报错）。
     *
     * messageProvider 是 final 静态字段，用反射写进去；失败也不致命，
     * 只是错误文案变成英文默认值。
     */
    private fun installFallbackMessageProvider() {
        if (messageProviderInstalled) return
        synchronized(AppPluginEngine::class.java) {
            if (messageProviderInstalled) return
            try {
                val field = org.mozilla.javascript.ScriptRuntime::class.java
                    .getDeclaredField("messageProvider")
                field.isAccessible = true
                // MessageProvider 是 Rhino 的内部接口，源码级引用会因访问限制编译失败，
                // 用反射拿 Class 再建代理。
                val providerClass = Class.forName(
                    "org.mozilla.javascript.ScriptRuntime\$MessageProvider",
                    true,
                    org.mozilla.javascript.ScriptRuntime::class.java.classLoader,
                )
                val provider = java.lang.reflect.Proxy.newProxyInstance(
                    providerClass.classLoader,
                    arrayOf(providerClass),
                ) { _, method, args ->
                    // getMessage(key, args) → 直接回 key，不查资源包。
                    if (method.name == "getMessage") {
                        val key = (args?.getOrNull(0) as? String) ?: "error"
                        key
                    } else null
                }
                field.set(null, provider)
                messageProviderInstalled = true
                logger.debug { "Rhino messageProvider 已替换（绕过 ResourceBundle）" }
            } catch (throwable: Throwable) {
                logger.warn("Rhino messageProvider 替换失败: ${throwable.message?.take(120)}")
            }
        }
    }

    @Volatile
    private var messageProviderInstalled = false

    /**
     * 把一个 Rhino Function 包成可在线程池里安全调用的 handler。
     *
     * 关键点：handler 在插件线程池里跑，那一线程没有 Rhino Context，
     * 必须现场 enter；否则报 "No Context associated with current Thread"。
     */
    private fun makeHandler(
        fn: org.mozilla.javascript.Function,
        scope: Scriptable,
    ): (JSONObject) -> JSONObject = handler@{ argsJson ->
        val cx = RhinoContext.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = RhinoContext.VERSION_ES6
            val jsArgs = cx.newObject(scope)
            argsJson.keys().forEach { key ->
                ScriptableObject.putProperty(jsArgs, key, RhinoContext.javaToJS(argsJson.opt(key), scope))
            }
            val callResult = runCatching { fn.call(cx, scope, scope, arrayOf<Any?>(jsArgs)) }
            if (callResult.isFailure) {
                val throwable = callResult.exceptionOrNull()
                return@handler error(
                    "plugin_throw",
                    throwable?.message?.take(300) ?: throwable?.javaClass?.simpleName ?: "throw",
                )
            }
            val result = callResult.getOrNull()
            // 用 JS 自己的 JSON.stringify 序列化，避免 Kotlin 桥接丢结构。
            val jsonObj = ScriptableObject.getProperty(scope, "JSON") as? Scriptable
            val stringify = jsonObj?.get("stringify", scope) as? org.mozilla.javascript.Function
            val text = if (stringify != null) {
                runCatching { stringify.call(cx, scope, jsonObj, arrayOf<Any?>(result)) }
                    .getOrNull()?.toString()
            } else null
            if (text.isNullOrBlank() || text == "undefined" || text == "null") {
                JSONObject().put("ok", true).put("raw", result?.toString() ?: "")
            } else {
                runCatching { JSONObject(text) }.getOrElse {
                    JSONObject().put("ok", true).put("data", text)
                }
            }
        } finally {
            RhinoContext.exit()
        }
    }

    /**
     * 直接执行一段插件源码（不走文件）。
     *
     * 实测在应用沙盒里读写插件目录不可靠（mount namespace + SELinux 双重隔离，
     * 外部 root 放进去的文件应用读不到）。所以支持「源码即调用」：
     * Agent 把 JS 源码随请求送来，宿主现场编译执行。
     * 这与「写文件再加载」等价，但绕开全部文件系统问题。
     */
    fun invokeSource(source: String, method: String, argsJson: JSONObject): JSONObject {
        val context = appContext ?: return error("plugin_host_not_ready")
        val classLoader = appClassLoader ?: return error("plugin_host_not_ready")
        // source 内联调用**一律走工作线程**：调用方给的方法名是任意的（defaultParser、
        // setPower…），按名字白名单判断必然误判成「业务方法」丢到主线程，
        // 15 秒后超时把目标应用一起拖死（实测米家进程被杀）。
        // 内联脚本本身就在做反射探测，不是应用自己的 UI/网络业务，没有占主线程的理由。
        val work: () -> JSONObject = {
            try {
                val handlers = compile(context, classLoader, "inline", source)
                if (handlers == null) {
                    error("plugin_compile_failed", lastCompileError.ifBlank { "compile returned null" })
                } else {
                    val handler = handlers[method]
                    if (handler == null) {
                        error("method_not_found", "method=$method available=${handlers.keys}")
                    } else {
                        handler(argsJson)
                    }
                }
            } catch (throwable: Throwable) {
                logger.warn("inline plugin failed: ${throwable.safeLogType()} ${throwable.message?.take(160)}")
                error("plugin_error", throwable.message?.take(300) ?: throwable.javaClass.simpleName)
            }
        }
        // P1 加固：内联脚本里可能调到会**永久阻塞**的框架方法（实测
        // javaCall("android.net.Uri","parse",str) 把唯一的工作线程卡死，
        // 之后所有请求全部排队超时，但进程和 /health 都活着，很容易误判成"应用被杀"）。
        // 这里给内联调用 20 秒上限，超时即换新线程，不让单次调用毒死整个宿主。
        return runOnWorkerThread(task = work, timeoutMs = 20_000L)
    }

    /** 当前已注册的全部「插件.方法」，给 /list 接口用。 */
    fun listMethods(): JSONArray {
        val array = JSONArray()
        loaded.forEach { (pluginId, _) ->
            array.put(pluginId)
        }
        return array
    }

    // ---------- 内部：脚本加载与执行 ----------

    private fun ensureLoaded(
        context: Context,
        classLoader: ClassLoader,
        pluginId: String,
    ): Map<String, (JSONObject) -> JSONObject>? {
        val scriptFile = File(pluginsDir(moduleDataDir(), context.packageName), "$pluginId.js")
        if (!scriptFile.isFile) return null
        val mtime = scriptFile.lastModified()
        loaded[pluginId]?.let { cached ->
            if (cached.mtime == mtime) return cached.handlers
        }
        // mtime 变了或首次：热重载
        val source = scriptFile.readText()
        val handlers = compile(context, classLoader, pluginId, source) ?: return null
        loaded[pluginId] = Loaded(scriptFile, mtime, handlers)
        logger.debug { "plugin loaded: $pluginId methods=${handlers.keys}" }
        return handlers
    }

    /**
     * 用 Rhino 编译并执行脚本，收集 register() 注册的处理器。
     * 脚本约定：
     *   register({ "search": function(args) { ... return {ok:true, data:...}; } })
     */
    /** 最近一次编译失败的原因，随错误回执返回给 Agent，便于自修。 */
    @Volatile
    private var lastCompileError: String = ""

    fun lastCompileError(): String = lastCompileError

    private fun compile(
        context: Context,
        classLoader: ClassLoader,
        pluginId: String,
        source: String,
    ): Map<String, (JSONObject) -> JSONObject>? {
        val handlers = ConcurrentHashMap<String, (JSONObject) -> JSONObject>()
        installFallbackMessageProvider()
        return try {
            val rhino = RhinoContext.enter()
            try {
                rhino.optimizationLevel = -1 // Android 无 JIT，用解释模式
                rhino.languageVersion = RhinoContext.VERSION_ES6
                val scope: Scriptable = rhino.initStandardObjects()

                // 只注入纯数据；任何 Kotlin 对象放进 scope 都会被 Rhino 包成
                // NativeJavaObject，之后调用即报「ft 类型的 JavaScript 值无效」（实测）。
                // 需要 Java 能力时用 host 提供的显式桥接函数（见下）。
                ScriptableObject.putProperty(scope, "pluginId", pluginId)
                ScriptableObject.putProperty(scope, "packageName", context.packageName)

                // Java 桥：javaCall / javaNew / javaCallOn。
                // 这是插件的真正价值——直接调目标应用的内部类与方法，
                // 不靠截图点击。用 Rhino 原生 BaseFunction 实现，避开 Kotlin 桥接坑。
                JavaBridge(classLoader, scope).install()

                // 不注入 register：实测 Kotlin↔Rhino 的函数桥接会把 JS 函数
                // 擦成 Kotlin Function Type（报「ft 类型的 JavaScript 值无效」）。
                // 改为约定：脚本最后求值结果是一个对象，其同名方法即 handler。
                //   ({ ping: function(args){...} })
                val evaluated = rhino.evaluateString(scope, source, "plugin:$pluginId", 1, null)
                if (evaluated is Scriptable && evaluated !== scope) {
                    for (id in evaluated.ids) {
                        if (id !is String) continue
                        val fn = evaluated.get(id, evaluated)
                        if (fn !is org.mozilla.javascript.Function) continue
                        handlers[id] = makeHandler(fn, scope)
                    }
                }
                if (handlers.isEmpty()) {
                    logger.warn("plugin $pluginId loaded but registered nothing")
                }
                handlers.toMap()
            } finally {
                RhinoContext.exit()
            }
        } catch (throwable: Throwable) {
            lastCompileError = "${throwable.javaClass.simpleName}: ${throwable.message?.take(300)}"
            logger.warn("plugin compile failed: ${throwable.safeLogType()} ${throwable.message?.take(200)}")
            null
        }
    }

    /**
     * 插件根目录。
     *
     * 实测：/data/local/tmp 下 root 建的目录是 shell_data_file 上下文，
     * untrusted_app 域读写都被 SELinux 拦（DAC chmod 777 无效）。
     * 所以插件目录落到应用自己的 filesDir：应用可读，Agent 用 root 推进去。
     */
    private fun moduleDataDir(): File =
        File(appContext?.filesDir ?: File("/data/local/tmp/eta-app-plugins"), "eta-app-plugins")

    private fun error(code: String, detail: String? = null): JSONObject {
        val json = JSONObject().put("ok", false).put("code", code)
        detail?.let { json.put("message", it) }
        return json
    }

    // ---------- Rhino 桥接对象 ----------

    /** 把 JSON 参数转成 JS 对象。 */
    private class JsonFn {
        fun parse(text: String): Any? = RhinoContext.jsToJava(
            RhinoContext.enter().evaluateString(
                RhinoContext.enter().initStandardObjects(), "($text)", "json", 1, null,
            ),
            Any::class.java,
        )

        fun stringify(value: Any?): String {
            val rhino = RhinoContext.enter()
            val scope = rhino.initStandardObjects()
            ScriptableObject.putProperty(scope, "__v", value)
            return rhino.evaluateString(scope, "JSON.stringify(__v)", "json", 1, null)?.toString() ?: "null"
        }
    }

    private class LogFn(private val logger: ModuleLogger) {
        fun d(message: String) { logger.debug { "[plugin] $message" } }
        fun w(message: String) { logger.warn("[plugin] $message") }
    }

    /** 给插件一个轻量 Context 包装：读资源、拿包名、startActivity。 */
    private class ContextAdapter(private val context: Context) {
        fun getPackageName(): String = context.packageName
        fun getStringResource(name: String): String = try {
            val id = context.resources.getIdentifier(name, "string", context.packageName)
            if (id != 0) context.getString(id) else ""
        } catch (exception: Exception) { "" }
        fun startUri(uriText: String) {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uriText))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /** Java 桥入口：插件可以用 java.class("类名").method(...) 走反射。 */
    private class PackageAdapter(private val classLoader: ClassLoader) {
        fun cls(name: String): Any? = try {
            Class.forName(name, true, classLoader).getDeclaredConstructor().newInstance()
        } catch (exception: Exception) { null }
        fun forName(name: String): Any? = try { classLoader.loadClass(name).kotlin } catch (exception: Exception) { null }
        fun loadClass(name: String): Class<*>? = try { classLoader.loadClass(name) } catch (exception: Exception) { null }
    }
}
