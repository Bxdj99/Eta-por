# Eta 3.4.1 移植分支交接说明

这是从 `Oo0520/Eta-por`（3.3.4 代 fork）把功能移植进上游 `Mangi-11/Eta` 3.4.1 基座的分支，
分支名 `por-on-341`。所有改动都在本仓库内，改功能直接在这里做。

## 目录与分支

- 分支：`por-on-341`
- 远端：`origin` = `Oo0520/Eta-por`，`upstream` = `Mangi-11/Eta`
- 每次发版递增 `app/build.gradle.kts` 里的 `versionCode` / `versionName`（`3.4.1-porNN`），否则覆盖安装会被拒。

## 构建（本机离网，必须 --offline）

```sh
export ANDROID_HOME=/workspace/android-sdk
export ETA_RELEASE_STORE_FILE=<你的 keystore>
export ETA_RELEASE_STORE_PASSWORD=<pwd>
export ETA_RELEASE_KEY_ALIAS=<alias>
export ETA_RELEASE_KEY_PASSWORD=<pwd>
./gradlew :app:assembleRelease --offline
```

产物：`app/build/outputs/apk/release/Eta-v<versionName>.apk`
签名配置只读环境变量，仓库不含任何 keystore / 密码。

## 测试

- 单元测试 `:app:testDebugUnitTest` 在离网机器上跑不了（Robolectric 依赖拉不到）。
- 台账内核有独立的离线契约测试：

```sh
sh tools/ledger-check/run.sh
```

覆盖 18 条：ok 必须带回执、同 dedupe_key 只记一次、review 列出缺证据、条目上限、taskId 不跳出目录、claim 互斥、数值结论双取证要求等。

## 关键模块地图

| 文件 | 作用 |
| --- | --- |
| `agent/runtime/AgentRuntimeWire.kt` | 进程间消息码常量（**注意不要撞值**，26 曾撞 16 导致插队消息路由错误） |
| `agent/runtime/AgentRuntimeClient.kt` | 客户端；同步客户端**禁止主线程**调用 |
| `agent/runtime/AgentRuntimeConnection.kt` | Binder 连接；主线程调用直接拒绝并打 trace |
| `agent/runtime/AgentRuntimeService.kt` | 服务端；MSG_STEER / requestSupplement / 浮窗 / 流体云 |
| `agent/runtime/AgentRuntimeSession.kt` | run 状态机；`steer` / `canAcceptSteer` |
| `agent/model/AgentLoop.kt` | 主循环；`finalGate` 收尾闸门、steering 消费 |
| `agent/tool/AgentTaskLedger.kt` + `LedgerCore.kt` | 工作台账（纯 JVM 内核，可离线测） |
| `agent/tool/AgentLocalTools.kt` | 工具执行；`ask_user`、`suicidalTerminalCommand` 拦截 |
| `agent/virtualdisplay/VirtualDisplayTuning.kt` | 灵屏分辨率 / 帧率 / 闲置时间设置项 |
| `agent/prompt/AgentPromptBuilder.kt` | 系统提示词（rg: `AgentPromptBuilder.kt`） |
| `ui/app/AgentAppState.kt` | UI 状态；插队 / 排队 / 停止 |
| `ui/SettingsScreen.kt` | 设置页；`SelectPref`（下拉型设置项） |

## 已经做过的能力（按版本）

| 版本 | 内容 |
| --- | --- |
| por2–por23 | 分类交互、自动任务页、斜杠命令、Markdown/KaTeX/文件卡片、灵屏点亮与宽限、子智能体插话 |
| por26–por35 | 灵屏面板、全屏查看器、悬浮小窗、手势分区、键盘联动 |
| por36–por43 | 浮窗按 fork 语义重写、气泡态修复、边缘拖动缩放、操作条跟随缩放、会话结束收气泡 |
| por44–por47 | 小窗默认最小档右上角、宽限 120 分钟、分辨率/帧率/宽限设置项、144fps 档 |
| por48–por52 | `task_ledger` 台账、`memory_detail` 记忆分层、`ask_user` 提示卡、收尾闸门 |
| por53 | SelectPref 监听 prefs 变化；自杀型命令拦截（杀宿主 / 改宿主配置） |
| por54 | 提示词：宿主设置走应用内 UI |
| por55–por59 | 插队改造：软通道优先 → 修 runId 取法 → 修消息码撞值 → **离开主线程调用**（真正的根因） |

## 排障入口

运行时数据（Android 侧，需 root）：

```sh
SP=$(pidof system_server | awk '{print $1}')
/proc/$SP/root/data/data/io.github.mangi.eta/
```

- `files/traces/eta-trace-YYYYMMDD.log` —— 主排障日志
- `files/agent/ledger/*.json` —— 工作台账
- `shared_prefs/eta_agent_preferences.xml` —— 灵屏设置
- `databases/eta.db` —— 会话 / 消息 / run

常用 trace 关键字：

| 关键字 | 含义 |
| --- | --- |
| `interrupt.steer` / `interrupt.steer.fail` | 插队决策与失败原因 |
| `interrupt.wire` | MSG_STEER 是否到达 Service |
| `interrupt.supplement` | 软通道投递结果（accepted=true/continuation/deferred/原因） |
| `runtime.acquire` | 主线程调用被拒绝（同步客户端误用） |
| `ledger record` / `ledger claim` | 台账写入 |
| `vscr.float` / `vscr.keep` | 灵屏悬浮窗与宽限 |

## 已知限制

- SharedPreferences 只在进程启动时读盘；外部改文件活进程看不到，跨进程改动必须重启进程。
- 软插队在「当前模型输出流结束后的下一回合」生效，无法从半句文本中间截断（SSE 协议限制）。
- 无障碍服务会被覆盖安装顶掉，安装后检查
  `settings get secure enabled_accessibility_services` 是否同时包含两个包名。
- 微信（`com.tencent.mm`）不向无障碍暴露控件，只能走截图 + 归一化坐标。
