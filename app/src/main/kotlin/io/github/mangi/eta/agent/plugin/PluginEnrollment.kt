package io.github.mangi.eta.agent.plugin

import android.content.Context
import io.github.mangi.eta.core.EtaTrace
import org.json.JSONArray
import java.io.File

/**
 * 把应用加入插件作用域的完整流程（issue #234）。
 *
 * 有两道门，缺一不可：
 *  1. **LSPosed 作用域**（系统级）：模块 APK 不被勾进目标应用，代码根本注入不进去；
 *  2. **插件作用域**（应用级）：注入之后，要不要在这个进程里起 HTTP 宿主。
 *
 * 用户只会在「设置页」看到第 2 道门；第 1 道埋在 LSPosed 管理器里，
 * 普通人根本不会去勾。所以这里把两道门合并成一个动作：
 * 加进来 = 同时写两边 + 重启应用让注入生效 + 验证宿主真的起来了。
 */
internal object PluginEnrollment {

    private const val LSPD_DB = "/data/adb/lspd/config/modules_config.db"
    /**
     * 本模块在 LSPosed 里登记的包名。
     *
     * 不能写死：设备上实测存在 `io.github.mangi.eta` 和 `io.github.mangi.etax`
     * 两条记录，**真正被加载的是 `etax`**。往 `eta` 写 scope 完全无效——
     * 这是之前「加了作用域却不生效」的真正原因之一。
     * 所以运行时从库里挑：优先取 modules_state 里 enabled=1 的那条。
     */
    /**
     * 找出 LSPosed 里本模块实际登记的包名。
     *
     * 早先只查 modules_state.enabled=1，某些 LSPosed 版本该表为空或字段不同，
     * 查出来是空串 → 后续 insert 用了错误 module_pkg_name → 作用域写入"成功"但
     * 实际没写对 → 日志里出现 AppPlugin check ... enabled=false（实测便签就是这样）。
     * 这里多路兜底，并且把查到的值做合法性校验。
     */
    private fun modulePackage(executor: io.github.mangi.eta.agent.device.BoundedRootCommandExecutor): String {
        val queries = listOf(
            "select module_pkg_name from modules_state where enabled=1 and module_pkg_name like 'io.github.mangi.eta%' order by length(module_pkg_name) desc limit 1",
            "select module_pkg_name from modules where module_pkg_name like 'io.github.mangi.eta%' limit 1",
            "select distinct module_pkg_name from scope where module_pkg_name like 'io.github.mangi.eta%' limit 1",
        )
        for (q in queries) {
            val result = executor.execute(
                command = "sqlite3 $LSPD_DB \"$q\"",
                timeoutMillis = 5_000L,
                maxOutputBytes = 500,
            )
            val detected = result.stdout.trim().lines().firstOrNull()?.trim().orEmpty()
            if (detected.isNotBlank() && detected.startsWith("io.github.mangi.eta")) return detected
        }
        return "io.github.mangi.eta"
    }
    private const val SCOPE_FILE = "/data/local/tmp/eta-app-plugins/scope.json"

    data class Result(
        val ok: Boolean,
        val steps: List<String>,
        val detail: String = "",
    ) {
        fun summary(): String = buildString {
            append(if (ok) "已加入" else "加入失败")
            append("：")
            append(steps.joinToString("；"))
            if (detail.isNotBlank()) {
                append(" —— ")
                append(detail)
            }
        }
    }

    /** 目标应用的插件宿主是否正在运行（有 loopback 监听且 /health 回声匹配）。 */
    fun hasHost(packageName: String): Boolean = hasHostListener(packageName)

    /** 目标应用是否已经完整就绪（两道门都开 + 宿主在监听）。 */
    fun isReady(context: Context, packageName: String): Boolean =
        inLsposedScope(packageName) && inPluginScope(packageName) && hasHostListener(packageName)

    fun inPluginScope(packageName: String): Boolean = try {
        val file = File(SCOPE_FILE)
        if (!file.isFile) false else {
            val array = JSONArray(file.readText().ifBlank { "[]" })
            (0 until array.length()).any { array.optString(it) == packageName }
        }
    } catch (throwable: Throwable) {
        false
    }

    /**
     * 完整加入流程。root 不可用时第 1 步会失败，返回明确的失败原因，
     * 不谎报成功（用户要能看出卡在哪一步）。
     */
    fun enroll(
        context: Context,
        packageName: String,
        executor: io.github.mangi.eta.agent.device.BoundedRootCommandExecutor,
    ): Result {
        val steps = mutableListOf<String>()
        val pkg = packageName.trim()
        if (pkg.isBlank()) return Result(false, steps, "包名为空")

        // 1) LSPosed 作用域（系统级门）
        val lsposedOk = addToLsposedScope(executor, pkg)
        steps += if (lsposedOk) "LSPosed 作用域已勾选" else "LSPosed 作用域写入失败（需要 root）"

        // 2) 插件作用域（应用级门）
        val pluginOk = PluginScopeStore.add(context, pkg)
        steps += if (pluginOk) "插件作用域已写入" else "插件作用域写入失败（检查 /sdcard 可写）"

        // 即使某一道门失败，也继续往下走：能做的都做掉，
        // 最后如实报告哪一步没成功，而不是提前 return 让人不知道后续状态。
        if (!pluginOk) {
            return Result(false, steps, "插件作用域写入失败，后面的步骤没必要继续")
        }

        // 3) 重启目标应用，让新注入生效
        val restarted = restartApp(executor, pkg)
        steps += if (restarted) "已重启 $pkg" else "重启 $pkg 失败"

        // 4) 等宿主起来并验证
        val ready = waitForHost(executor, pkg, timeoutMs = 20_000)
        steps += if (ready) "插件宿主已就绪（检测到监听端口）" else "未检测到宿主监听（应用可能在后台被杀，或注入未生效）"

        EtaTrace.log("plugin.enroll", "pkg=$pkg lsposed=$lsposedOk plugin=$pluginOk ready=$ready")
        return Result(ready, steps, if (ready) "" else "可以再手动打开一次该应用，或检查 LSPosed 是否已对模块启用")
    }

    // ---------- 内部 ----------

    private fun addToLsposedScope(
        executor: io.github.mangi.eta.agent.device.BoundedRootCommandExecutor,
        packageName: String,
    ): Boolean {
        val modulePkg = modulePackage(executor)
        val sql = "insert or ignore into scope (module_pkg_name,app_pkg_name,user_id) values ('" +
            modulePkg + "','" + packageName + "',0);"
        val result = executor.execute(
            command = "sqlite3 $LSPD_DB \"$sql\"",
            timeoutMillis = 6_000L,
            maxOutputBytes = 1_000,
        )
        if (!result.ok) return false
        return inLsposedScope(packageName)
    }

    /**
     * 校验是否已在 LSPosed 作用域。
     *
     * 注意：必须走 root 执行器。/data/adb/lspd 只有 root 能读，
     * 早先用 ProcessBuilder 直接跑 sqlite3（Eta 自身 uid）必然失败，
     * 结果「实际已写入、却报写入失败」——用户看到的假报错就是这么来的。
     */
    private fun inLsposedScope(packageName: String): Boolean = try {
        val db = File(LSPD_DB)
        if (!db.isFile) false else {
            val process = ProcessBuilder(
                "su", "-c",
                "sqlite3 " + LSPD_DB +
                " \"select count(*) from scope where module_pkg_name in ('io.github.mangi.eta','io.github.mangi.etax') " +
                    "and app_pkg_name='" + packageName + "'",
            ).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            out.toIntOrNull()?.let { it > 0 } ?: false
        }
    } catch (throwable: Throwable) {
        false
    }

    private fun restartApp(
        executor: io.github.mangi.eta.agent.device.BoundedRootCommandExecutor,
        packageName: String,
    ): Boolean {
        executor.execute("am force-stop $packageName", 5_000L, 500)
        // 用 monkey 拉起主入口，不猜 Activity 名。
        val result = executor.execute(
            "monkey -p $packageName -c android.intent.category.LAUNCHER 1",
            timeoutMillis = 8_000L,
            maxOutputBytes = 1_500,
        )
        return result.ok
    }

    /** 等宿主监听端口出现（最多 timeoutMs）。 */
    private fun waitForHost(
        executor: io.github.mangi.eta.agent.device.BoundedRootCommandExecutor,
        packageName: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasHostListener(packageName)) return true
            Thread.sleep(700)
        }
        return hasHostListener(packageName)
    }

    /**
     * 检测目标应用有没有插件宿主的监听端口。
     * 判据：ss 里有该包的 loopback 监听，且 /health 回声的 package 对得上。
     */
    fun hasHostListener(packageName: String): Boolean = try {
        val ss = ProcessBuilder("sh", "-c",
            "ss -tln 2>/dev/null | grep '127.0.0.1' | grep -oE ':[0-9]+' | tr -d ':' | sort -u | head -40")
            .redirectErrorStream(true).start()
        val ports = ss.inputStream.bufferedReader().use { it.readText() }
        ss.waitFor()
        ports.lineSequence()
            .mapNotNull { it.trim().toIntOrNull() }
            .any { port ->
                runCatching {
                    val connection = java.net.URL("http://127.0.0.1:$port/health").openConnection()
                        as java.net.HttpURLConnection
                    connection.connectTimeout = 600
                    connection.readTimeout = 900
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrNull()?.contains("\"package\":\"$packageName\"") == true
            }
    } catch (throwable: Throwable) {
        false
    }
}
