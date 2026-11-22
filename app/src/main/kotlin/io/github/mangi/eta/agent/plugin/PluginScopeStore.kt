package io.github.mangi.eta.agent.plugin

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 插件作用域的读写（App 侧）。
 *
 * 为什么走文件而不是 SharedPreferences：
 * 作用域要能被**目标应用进程**读到，而 /data/data/<eta> 是 700 权限它进不去；
 * 唯一双方都可达的是 /data/local/tmp/eta-app-plugins/scope.json（全局可读）。
 * 目标应用读它判断是否启动宿主，Eta 写它配置作用域，两边共用一份。
 */
internal object PluginScopeStore {

    /**
     * 作用域文件位置的选择（试过三处，只有这处两端都能用）：
     *
     * - `/data/data/<eta>`：700，目标应用进不去 ❌
     * - `/sdcard/...`：Eta 能写，但 Android 11+ 存储隔离让目标应用读不到 ❌
     * - `/data/local/tmp/...`：**SELinux 是 shell_data_file，普通应用可读**（实测 uid 9999
     *   能 cat），只是不能写——所以 Eta 用 root 写，目标应用只读 ✅
     */
    private fun scopeFile(): File = File("/data/local/tmp/eta-app-plugins/scope.json")

    fun read(): List<String> = try {
        val file = scopeFile()
        if (!file.isFile) emptyList() else {
            val array = JSONArray(file.readText().ifBlank { "[]" })
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
        }
    } catch (throwable: Throwable) {
        emptyList()
    }

    /**
     * 写入作用域。**必须走 root**：/data/local/tmp 是 shell_data_file，
     * Eta 自己的 uid 写不了（SELinux 拦，chmod 无效），实测点「加入」毫无反应就是这个原因。
     * 写法：纯数据 + 644，让目标应用能读。
     */
    fun write(packages: List<String>): Boolean = try {
        val json = JSONArray(packages.distinct().sorted()).toString()
        val dir = scopeFile().parentFile ?: return false
        // 用 base64 传内容，避免 shell 引号转义把 JSON 弄坏。
        val encoded = android.util.Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP,
        )
        val command = buildString {
            append("mkdir -p ").append(dir.absolutePath).append(" && ")
            append("echo ").append(encoded).append(" | base64 -d > ").append(scopeFile().absolutePath)
            append(" && chmod 644 ").append(scopeFile().absolutePath)
        }
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0) {
            io.github.mangi.eta.core.EtaTrace.log("plugin.scope", "write failed code=$code out=${out.take(160)}")
        }
        code == 0 && read().containsAll(packages.distinct())
    } catch (throwable: Throwable) {
        io.github.mangi.eta.core.EtaTrace.log("plugin.scope", "write error: ${throwable.message?.take(120)}")
        false
    }

    fun add(context: Context, packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return false
        return write(read() + pkg)
    }

    fun remove(packageName: String): Boolean = write(read() - packageName)

    /** 已安装应用（含应用名），用于作用域选择列表。 */
    data class InstalledApp(val packageName: String, val label: String, val isSystem: Boolean)

    fun installedApps(context: Context): List<InstalledApp> = try {
        val pm = context.packageManager
        pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            .mapNotNull { info ->
                val pkg = info.packageName ?: return@mapNotNull null
                // 过滤自己：给自己注入代码没有意义。
                if (pkg == context.packageName) return@mapNotNull null
                val label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(pkg)
                val system = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                InstalledApp(pkg, label, system)
            }
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
    } catch (throwable: Throwable) {
        emptyList()
    }
}
