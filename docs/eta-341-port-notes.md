# Eta 3.4.1 移植与灵屏实测手册（2026-11-10）

本机环境：ColorOS / Android 16（SDK 37）、aarch64、KernelSU root。
代码树：`/workspace/por-git`（= `/data/local/tmp/eta/por-git`），分支 `por-on-341`，上游 `Mangi-11/Eta`。
构建：`export ANDROID_HOME=/workspace/android-sdk`，签名走 `ETA_RELEASE_STORE_FILE=/workspace/fix153/eta-fix.keystore`（口令 `etafix123`，别名 `etafix`，证书 SHA-256 `40:CE:1C:39:…:5B:0D`）。

```bash
cd /workspace/por-git
export ANDROID_HOME=/workspace/android-sdk \
       ETA_RELEASE_STORE_FILE=/workspace/fix153/eta-fix.keystore \
       ETA_RELEASE_STORE_PASSWORD=etafix123 \
       ETA_RELEASE_KEY_ALIAS=etafix ETA_RELEASE_KEY_PASSWORD=etafix123
./gradlew :app:assembleRelease --offline
# 产物：app/build/outputs/apk/release/Eta-v<version>.apk
pm install -r -d --user 0 <apk>
```

---

## 1. 数据与库

- 3.4.1 的数据目录是 **`io.github.mangi.eta`**（不是 etax）。系统 shell 的 `/data/data` 被 tmpfs 遮住，只能看到少数条目；
  正确入口是 **`/proc/$(pidof system_server)/root/data/data/io.github.mangi.eta/...`**。
- 直接写库（绕过 Room）时：**列必须是 `text` 类型**。用 `readfile()` 写入会得到 BLOB，Room 读 `getString` 直接
  `Unable to convert BLOB to string` → **冷启动崩溃**。写前 `select typeof(col)`，必要时 `CAST(... AS TEXT)`。
- 改库流程（app 运行中不要直接改）：`freeze` → 复制出来改 → 落 `eta.db.new` → `chown 10635:10635`、`chmod 660` →
  删旧 `-wal/-shm` → 同目录 `mv` 覆盖 → `unfreeze`。
- 删除会话不会级联删消息（SQLite 默认关闭外键）：删会话后要手工清 `conversation_messages` 与
  `agent_text_chunks` 里的孤儿行。

## 2. 灵屏（后台虚拟屏）

建屏在 `agent/virtualdisplay/`。三个必须记住的结论：

1. **不要 `FLAG_OWN_CONTENT_ONLY`**：AOSP 用它推导 `DisplayDeviceInfo.canHostTasks`，设上就是 `false`，
   任何 Activity/Task 都落不进这块屏（现象：应用"迁不进灵屏"、退回主屏）。
2. **不要 `FLAG_OWN_FOCUS`**：独立焦点屏会让 `AccessibilityManagerService` 只枚举默认 DisplayContent，
   节点树恒为空（现象：截图有画面、`observe_screen` 没有节点）。
3. **`hasRequiredDisplayFlags` 只硬性要求 TRUSTED**：把 OWN_FOCUS 也当门槛会产生"刚建好就判不合格 → 销毁重建"的死循环。

另外：

- 本机实测 `Display.getFlags()` 只回 `0x482`（TRUSTED|SECURE|NEVER_BLANK），**`OWN_DISPLAY_GROUP` / `ALWAYS_UNLOCKED` 会被框架剥掉**。
  意味着虚拟屏落在默认 display group：**主屏一熄，虚拟屏不再产生交互窗口，无障碍 windows 归零**。
  对策：会话存活期间持 `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`；若 `isKeyguardLocked`，
  用 root 执行 `cmd window dismiss-keyguard`。两个动作都有开关（见第 5 节）。
- **任务迁移在本机不可行**：ColorOS 把后台应用的任务塞进 `mHiddenTasks`，`ActivityTaskManager.getTasks`（含 root 身份）
  都取不到 → `TASK_NOT_FOUND`。所以"已运行就整块迁移"只对确实在任务列表里的前台任务有效，其余回落到启动命令。
- 启动应用用 `-f 0x18800000`（NEW_TASK|MULTIPLE_TASK|EXCLUDE_FROM_RECENTS），应用不会往主屏最近任务丢卡片。
- Android 16 的 `SafeActivityOptions` 拒 uid 0/2000 带 `--display`，只有 uid 1000(system) 能过；
  遇到 `Permission Denial`/`SecurityException` 时用 `su 1000 -c "…"` 重试。
- 不要一 `release` 就拆屏（原来每轮一个新 display，应用状态全丢），改成 **4 分钟宽限保留**，期间复用同一块屏。

## 3. 无障碍（a11y）与"节点为空"

判断"灵屏瞎不瞎"只需一行 trace：`a11y.tree display=N pkg=P nodes=… visited=… rootChildren=…`

- `rootChildren>0` + `nodes=0` → 节点全被判"不可见"（锁屏/熄屏方向）
- `rootChildren=0` → 该应用窗口的根没有孩子（应用自身不给树）
- 用 `windowsOnAllDisplays` 统计每个 display 的窗口数，可以确认"窗口在不在"：
  `all={0=3, 54=1} target=[t1 f1 a1 com.tencent.mm]`

**本机实测：微信（com.tencent.mm）在主屏与灵屏都是 `rootChildren=0`，并且开了 FLAG_SECURE（截图整块黑）；
对照同一时刻 launcher 15 节点、QQ 60 节点、计算器 26 节点。** 这是应用自身限制，与灵屏无关。

降级策略（`observe_screen` 已实现）：要了树但树为空 → 自动补一张截图一起返回，并提示
「用 normalized 0–999 估坐标 + tap_area；附图全黑说明该应用禁止截屏，不要再硬试」。

## 4. 自动任务

- 新工具（`tasks_*`、`conversation_categories`）**必须在 `AgentToolRequirements.definitions` 登记**，
  否则 `project()` 里 `requireNotNull` 会抛 `Missing tool requirements: xxx`，**整轮 Runtime 直接失败**
  （不是"这个工具不可用"，是整个 run 崩）。现已改成：未登记只跳过 + 告警，不拖垮整轮。
- `opportunisticRun` 会被无障碍的窗口变化频繁触发（实测 20–30 秒一次），每次都读库 + 重装闹钟。
  已加 `cachedNextDueAt` 闸门：未到期直接 return，trace 里这类记录归零。

## 5. 开关（设置页）

| 键 | 默认 | 含义 |
| --- | --- | --- |
| `agent_virtual_display_enabled` | true | 是否允许使用后台虚拟屏 |
| `agent_virtual_display_keep_awake` | true | 灵屏使用期间点亮并保持屏幕 |
| `agent_virtual_display_dismiss_keyguard` | true | 灵屏使用期间若锁屏则自动解除 |

前两个关掉后：灵屏仍可建、应用仍可启动，但**熄屏/锁屏时无障碍可能看不到窗口**（只能靠截图）。

## 6. 排障关键字（trace: `files/traces/eta-trace-YYYYMMDD.log`）

| 关键字 | 含义 |
| --- | --- |
| `vscr.create … flags=… interactive=… keyguard=… a11yWindows=…` | 建屏瞬间的状态 |
| `vscr.screen acquire/release` | 点亮屏幕的持锁/放锁 |
| `vscr.keyguard locked=… / dismiss requested` | 锁屏检测与解除 |
| `vscr.keep 复用既有屏 display=N` / `宽限期结束，拆屏` | 宽限保留是否生效 |
| `vscr.launch moved task=… / move_failed code=…` | 迁移分支结果 |
| `gui.observe canvas=… display=… tree=… nodes=… a11y=all={…}` | 每次观察的画布/节点/窗口分布 |
| `a11y.tree … rootChildren=…` | 空树归因 |
| `subagent.steer subagent=… delivered=…` | 子智能体定向插话是否投递成功 |
| `task.opportunistic` / `task.refresh` | 自动任务调度（前者应几乎不出现） |

## 7. 版本流水（本机 fork 侧）

| 版本 | 内容 |
| --- | --- |
| por2 | 会话分类交互（长按移动/改名/删除） |
| por4 | 自动任务页（列表/运行/停止/历史/编辑器） |
| por5 | 斜杠命令面板 + 注入 + 气泡剥块 |
| por6 | 代码块折行、任务清单勾选回写 |
| por7–8 | KaTeX 公式、文件卡片、卡片内勾选落盘 |
| por10–11 | 工具需求登记修复、投影兜底 |
| por12 | 自动任务空转闸门、observe 留痕 |
| por14–15 | 灵屏点亮、观测诊断 |
| por16 | 启动用 `EXCLUDE_FROM_RECENTS`、`--display` 被拒时 system 身份重试 |
| por17–18 | 迁移优先（进程内 → root helper），实测受限于 `mHiddenTasks` |
| por19 | 空树自动降级为截图 |
| por20 | 灵屏 4 分钟宽限保留、技能正文历史压缩 |
| por21 | 黑屏（FLAG_SECURE）提示 |
| por22 | 子智能体定向插话（注册表 + 定向通道 + 面板输入） |
| por23 | 打磨：灵屏点亮/解锁开关、自动任务上次结果预览、分类排序 |
