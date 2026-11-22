package io.github.mangi.eta.hook.appplugin

import io.github.mangi.eta.config.Prefs
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import org.json.JSONArray

/**
 * 插件作用域与宿主入口（issue #234）。
 *
 * 作用域：模块 remote preferences 的 `agent_plugin_scope_json`
 * （JSON 数组，如 ["com.example.app"]）。App 内可改，改完重启目标应用生效。
 *
 * 时机：Application.attach(baseContext) 是拿 Context 的最早稳定点——
 * onPackageReady 时还没有活的 Context，而插件引擎必须要 Context 才能调应用代码。
 */
internal object AppPluginScope {

    /**
     * 作用域判断。两条来源，命中任一即启用：
     *  1. remote prefs 的 agent_plugin_scope_json（走 LSPosed 配置同步，设置页可改）；
     *  2. 文件 /data/data/io.github.mangi.eta/files/app-plugin-scope.json（JSON 数组）。
     *
     * 加文件这条是因为实测踩坑：直接往 LSPosed 的 module_configs 表里插值，
     * 已运行的进程读到的还是旧快照（remote prefs 只在 attach 时同步，
     * 之后靠 binder 通知，写库不触发）。Agent/用户用文件配作用域即时生效——
     * 目标应用重启后新进程读的就是新文件。
     */
    fun isEnabled(packageName: String): Boolean = try {
        // 总开关：文件不存在视为开启（默认开），文件写 "0" 才关闭。
        // 目标应用进程读不到 Eta 的 SharedPreferences，所以总开关也走文件。
        if (!pluginMasterEnabled()) return false
        inRemoteScope(packageName) || inFileScope(packageName)
    } catch (throwable: Throwable) {
        false
    }

    /** 进程内插件总开关：/data/local/tmp/eta-app-plugins/enabled，内容为 0/1。 */
    fun pluginMasterEnabled(): Boolean = try {
        val f = java.io.File("/data/local/tmp/eta-app-plugins/enabled")
        if (!f.isFile) true else f.readText().trim() != "0"
    } catch (throwable: Throwable) {
        true
    }

    private fun inRemoteScope(packageName: String): Boolean {
        val json = Prefs.getString(Prefs.Keys.AGENT_PLUGIN_SCOPE_JSON).ifBlank { "[]" }
        return packageInJson(packageName, json)
    }

    private fun inFileScope(packageName: String): Boolean {
        // 候选路径：/sdcard 是 fuse，Eta 和目标应用都能读；
        // /data/local/tmp 那份保留兼容（root 手动写过的场景）。
        val candidates = listOf(
            "/sdcard/eta-app-plugins-scope.json",
            "/data/local/tmp/eta-app-plugins/scope.json",
        )
        for (path in candidates) {
            val file = java.io.File(path)
            if (!file.isFile) continue
            val json = runCatching { file.readText() }.getOrNull() ?: continue
            if (json.isNotBlank() && packageInJson(packageName, json)) return true
        }
        return false
    }

    private fun packageInJson(packageName: String, json: String): Boolean = try {
        val array = JSONArray(json)
        (0 until array.length()).any { array.optString(it) == packageName }
    } catch (throwable: Throwable) {
        false
    }
}

internal object AppPluginHostEntry {

    fun install(
        module: XposedModule,
        rootLogger: io.github.mangi.eta.core.ModuleLogger,
        classLoader: ClassLoader,
        packageName: String,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "AppPlugin")
        return hooks.install {
            val applicationClass = HookSupport.findClassOrNull(classLoader, "android.app.Application")
            if (applicationClass == null) {
                hooks.missing(
                    id = "appplugin.application-attach",
                    description = "Application.attach hook（插件宿主启动点）",
                    detail = "android.app.Application 未找到（理论上不可能）",
                )
                return@install
            }
            val attachMethod = applicationClass.getDeclaredMethod("attach", android.content.Context::class.java)
            hooks.intercept(
                id = "appplugin.application-attach",
                executable = attachMethod,
                description = "Application.attach → 启动插件宿主（scope=$packageName）",
            ) { chain ->
                try {
                    val base = chain.args.firstOrNull() as? android.content.Context
                    if (base != null) {
                        AppPluginHost.start(base.applicationContext ?: base, module, rootLogger)
                    }
                } catch (throwable: Throwable) {
                    rootLogger.warn("AppPluginHost start failed: ${throwable.message?.take(160)}")
                }
                chain.proceed()
            }

            // 兜底：onPackageReady 时 Application.attach 往往已经执行完（hook 错过第一次），
            // 而 currentApplication 可能还没就绪（install 太早）。
            // 所以这里起一个短轮询线程：5 秒内每 500ms 试一次 currentApplication，
            // 拿到就启动宿主。进程都到 onPackageReady 了，Application 一定很快就有。
            Thread {
                val activityThreadClass = runCatching { Class.forName("android.app.ActivityThread") }
                    .getOrNull() ?: return@Thread
                val method = runCatching {
                    activityThreadClass.getDeclaredMethod("currentApplication")
                }.getOrNull() ?: return@Thread
                repeat(10) {
                    val app = runCatching { method.invoke(null) as? android.content.Context }
                        .getOrNull()
                    if (app != null) {
                        rootLogger.info("AppPluginHost ready: currentApplication 就绪，启动宿主")
                        try {
                            AppPluginHost.start(app, module, rootLogger)
                        } catch (throwable: Throwable) {
                            rootLogger.warn("AppPluginHost start failed: ${throwable.message?.take(160)}")
                        }
                        return@Thread
                    }
                    Thread.sleep(500)
                }
                rootLogger.warn("AppPluginHost: 5 秒内 currentApplication 一直为 null，放弃")
            }.apply {
                isDaemon = true
                name = "eta-plugin-wait"
                start()
            }
        }
    }
}
