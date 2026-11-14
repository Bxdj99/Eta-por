package io.github.mangi.eta.agent.runtime

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.app.Service
import android.app.ActivityOptions
import android.app.PendingIntent
import io.github.mangi.eta.ui.MainActivity
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.window.OnBackInvokedDispatcher
import android.window.OnBackInvokedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayGlow
import io.github.mangi.eta.agent.overlay.ScreenCornerRadii
import io.github.mangi.eta.agent.overlay.AgentOverlayCapsule
import io.github.mangi.eta.agent.overlay.AgentOverlayClearance
import io.github.mangi.eta.agent.overlay.AgentResultCard
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.overlay.applyEvent
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySnapshot
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 模块进程内的通用 Agent Runtime。
 *
 * Hook 入口只发送请求和接收结果；模型调用、工具执行、运行状态浮窗都在本服务中完成。
 */
internal class AgentRuntimeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "agent-result-io") }
    private val serviceMessenger = Messenger(IncomingHandler())

    private val runs = RuntimeRunRegistry()
    private val guiControl = GuiControlCoordinator()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var destroyed = false
    @Volatile private var lastGuiOwnerRunId: String? = null
    private var displayedRun: RuntimeRunRegistry.Run? = null

    private var windowManager: WindowManager? = null
    private var glowView: ComposeView? = null
    private var capsuleView: ComposeView? = null
    private var resultCardView: ComposeView? = null
    private var glowParams: WindowManager.LayoutParams? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    private var resultCardParams: WindowManager.LayoutParams? = null
    private var resultCardBack: Pair<OnBackInvokedDispatcher, OnBackInvokedCallback>? = null

    /** 窗口只投影当前手机控制者；各运行的状态保留在自己的记录中。 */
    private val state = mutableStateOf(AgentOverlayState.Initial)
    private val virtualDisplayState = mutableStateOf(VirtualDisplaySnapshot.Inactive)
    private val capsuleExpanded = mutableStateOf(false)
    private val capsuleSupplementFocusable = mutableStateOf(false)
    /** Agent 手势穿过胶囊时为 true：窗口不可触摸，胶囊淡出让路。 */
    private val capsuleYielded = mutableStateOf(false)
    private val restoreCapsule = Runnable { setCapsuleYielded(false) }
    private val clearanceHost = object : AgentOverlayClearance.Host {
        override fun capsuleTouchBounds(): Rect? {
            val view = capsuleView?.takeIf { it.isAttachedToWindow } ?: return null
            val location = IntArray(2).also(view::getLocationOnScreen)
            val slop = dpToPx(CAPSULE_CLEARANCE_SLOP_DP)
            return Rect(
                location[0] - slop,
                location[1] - slop,
                location[0] + view.width + slop,
                location[1] + view.height + slop,
            )
        }

        override fun yieldCapsule() = setCapsuleYielded(true)

        override fun scheduleCapsuleRestore(delayMs: Long) {
            mainHandler.removeCallbacks(restoreCapsule)
            mainHandler.postDelayed(restoreCapsule, delayMs)
        }

        override fun cancelRestore() = mainHandler.removeCallbacks(restoreCapsule)
    }
    private val hideToken = Any()

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        AgentOverlayClearance.attach(clearanceHost)
        serviceScope.launch { guiControl.state.collect(::applyGuiControlState) }
        serviceScope.launch {
            VirtualDisplayController.snapshots.collect { snapshot ->
                val previous = virtualDisplayState.value
                val wasActive = previous.active
                virtualDisplayState.value = snapshot
                if (wasActive != snapshot.active) {
                    if (!snapshot.active) VirtualDisplayController.setStageExpanded(false)
                    // 灵屏一开就把小窗摆出来，灵屏真结束才收掉；会话结束不属于「结束」。
                    if (snapshot.active) {
                        vsrcFloatDismissedByUser = false
                        showVsrcFloat(auto = true)
                    } else {
                        vsrcFloatDismissedByUser = false
                        hideVsrcFloat()
                        VirtualScreenFloatNotifier.clear(this@AgentRuntimeService)
                    }
                    updateCapsuleWindowBounds()
                    displayedRun?.let(::syncActiveRunOverlayVisibility)
                }
                // 会话进行中流体云让给任务卡（会话卡点开就能看到灵屏），
                // 任务收尾后再把灵屏卡补上，两个时段各一张，不抢位置。
                if (snapshot.active && runs.all().isEmpty()) {
                    if (!wasActive || previous.packageName != snapshot.packageName ||
                        previous.appName != snapshot.appName
                    ) {
                        VirtualScreenFloatNotifier.publish(this@AgentRuntimeService, snapshot.appName)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 流体云/仪表盘上的按钮走 PendingIntent.getService 进来，在这里落到运行时控制。
        when (intent?.action) {
            ACTION_SHOW_VSRC_FLOAT -> {
                showVsrcFloat()
                return START_NOT_STICKY
            }
            ACTION_HIDE_VSRC_FLOAT -> {
                hideVsrcFloat()
                return START_NOT_STICKY
            }
            ACTION_CLOSE_VSRC -> {
                // 流体云卡上的「收起灵屏」：直接拆屏，不走宽限（120 分钟）
                hideVsrcFloat()
                VirtualScreenFloatNotifier.clear(this)
                thread(name = "eta-vsrc-close", isDaemon = true) {
                    runCatching { VirtualDisplayController.closeNow(this@AgentRuntimeService) }
                }
                return START_NOT_STICKY
            }
            ACTION_VSRC_FLOAT_SELFTEST -> {
                vsrcFloatSelfTest()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_RUN -> requestPause(intent.getStringExtra(EXTRA_RUN_ID))
            ACTION_PAUSE_RUNS -> requestPause(null)
            ACTION_RESUME_RUN -> requestResume()
            ACTION_STOP_RUN -> intent.getStringExtra(EXTRA_RUN_ID)?.takeIf { it.isNotBlank() }?.let(::cancelRun)
        }
        if (intent?.action != ACTION_KEEP_ALIVE || runs.all().isEmpty()) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (runs.all().isNotEmpty()) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        return false
    }

    override fun onDestroy() {
        destroyed = true
        if (activeInstance === this) activeInstance = null
        setKeepScreenOn(false)
        runs.all().forEach { run ->
            run.releaseIncoming()
            run.session.cancel("Agent Runtime 服务已停止")
        }
        displayedRun = null
        serviceScope.cancel()
        AgentExecutionService.updateRunStatus(this, null, activeRunCount = 0)
        resultIo.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        AgentOverlayClearance.detach(clearanceHost)
        VirtualDisplayController.release(this)
        unregisterResultCardBack()
        hideVsrcFloat()
        VirtualScreenFloatNotifier.clear(this)
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        glowView = null
        resultCardParams = null
        capsuleParams = null
        glowParams = null
        windowManager = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!isMessageSenderAllowed(msg)) {
                if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                }
                return
            }
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    val data = msg.data
                    if (data == null) {
                        finishWithFailure("Agent Runtime 请求缺少消息体", msg.replyTo)
                        return
                    }
                    val incoming = runCatching {
                        AgentRuntimeWire.incomingRunRequestFromBundle(data)
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                            "Agent runtime rejected invalid start request: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure("Agent Runtime 请求格式无效", msg.replyTo)
                        return
                    }
                    val request = incoming.request
                    if (request.runId.isBlank() || (request.operation != AgentRuntimeWire.OP_COMPACT && request.prompt.isBlank() && incoming.images.isEmpty() && !incoming.hasDeferredPrompt)) {
                        incoming.close()
                        finishWithFailure("Agent Runtime 请求缺少 runId 或用户输入", msg.replyTo)
                        return
                    }
                    ingestRunRequest(incoming, msg.replyTo, msg.sendingUid == android.os.Process.myUid())
                }

                AgentRuntimeWire.MSG_PAUSE -> {
                    io.github.mangi.eta.core.EtaTrace.log("interrupt.wire", "pause received")
                    requestPause(msg.data?.let(AgentRuntimeWire::runIdFromBundle)?.takeIf { it.isNotBlank() })
                }

                AgentRuntimeWire.MSG_RESUME -> {
                    requestResume()
                }

                AgentRuntimeWire.MSG_STEER -> {
                    val text = msg.data?.getString(AgentRuntimeWire.KEY_PROMPT).orEmpty()
                    val conversationId = msg.data?.getString(AgentRuntimeWire.KEY_CONVERSATION_ID).orEmpty()
                    val subagentId = msg.data?.getString(AgentRuntimeWire.KEY_SUBAGENT_ID).orEmpty()
                    io.github.mangi.eta.core.EtaTrace.log(
                        "interrupt.wire",
                        "steer received chars=${text.length} conv=$conversationId sub=$subagentId " +
                            "hasReplyTo=${msg.replyTo != null}",
                    )
                    val delivered = if (subagentId.isNotBlank()) {
                        // 定向给正在跑的子智能体：投它自己的 childController，不进主线补充队列
                        val ok = io.github.mangi.eta.agent.subagent.SubagentSteerRegistry
                            .steer(subagentId, text, conversationId)
                        io.github.mangi.eta.core.EtaTrace.log(
                            "subagent.steer",
                            "subagent=$subagentId delivered=$ok conv=$conversationId",
                        )
                        ok
                    } else {
                        val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle)?.takeIf { it.isNotBlank() }
                        requestSupplement(runId ?: displayedRun?.runId, text) != null
                    }
                    // 带了 replyTo 就回发投递结果：插队 UI 需要知道软通道是否成功，
                    // 失败才回落硬取消。不带 replyTo 的旧调用方行为不变（fire-and-forget）。
                    msg.replyTo?.let { reply ->
                        runCatching {
                            reply.send(Message.obtain().apply {
                                data = android.os.Bundle().apply { putBoolean("steer_delivered", delivered) }
                            })
                        }
                    }
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    dispatchResultIo { AgentRuntimeResultStore.remove(this@AgentRuntimeService, runId) }
                }

                AgentRuntimeWire.MSG_READ_CONTEXT_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    val owner = msg.data.getString("context_owner").orEmpty()
                    val replyTo = msg.replyTo
                    dispatchResultIo {
                        val target = AgentRuntimeResultStore.readOwned(this@AgentRuntimeService, runId, owner)
                        sendResultNow(replyTo, target?.result ?: AgentRuntimeWire.RunResult(
                            runId, false, "", "完整运行结果不可用", contextSnapshotRef = runId,
                        ))
                    }
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo, msg.data?.getBoolean("complete_result_refs") == true)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN -> {
                    sendActiveRun(msg.replyTo)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS -> {
                    sendActiveRuns(msg.replyTo)
                }

                AgentRuntimeWire.MSG_ATTACH_RUN -> {
                    attachRun(
                        runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return),
                        replyTo = msg.replyTo,
                    )
                }
            }
        }
    }

    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
        isEtaProcess: Boolean,
    ) {
        val run = admitRun(incoming.request, replyTo, incoming) ?: return
        thread(name = "agent-runtime-image-ingest") {
            val prepared = runCatching {
                run.session.controller.throwIfCancelled()
                run.awaitPredecessors()
                val request = AgentRuntimeRequestConfigResolver.constrainPresentation(
                    AgentRuntimeImageTransfer.materialize(incoming), isEtaProcess,
                )
                if (!AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request)) {
                    request
                } else {
                    val runtimeConfig = runBlocking {
                        if (AgentRuntimeRequestConfigResolver.usesDefaultRuntimeConfig(request)) {
                            RuntimeConfigRepository.currentRuntimeConfig()
                        } else {
                            RuntimeConfigRepository.oauthRuntimeConfigFor(
                                providerId = request.config.providerId,
                                authMode = request.config.authMode,
                                modelName = request.config.model,
                            )
                        }
                    } ?: throw RuntimeConfigUnavailableException()
                    AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
                }
            }
            mainHandler.post {
                run.releaseIncoming()
                if (destroyed || !runs.contains(run) || run.session.controller.isCancelled) return@post
                prepared.fold(
                    onSuccess = { request ->
                        val permissions = AgentRuntimePolicy.permissions(
                            Prefs.localAgentPreferences()
                        )
                        run.prepared(
                            request.copy(
                                config = AgentRuntimePolicy.constrain(request.config, permissions),
                            ),
                        )
                        executePreparedRun(run)
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_request_prepare_failed") {
                            "Agent runtime request preparation failed: type=${throwable.safeLogType()}"
                        }
                        failBeforeExecution(
                            run,
                            when (throwable) {
                                is AgentRuntimeImageTransfer.ImageTransferException ->
                                    throwable.message ?: "Agent Runtime 无法读取图片"
                                is RuntimeConfigUnavailableException ->
                                    "请先在 Eta 中配置可用的模型"
                                else -> "Agent Runtime 无法准备请求"
                            },
                        )
                    },
                )
            }
        }
    }

    private fun startRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
    ) {
        val run = admitRun(request, replyTo) ?: return
        run.prepared(request)
        executePreparedRun(run)
    }

    private fun admitRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger?,
        incoming: AgentRuntimeWire.IncomingRunRequest? = null,
    ): RuntimeRunRegistry.Run? {
        if (destroyed) {
            incoming?.close()
            sendRequestIngestedTo(replyTo, request.runId)
            sendResultTo(replyTo, AgentRuntimeWire.RunResult(request.runId, false, "", "Agent Runtime 服务已停止"))
            return null
        }
        // 多会话并发的内存闸门（移植自 fork）：并发数取「剩余内存算出的弹性上限」与硬上限的较小值。
        val runningCount = runs.all().count { !it.session.isTerminal }
        val limit = runCatching { AgentMemoryGate.sessionLimit() }
            .getOrDefault(MAX_CONCURRENT_RUNS)
            .coerceAtMost(MAX_CONCURRENT_RUNS)
        if (runningCount >= limit) {
            incoming?.close()
            sendRequestIngestedTo(replyTo, request.runId)
            sendResultTo(
                replyTo,
                AgentRuntimeWire.RunResult(
                    request.runId, false, "",
                    "并发会话已达上限（$runningCount/$limit，剩余 ${AgentMemoryGate.availableMb()}MB），请等前一个会话结束后再试",
                ),
            )
            io.github.mangi.eta.core.EtaTrace.log(
                "run.admit",
                "拒绝 ${request.runId} 并发=$runningCount/$limit 剩余=${AgentMemoryGate.availableMb()}MB",
            )
            return null
        }
        val admission = runs.admit(request) {
            lateinit var session: AgentRuntimeSession
            session = AgentRuntimeSession(
                runId = request.runId,
                operation = request.operation,
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                beforeTerminalPublish = {
                    runs.finish(request.runId, session)?.releaseIncoming()
                    AgentExecutionService.release("run:${request.runId}")
                    mainHandler.post {
                        if (!destroyed) {
                            publishExecutionState()
                            updateKeepScreenOn()
                            if (runs.all().isEmpty() && displayedRun == null && !guiControl.state.value.userPaused) stopSelf()
                        }
                    }
                },
            )
            RuntimeRunRegistry.Run(request, session, incoming) {
                sendRequestIngestedTo(replyTo, request.runId)
            }
        }
        val run = when (admission) {
            is RuntimeRunRegistry.Admission.Accepted -> admission.run
            is RuntimeRunRegistry.Admission.Duplicate -> {
                incoming?.close()
                sendRequestIngestedTo(replyTo, request.runId)
                if (replyTo != null) {
                    val attached = admission.run.session.attach(
                        eventSink = { event -> sendEventTo(replyTo, event) },
                        resultSink = { result -> sendResultTo(replyTo, result) },
                    )
                    if (!attached) sendResultTo(replyTo, AgentRuntimeWire.RunResult(
                        request.runId, false, "", "任务已结束，请恢复完整结果",
                        contextSnapshotRef = request.runId, operation = request.operation,
                    ))
                }
                return null
            }
            is RuntimeRunRegistry.Admission.Rejected -> {
                incoming?.close()
                sendRequestIngestedTo(replyTo, request.runId)
                AndroidAgentLogger.debug { "Agent runtime request rejected: code=${admission.code}" }
                sendResultTo(replyTo, AgentRuntimeWire.RunResult(
                    request.runId, false, "", admission.message,
                    operation = request.operation, rewriteTargetMessageId = request.rewriteTargetMessageId,
                ))
                return null
            }
        }
        // Root 入口保留原有绑定服务生命周期；新增 FGS 不能成为厂商后台入口的新前置权限。
        val allowBoundFallback = RootAccess.isGranted
        val executionHeld = AgentExecutionService.acquire(
            this, "run:${request.runId}", allowBoundFallback = allowBoundFallback,
        ) { mainHandler.post { cancelRun(request.runId) } }
        if (!executionHeld && !allowBoundFallback) {
            failBeforeExecution(run, "无法启动后台执行服务，请返回 Eta 后重试")
            return null
        }
        admission.replacedRuns.forEach { cancelRun(it.runId) }
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        publishExecutionState()
        return run
    }

    private fun failBeforeExecution(run: RuntimeRunRegistry.Run, message: String) {
        run.releaseIncoming()
        val result = AgentRuntimeWire.RunResult(
            run.runId, false, "", message,
            operation = run.request.operation, rewriteTargetMessageId = run.request.rewriteTargetMessageId,
        )
        dispatchResultIo {
            run.session.complete(result) {
                runCatching { persistRunArtifacts(run.snapshotRequest(), result, emptyList()) }
                    .onFailure { throwable ->
                        AndroidAgentLogger.error("Agent runtime preparation result persistence failed: type=${throwable.safeLogType()}")
                    }
            }
        }
    }

    private fun executePreparedRun(run: RuntimeRunRegistry.Run) {
        if (!runs.contains(run)) return
        val screenOnBinding = run.session.controller.register { mainHandler.post(::updateKeepScreenOn) }
        thread(name = "agent-runtime") {
            try {
                executeRun(run)
            } finally {
                screenOnBinding.close()
            }
        }
    }

    private fun executeRun(run: RuntimeRunRegistry.Run) {
        val outcome = AgentRuntimeRunExecutor(
            context = this,
            guiControl = guiControl,
            currentPermissions = ::currentRuntimePermissions,
            snapshotRequest = run::snapshotRequest,
            onAcceptedEvent = { event, _ ->
                handleAcceptedRunEvent(run, event)
            },
            persistArtifacts = ::persistRunArtifacts,
        ).execute(run.session, run.request)
        if (!outcome.shouldUpdateHost) return
        postTerminalOverlay(
            run = run,
            result = outcome.result,
            completedContext = outcome.response?.let { completedResponse ->
                outcome.completedRequest?.let { completedRequest ->
                    RuntimeRunRegistry.CompletedContext(
                        request = completedRequest,
                        response = completedResponse,
                    )
                }
            },
        )
    }

    private fun handleAcceptedRunEvent(
        run: RuntimeRunRegistry.Run,
        event: AgentEvent,
    ) {
        if (event is AgentEvent.GuiControlChanged) {
            run.guiStatus = event.status
            if (event.status == GuiControlStatus.Acquired) lastGuiOwnerRunId = run.runId
        }
        mainHandler.post {
            if (destroyed) return@post
            val guiState = guiControl.state.value
            val ownsGui = guiState.ownerRunId == run.runId
            // StateFlow 可以合并短暂的获得与释放；终态归属仍以顺序事件记录的最后所有者为准。
            if (event is AgentEvent.GuiControlChanged && event.status == GuiControlStatus.Acquired &&
                !guiState.userPaused && lastGuiOwnerRunId == run.runId && (ownsGui || guiState.ownerRunId == null)) {
                bindDisplayedRun(run)
            }
            if (AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, true)) {
                run.hasExecutedForegroundTool = true
            }
            runCatching {
                run.overlayState = run.overlayState.applyEvent(event)
                if (displayedRun === run && ownsGui && !run.session.isTerminal) {
                    state.value = run.overlayState
                    syncActiveRunOverlayVisibility(run)
                }
                publishExecutionState()
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_overlay_event_failed") {
                    "Agent runtime overlay event failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun persistRunArtifacts(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>,
    ) {
        // outbox 是终态与在途 checkpoint 之间的提交点；失败时保留 checkpoint 供下次恢复。
        persistCompletedRun(request, result)
        runCatching { persistArchivedRun(request, result, events) }
            .onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime archive persistence failed: type=${throwable.safeLogType()}"
                )
            }
    }

    private fun postTerminalOverlay(
        run: RuntimeRunRegistry.Run,
        result: AgentRuntimeWire.RunResult,
        completedContext: RuntimeRunRegistry.CompletedContext? = null,
    ) {
        mainHandler.post {
            if (destroyed) return@post
            run.completedContext = completedContext
            val guiState = guiControl.state.value
            if (lastGuiOwnerRunId != run.runId || guiState.userPaused ||
                (guiState.ownerRunId != null && guiState.ownerRunId != run.runId)) return@post
            bindDisplayedRun(run)
            updateKeepScreenOn()
            runCatching {
                if (result.ok) {
                    enterFinalState(
                        state.value.copy(
                            phase = AgentOverlayPhase.FINISHED,
                            status = AgentOverlayStatus.ResultReady,
                            detailText = result.content.trim().ifBlank { state.value.detailText },
                        ),
                    )
                } else {
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = if (result.error == "已停止") {
                                AgentOverlayStatus.Stopped
                            } else {
                                AgentOverlayStatus.RunFailed
                            },
                            detailText = result.error.orEmpty(),
                        ),
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_terminal_overlay_failed") {
                    "Agent runtime terminal overlay failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun sendEventTo(
        target: Messenger?,
        event: AgentEvent,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun dispatchResultIo(block: () -> Unit) {
        try {
            resultIo.execute {
                try { block() } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("runtime_result_io_failed") {
                        "Agent runtime result I/O failed: type=${failure.safeLogType()}"
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            AndroidAgentLogger.info("Agent runtime result delivery deferred after service stop")
        }
    }

    private fun sendResultTo(target: Messenger?, result: AgentRuntimeWire.RunResult) {
        dispatchResultIo { sendResultNow(target, result) }
    }

    private fun sendResultNow(
        target: Messenger?,
        result: AgentRuntimeWire.RunResult,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result, cacheDir)
            AgentWireText.send(target, msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
            // 不把传输失败伪装成已交付终态；引用使新客户端保留 outbox，等待完整恢复。
            val fallback = AgentRuntimeWire.RunResult(result.runId, false, "",
                "完整结果传输失败，已保存的历史未删除。请重新打开会话恢复。",
                contextSnapshotRef = result.runId, operation = result.operation)
            try {
                target?.send(Message.obtain(null, AgentRuntimeWire.MSG_RESULT).apply {
                    data = AgentRuntimeWire.toBundle(fallback)
                })
            } catch (deliveryFailure: Exception) {
                AndroidAgentLogger.warnThrottled("runtime_result_failure_notice_undelivered") {
                    "Agent runtime result notice undelivered: type=${deliveryFailure.safeLogType()}"
                }
            }
        }
    }

    private fun sendRequestIngestedTo(
        target: Messenger?,
        runId: String,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_REQUEST_INGESTED)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_ingest_ack_failed") {
                "Agent runtime ingest acknowledgement failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?, referencesOnly: Boolean) {
        dispatchResultIo { sendDrainedResultsNow(replyTo, referencesOnly) }
    }

    private fun sendDrainedResultsNow(replyTo: Messenger?, referencesOnly: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(
                if (referencesOnly) AgentRuntimeResultStore.pendingPage(this) else AgentRuntimeResultStore.list(this).take(8)
            )
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_drain_results_failed") {
                "Agent runtime drain results failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRun(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
            val snapshots = runs.snapshots()
            val runId = snapshots.firstOrNull { it.runId == guiControl.state.value.ownerRunId }?.runId
                ?: snapshots.firstOrNull()?.runId.orEmpty()
            msg.data = AgentRuntimeWire.ackBundle(runId)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_run_delivery_failed") {
                "Agent runtime active run delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRuns(replyTo: Messenger?) {
        runCatching {
            replyTo?.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS_RESPONSE).apply {
                data = AgentRuntimeWire.activeRunsToBundle(runs.snapshots())
            })
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_runs_delivery_failed") {
                "Agent runtime active runs delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun attachRun(runId: String, replyTo: Messenger?) {
        val session = runs.get(runId)?.session
        val attached = replyTo != null &&
            runId.isNotBlank() &&
            session?.runId == runId &&
            session.attach(
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                onReplayComplete = { sendAttachRunResponse(runId, replyTo, attached = true) },
            )
        if (!attached) sendAttachRunResponse(runId, replyTo, attached = false)
    }

    private fun sendAttachRunResponse(runId: String, replyTo: Messenger?, attached: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.attachRunResponseBundle(runId, attached)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_attach_run_delivery_failed") {
                "Agent runtime attach response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ) {
        val handoff = request.handoff ?: return
        AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun persistArchivedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        val handoff = request.handoff ?: return
        AgentExternalArchivePayload.from(handoff.payload) ?: return
        val userImagePreviews = if (
            handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        ) {
            request.images
                .asSequence()
                .take(MAX_ARCHIVED_USER_IMAGE_PREVIEWS)
                .mapNotNull { image ->
                    AgentImageCodec.previewFromReference(this, image)?.reference
                }
                .toList()
        } else {
            emptyList()
        }
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis(),
                userImagePreviews = userImagePreviews,
            )
        )
    }

    private fun finishWithFailure(
        message: String,
        replyTo: Messenger? = null,
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = "", ok = false, content = "", error = message),
        )
    }

    private fun requestStop(runId: String?) {
        if (runId == null || runs.get(runId) == null) {
            if (!guiControl.state.value.userPaused && displayedRun?.runId == runId &&
                guiControl.state.value.ownerRunId == null) dismissAndStop()
            return
        }
        cancelRun(runId)
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        val run = runs.get(runId) ?: return
        if (run.preparing) {
            run.session.controller.cancel()
            failBeforeExecution(run, "已停止")
            return
        }
        val session = run.session
        if (!session.isTerminal) {
            session.controller.cancel()
            run.overlayState = run.overlayState.copy(status = AgentOverlayStatus.Stopping)
            if (displayedRun === run && !guiControl.state.value.userPaused) state.value = run.overlayState
            updateKeepScreenOn()
            publishExecutionState()
        }
    }

    private fun requestPause(runId: String?) {
        if (runId != null) guiControl.pauseForUser(runId)
    }

    private fun requestResume() {
        guiControl.resumeForUser()
    }

    private fun requestSupplement(runId: String?, text: String): AgentEvent.UserSupplementReceived? {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return null
        // displayedRun 是「当前 UI 展示的那个 run」，插队目标未必等于它；
        // 一律以显式 runId 为准去 runs 里取，避免目标 run 因为不是 displayed 而被当成失败回落硬取消。
        val run = (runId?.takeIf { it.isNotBlank() }?.let { runs.get(it) }) ?: displayedRun ?: run {
            io.github.mangi.eta.core.EtaTrace.log(
                "interrupt.supplement", "runId=${runId.orEmpty()} accepted=false reason=run_not_found",
            )
            return null
        }
        if (guiControl.state.value.userPaused && guiControl.state.value.ownerRunId == null) {
            io.github.mangi.eta.core.EtaTrace.log(
                "interrupt.supplement", "runId=${run.runId} accepted=false reason=user_paused",
            )
            return null
        }
        setCapsuleInputMode(focusable = false)
        runs.get(run.runId)?.let { active ->
            val event = active.supplement(supplementText)
            if (event == null) {
                io.github.mangi.eta.core.EtaTrace.log(
                    "interrupt.supplement",
                    "runId=${active.runId} accepted=false reason=session_not_running terminal=${active.session.isTerminal}",
                )
            }
            if (event != null) {
                AndroidAgentLogger.info(
                    "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
                )
                active.overlayState = active.overlayState.applyEvent(event)
                if (displayedRun === active) state.value = active.overlayState
                io.github.mangi.eta.core.EtaTrace.log(
                    "interrupt.supplement",
                    "runId=${active.runId} index=${event.index} accepted=true",
                )
                return event
            }
            // event == null：session 已不在 RUNNING（正在收尾/已终结）。
            // 如果它还在跑（COMMITTING/尚未 TERMINAL），说明这一回合刚好收口，消息留给续聊接上；
            // 若已 TERMINAL，同样走下面的续聊。两者都不应该回落成「硬取消」。
            io.github.mangi.eta.core.EtaTrace.log(
                "interrupt.supplement",
                "runId=${active.runId} accepted=deferred can_steer=${active.session.canAcceptSteer}",
            )
        }

        // run 已经终结但保留了 completedContext：不是插话，走「补充续聊」——开一个新 run 继续。
        val completed = run.completedContext ?: return null
        if (completed.request.operation != AgentRuntimeWire.OP_CHAT ||
            completed.request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            state.value = state.value.copy(status = AgentOverlayStatus.ContinuationUnavailable)
            return null
        }
        val continuationRequest = AgentContinuationBuilder.build(
            request = completed.request,
            response = completed.response,
            supplement = supplementText,
        )
        startRun(continuationRequest)
        // 续聊已接手：对上层报告"已受理"，避免调用方把它当成失败去硬取消上一个 run。
        io.github.mangi.eta.core.EtaTrace.log(
            "interrupt.supplement",
            "runId=${run.runId} accepted=continuation newRunId=${continuationRequest.runId}",
        )
        return AgentEvent.UserSupplementReceived(-1, supplementText)
    }

    private fun ensureOverlayVisible() {
        showOverlay()
    }

    private fun syncActiveRunOverlayVisibility(run: RuntimeRunRegistry.Run?) {
        if (AgentOverlayVisibilityPolicy.shouldSuppressOverlayInEtaUi(
                handoffSource = run?.request?.handoff?.source,
                etaUiInForeground = MainActivity.isInForeground,
                virtualDisplayActive = virtualDisplayState.value.active,
            )
        ) {
            removeAmbientWindows()
        } else {
            ensureOverlayVisible()
        }
    }

    private fun handleEtaUiVisibilityChanged() {
        if (destroyed) return
        val run = displayedRun ?: return
        val suppress = AgentOverlayVisibilityPolicy.shouldSuppressOverlayInEtaUi(
            handoffSource = run.request.handoff?.source,
            etaUiInForeground = MainActivity.isInForeground,
            virtualDisplayActive = virtualDisplayState.value.active,
        )
        if (resultCardView != null) {
            if (suppress) dismissResult(run)
            return
        }
        val guiState = guiControl.state.value
        val activeGuiRun = guiState.userPaused || (guiState.ownerRunId == run.runId && !run.session.isTerminal)
        if (!activeGuiRun) return
        if (suppress) {
            removeAmbientWindows()
        } else {
            state.value = run.overlayState
            ensureOverlayVisible()
        }
    }

    private fun updateKeepScreenOn() {
        val guiState = guiControl.state.value
        val session = guiState.ownerRunId?.let(runs::get)?.session
        setKeepScreenOn(
            !guiState.userPaused && session != null && !session.isTerminal &&
                !session.controller.isCancelled && !session.controller.isPaused,
        )
    }

    private fun applyGuiControlState(guiState: GuiControlCoordinator.State) {
        if (destroyed) return
        val owner = guiState.ownerRunId?.let(runs::get)
        if (owner != null && displayedRun !== owner) {
            bindDisplayedRun(owner)
            AgentHapticFeedback.perform(this, AgentHapticFeedback.Type.RUN_STARTED)
        }
        when {
            guiState.userPaused -> {
                state.value = (owner?.overlayState ?: AgentOverlayState.Initial).copy(
                    phase = AgentOverlayPhase.PAUSED,
                    status = AgentOverlayStatus.Paused,
                    detailText = "手机操作已暂停",
                )
                owner?.overlayState = state.value
                syncActiveRunOverlayVisibility(owner ?: displayedRun)
            }
            owner != null -> {
                if (owner.overlayState.phase == AgentOverlayPhase.PAUSED) {
                    owner.overlayState = owner.overlayState.copy(
                        phase = AgentOverlayPhase.RUNNING,
                        status = AgentOverlayStatus.Continuing,
                        detailText = "",
                    )
                }
                state.value = owner.overlayState
                syncActiveRunOverlayVisibility(owner)
            }
            else -> removeAmbientWindows()
        }
        updateKeepScreenOn()
        publishExecutionState()
    }

    private fun bindDisplayedRun(run: RuntimeRunRegistry.Run) {
        if (displayedRun === run) return
        removeOverlayWindows()
        displayedRun?.completedContext = null
        displayedRun = run
        capsuleExpanded.value = false
        // 任务卡接棒：灵屏的常驻卡先撤，任务结束（dismissAndStop）再补回来。
        VirtualScreenFloatNotifier.clear(this)
    }

    private fun publishExecutionState() {
        val guiState = guiControl.state.value
        val activeRuns = runs.all().filterNot { it.session.isTerminal }
        val owner = activeRuns.firstOrNull { it.runId == guiState.ownerRunId }
        val status = if (owner != null && guiState.userPaused) {
            AgentOverlayStatus.Paused
        } else {
            (owner ?: activeRuns.firstOrNull())?.overlayState?.status
        }
        AgentExecutionService.updateRunStatus(
            this,
            status,
            activeRunCount = activeRuns.size,
        )
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        keepScreenOnState.value = enabled
        capsuleView?.keepScreenOn = enabled
    }

    private fun showOverlay() {
        if (capsuleView != null) return
        // TYPE_ACCESSIBILITY_OVERLAY 免 SYSTEM_ALERT_WINDOW 权限；仅回退态（无障碍未启用）才需检查
        if (AgentAccessibilityService.current() == null && !Settings.canDrawOverlays(this)) return
        val wm = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        // ── 氛围光窗口：全屏触摸穿透，彩虹光圈（后台虚拟屏运行时隐藏流光，仅承载全屏破壁动画） ─
        val glow = createOverlayComposeView {
            AgentOverlayGlow(
                state = state.value,
                corners = screenCornerRadii(),
                virtualDisplay = virtualDisplayState.value,
            )
        }
        val glowLp = glowLayoutParams()
        runCatching { wm.addView(glow, glowLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_glow_add_view_failed") {
                "Agent runtime glow addView failed: type=${throwable.safeLogType()}"
            }
        }
        glowView = glow
        glowParams = glowLp

        // ── 状态胶囊窗口：状态栏下方居中，收起时一句状态（含微缩镜），点击展开控制与画廊视窗 ─
        val capsule = createOverlayComposeView {
            val controlRunId = displayedRun?.runId
            AgentOverlayCapsule(
                state = state.value,
                expanded = capsuleExpanded.value,
                yielded = capsuleYielded.value,
                onToggleExpanded = ::toggleCapsule,
                onCollapsedSettled = ::onCapsuleCollapsedSettled,
                onPause = {
                    VirtualDisplayController.setUserControlling(false)
                    requestPause(controlRunId)
                },
                onResume = {
                    VirtualDisplayController.setUserControlling(false)
                    requestResume()
                },
                onStop = { requestStop(controlRunId) },
                onSupplementModeChange = ::setCapsuleInputMode,
                onSupplement = { requestSupplement(controlRunId, it) },
                virtualDisplay = virtualDisplayState.value,
                onTakeOverVirtualDisplay = {
                    VirtualDisplayController.setUserControlling(true)
                    requestPause(controlRunId)
                },
                onResumeVirtualDisplay = {
                    VirtualDisplayController.setUserControlling(false)
                    requestResume()
                },
                onFullscreenVirtualDisplay = ::requestFullscreenVirtualDisplay,
                onTapVirtualCanvas = { nx, ny, longPress ->
                    VirtualDisplayController.dispatchUserTapOnCanvas(nx, ny, longPress)
                },
                onSwipeVirtualCanvas = { sx, sy, ex, ey, durationMs ->
                    VirtualDisplayController.dispatchUserSwipeOnCanvas(sx, sy, ex, ey, durationMs)
                },
            )
        }
        val capsuleLp = capsuleLayoutParams()
        capsule.keepScreenOn = keepScreenOn.value
        runCatching { wm.addView(capsule, capsuleLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_add_view_failed") {
                "Agent runtime capsule addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        capsuleView = capsule
        capsuleParams = capsuleLp
    }

    /**
     * 让路时同步切换触摸穿透：标志位立刻生效保证手势不被截走，视觉过渡交给胶囊内的动画。
     * 恢复时先淡入再收回触摸，两者顺序无关紧要，因为恢复发生在手势结束之后。
     */
    private fun setCapsuleYielded(yielded: Boolean) {
        if (!yielded) mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = yielded
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (yielded) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_clearance_failed") {
                "Agent runtime capsule clearance update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun toggleCapsule() {
        val expand = !capsuleExpanded.value
        VirtualDisplayController.setStageExpanded(expand && virtualDisplayState.value.active)
        // 先把窗口扩到展开档再开始动画；收起由胶囊在动画结束后回调 onCollapsedSettled 缩窗。
        if (expand) updateCapsuleWindowBounds(expanding = true)
        capsuleExpanded.value = expand
    }

    private fun onCapsuleCollapsedSettled() {
        if (!capsuleExpanded.value) {
            VirtualDisplayController.setStageExpanded(false)
            updateCapsuleWindowBounds(expanding = false)
        }
    }

    private fun updateCapsuleWindowBounds(expanding: Boolean = capsuleExpanded.value) {
        val vdActive = virtualDisplayState.value.active
        val focusable = capsuleSupplementFocusable.value
        val widthDp = if (vdActive && expanding && !focusable) {
            CAPSULE_STAGE_WINDOW_WIDTH_DP
        } else if (vdActive) {
            CAPSULE_MICRO_WINDOW_WIDTH_DP
        } else {
            CAPSULE_WINDOW_WIDTH_DP
        }
        val heightDp = when {
            focusable -> CAPSULE_SUPPLEMENT_HEIGHT_DP
            expanding && vdActive -> CAPSULE_STAGE_HEIGHT_DP
            expanding -> CAPSULE_CONTROLS_HEIGHT_DP
            else -> CAPSULE_COLLAPSED_HEIGHT_DP
        }
        setCapsuleWindowSize(widthDp, heightDp)
    }

    private fun setCapsuleWindowSize(widthDp: Int, heightDp: Int) {
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val width = dpToPx(widthDp)
        val height = dpToPx(heightDp)
        if (lp.width == width && lp.height == height) return
        lp.width = width
        lp.height = height
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_resize_failed") {
                "Agent runtime capsule resize failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun requestFullscreenVirtualDisplay() {
        capsuleExpanded.value = false
        VirtualDisplayController.setStageExpanded(false)
        thread(name = "eta-vd-fullscreen-handoff", isDaemon = true) {
            VirtualDisplayController.handoffToMainDisplay(this@AgentRuntimeService)
        }
    }

    private fun showResultCard(wm: WindowManager) {
        if (resultCardView != null) return
        val card = createOverlayComposeView {
            val resultRun = displayedRun
            AgentResultCard(
                state = state.value,
                onClose = { dismissResult(resultRun) },
                onOpenEta = { openEtaFromResult(resultRun) },
                hasVirtualDisplayApp = virtualDisplayState.value.active &&
                    virtualDisplayState.value.packageName.isNotBlank(),
                onOpenVirtualApp = {
                    thread(name = "eta-vd-result-handoff", isDaemon = true) {
                        VirtualDisplayController.handoffToMainDisplay(this@AgentRuntimeService)
                        mainHandler.post { dismissResult(resultRun) }
                    }
                },
            )
        }
        val lp = resultCardLayoutParams()
        runCatching { wm.addView(card, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_card_add_view_failed") {
                "Agent runtime result card addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        resultCardView = card
        resultCardParams = lp
        registerResultCardBack(card)
        card.requestFocus()
    }

    /**
     * 结果卡片是任务结束后的停留界面，系统返回应先关闭它，而不是退回底下的应用。
     * 窗口需可获焦才能收到返回；卡片外仍是 FLAG_NOT_TOUCH_MODAL，触摸照常穿透到下层。
     */
    /**
     * 回到 Eta 本体并定位到本次任务的会话。卡片窗口可见时属于前台可见的用户操作，
     * 允许从服务启动 Activity；启动失败时保留卡片，不让用户丢失结果。
     */
    private fun openEtaFromResult(run: RuntimeRunRegistry.Run?) {
        val handoff = run?.request?.handoff
        val conversationId = handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
            ?.takeIf(String::isNotBlank)
        val archive = handoff
            ?.takeIf { it.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { item -> AgentExternalArchivePayload.from(item.payload)?.conversationKey?.let { item.source to it } }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                when {
                    conversationId != null -> {
                        action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                    }
                    archive != null -> {
                        action = MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION
                        putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, archive.first)
                        putExtra(MainActivity.EXTRA_CONVERSATION_KEY, archive.second)
                    }
                }
            }
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pendingIntentBackgroundActivityStartMode = if (Build.VERSION.SDK_INT >= 36) {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                } else {
                    @Suppress("DEPRECATION")
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
            }
        }
        val launched = runCatching {
            PendingIntent.getActivity(
                this, RESULT_OPEN_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).send(this, 0, null, null, null, null, options.toBundle())
        }
        if (launched.isFailure) {
            AndroidAgentLogger.warn("Agent runtime result open failed: type=${launched.exceptionOrNull()?.safeLogType()}")
            return
        }
        dismissResult(run)
    }

    private fun dismissResult(run: RuntimeRunRegistry.Run?) {
        if (run != null && displayedRun === run && lastGuiOwnerRunId == run.runId &&
            guiControl.state.value.ownerRunId == null && !guiControl.state.value.userPaused) dismissAndStop()
    }

    private fun registerResultCardBack(view: View) {
        unregisterResultCardBack()
        val dispatcher = view.findOnBackInvokedDispatcher() ?: run {
            AndroidAgentLogger.warn("Agent runtime result card back dispatcher unavailable")
            return
        }
        val resultRun = displayedRun
        val callback = OnBackInvokedCallback { dismissResult(resultRun) }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        resultCardBack = dispatcher to callback
    }

    private fun unregisterResultCardBack() {
        val (dispatcher, callback) = resultCardBack ?: return
        resultCardBack = null
        runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    private var vsrcFloatView: ComposeView? = null

    private var vsrcFloatParams: WindowManager.LayoutParams? = null

    /** 用户在小窗上按了「关闭」：本次灵屏生命周期里不再自动弹出来。 */
    private var vsrcFloatDismissedByUser = false

    /** 灵屏悬浮小窗：贴屏幕右上，标题条拖动，内容区点/滑直接注入灵屏。 */
    private fun showVsrcFloat(auto: Boolean = false) {
        if (auto && vsrcFloatDismissedByUser) return
        if (vsrcFloatView != null) return
        // 重新建窗以前先把「折叠前尺寸」清掉，否则展开气泡会跳到上一次的旧尺寸。
        vsrcPreCollapseWidth = 0
        vsrcPreCollapseHeight = 0
        vsrcFloatDismissedByUser = false
        // 关键：TYPE_ACCESSIBILITY_OVERLAY 必须用无障碍服务的 WindowManager 添加，
        // 用 Service 自己的 WindowManager 会抛 BadTokenException（上游注释里踩过这个坑）。
        val addContext = overlayContext()
        val windowManager = addContext.getSystemService(WindowManager::class.java) ?: run {
            io.github.mangi.eta.core.EtaTrace.log("vscr.float", "no WindowManager")
            return
        }
        io.github.mangi.eta.core.EtaTrace.log(
            "vscr.float",
            "show type=${overlayType()} ctx=${addContext.javaClass.simpleName}",
        )
        val view = createOverlayComposeView {
            io.github.mangi.eta.ui.components.VirtualScreenFloatContent(host = vsrcFloatHost)
        }
        // 一开始就用最小档：72dp 短边（长边按灵屏比例撑开），贴在右上角。
        // 想要更大自己拖边缘；这里不再默认铺一块 210×400 的大窗挡屏幕。
        val snapshot = virtualDisplayState.value
        val aspect = if (snapshot.width > 0 && snapshot.height > 0) {
            snapshot.height.toFloat() / snapshot.width.toFloat()
        } else {
            2.17f
        }
        val paramsWidth = dpToPx(FLOAT_MIN_DP.toInt())
        val paramsHeight = (paramsWidth * aspect).toInt().coerceAtLeast(1)
        val params = WindowManager.LayoutParams(
            paramsWidth,
            paramsHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            val bounds = vsrcScreenBounds()
            x = (bounds.width() - paramsWidth - dpToPx(8)).coerceAtLeast(0)
            // 让开状态栏与顶部胶囊（胶囊底边约 112dp），落在右上角。
            y = dpToPx(120)
        }
        vsrcTargetShortDp = FLOAT_MIN_DP
        runCatching { windowManager.addView(view, params) }.onFailure {
            AndroidAgentLogger.warn("virtual screen float window add failed: ${it.javaClass.simpleName}")
            io.github.mangi.eta.core.EtaTrace.log(
                "vscr.float",
                "add_failed type=${it.javaClass.simpleName} msg=${it.message?.take(80)}",
            )
            return
        }
        vsrcFloatView = view
        vsrcFloatParams = params
        AndroidAgentLogger.debug { "virtual screen float window shown" }
        io.github.mangi.eta.core.EtaTrace.log(
            "vscr.float",
            "shown w=${params.width} h=${params.height} x=${params.x} y=${params.y} gravity=${params.gravity}",
        )
    }

    /** 气泡边长（fork 的 collapse 里写死 35dp 方形）。 */
    private val BUBBLE_DP = 35f

    private var tierIndex = 2

    /** 拖动位移的小数余量（像素），避免逐事件 toInt() 截断成 0。 */
    private var vsrcMoveAccX = 0f
    private var vsrcMoveAccY = 0f

    /** 缩放的目标短边（dp）。和位移一样：逐事件的因子变化太小，必须累积，否则全被闸门丢掉。 */
    private var vsrcTargetShortDp = 0f

    /** 折叠气泡的记忆点：哪一侧、折叠前的位置，展开时按 fork 的 expand() 还原。 */
    private var vsrcCollapsedSide = 0
    private var vsrcPreCollapseX = 0
    private var vsrcPreCollapseY = 0

    /** 折叠前的窗口尺寸。恢复 fork 的 FLOAT_TIERS 会把用户拖过的大小扔掉。 */
    private var vsrcPreCollapseWidth = 0
    private var vsrcPreCollapseHeight = 0


    private val vsrcFloatMaxWidth by lazy { dpToPx(420) }

    private val vsrcFloatMinWidth by lazy { dpToPx(120) }

    private fun vsrcScreenBounds(): android.graphics.Rect = runCatching {
        overlayContext().getSystemService(WindowManager::class.java)
            ?.currentWindowMetrics?.bounds
    }.getOrNull() ?: android.graphics.Rect(0, 0, 1440, 3136)

    private fun applyVsrcFloatParams(mutate: (WindowManager.LayoutParams) -> Unit) {
        val params = vsrcFloatParams ?: return
        val view = vsrcFloatView ?: return
        mutate(params)
        runCatching {
            overlayContext().getSystemService(WindowManager::class.java)
                ?.updateViewLayout(view, params)
        }
    }

    /** 悬浮窗手势宿主：按 fork 的 VirtualScreenFloatWindow 语义操作窗口参数。 */
    private val vsrcFloatHost = object : io.github.mangi.eta.ui.components.VirtualScreenFloatHost {
        override fun moveBy(dx: Float, dy: Float) {
            // 一次 MOVE 往往不到 1px：直接 toInt() 会被截断成 0，表现就是"怎么拖都不动"。
            // 把小数余量攒起来，凑够 1px 再真正改窗口参数。
            vsrcMoveAccX += dx
            vsrcMoveAccY += dy
            val stepX = vsrcMoveAccX.toInt()
            val stepY = vsrcMoveAccY.toInt()
            if (stepX == 0 && stepY == 0) return
            vsrcMoveAccX -= stepX
            vsrcMoveAccY -= stepY
            applyVsrcFloatParams { params ->
                params.x += stepX
                params.y += stepY
            }
        }

        override fun scaleBy(factor: Float) {
            // 逐事件因子通常只有 1.002 这种量级，直接乘当前尺寸再四舍五入等于原地踏步，
            // 所以维护一个浮点"目标短边"，累积到够一格（0.2dp）才真正重排窗口。
            if (vsrcTargetShortDp <= 0f) vsrcTargetShortDp = vsrcShortSideDp()
            vsrcTargetShortDp = (vsrcTargetShortDp * factor).coerceIn(FLOAT_MIN_DP, FLOAT_MAX_DP)
            if (kotlin.math.abs(vsrcTargetShortDp - vsrcShortSideDp()) < 0.2f) return
            applyVsrcFloatSize(vsrcTargetShortDp)
            io.github.mangi.eta.core.EtaTrace.log("vscr.float", "scale short=$vsrcTargetShortDp")
        }

        override fun settle(snapNow: Boolean) {
            val bounds = vsrcScreenBounds()
            val density = resources.displayMetrics.density.coerceAtLeast(1f)
            val bubble = (BUBBLE_DP * density).toInt()
            var side = -1
            applyVsrcFloatParams { params ->
                params.y = params.y.coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
                val maxX = (bounds.width() - params.width).coerceAtLeast(0)
                params.x = params.x.coerceIn(0, maxX)
                // 贴到左右边缘就收气泡（拖动中 snapNow=true 时立即收，与 fork 的 collapseIfAtEdge 一致）
                when {
                    params.x <= 1 -> side = 0
                    params.x >= maxX - 1 -> side = 1
                    else -> side = -1
                }
                if (side >= 0) {
                    // 照 fork 的 collapse()：35dp 方形气泡、垂直居中于原窗口、贴死边缘
                    vsrcPreCollapseX = params.x
                    vsrcPreCollapseY = params.y
                    vsrcPreCollapseWidth = params.width
                    vsrcPreCollapseHeight = params.height
                    params.y = (params.y + (params.height - bubble) / 2)
                        .coerceIn(0, (bounds.height() - bubble).coerceAtLeast(0))
                    params.width = bubble
                    params.height = bubble
                    params.x = if (side == 0) 0 else bounds.width() - bubble
                }
            }
            if (side >= 0) {
                vsrcCollapsedSide = side
                io.github.mangi.eta.core.EtaTrace.log(
                    "vscr.float",
                    "collapse side=$side bubble=$bubble x=${vsrcPreCollapseX} snap=$snapNow",
                )
            }
        }

        override fun setCollapsed(collapsed: Boolean) {
            if (!collapsed) {
                // 展开回用户折叠前的尺寸；没有记录才回到档位默认。
                if (vsrcPreCollapseWidth > 0 && vsrcPreCollapseHeight > 0) {
                    applyVsrcFloatExactSize(vsrcPreCollapseWidth, vsrcPreCollapseHeight)
                } else {
                    applyVsrcFloatSize(FLOAT_TIERS_DP[tierIndex])
                }
                val bounds = vsrcScreenBounds()
                applyVsrcFloatParams { params ->
                    params.x = vsrcPreCollapseX
                        .coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
                    params.y = vsrcPreCollapseY
                        .coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
                }
            } else {
                settle(snapNow = false)
            }
            io.github.mangi.eta.core.EtaTrace.log("vscr.float", "collapsed=$collapsed")
        }

        override fun close() {
            // 用户主动关掉：灵屏还活着也不再自动弹，等他点流体云或面板再说。
            vsrcFloatDismissedByUser = true
            hideVsrcFloat()
            io.github.mangi.eta.core.EtaTrace.log("vscr.float", "closed_by_user")
        }

        override fun openFullscreen() {
            hideVsrcFloat()
            runCatching {
                startActivity(
                    Intent(this@AgentRuntimeService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** 按像素尺寸还原窗口（折叠展开时恢复用户拖出来的大小）。 */
    private fun applyVsrcFloatExactSize(widthPx: Int, heightPx: Int) {
        val bounds = vsrcScreenBounds()
        applyVsrcFloatParams { params ->
            params.width = widthPx
            params.height = heightPx
            params.x = params.x.coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
        }
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        vsrcTargetShortDp = (minOf(widthPx, heightPx) / density).coerceAtLeast(FLOAT_MIN_DP)
    }

    /**
     * 会话结束但灵屏还活着：把大窗收成贴右边的气泡。
     * 入口不会消失：气泡点一下就原尺寸展开，流体云卡也能叫回来。
     */
    private fun collapseVsrcFloatToEdge() {
        val params = vsrcFloatParams ?: return
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val bubble = (BUBBLE_DP * density).toInt()
        if (params.width <= bubble + 6) return
        val bounds = vsrcScreenBounds()
        applyVsrcFloatParams { p ->
            vsrcPreCollapseX = p.x
            vsrcPreCollapseY = p.y
            vsrcPreCollapseWidth = p.width
            vsrcPreCollapseHeight = p.height
            p.width = bubble
            p.height = bubble
            p.x = (bounds.width() - bubble).coerceAtLeast(0)
            p.y = (vsrcPreCollapseY + (vsrcPreCollapseHeight - bubble) / 2)
                .coerceIn(0, (bounds.height() - bubble).coerceAtLeast(0))
        }
        vsrcCollapsedSide = 1
        io.github.mangi.eta.core.EtaTrace.log(
            "vscr.float",
            "auto_collapse bubble=$bubble x=${bounds.width() - bubble}",
        )
    }

    /** 当前窗口短边（dp），用于捏合与切档。 */
    private fun vsrcShortSideDp(): Float {
        val params = vsrcFloatParams ?: return FLOAT_TIERS_DP[tierIndex]
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        return (minOf(params.width, params.height) / density).coerceAtLeast(FLOAT_MIN_DP)
    }

    /** 以"内容短边 dp"设窗口尺寸，长边按灵屏比例撑开。 */
    private fun applyVsrcFloatSize(shortDp: Float) {
        val snapshot = virtualDisplayState.value
        val aspect = if (snapshot.width > 0 && snapshot.height > 0) {
            snapshot.height.toFloat() / snapshot.width.toFloat()
        } else {
            2.17f
        }
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val shortPx = (shortDp * density).toInt().coerceAtLeast(1)
        val longPx = (shortPx * aspect).toInt().coerceAtLeast(1)
        val bounds = vsrcScreenBounds()
        applyVsrcFloatParams { params ->
            params.width = shortPx
            params.height = longPx
            params.x = params.x.coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
        }
        vsrcTargetShortDp = shortDp
    }

    /** 自检：顶到右边缘 → 走一次 settle，验证是否变成 35dp 方形气泡。 */
    private fun vsrcFloatSelfTest() {
        // 先验证位移累加：连续 10 次 0.4px 应当累积成 4px（以前逐次 toInt() 会全丢）
        val px0 = vsrcFloatParams?.x ?: 0
        val py0 = vsrcFloatParams?.y ?: 0
        repeat(10) { vsrcFloatHost.moveBy(0.4f, 0.3f) }
        val px1 = vsrcFloatParams?.x ?: 0
        val py1 = vsrcFloatParams?.y ?: 0
        io.github.mangi.eta.core.EtaTrace.log(
            "vscr.float",
            "selftest move dx=${px1 - px0} dy=${py1 - py0} (期望 4 / 3)",
        )
        // 先验证"边缘拖动缩放"那条链路：放大 25% 再贴边收气泡，两步都打点
        val before = vsrcFloatParams
        vsrcFloatHost.scaleBy(1.25f)
        val afterScale = vsrcFloatParams
        io.github.mangi.eta.core.EtaTrace.log(
            "vscr.float",
            "selftest scale ${before?.width}x${before?.height} -> ${afterScale?.width}x${afterScale?.height}",
        )
        val bounds = vsrcScreenBounds()
        applyVsrcFloatParams { params -> params.x = bounds.width() }
        vsrcFloatHost.settle(snapNow = true)
        val params = vsrcFloatParams
        io.github.mangi.eta.core.EtaTrace.log(
            "vscr.float",
            "selftest after-settle w=${params?.width} h=${params?.height} x=${params?.x} y=${params?.y}",
        )
    }

    private fun hideVsrcFloat() {
        val view = vsrcFloatView ?: return
        io.github.mangi.eta.core.EtaTrace.log("vscr.float", "hide")
        runCatching {
            overlayContext().getSystemService(WindowManager::class.java)?.removeView(view)
        }
        vsrcFloatView = null
        vsrcFloatParams = null
    }

    private fun createOverlayComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = AppearanceSettings())
                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = false,
                ) {
                    // 部分 ROM 会给 TYPE_ACCESSIBILITY_OVERLAY 分配软件 Canvas；Miuix 与 COUI 的
                    // RuntimeShader 只检查系统版本，因此系统浮层统一使用无 RuntimeShader 的圆角回退。
                    CompositionLocalProvider(
                        LocalSquircleEnabled provides false,
                        LocalCouiContourGlowEnabled provides false,
                    ) {
                        content()
                    }
                }
            }
        }

    /**
     * 胶囊窗口使用固定宽度与分档高度，动画只在窗口内进行。
     * WRAP_CONTENT 会让每一帧的尺寸变化都触发 WindowManager 重新布局整个窗口，这是卡顿的来源；
     * 但窗口内透明区域同样会吞掉触摸（包括 Agent 自己注入的点击），所以高度只在需要时扩大：
     * 展开前先扩到目标档，收起动画结束后再缩回，见 [setCapsuleWindowHeight]。
     */
    private fun capsuleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dpToPx(if (virtualDisplayState.value.active) CAPSULE_MICRO_WINDOW_WIDTH_DP else CAPSULE_WINDOW_WIDTH_DP),
            dpToPx(CAPSULE_COLLAPSED_HEIGHT_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 贴在状态栏下方居中：远离多数应用的主要点击区，也和系统胶囊、流体云的位置习惯一致。
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeightPx()
            windowAnimations = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

    private fun statusBarHeightPx(): Int {
        val metrics = (overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.currentWindowMetrics ?: return dpToPx(28)
        return metrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout())
            .top
            .takeIf { it > 0 } ?: dpToPx(28)
    }

    private fun resultCardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            resultCardWindowHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 半屏底部居中，窗口外触摸穿透
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

    private fun overlayType(): Int =
        // 无障碍服务可用时用 TYPE_ACCESSIBILITY_OVERLAY（免 SYSTEM_ALERT_WINDOW 权限，且截图
        // filterValidWindows 可过滤）；需用无障碍服务 context 创建，否则 BadTokenException
        if (AgentAccessibilityService.current() != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun overlayContext(): Context =
        AgentAccessibilityService.current() ?: this

    @Suppress("DEPRECATION")
    private fun glowLayoutParams(): WindowManager.LayoutParams {
        // 真实屏幕高度（含状态栏 + 导航栏），MATCH_PARENT 在部分设备不含系统栏
        val realHeight = runCatching {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(point)
            point.y
        }.getOrDefault(WindowManager.LayoutParams.MATCH_PARENT)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            realHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 全屏覆盖（含状态栏/导航栏），触摸穿透不拦截页面操作；
            // TYPE_ACCESSIBILITY_OVERLAY 让 takeScreenshotOfWindow 过滤掉，对 Agent 透明
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 非应用窗口默认按系统栏与挖孔收缩窗口框，光带画出的圆角就会整体下移，和物理屏幕角错位。
            // 显式不避让任何 inset，窗口原点才与屏幕原点重合，Display 上报的圆角半径才对得上。
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    /**
     * 屏幕四角的物理圆角半径。光效窗口覆盖整屏，直接取 Display 上报的值；
     * 部分 ROM 不上报时用 0，兜底按常见大圆角机型估一个保守值，宁可略小也不让光带越出屏幕。
     */
    private fun screenCornerRadii(): ScreenCornerRadii {
        val display = (overlayContext().getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val fallback = dpToPx(FALLBACK_SCREEN_CORNER_DP).toFloat()
        fun radius(position: Int): Float =
            display?.getRoundedCorner(position)?.radius?.takeIf { it > 0 }?.toFloat() ?: fallback
        return ScreenCornerRadii(
            topLeft = radius(android.view.RoundedCorner.POSITION_TOP_LEFT),
            topRight = radius(android.view.RoundedCorner.POSITION_TOP_RIGHT),
            bottomRight = radius(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT),
            bottomLeft = radius(android.view.RoundedCorner.POSITION_BOTTOM_LEFT),
        )
    }

    private fun setCapsuleInputMode(focusable: Boolean) {
        capsuleSupplementFocusable.value = focusable
        updateCapsuleWindowBounds()
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_focus_update_failed") {
                "Agent runtime capsule focus update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun resultCardWindowHeightPx(): Int =
        (resources.displayMetrics.heightPixels * RESULT_CARD_HEIGHT_RATIO).toInt()

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun enterFinalState(finalState: AgentOverlayState) {
        state.value = finalState

        val run = displayedRun
        if (AgentOverlayVisibilityPolicy.shouldShowResultCard(
                hasExecutedForegroundTool = run?.hasExecutedForegroundTool == true,
                handoffSource = run?.request?.handoff?.source,
                etaUiInForeground = MainActivity.isInForeground,
            )
        ) {
            // 撤掉胶囊和边缘光，改显半屏结果卡片；无交互时由卡片自身延时退场并回调关闭
            capsuleExpanded.value = false
            removeAmbientWindows()
            if (windowManager == null && (AgentAccessibilityService.current() != null || Settings.canDrawOverlays(this))) {
                windowManager = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            }
            windowManager?.let(::showResultCard) ?: dismissAndStop()
            mainHandler.removeCallbacksAndMessages(hideToken)
        } else {
            dismissAndStop()
        }
    }

    private fun removeAmbientWindows() {
        mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = false
        capsuleView?.keepScreenOn = false
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView = null
        glowView = null
        capsuleParams = null
        glowParams = null
    }

    private fun dismissAndStop() {
        // stopSelf 不会销毁仍被客户端绑定的服务；窗口关闭后不再保留完整请求与结果历史。
        displayedRun?.completedContext = null
        displayedRun = null
        removeOverlayWindows()
        // 会话收尾但灵屏还在：小窗不撤，收成贴边气泡并保留流体云卡。
        // 用户要的是「别消失、收起来一点就能看见」。
        if (virtualDisplayState.value.active) {
            collapseVsrcFloatToEdge()
            VirtualScreenFloatNotifier.publish(this, virtualDisplayState.value.appName)
        }
        if (runs.all().isEmpty() && !guiControl.state.value.userPaused) {
            VirtualDisplayController.release(this)
            stopSelf()
        }
    }

    private fun removeOverlayWindows() {
        mainHandler.removeCallbacksAndMessages(hideToken)
        mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = false
        capsuleView?.keepScreenOn = false
        unregisterResultCardBack()
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        glowView = null
        resultCardParams = null
        capsuleParams = null
        glowParams = null
        windowManager = null
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean {
        val uid = msg.sendingUid
        if (uid == Process.myUid()) return true
        val packages = runCatching {
            packageManager.getPackagesForUid(uid)
        }.getOrNull().orEmpty()
        return packages.any { it in ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES }
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun currentRuntimePermissions(): AgentRuntimePolicy.Permissions =
        AgentRuntimePolicy.permissions(
            Prefs.localAgentPreferences()
        )

    companion object {
        /** 会话并发硬上限；实际并发由 AgentMemoryGate.sessionLimit() 按剩余内存决定。 */
        const val MAX_CONCURRENT_RUNS = 6
        const val ACTION_PAUSE_RUN = "io.github.mangi.eta.agent.runtime.PAUSE_RUN"
        const val ACTION_RESUME_RUN = "io.github.mangi.eta.agent.runtime.RESUME_RUN"
        const val ACTION_PAUSE_RUNS = "io.github.mangi.eta.agent.runtime.PAUSE_RUNS"
        const val ACTION_STOP_RUN = "io.github.mangi.eta.agent.runtime.STOP_RUN"
        const val ACTION_SHOW_VSRC_FLOAT = "io.github.mangi.eta.agent.runtime.VSRC_FLOAT_SHOW"
        const val ACTION_HIDE_VSRC_FLOAT = "io.github.mangi.eta.agent.runtime.VSRC_FLOAT_HIDE"

        /** 流体云/通知栏上的「收起灵屏」：不走宽限，直接拆屏并收掉小窗。 */
        const val ACTION_CLOSE_VSRC = "io.github.mangi.eta.agent.runtime.VSRC_CLOSE"

        /** 内部自检：把窗口顶到右边缘并触发贴边折叠，用来核对气泡几何。 */
        const val ACTION_VSRC_FLOAT_SELFTEST = "io.github.mangi.eta.agent.runtime.VSRC_FLOAT_SELFTEST"

        /** fork 的悬浮窗档位（内容短边 dp），双击在四档间循环。 */
        private val FLOAT_TIERS_DP = floatArrayOf(96f, 144f, 200f, 272f)
        private const val FLOAT_MIN_DP = 72f
        private const val FLOAT_MAX_DP = 420f

        /** 打开灵屏悬浮小窗（由 App 内面板/查看器调用）。 */
        fun showVirtualScreenFloat(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AgentRuntimeService::class.java)
                        .setAction(ACTION_SHOW_VSRC_FLOAT)
                )
            }
        }

        fun hideVirtualScreenFloat(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AgentRuntimeService::class.java)
                        .setAction(ACTION_HIDE_VSRC_FLOAT)
                )
            }
        }
        const val EXTRA_RUN_ID = "run_id"
        @Volatile
        private var activeInstance: AgentRuntimeService? = null

        // 只投影到 App 与 GUI 浮层的现有窗口，窗口不可见时由系统恢复息屏。
        private val keepScreenOnState = MutableStateFlow(false)
        val keepScreenOn = keepScreenOnState.asStateFlow()

        internal fun onEtaUiVisibilityChanged() {
            val instance = activeInstance ?: return
            instance.mainHandler.post { instance.handleEtaUiVisibilityChanged() }
        }

        private const val ACTION_KEEP_ALIVE = "io.github.mangi.eta.agent.runtime.KEEP_ALIVE"
        private const val HIDE_DELAY_MS = 2_500L
        private const val RESULT_REVIEW_DELAY_MS = 120_000L
        private const val RESULT_CARD_HEIGHT_RATIO = 0.5f
        private const val RESULT_OPEN_REQUEST_CODE = 1108
        private const val CAPSULE_WINDOW_WIDTH_DP = 272
        private const val CAPSULE_MICRO_WINDOW_WIDTH_DP = 290
        private const val CAPSULE_STAGE_WINDOW_WIDTH_DP = 324
        private const val CAPSULE_COLLAPSED_HEIGHT_DP = 68
        private const val CAPSULE_CONTROLS_HEIGHT_DP = 124
        private const val CAPSULE_STAGE_HEIGHT_DP = 524
        private const val CAPSULE_SUPPLEMENT_HEIGHT_DP = 260
        /** 注入点击只是一个点，目标控件常贴着胶囊边缘，判定范围向外多留一点。 */
        private const val CAPSULE_CLEARANCE_SLOP_DP = 8
        private const val FALLBACK_SCREEN_CORNER_DP = 28
        private const val MAX_ARCHIVED_USER_IMAGE_PREVIEWS = 4
    }

    private class RuntimeConfigUnavailableException : IllegalStateException()
}
