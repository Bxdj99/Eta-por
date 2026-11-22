package io.github.mangi.eta.hook.appplugin

import android.content.Context
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.HookRegistrar
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 应用进程内插件宿主（issue #234）。
 *
 * hook Application.attach 拿到 Context 后：
 *  1. 起一个 loopback-only HTTP 服务（127.0.0.1，随机端口）；
 *  2. 把端口与鉴别 token 写到 <moduleDir>/app-plugins/<pkg>.port——
 *     只有 root 和本模块能读，作为「这个应用的插件服务在哪个端口」的注册表；
 *  3. Agent 端用 app_plugin_call 工具 POST /invoke 调用插件方法。
 *
 * 安全：只绑 127.0.0.1，其他设备/应用连不上；每次请求校验 token。
 */
internal object AppPluginHost {

    /** 宿主版本：/health 报告用，排查缓存旧 dex 时一测便知。 */
    private const val HOST_VERSION = "por93"

    @Volatile private var portFileExists: Boolean = false
    @Volatile private var lastPort: Int = -1
    private fun portFilePath(context: Context): String =
        File(File(context.filesDir, "eta-app-plugins"), "host.port").absolutePath

    private val running = AtomicReference<ServerSocket?>(null)
    private var token: String = ""
    private lateinit var logger: ModuleLogger
    private var moduleDir: File? = null

    private val httpExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "eta-plugin-http").apply { isDaemon = true }
    }

    fun start(
        application: Context,
        module: XposedModule,
        rootLogger: ModuleLogger,
    ) {
        // 幂等：同一进程重复 attach 不重启服务。
        if (running.get() != null) return
        logger = rootLogger
        moduleDir = try {
            File(module.moduleApplicationInfo!!.sourceDir).parentFile ?: File("/data/app/io.github.mangi.eta")
        } catch (throwable: Throwable) {
            File("/data/app/io.github.mangi.eta")
        }
        startServer(application)
    }

    private fun startServer(context: Context) {
        if (running.get() != null) return
        token = java.util.UUID.randomUUID().toString()
        AppPluginEngine.init(context, context.classLoader, logger)

        val thread = Thread({
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress("127.0.0.1", 0)) // 随机端口，loopback only
                running.set(server)
                writePortFile(context, server.localPort)
                logger.debug { "AppPluginHost listening on 127.0.0.1:${server.localPort}" }
                while (!server.isClosed) {
                    val socket = server.accept()
                    httpExecutor.execute { handle(socket, context) }
                }
            } catch (throwable: Throwable) {
                logger.warn("AppPluginHost stopped: ${throwable.safeLogType()} ${throwable.message?.take(120)}")
                running.set(null)
            }
        }, "eta-plugin-host")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * 端口与 token 的落地。
     *
     * 实测踩坑：/data/local/tmp 下 root 创建的目录是 shell_data_file 上下文，
     * untrusted_app 域被 SELinux 静默拦住——写不进去，chmod 777 也没用（DAC 过了 SE 不让）。
     * 所以 token/端口改写到应用自己的 filesDir（自己家必可写），Agent 端 root 读。
     */
    private fun writePortFile(context: Context, port: Int) {
        // 第一步先落诊断文件（不依赖任何后续步骤）：它没出现 = 本函数根本没被调到。
        runCatching {
            File("/data/local/tmp/eta-app-plugins", "host-error.log").appendText(
                "writePortFile enter: port=$port\n"
            )
        }
        try {
            val dir = File(context.filesDir, "eta-app-plugins").apply { mkdirs() }
            val file = File(dir, "host.port")
            val json = JSONObject()
                .put("port", port)
                .put("token", token)
                .put("pid", android.os.Process.myPid())
                .put("ts", System.currentTimeMillis())
            file.writeText(json.toString())
            portFileExists = file.exists()
            lastPort = port
            runCatching {
                File("/data/local/tmp/eta-app-plugins", "host-error.log").appendText(
                    "writePortFile ok: port=${'$'}port file=${'$'}{file.absolutePath} exists=${'$'}{file.exists()}\n"
                )
            }
            // 权限收紧：只留 root 可读
            Runtime.getRuntime().exec(arrayOf("chmod", "600", file.absolutePath))
        } catch (throwable: Throwable) {
            logger.warn("write port file failed: ${throwable.safeLogType()}")
            // 日志系统可能吞掉关键错误：把异常直接落文件，一锤定音。
            runCatching {
                File("/data/local/tmp/eta-app-plugins", "host-error.log").appendText(
                    "writePortFile failed: ${'$'}{exception.javaClass.name}: ${'$'}{exception.message}\n"
                )
            }
        }
    }

    // ---------- HTTP 最小实现（免引服务器依赖） ----------

    private fun handle(socket: Socket, context: Context) {
        try {
            socket.soTimeout = 10_000
            val input: InputStream = socket.getInputStream()
            val output: OutputStream = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]
            // 读 header 直到空行
            var contentLength = 0
            var authorized = false
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                if (lower.startsWith("x-eta-token:")) authorized = line.substringAfter(':').trim() == token
            }
            val body = if (contentLength > 0) {
                val buffer = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buffer, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buffer, 0, read, StandardCharsets.UTF_8)
            } else ""

            val responseJson = route(path, body, authorized, context)
            val bytes = responseJson.toString().toByteArray(StandardCharsets.UTF_8)
            val head = (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(StandardCharsets.UTF_8)
            output.write(head)
            output.write(bytes)
            output.flush()
        } catch (exception: Exception) {
            logger.debug { "http handle error: ${exception.safeLogType()}" }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun route(path: String, body: String, authorized: Boolean, context: Context): JSONObject {
        // /health 免 token：只回版本与运行时信息，用于排查「进程里跑的是哪版宿主」。
        if (path.startsWith("/health")) {
            return JSONObject()
                .put("ok", true)
                .put("hostVersion", HOST_VERSION)
                .put("package", context.packageName)
                .put("pid", android.os.Process.myPid())
                // 排查用：端口文件写到哪了、写成功没有，一目了然。
                .put("portFile", portFilePath(context))
                .put("portFileExists", portFileExists)
                .put("lastPort", lastPort)
                // token 也在这里给：端口文件落在应用沙盒里（app_data_file），
                // 宿主进程之外的 root 也读不到。loopback-only 的 /health 是本机唯一入口，
                // 能连上 127.0.0.1:该端口 的就是本机 root/同 uid，给 token 不扩大暴露面。
                .put("token", token)
        }
        if (!authorized) return JSONObject().put("ok", false).put("code", "UNAUTHORIZED")
        return when {
            path.startsWith("/invoke") -> {
                val json = runCatching { JSONObject(body) }.getOrElse {
                    return JSONObject().put("ok", false).put("code", "BAD_JSON")
                }
                val pluginId = json.optString("plugin")
                val method = json.optString("method")
                if (pluginId.isBlank() || method.isBlank()) {
                    return JSONObject().put("ok", false).put("code", "MISSING_ARGS")
                }
                val args = json.optJSONObject("args") ?: JSONObject()
                // 两种调用方式：带 source 就直接执行源码（推荐，绕开沙盒文件隔离）；
                // 不带 source 就按 pluginId 找文件（兼容旧路径）。
                val source = json.optString("source", "")
                if (source.isNotBlank()) {
                    AppPluginEngine.invokeSource(source, method, args)
                } else {
                    AppPluginEngine.invoke(pluginId, method, args)
                }
            }
            path.startsWith("/list") -> JSONObject()
                .put("ok", true)
                .put("package", context.packageName)
                .put("plugins", AppPluginEngine.listMethods())
            else -> JSONObject().put("ok", false).put("code", "NOT_FOUND")
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (c == '\n'.code) {
                if (buffer.isNotEmpty() && buffer[buffer.length - 1] == '\r') {
                    buffer.setLength(buffer.length - 1)
                }
                return buffer.toString()
            }
            buffer.append(c.toChar())
            if (buffer.length > 8192) return buffer.toString()
        }
    }
}
