package io.github.mangi.eta.agent.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

internal class AgentTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        worker.execute {
            try {
                if (intent.action == ACTION_PREWAKE) {
                    // 深睡(Doze)下系统会把任何闹钟推后 4~5 分钟（实测 setAlarmClock 也一样），
                    // 所以到期前先把自己叫醒并按住 CPU，等主闹钟真正到点时设备是醒的。
                    val power = context.getSystemService(android.os.PowerManager::class.java)
                    val lock = power?.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK, "Eta:task-prewake"
                    )?.apply {
                        setReferenceCounted(false)
                        acquire(20 * 60 * 1000L)
                    }
                    io.github.mangi.eta.core.EtaTrace.log(
                        "task.prewake", "预唤醒，已持 CPU 锁 ${lock?.let { 20 } ?: 0} 分钟"
                    )
                    return@execute
                }
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    runBlocking {
                        io.github.mangi.eta.data.db.EtaDatabase.get(context).agentTaskDao()
                            .recoverInterrupted(System.currentTimeMillis())
                    }
                    AgentTaskScheduler.publish(
                        context,
                        "device_boot",
                        System.currentTimeMillis().toString()
                    )
                    // 开机续跑提示：检查点里还有没跑完的会话就提醒一句，点开走现成的恢复流程。
                    io.github.mangi.eta.agent.runtime.InterruptedRunNotifier.notifyIfAny(context)
                }
                AgentTaskScheduler.enqueueDue(context)
                AgentTaskScheduler.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "io.github.mangi.eta.action.AUTOMATION_TICK"
        const val ACTION_PREWAKE = "io.github.mangi.eta.action.AUTOMATION_PREWAKE"
        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(
                runnable,
                "eta-task-alarm"
            ).apply { isDaemon = true }
        }
    }
}
