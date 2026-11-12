package io.github.mangi.eta.agent.device

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.mangi.eta.data.repository.NotificationHistoryRepository

class AgentNotificationHistoryService : NotificationListenerService() {
    private val repository by lazy { NotificationHistoryRepository(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        record(sbn)
        // 气泡只是视觉层：放在 record 之后，任何异常都不影响通知入库
        io.github.mangi.eta.agent.overlay.BubbleNotificationOverlay.onPosted(this, sbn)
        // 有通知说明系统已经醒着，零成本补跑过期任务
        io.github.mangi.eta.agent.automation.AgentTaskScheduler.opportunisticRun(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedService = this
        activeNotifications?.forEach(::record)
        runCatching {
            java.io.File(filesDir, "bubble_debug.log")
                .appendText("${System.currentTimeMillis()} listener connected\n")
        }
    }

    override fun onListenerDisconnected() {
        if (connectedService === this) connectedService = null
        super.onListenerDisconnected()
        // ColorOS 会在后台回收监听服务，导致气泡收不到通知：主动请求系统重连。
        // requestRebind 有每秒一次的频率限制，失败就静默等系统自己拉起。
        runCatching {
            requestRebind(ComponentName(this, AgentNotificationHistoryService::class.java))
        }
    }

    override fun onDestroy() {
        if (connectedService === this) connectedService = null
        super.onDestroy()
    }

    private fun record(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        repository.record(
            key = sbn.key,
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            postedAt = sbn.postTime,
        )
    }

    companion object {
        @Volatile
        private var connectedService: AgentNotificationHistoryService? = null

        internal fun currentNotifications(): Array<StatusBarNotification>? {
            val service = connectedService ?: return null
            return try {
                service.activeNotifications
            } catch (_: RuntimeException) {
                null
            }
        }

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
                ?: return false
            return manager.isNotificationListenerAccessGranted(
                ComponentName(context, AgentNotificationHistoryService::class.java),
            )
        }
    }
}
