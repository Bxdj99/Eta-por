package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.R

/**
 * 灵屏的常驻入口（流体云 / 通知栏）。
 *
 * 会话结束、小窗被收成气泡之后，灵屏往往还在跑（120 分钟宽限 + 用户主动保留）。
 * 这条卡就是那时的唯一可见入口：点一下把悬浮小窗重新叫回来，点「收起」直接拆灵屏。
 *
 * 与 [io.github.mangi.eta.agent.display.dashboard.AgentLiveActivityBridge] 的分工：
 * 那边描述「一次任务跑到哪一步」，这边描述「灵屏这个屏还在不在」。所以用独立渠道与 ID，
 * 不参与会话卡的就地更新，也不会被任务结束清卡时一起撤掉。
 */
internal object VirtualScreenFloatNotifier {

    private const val CHANNEL = "eta_vsrc_float"
    private const val CHANNEL_NAME = "灵屏悬浮窗"

    /** 与 SessionNotificationIds 的 4772 / 4800+ / 5300+ 岔开。 */
    const val NOTIFICATION_ID = 4775

    private const val LIVE_SDK = 36

    fun publish(context: Context, appName: String) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "灵屏在后台运行时保留一个入口，点一下回到悬浮小窗"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
        val label = appName.trim().ifBlank { "" }
        val title = if (label.isEmpty()) "灵屏正在后台运行" else "灵屏 · $label"
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("小窗已收起，点一下看画面")
            .setColor(0xFF7BD8FF.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(showFloatIntent(app))
            .addAction(Notification.Action.Builder(null, "收起灵屏", hideFloatIntent(app)).build())
        if (Build.VERSION.SDK_INT >= LIVE_SDK && FluidNotificationPolicy.allowsFluid()) {
            builder.setShortCriticalText("灵屏")
            builder.setRequestPromotedOngoing(true)
        }
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun clear(context: Context) {
        val manager = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    private fun showFloatIntent(app: Context): PendingIntent = PendingIntent.getService(
        app,
        0x0B01,
        Intent(app, AgentRuntimeService::class.java)
            .setAction(AgentRuntimeService.ACTION_SHOW_VSRC_FLOAT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 直接关灵屏：先拆屏，再由快照回执清掉浮窗与这张卡。 */
    private fun hideFloatIntent(app: Context): PendingIntent = PendingIntent.getService(
        app,
        0x0B02,
        Intent(app, AgentRuntimeService::class.java)
            .setAction(AgentRuntimeService.ACTION_CLOSE_VSRC),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
