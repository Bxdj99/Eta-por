================================================================
 Eta 3.3.0 补丁包 — 功能清单

生成日期：2026-10-08（最后更新：2026-10-11）
================================================================

本补丁包共三十一层，按顺序应用（01→09、10 构建层、11→31）：
  01-features-on-3.3.patch        第一层（@ljw666a 功能）
  02-悬浮窗与胶囊定制.patch        第二层（在此基础上的定制）
  03-本轮新增功能（增量）.patch    第三层（工具档五档/离线朗读/会话分类/批次3/会话分类收尾）
  04-用量统计+崩溃留证+自动备份（增量）.patch  第四层（用量统计30天/趋势图/CSV导出/数据占用清理/崩溃留证/定时自动备份/中文单语）
  05-并发多会话+悬浮胶囊会话列表+工具串行锁+文本重复修复（增量）.patch  第五层（多会话并发/悬浮胶囊列会话/工具串行锁/重连文本重复修复/内存胶囊/图片预览/Linux能力探测）
  06-功能自检+悬浮胶囊最近会话（增量）.patch  第六层（功能自检一键体检/胶囊常驻开关/面板放大可点/完成提示不消失/保留最近3条会话）
  08-健康提醒+进度通知+任务重试+行为核查+诊断包自动化+自检补齐（增量）.patch  第八层（无障碍断连提醒/运行进度通知/中断续跑提示/自动任务重试与立刻重试/内存降档说明/行为核查与账目/诊断包每周自动导出与分享/续跑横幅小结/功能自检补齐）
  09-工具档细化+上下文三轮压缩+返回栈崩溃与分享修复（增量）.patch  第九层（工具档六档细化与长按说明气泡/上下文三轮压缩与锁死/返回栈重复Home崩溃修复/分享链路加固/崩溃报告带本进程日志/记忆界面与仓库调整/versionCode递增）
  10-版本号递增到2026101020（构建层）.patch  第十层（app/build.gradle.kts 的 versionCode 2026101019→2026101020，构建层，与业务代码零耦合）
  11-插话按会话投递（修串回复）.patch  第十一层（插话协议带会话键 + 服务端按会话精确匹配 + 胶囊入口绑定会话，修「别的会话的插话被吸进正在跑的长任务」）
  12-顶栏状态行右靠紧凑.patch  第十二层（actionIconPadding 16dp→6dp + 胶囊行去行尾 4dp，顶栏整串右移）
  13-功能自检补两条内置项.patch  第十三层（自检 ③ 补「插话按会话投递」「顶栏状态行右靠紧凑」，共 34 → 36 项；③ 列表 24 → 26）
  14-功能自检补四条内置项（补齐09层与11-13层功能）.patch  第十四层（自检 ③ 再补 09 层的四项：对话内三轮压缩／工具档「开发」档与分组／记忆自动精简／返回栈去重，共 36 → 40 项；③ 列表 26 → 30）
  15-功能自检补齐新增功能（十四项）.patch  第十五层（自检 ③ 一次性补齐十四条：崩溃留证与导出／数据占用与清理／搜索跳转高亮／消息选择复制／会话批量删除与分类折叠／胶囊常驻与最近会话／任务完成通知／重任务互斥闸门／分享入口加固／朗读档位与熄屏续读／中文单语与提示中文化／工具档长按说明气泡／记忆界面与段落仓库，③ 列表 30 → 44 项；总数 40 → 54）
  16-自检工具档文案校正（六档）.patch  第十六层（自检 ③「工具档」一条由五档校正为六档并列出档位名，条数不变，仍 44 条）

基础版本：Eta 官方 3.3.0（作者 @蛮吉1664 / Mangi-11）


================================================================
 第一层：@ljw666a 功能补丁（01-features-on-3.3.patch）
================================================================

1. 独立 Root 虚拟屏
   文件：agent/display/RootDisplayCommandMain、agent/display/VirtualScreen*
   - GUI 任务在独立 display 执行，不占用主屏
   - 统一 GUI 路由、触控预览、Unicode / 中文输入
   - 续聊保留、空树截图后备、可恢复节点
   - 独立熄屏许可与通知授权回退
   - 应用冲突三选项（停止重开虚拟屏 / 主屏继续 / 取消）
   - 要求 Android 14+ 与 Root，vivo Android 16 实机验证

2. 定时任务与场景规则
   文件：agent/automation/AgentTask*
   - 任务类型：一次 / 间隔 / 每日
   - 23 种触发器：时间、通知、前台应用、屏幕亮灭、供电（充电/断电/低电）、
     网络连接/断开、蓝牙、耳机、开机等
   - 管理页、权限状态、队列去重、执行次数与历史记录

3. 自动经验复盘
   文件：agent/runtime/AgentExperienceReview
   - 成功任务后独立复盘
   - 按设置追加记忆、创建/更新用户技能
   - 带工具白名单、限额与版本校验

4. 顶栏 tok/s 与用量统计
   - 平均输出速度、上下文占比
   - 持久化展示

5. vivo 蓝心小 V 支持（6.8.5.3）
   文件：hook/vivo/*
   - 文字 / 语音接管
   - 流式续聊
   - 原子岛通知
   - 便签 / 待办上下文读取
   - 需对应 ROM 与 LSPosed

6. 其他
   - 蓝牙连接权限入口
   - 侧栏 / 触发器目录
   - 网页搜索 Bing RSS 后备


================================================================
 第二层：悬浮窗与胶囊定制（02-悬浮窗与胶囊定制.patch）
================================================================

1. 虚拟屏悬浮小窗
   文件：agent/display/VirtualScreenFloatWindow.kt（新增）+ EtaApp.kt 初始化 +
        SettingsDataStore / Settings / SettingsScreen / VirtualScreenViewerActivity
   - 纯监控小窗：画面区不转发触摸，防止误触
   - 白色透光包边（70% 白）+ 22dp 大圆角
   - 单指拖拽移动、双指缩放（45dp ~ 420dp）、双击跳全屏触控
   - 贴边 4dp 内自动折叠成 35dp 圆形气泡，点气泡从原位置展开恢复
   - 横竖屏旋转时自动对调宽高比例（横屏横着放、竖屏竖着放）
   - 尺寸 / 位置 / 透明度全部持久化，重启不丢，支持一键重置

2. 虚拟屏胶囊控制
   文件：agent/runtime/AgentRuntimeService.kt
   - 虚拟屏运行时屏幕顶部显示状态胶囊
   - 胶囊含：插入信息 / 暂停 / 结束
   - 胶囊永不随小窗折叠隐藏，始终输出虚拟屏状态
   - 原无障碍模式的胶囊能力完整接入虚拟屏模式

3. 生成中插话（输入栏一变三）
   文件：AgentRuntimeWire / AgentRuntimeClient / AgentRuntimeService /
        AgentAppState / AgentUiActions / AgentAppRoot / AgentHomeScreen /
        AgentChatBody / AgentChatInputBar
   - Eta 生成时，输入框右侧按钮变成三个：暂停 / 停止 / 发送
   - 直接打字点发送 = 插话，走 steering 通道注入下一轮
   - 插话不打断后台运行、不取消在途请求
   - 暂停在 turn 边界生效，不杀正在跑的模型请求和工具批次
   - 真正跨进程实现（MSG_PAUSE / MSG_RESUME / MSG_STEER）

4. 任务完成通知
   文件：agent/display/VirtualScreenNotification.kt
   - 虚拟屏指令跑完自动弹通知
   - 走 IMPORTANCE_HIGH 高优先级通道，ColorOS 归入流体云
   - 区分「任务已完成 / 任务失败」，点击跳回 Eta

5. 会话历史批量删除
   文件：ConversationPanePanel / ConversationSidePaneScaffold / AgentAppShell /
        AgentAppRoot / 三语言 strings.xml
   - 「会话历史」标题最右侧一键进入批量模式
   - 勾选多项 → 全选 / 取消 / 删除
   - 二次确认弹窗防误删


================================================================
 应用顺序（重要）
================================================================

  官方 3.3.0
     │  应用 01-features-on-3.3.patch
     ▼
  合并版 c82c51f（@ljw666a 功能）
     │  应用 02-悬浮窗与胶囊定制.patch
     ▼
  上一版完整版
     │  应用 03-本轮新增功能（增量）.patch
     ▼
  当前完整版（含全部定制 + 本轮新增）

必须 01 → 02 → 03 顺序应用。02 依赖 01 打完后才存在的虚拟屏代码，03 的上下文行依赖 02 打完后的内容。

命令示例：
  git clone https://github.com/Mangi-11/Eta.git Eta && cd Eta
  git checkout -b add-features
  git am --3way /path/to/01-features-on-3.3.patch
  git apply --3way /path/to/02-悬浮窗与胶囊定制.patch
  git apply --3way /path/to/03-本轮新增功能（增量）.patch
  ./gradlew :app:assembleDebug


================================================================
 文件索引
================================================================

  01-features-on-3.3.patch      第一层功能补丁（@ljw666a）
  02-悬浮窗与胶囊定制.patch      第二层定制补丁
  03-本轮新增功能（增量）.patch  第三层增量补丁（63 文件，281,734 B；第四批整层重算）
  feature-commits/              第一层原始的 18 个功能提交
  apply-features.sh             一键顺序应用 01→02→03
  PORTING.md                    移植指南（三层易冲突点、数据库红线）
  新增功能入库流程.txt           以后新增功能的归档规范
  源码/                         第二层定制的 22 个文件完整版


================================================================
 第三层：03-本轮新增功能（增量）.patch     —— 2026-10-08
================================================================

基线：打过 01 + 02 之后的完整版
产物：Eta-3.3.0-会话分类-release.apk（BUILD SUCCESSFUL，9,958,926 B）
变更：45 个文件（另 4 个编译环境文件已剔除），补丁 189,157 B；完整文件在 源码-03/

【本地工具档 · 五档 + 一行说明】
  正常·原样 / 轻度·截短 / 标准·核心 / 纯聊天·无工具 / 极简·最小
  顶栏胶囊可切换；新增 LocalToolProfile / LocalToolProfileStore /
  AgentToolSchemaCompactor，工具 schema 按档位裁剪

【离线系统朗读】
  新增 SystemTtsSpeaker，TTS 提供商默认 SYSTEM（系统语音）
  消息工具条喇叭：点读 / 再点停止
  未装中文语音引擎时给提示，不再静默失败

【会话分类】
  数据库 v27：新增表 conversation_categories，
  conversations 加 category_id 列（只加迁移，不改旧迁移）
  抽屉一级入口「我的分类」：新建 / 重命名 / 删除（只解散）/ 上下移排序
  「自动任务 / 角色 / 权限」收进「更多选项」展开区
  会话长按菜单加「移到分类」（共 5 项：置顶/移到分类/重命名/导出/删除）
  会话列表分组：置顶 → 各分类（按自定义顺序）→ 未分类，标题带数量
  一条分类都没有时保持原来的「今天 / 更早」时间分组

【虚拟屏 / 小窗】
  VirtualScreenTopBarCapsule、LocalProfileTopBarCapsule、
  RootTouchInjector（跟手滑动）、VirtualScreenSettingsScreen
  小窗最小尺寸 72dp；点按在「最小 / 放大」两档来回切；
  出现即最小；关闭小窗时连目标应用一起关

【虚拟屏闪退修复】
  RootDisplayCommandMain 帧回调整段包 try/catch（回调线程抛异常会杀死 root helper 进程）
  帧尺寸用快照值并做边界校验，修 createBitmap 越界
  Image.planes / plane.buffer 读取加 runCatching，修 Image 已关闭
  切换横竖屏换 ImageReader 前先摘掉旧监听，减少竞态

【设置页精简】
  删除「Gemini / 一圈即搜」两段


================================================================
 文件索引（第三层）
================================================================

  03-本轮新增功能（增量）.patch      第三层增量补丁（45 文件）
  源码-03/                          第三层 63 个文件完整版 + 文件清单.txt
  本机环境适配（勿打进源码）/       4 个编译环境文件，不要打进源码
  Eta-3.3.0-会话分类-release.apk    第三层对应的可运行产物

说明：本机环境未安装 git/patch，补丁未做试打验证；
      补丁为标准 unified diff（a/ b/ 前缀，-p1 可打）。

================================================================
 03 层增量第二批（2026-10-09 追加，已装机验证）
================================================================

【消息「选择复制」】
  消息操作栏新增「选择复制」入口（正文复制按钮左侧）
  点开弹出静态可选文本框，内容为该条消息原文，可任意拖选片段单独复制
  原因：Markdown 消息由多个文本块组成，跨块拖选会被横向滚动容器与流式重排打断，
        用户需要的正是「分段复制」，所以给一个不受重排影响的静态选择面板
  新增文件：ui/components/SelectionCopyDialog.kt
  修改文件：ui/components/ChatMessageItem.kt（+ Icons.Rounded.TextFields）、三个 strings.xml
  文案键：selection_copy_entry / selection_copy_title / selection_copy_hint

【虚拟屏横竖屏切换不再杀进程（v2）】
  现象：竖→横切换瞬间偶发 SIGSEGV，root helper 被杀，约 5 次里 1 次
  根因：applyShape 在 main 线程换 surface/resize，帧拷贝跑在 eta-virtual-frames 线程，
        两者并发；换 surface 时旧帧缓冲被释放，正在拷贝的那一帧读到已解除的映射
  修复（均在 agent/display/RootDisplayCommandMain.kt）：
    1. applyShape 全部动作 handler.post 到帧线程串行执行，与帧拷贝互斥
    2. 帧回调校验 image 尺寸与当前画布尺寸，不符的帧直接丢弃
    3. 尺寸快照 renderWidth/renderHeight（@Volatile）替代跨线程读局部 width/height
    4. 切尺寸调用 FrameStore.reset() 释放并回收整帧位图与拷贝缓冲（省约 12MB/次）
  验证：装机连续竖↔横切换 5 次未再闪退

说明：本机环境未安装 git/patch，第二批追加部分未做试打验证；
      补丁为标准 unified diff（a/ b/ 前缀，-p1 可打）。


================================================================
 03 层增量第三批（2026-10-09 追加）
================================================================

【工具档跟随模型（按模型分档记忆）】
  原来「工具档」全局一份，换模型后档位跟着串；现在按「服务商 id + API 模型串」各记一份，
  旧键自动回退默认档，升级不丢档位
  文件：agent/model/LocalToolProfileStore.kt（重写）、agent/model/AgentModelClient.kt、
        agent/model/AgentPromptBuilder.kt、ui/components/LocalProfileTopBarCapsule.kt、
        ui/components/ConversationStatusCapsules.kt

【上下文超限提示带建议】
  「模型上下文超过容量限制。」追加「可把顶栏『工具档』调低（标准或极简），或新开会话继续。」
  文件：agent/model/AgentModelFailure.kt

【请求侧裁历史 + 顶栏「已省略 N 条」】
  发请求前按用户轮次整轮丢最老历史：最少保留 10 轮、历史总量不超上下文窗口 55%；
  窗口未知不裁；最后一条当前用户输入与系统消息永不裁；裁掉时顶栏多一颗胶囊提示
  新增文件：agent/model/AgentHistoryTrimmer.kt
  修改文件：agent/model/AgentModelClient.kt、agent/runtime/AgentEvent.kt、agent/runtime/AgentRuntimeWire.kt、
            agent/overlay/AgentOverlayState.kt、ui/app/AgentAppState.kt、ui/app/AgentAppRoot.kt、
            ui/app/AgentRunMessageProjector.kt、ui/components/ConversationStatusCapsules.kt、三个 strings.xml

【会话分类收尾：分组折叠 + 按分类删除】
  抽屉分组标题（置顶/分类/时间）整行可点折叠展开，折叠状态跨重启记住；
  分类标题右侧「删除」＝按该分类批量删除，弹二次确认（提示会保留置顶会话），
  确认后只删非置顶项；置顶组本身不提供删除入口，置顶仍独立在最上
  文件：ui/components/ConversationPanePanel.kt、三个 strings.xml（conversation_category_purge_title/_summary）

【选择复制面板改版】
  SelectionCopyDialog.kt：右侧图标点按复制整段，段内仍可拖选任意片段单独复制
  文件：ui/components/SelectionCopyDialog.kt（第二批为新增，本批为修改）

说明：本机环境未安装 git/patch，第三批未做试打验证；
      补丁为标准 unified diff（a/ b/ 前缀，-p1 可打），基线为 01+02+03 前两批打完之后。

================================================================
 03 层增量第四批（2026-10-09 追加，整层重算）
================================================================

基线：打过 01 + 02 之后的完整版
方式：post-02 基线（+ 02 的 源码/）与当前工作树 diff -rq 一次生成整层 03，
      同一文件只出一段 diff，所以 03 层内部不再有批次顺序要求（01→02→03 仍必须）
统计：63 个文件（含 14 个全新文件）、63 个 diff 段、281,734 B / 5,477 行
产物：ai共享文件夹/Eta-3.3.0-诊断包导出+朗读批次2-release.apk（10,009,911 B，已补签名）

【上下文超限自动降档重试】
  撞「模型上下文超过容量限制。」时自动逐级降档重试：
  FULL → LIGHT → STANDARD → MINIMAL，最多 3 次
  只作用于本次运行、不改用户设置；顶栏出「已自动降档：X」胶囊
  文件：agent/model/AgentModelRetry.kt、AgentModelClient.kt、AgentModelFailure.kt、
        agent/overlay/AgentOverlayState.kt、ui/components/ConversationStatusCapsules.kt

【设置 · 关于「导出诊断包」】
  一键把「logcat + root helper 退出原因 + 关键状态」打包成 zip
  新增 core/DiagnosticsBundle.kt：只读采集，不含凭据与对话正文；
  无 root 时降级用本应用 logcat
  新增 agent/display/HelperExitLog.kt：保留最近 5 次 helper 会话，退出码带描述
  （0 正常 / 137 SIGKILL / 139 SIGSEGV / 134 SIGABRT / 143 SIGTERM 等）；
  RootDisplayCommandMain 退出时打 ETA_HELPER_EXIT 与未捕获异常堆栈
  入口：设置 → 关于 → 导出诊断包（系统保存器选路径，默认名 Eta-诊断包-yyyyMMdd-HHmm.zip）

【朗读批次2 · 语速音调 / 熄屏续读 / 正文高亮】
  语速与音调各 6 档（0.5 · 0.75 · 1.0 · 1.25 · 1.5 · 2.0），只在系统语音下显示该分组
  （云端合成接口不接受语速/音调参数，故云端档不显示）
  熄屏后继续朗读：SpeechAudioLease 不再因熄屏打断，改持 PARTIAL_WAKE_LOCK
  （30 分钟自动释放，耳机拔出仍停）
  正文逐段高亮：新增 agent/voice/SpeechSegmentLocator.kt，把朗读片段定位回原文区间，
  当前朗读段加底色（定位失败只跳过高亮，不影响朗读）

【出包红线（新增）】
  release 构建必须带 4 个签名环境变量（ETA_RELEASE_STORE_FILE / _STORE_PASSWORD /
  _KEY_ALIAS / _KEY_PASSWORD），否则 Gradle 静默产出未签名包；
  一键脚本：本机环境适配（勿打进源码）/build_release.sh

【第四层：用量统计 / 数据占用 / 崩溃留证 / 定时自动备份 / 中文单语（2026-10-09）】

  · 用量统计（30 天）：数据表 token_usage_records（DB v28，新增 MIGRATION_27_28，只新增不改旧迁移）
    今日与近 30 天汇总（请求次数、输入/输出/推理/缓存 token、上下文峰值、降档重试次数）
    每日趋势柱状图（纯 Compose）、按模型/按会话完整排行、导出 CSV 并分享
    涉及文件：data/db/TokenUsageEntities.kt（新增）、data/usage/TokenUsageRecorder.kt（新增）、
              data/usage/TokenUsageExporter.kt（新增）、ui/screens/usage/TokenUsageScreen.kt（新增）、
              data/db/EtaDatabase.kt、data/db/ConversationDao.kt、ui/app/AgentAppRoot.kt、
              ui/app/AgentAppShell.kt、ui/app/AgentAppState.kt、ui/navigation/AppRoute.kt、ui/SettingsScreen.kt
  · 数据占用与清理：ui/screens/usage/DataUsageScreen.kt（新增）
    显示版本/库大小/会话数/消息数/用量条数/时间范围/归档运行数；
    三个清理项只动 token_usage_records 与 runtime_archive_runs，不碰会话、消息与分类
  · 崩溃留证：core/CrashRecorder.kt（新增）
    兜底 UncaughtExceptionHandler 写 files/crash/crash-<时间>.txt（调用栈+设备信息+日志尾部），保留最近 10 份，
    仍转交系统原处理器；设置 → 关于「崩溃记录」可看条数与最近一份并一键导出；诊断包带 crash/*
  · 定时自动备份：data/repository/AutoBackupRunner.kt、agent/automation/AutoBackupAlarm.kt、
    agent/automation/AutoBackupReceiver.kt（均新增）、ui/screens/backup/DataBackupScreen.kt、AndroidManifest.xml
    周期 24 小时/3 天/7 天，保留 3/5/10 份，产物写共享目录 Eta备份/，超份数删最旧；
    只排一次不确定闹钟（无常驻进程），到点先比指纹（会话/消息/用量条数）没变就不写文件；
    开机、时间变更、覆盖安装后自动重排闹钟；应用启动时补一次到期检查
  · 搜索跳转高亮 + 中断续跑：ui/model/ConversationSearch.kt、ui/model/AgentChatUiState.kt、
    ui/components/AgentChatBody.kt、ui/model/AgentUiActions.kt、ui/components/AgentResumableRunBanner.kt（新增）
  · 中文单语：删除 values-b+zh+Hans/、values-b+zh+Hant/ 共 10 个文件，简体中文提为默认，
    locales_config.xml 只留 zh-Hans（app/build.gradle.kts 的 localeFilters 属本机环境适配，不在补丁内）

【第四层红线】
  DB 版本 v27 → v28：只新增迁移，绝不动旧迁移脚本
  新增路由/枚举/密封类分支必须补全所有 when（否则编译失败）
  自动备份用 AlarmManager 不确定闹钟；不要改成常驻前台服务

说明：本机 debian 环境已有 patch（无 git），第四层已用真实 patch -p1 试打验证：
      45 个文件全部成功、无 FAILED/Reversed，试打结果与当前源码逐字节一致。
      补丁为标准 unified diff（a/ b/ 前缀，-p1 可打）。


================================================================
 第五层：并发多会话 + 工具串行锁 + 重连文本重复修复（2026-10-09）
================================================================

5.1 多会话并发（最多 3 个会话同时跑）
  文件：agent/runtime/AgentRuntimeService.kt、agent/runtime/AgentRuntimeSession.kt、ui/app/AgentAppState.kt、
        ui/app/AgentAppRoot.kt、ui/app/AgentAppShell.kt、agent/runtime/AgentUiHandoffPayload.kt、
        ui/components/ConversationStatusCapsules.kt、agent/overlay/AgentOverlayCapsule.kt、agent/overlay/AgentOverlayState.kt
  - 原先新任务会掐掉正在跑的旧任务，现在最多 3 个会话各自跑，互不干扰
  - 运行中会话表（含会话名与状态）随事件同步刷新
  - 顶栏「虚拟屏」下拉与悬浮胶囊都显示「N 个会话运行中」，点一条跳回该会话
  - 同一会话重复启动仍被拦住；每个 run 的消息 ID 带 runId，会话之间不会串话

5.2 工具串行锁（并发会话不抢界面/终端）
  文件：agent/model/AgentToolGate.kt（新增）、agent/model/AgentLoop.kt
  - 「会动手机或系统状态」的工具（点按/滑动/输入/启动应用/终端命令/文件写入/系统开关等）
    全局排队（ReentrantLock 公平锁，先到先得）
  - 纯只读工具（read_file/stat_file/list_directory/glob_files/grep_files/get_*/search_*/recent_* 等）直接放行，仍可并行
  - 接入点在工具执行处：AgentLoop.executeTool → AgentToolGate.withGate(name) { toolExecutor.execute(call) }

5.3 界面重连的文本重复修复（根因与修法）
  文件：agent/runtime/AgentRuntimeSession.kt
  - 现象：长任务运行中切出去再回来，会话里同一段助手文本被追加 2~3 遍（逐词三连 + 整段重复混排）；
    任务跑完自动消失（旧实现只在 run 终态清空订阅者）
  - 根因：subscribers 只加不减；每次界面重连 attach 都新增订阅者，实时 emit 向每个订阅者各投一遍；
    且 attach 会全量重放 replayEvents（含文本增量），而客户端 delta 是「追加」语义 → 重复写入
  - 修法：Subscriber 增加 kind(RUN/ATTACH) 与 receivingEvents 标记；attach 时移除旧 ATTACH 订阅者、
    并把其余订阅者置为不再收事件（结果回执仍发全部，避免发起方一直等待）；回放跳过 AssistantBlockDelta，
    只补轮次/工具/结果等结构事件
  - 说明：倍数 = 1 + 重连次数，与并发上限无关；重复发生在本地投递与渲染，不额外消耗模型 token

5.4 其它
  - 内存占用胶囊：ui/components/MemoryUsageCapsule.kt（新增）
  - 图片预览浮层：ui/components/ImagePreviewOverlay.kt（新增）+ agent/media/AgentModelImageEncoder.kt
    （预览压缩：JPEG 92、最长边 1920、上限 3,686,400 像素）+ res/xml/eta_file_paths.xml 的 shared_images cache-path
  - Linux 能力探测：agent/terminal/LinuxCapabilityProbe.kt（新增），终端环境页据此显示可用能力
  - 会话分类页、Linux 环境页分区、备份页、模型选择器、Settings/SettingsDataStore、strings.xml 等细节调整

【第五层红线】
  工具串行锁只锁「独占类」工具，不要在只读工具上加锁，否则并发会话会退化成串行
  AgentRuntimeSession 的订阅者必须「接管」而不是「追加」；结果回执要发给所有订阅者
  AgentToolGate 是进程内锁（同一进程多会话），不要跨进程共享

验证：真实 patch -p1 试打（基线 = 01+02+03+04 打完的状态），31 个文件全部成功、无 FAILED/Reversed；
      试打结果与当前源码一致（差异仅剩本机环境适配 4 个文件与 2 个已删空目录）。


================================================================
 第六层：功能自检 + 悬浮胶囊最近会话（2026-10-09）
================================================================

6.1 功能自检（一键体检）
  文件：ui/screens/selfcheck/FeatureSelfCheckScreen.kt（新增）、ui/navigation/AppRoute.kt、
        ui/app/AgentAppRoot.kt、ui/app/AgentAppShell.kt、ui/SettingsScreen.kt
  - 入口：设置 → 关于 → 功能自检（搜索"自检/体检"亦可）
  - ① 权限与环境：悬浮窗权限 / 通知权限 / 通知使用权 / 无障碍服务 / Root（su 路径）
    不合格条目可点，直接跳到 Eta 应用详情页去开权限
  - ② 数据与存储：共享目录可读写 / 数据库结构版本（预期 v28）与会话、消息、用量条数 /
    崩溃留证份数 / 定时自动备份开关、间隔、保留份数、上次执行时间
  - ③ 已内置功能确认：多会话并发(3)、工具串行锁、界面重连文本修复、虚拟屏与顶层浮动、内存占用胶囊、
    图片预览浮层、Linux 能力探测、离线朗读、会话分类与自动任务、用量统计/诊断包/环境包
  - 顶部汇总"N 项待处理"；说明：③ 组是"包内已含"的编译期确认，不是运行时压测

6.2 悬浮胶囊常驻
  文件：ui/SettingsScreen.kt（开关）、EtaApp.kt（启动自动挂）、data/model/Settings.kt、
        data/datastore/SettingsDataStore.kt、agent/runtime/AgentRuntimeService.kt
  - 设置 → 能力增强 → 「悬浮胶囊常驻」；打开后进入 App 即挂上，空闲显示待机
  - 缺悬浮窗权限时自动跳授权页；关掉开关立刻卸载；重启 App 按上次状态自动恢复

6.3 胶囊面板放大 + 会话条目可点
  文件：agent/overlay/AgentOverlayCapsule.kt
  - 展开宽度 340 → 372dp；会话条目字号 16 → 17sp、内边距 16 → 18dp；标题 12 → 13sp
  - 每条会话一行，点击直接打开对应会话（复用 MainActivity 的 ACTION_OPEN_CONVERSATION_ID）

6.4 完成提示不消失
  文件：agent/runtime/AgentRuntimeService.kt（enterFinalState）
  - 任务完成/失败后保留「已完成 / 已失败」提示，不再自动收起
  - 用户打开 Eta 或点开会话后才清掉

6.5 保留最近 3 条会话（含已完成）
  文件：agent/runtime/AgentRuntimeService.kt、agent/overlay/AgentOverlayState.kt
  - 新增 SESSION_LIST_LIMIT = 3 与 recentSessionInfo（按完成顺序保留，超出丢最旧）
  - syncRunningSessions() 合并「运行中（在前）+ 最近完成」，最多 3 条写入胶囊状态
  - AgentOverlayRunningSession 增加 running 字段；已完成条目标「已完成」并仍可点开

【第六层红线】
  新增路由必须补 AgentAppShell.titleForRoute 的 when 分支，否则 NO_ELSE_IN_WHEN 编译失败
  图标只能用已 import 的 Icons.Rounded.*（本包可用：VerifiedUser / Smartphone / Inventory2 / BarChart 等）
  胶囊会话列表数量上限改动只动 SESSION_LIST_LIMIT，不要在服务里另存无界队列

验证：真实 patch -p1 试打（基线 = 01…05 打完的状态），12 个文件全部成功、无 FAILED/Reversed；
      试打结果与当前源码一致（差异仅剩本机环境适配 4 个文件与 2 个已删空目录）。


────────────────────────────────────────
第七层：07-多开会话修复+弹性并发+提示中文化（增量）（2026-10-10）
────────────────────────────────────────
基线：01…06 全部打完的状态（patchwork/base07）。本层 31 个文件（26 改 / 5 增），128,771 B。

1) 多开会话修复（核心）
   - 运行时上报全部活跃 runId 集合（AgentRuntimeWire.KEY_ACTIVE_RUN_IDS），不再只报第一个非终态会话。
   - 恢复协调器按集合判定，集合内会话不算"孤儿中断"，不再删其 checkpoint，杜绝运行中会话被误清、
     以及随之而来的 FOREIGN KEY constraint failed (787)。
2) 弹性会话并发上限 + 内存三档闸门
   - 上限 = clamp(floor((可用内存 - 1500MB) / 500MB), 1, 6)；只在新建会话与会话结束节点重算。
   - 闸门只看剩余内存绝对值：<1200MB 排队 / <800MB 停放行 / <500MB 冻结存档；读不到数据按正常放行。
   - 顺序固定：先闸门、后上限；共用同一套内存读数。
3) 并发满 → 排队
   - 不再抛 RUNTIME_CONCURRENCY_LIMIT；提示「当前剩余内存 XXXXMB，最多同时 X 个会话，本条已排队」。
4) 重任务互斥闸门（HeavyCommandGate，新文件）
   - 编译／构建类命令同时只跑一个，独立计数，不占轻会话弹性名额。
   - 信号量 + 残留自检（owner 线程已死或持锁 >10 分钟强制释放）。
   - 等不到槽位即时提示「当前有编译在跑，本条已排队，结束后自动执行」；交互终端 5 秒超时提示「正在编译」。
5) 错误提示中文化（AgentErrorText，新文件）
   - 「Runtime 运行失败」→「运行失败」；错误码转中文；未知码显示「运行出错，请重试。（代码：XXX）」。
   - 工具层错误码保持英文原样（模型与内部逻辑依赖）。
6) 诊断与配套
   - 诊断包导出、EtaTrace 自留日志、输入草稿、设置项、会话面板／首页／聊天组件、虚拟屏会话、悬浮胶囊状态。


================================================================
 第八层（08）新增功能明细
================================================================

1) 无障碍断连提醒（AccessibilityHealthNotifier）
   - 无障碍服务被解绑或中断时，8 秒二次确认后才发通知，避免误报。
   - 通知写明原因与怎么修，点通知直达无障碍设置；服务重连后自动撤销，5 分钟内不重复提醒。
2) 运行进度通知（RunProgressNotifier）
   - 跑任务时通知栏显示经过时间与当前动作；并发归 0 立刻撤销，不留残条。
   - 内存把并发上限压低时，通知里直接写「内存紧张，并发上限 N/6」。
3) 中断续跑提示（InterruptedRunNotifier）
   - 开机发现未完成的会话时提示一次，不自动重跑。
4) 自动任务重试（AgentTaskRetryPolicy + AgentTaskRetryNowReceiver + 任务作业服务）
   - 可重试的失败先自动退避重试；通知写明第几次、约多久后再试、原因。
   - 通知带「立刻重试」按钮，点了立刻排一次作业，不改动重试计数与退避节奏。
   - 只有不再重试的失败才发失败通知（含原因与处理办法）。
5) 行为核查与账目（BehaviorAudit）
   - 十多项探针逐项核查功能标记，并给出账目：开始/结束、失败率、平均会话时长（含中位）、
     自动任务重试与不重试收场次数。
   - 新增「诊断包自动导出」探针（diag.auto / diag.auto.fail）。
6) 诊断包自动化与分享
   - 诊断包含按天 7 天日志；设置 → 关于 可导出或直接分享。
   - 每周自动导出一份到「诊断包备份/自动」，只在该子目录滚动保留最近 4 份，
     不碰手动存下的诊断包；导出失败只写日志，不打扰。
   - 开机、改时间、覆盖安装后自动把闹钟重排回来。
7) 续跑横幅小结（AgentResumableRunBanner / AgentChatUiState / AgentAppState）
   - 被中断的回合除「继续 / 忽略」外，另写明上次停在哪一步、有没有动过手机，
     并尽量点名动作（点屏幕 / 输入或发送 / 读写文件 / 跑命令 / 打开应用 各几次）；
     认不出动作就说「动作没认出来」，记录为空则不显示，不硬编。
8) 功能自检补齐（FeatureSelfCheckScreen）
   - 「已内置功能」由 10 项扩到 24 项，逐项一句话说明；总数 19 → 34 项。
   - 「数据与存储」新增「诊断包自动导出」真检查：实际查接收器是否注册 + 自动目录现有几份。


================================================================
 第九层（09）—— 工具档细化 + 上下文三轮压缩 + 返回栈崩溃与分享修复
 日期：2026-10-10
================================================================

1) 工具档六档细化与说明气泡（LocalToolProfile / AgentToolCatalog / AgentToolSchemaCompactor /
   AgentDeviceToolCatalog / Settings / SettingsDataStore / LocalProfileTopBarCapsule / AgentUiActions）
   - 六档顺序确定：纯聊天置顶，其后「能干活」「省 token」分组，组内按常用度排。
   - 分组标题不再占整行，行外化进档位条目；下拉高度回落，不再盖住对话。
   - 档位标题后跟小字说明；长按档位弹说明气泡（悬浮展示，不占菜单空间）。

2) 上下文三轮压缩与锁死（新增 AgentContextPolicy.kt / AgentContextCompactor / AgentContextSession /
   ConversationStatusCapsules / AgentAppState / strings.xml）
   - 触发点 = 上下文窗口 85%（约 170K），压到阈值以上时自动压一次。
   - 最多压 3 轮；第 3 轮后对话锁死，发送入口直接拦下并提示「新开对话继续」。
   - 防套娃：已生成的摘要不再进压缩器，只压摘要之后的新历史；covered 轮数累加以保持单调。
   - 胶囊显示「第 N/3 轮」，N=0 时不显示。

3) 返回栈重复 Home 崩溃修复（AgentAppRoot / AgentNavigator）
   - 现象：分享纯文本给 Eta、或从语音设置返回时，主线程崩，报
     Duplicate contentKey on the back stack: Home（2026-10-10 两份崩溃报告实证，38 秒内连崩两次）。
   - 修法：两条路径改走统一 push；Home 已在栈里就退回首页而非再压一个；启动时对返回栈兜底去重。

4) 分享链路加固（MainActivity）
   - 支持 ACTION_SEND_MULTIPLE；文字收 CharSequence / String / ClipData；图片收单个 Uri /
     ArrayList / ClipData，去重后交给输入框。解决「提示分享成功、输入框却是空的」和「分享成功但没图」。

5) 崩溃报告带本进程日志（CrashRecorder）
   - 崩溃报告先抓本进程 pid 的 logcat，再补全系统尾部；此前报告里没有一条本应用日志，
     只能靠系统侧 START 记录反推触发点。

6) 记忆界面与仓库调整（AgentMemoryRepository / AgentMemoryStore / AgentMemoryUiState / AgentMemoryScreen）

7) versionCode 递增：2026101019 → 2026101020。

文件索引（22 个，相对 app/src/main）：


验证结论：patch -p1 --dry-run 无 FAILED/Reversed；实打后与当前源码逐文件比对 22/22 一致；
源码-09/ 22 个完整文件与当前源码逐字节一致。
补丁文件：09-工具档细化+上下文三轮压缩+返回栈崩溃与分享修复（增量）.patch

第十层（10）—— 版本号递增（构建层）

1) 只改一个文件：app/build.gradle.kts 的 android { defaultConfig { versionCode } } 一行
   - 2026101019 → 2026101020（规则：yyyyMMdd + 两位当日序号；63 与 64 曾同为 …19，装机后分不清版本）
   - versionName 不变，仍是 3.3.0
2) 为什么单列一层：01~09 层只覆盖 app/src/main 下的源码与资源，构建脚本不在其中；
   不补这一行，别人打完全部补丁后版本号仍是旧的，排查崩溃时容易误判是哪一版。
3) 与业务代码零耦合，可最后单独应用；也可以不打补丁、直接手改那一行。

文件索引（1 个，相对源码根目录）：
  app/build.gradle.kts

验证结论：patch -p1 --dry-run 无 FAILED/Reversed；实打后与当前源码逐文件比对 1/1 一致；
源码-10/ 1 个完整文件与当前源码逐字节一致。
字节数：51692，行数：1093


第十一层（11-插话按会话投递（修串回复）.patch）

1) 插话（运行中补充输入）按会话精确投递
   - 现象：本机一个会话在跑长任务时，别的会话发的内容会被吸进它，以「用户补充指令：…」的形式出现。
   - 原因：插话协议 AgentRuntimeWire.MSG_STEER 只带文本，服务端 requestSupplement 用
     sessions.values.lastOrNull { !it.isTerminal } 挑「最后一个还没结束的 run」，与会话无关；
     悬浮胶囊入口直接绑同一个 requestSupplement，天生全局。
   - 修法：协议新增 KEY_CONVERSATION_ID；requestSupplement(text, conversationId) 只在同会话的未终态 run 里找目标；
     调用方没带会话键（旧客户端）时只有唯一候选才投；续跑分支核对「上次跑完的运行」属于该会话，
     不匹配就拒绝并写自留日志 run.steer.reject；胶囊入口带上胶囊当前指向的会话。

文件索引（5 个，相对源码根目录）：
  app/build.gradle.kts
  app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeWire.kt
  app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeClient.kt
  app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeService.kt
  app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState.kt

验证结论：patch -p1 --dry-run 无 FAILED/Reversed；实打后与当前源码逐文件比对 5/5 一致；
源码-11/ 5 个完整文件与当前源码逐字节一致。versionCode 2026101020 → 2026101021。

第十二层（12-顶栏状态行右靠紧凑.patch）

1) 只动排版数值，不动功能
   - AgentAppShell.kt：首页 SmallTopAppBar 加 actionIconPadding = 6.dp（miuix 默认 16dp）。
   - ConversationStatusCapsules.kt：胶囊行 padding(horizontal = 4.dp) → padding(start = 4.dp)。
   - 依据（反编译 miuix 0.9.4 实测）：actions 行本身是 Arrangement.End（已经靠右），
     右边空白来自 TopAppBarDefaults.ActionIconPadding = 16dp。
2) versionCode 2026101021 → 2026101022。

文件索引（3 个，相对源码根目录）：
  app/build.gradle.kts
  app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppShell.kt
  app/src/main/kotlin/io/github/mangi/eta/ui/components/ConversationStatusCapsules.kt

验证结论：patch -p1 --dry-run 无 FAILED/Reversed；实打后与当前源码逐文件比对 3/3 一致；
源码-12/ 3 个完整文件与当前源码逐字节一致。

第十三层（13-功能自检补两条内置项.patch）

1) ③「已内置功能（本包确认存在）」补两条：插话按会话投递、顶栏状态行右靠紧凑。
   - 项数 = permissions + storage + builtin 动态相加，补两条后顶部自动由「共 34 项」变「共 36 项」。
2) versionCode 2026101022 → 2026101023。

文件索引（2 个，相对源码根目录）：
  app/build.gradle.kts
  app/src/main/kotlin/io/github/mangi/eta/ui/screens/selfcheck/FeatureSelfCheckScreen.kt

验证结论：patch -p1 --dry-run 无 FAILED/Reversed；实打后与当前源码逐文件比对 2/2 一致；
源码-13/ 2 个完整文件与当前源码逐字节一致。

第十四层（14-功能自检补四条内置项（补齐09层与11-13层功能）.patch）

1) ③「已内置功能（本包确认存在）」补四条，都是 09 层已经做进包里、但自检里没有对号入口的功能：
   - 对话内三轮压缩：同一对话最多压缩 3 轮，顶栏胶囊显示「第 x/3 轮」，到上限提示新开对话。
   - 工具档「开发」档与分组：五档分「能干活／省 token」两组，新增「开发」档只发开发链工具
     （终端／文件／GUI／日志／记忆），手机个人数据类工具不发送；降档顺序 正常→轻度→标准→极简。
   - 记忆自动精简：记忆超过 60 KB 时自动把最旧段落正文移入冷区 MEMORY-ARCHIVE.md；
     环境包、硬边界、偏好、进行中项目永不搬动，可在记忆界面开关。
   - 返回栈去重：同一 key 不再重复入返回栈，分享后回首页、二级页互跳不再崩（Duplicate contentKey）。
   - 项数 = permissions + storage + builtin 动态相加，补四条后顶部自动由「共 36 项」变「共 40 项」。
2) versionCode 2026101023 → 2026101024。

文件索引（2 个，相对源码根目录）：
  app/build.gradle.kts
  app/src/main/kotlin/io/github/mangi/eta/ui/screens/selfcheck/FeatureSelfCheckScreen.kt

验证结论：逆序回退 14 后与 源码-13/ 2 个文件逐字节一致；正序重放后与当前源码逐文件比对 2/2 一致；
源码-14/ 2 个完整文件与当前源码逐字节一致。
对应产物：新建包/Eta-3.3.0-68-自检补齐09层与11-13层功能项-release.apk（10,044,702 B，versionCode 2026101024）。

── 第 15 层（2026-10-10）· 功能自检补齐新增功能（十四项） ──
1) 功能自检 ③「已内置功能」补十四条，列表项数 30 → 44：
   崩溃留证与导出／数据占用与清理／搜索跳转高亮／消息选择复制／会话批量删除与分类折叠／
   胶囊常驻与最近会话／任务完成通知／重任务互斥闸门／分享入口加固／朗读档位与熄屏续读／
   中文单语与提示中文化／工具档长按说明气泡／记忆界面与段落仓库／（另，09 层四项已在 14 层补过，不重复）
2) versionCode 2026101024 → 2026101025。

文件索引（2 个，相对源码根目录）：
  app/build.gradle.kts
  app/src/main/kotlin/io/github/mangi/eta/ui/screens/selfcheck/FeatureSelfCheckScreen.kt

验证结论：逆序回退 15 后与 源码-14/ 2 个文件逐字节一致；正序重放后与当前源码逐文件 2/2 一致；
源码-15/ 2 个完整文件与当前源码逐字节一致。
对应产物：新建包/Eta-3.3.0-69-自检补齐新增功能（十四项）-release.apk（10,047,094 B，versionCode 2026101025，
md5 2694cbe7772620342d654286b3099a93）。



── 第 16 层（2026-10-10）· 自检工具档文案校正（六档） ──
1) 功能自检 ③「已内置功能」里原有的「工具档」一条，摘要由「五档可选」改成
   「六档可选（正常／轻度／标准／纯聊天／极简／开发），每档带说明文案；档位跟随所选模型」。
2) 只改文案，③ 列表条数不变（仍 44 条）；此前「工具档「开发」档与分组」「工具档长按说明气泡」
   两条与新档位数已一致，本层把最后一条对不上的口径补齐。
3) versionCode 2026101025 → 2026101026。

文件索引（2 个，相对源码根目录）：
  app/build.gradle.kts
  app/src/main/kotlin/io/github/mangi/eta/ui/screens/selfcheck/FeatureSelfCheckScreen.kt

验证结论：逆序回退 16 后与 源码-15/ 2 个文件逐字节一致；正序重放后与当前源码逐文件 2/2 一致；
源码-16/ 2 个完整文件与当前源码逐字节一致。
编译：BUILD SUCCESSFUL（1m 59s），EXIT=0，`e: ` 错误 0 条。
对应产物：新建包/Eta-3.3.0-70-自检工具档六档文案校正-含65-69-release.apk（10,047,154 B，versionCode 2026101026，md5 cc8cb1d4d3974ce69ed1a7d417116269）。

自检总数口径（实测，逐层逆序回退计数）：③ 已内置功能 24 → 26（13 层）→ 30（14 层）→ 44（15 层，16 层不变）
→ 46（17 层）→ 49（18 层）；页面顶部总数 = permissions 5 + storage 6 + builtin，即 36 → 38 → 42 → 56 → 58 → 61。
（实测口径校正：② 数据与存储 自第 08 层「诊断包自动导出」起是 6 项，17 层后页面总数实测 57 = ① 5 + ② 6 + ③ 46。）


=====================================================================
第十七层（2026-10-11）文本库体量上限与瘦身 —— 大堆 + 保尾截断 + 体检
=====================================================================
背景：`agent_text_chunks` 涨到 95.5 MB（5975 块），其中 checkpoint 的 history/journal 有 1294 万字符级别；
`restoreText` 把整段拼回字符串，堆里同时存在三份拷贝，256 MB 堆必炸（崩溃日志实测一次要 8383200 字节）。

改动（全部在 App 内，不需要 root 改系统、不需要重启）：
1. `android:largeHeap="true"`：Eta 自身堆上限 256 MB → 512 MB，只影响本 App。
2. 单字段容量上限 1200 万字符（`ChunkedTextDao.MAX_FIELD_CHARS`），写入侧唯一裁判：
   超限保留尾部（上下文只用最近的），尽量落在 JSON 数组元素边界（"},{"）不切坏结构；
   JSON 对象与结构不明的文本不截断，宁可留大也不写坏；引用头长度与被写内容始终一致。
3. `restoreText` 改成 `StringBuilder(length)` 一次到位，去掉边拼边翻倍的额外拷贝峰值。
4. 新增 `TextStoreMaintenance`（体检 / 瘦身 / 升级后首次起动自动跑一次）与 `ConversationDao` 分块用量查询。
5. 功能自检：② 数据与存储 新增「文本库体量」真检查（分块总量、最大字段、越线数、上限），
   新增「文本库瘦身」入口（点一下按同一条规则截断并回报省下多少字符）；③ 已内置功能补两条。

已知边界（明确说明）：
- 堆上限只能到 512 MB：这是 App 侧唯一口子（`largeHeap`），再往上要改全机属性，本包不做。
- 单字段 1200 万字符对应的读入峰值约 50 MB，512 MB 堆内安全；更大的容量上限需要把读取改成流式分页，本层未做。
- `journal` 与 `history` 的历史重复副本本次不删（跨字段引用会在后续改写时失效），体积问题交给截断与瘦身。


=====================================================================
第十八层（2026-10-11）文本库瘦身修复 + 单次读入窗口 + journal 去重
=====================================================================
背景：17 层的体检能报出「越线 2 个」，但设备上首启瘦身记账是 fields=246 oversized=2 trimmed=0 —— 一个都没删掉。
根因三处，都在代码里查到实证：
1. 引用头计法与 SQL 计法不同：引用头里的长度是 Kotlin `String.length`（UTF-16 单元），
   而瘦身用的是 `SUM(LENGTH(content))`（SQLite 对 TEXT 数的是码点）。含 emoji / 非 BMP 字符时两者不等，
   `restoreText` 末尾 `check(result.length == length)` 直接抛 "History payload length mismatch"，
   异常被 runCatching 吞掉，于是每个越线字段都静默跳过（trimmed=0）。
2. 旧瘦身把 `storeText` 返回的新引用头丢掉了：即使勉强删了块，列里还是旧头，与分块不一致，下次读取必炸。
3. 旧瘦身与旧读取都会把整段（1294 万字符 ≈ 26 MB）读进堆，瘦身过程本身就是一次 OOM 风险。

改动（全部在 App 内，不需要 root 改系统、不需要重启）：
1. `ChunkedTextDao` 新增 `MAX_RESTORE_CHARS = 2_000_000`（单次读入窗口）与
   `restoreTextTail()`（只读尾部分块）、`trimToTail()`（保尾瘦身并返回新引用头 + 省下字符数）、
   `trimToElementBoundary()`（去掉被腰斩的首个元素，让 JSON 数组仍完整）、
   `parseReference()`（解析引用头；明确其计法是 UTF-16 单元，与 SQL 的 LENGTH() 不同）。
2. `ConversationDao`：`restoreCheckpoint()` 改为窗口读尾部；`journal` 列为空时回落到 `history`；
   新增 `setCheckpointHistory / setCheckpointJournal / setMessageContent / setMessageImages /
   setConversationRuns / setConversationRoleplay / setConversationRevisions` 七个写回引用头的查询，
   以及 `messageRow(id) / conversationEntityRow(id)` 两个只读行查询；
   checkpoint 写入时 `journal` 与 `history` 逐字相同则只存一份（读时回落）。
3. `TextStoreMaintenance.trimOversized()` 重写：越线判断只信引用头，只读尾部分块，
   新增 `trimOne()` 按（表, 字段）分辨到正确的行，7 个组合全覆盖，瘦身后把新引用头写回原列。
4. 功能自检：② 「文本库体量」文案补上「单次读入窗口 200 万」；③ 已内置功能补三条
   （瘦身写回引用头 / journal 与 history 去重 / 单次读入窗口）。

已知边界（明确说明）：
- 存储上限仍是单字段 1200 万字符；单次读入窗口 200 万字符（约 4 MB 堆内），
  超出窗口的旧上下文不再进堆 —— 这正是「容量可以大、单次不许大」的分层预算。
- 堆上限仍只能到 512 MB（`largeHeap` 是 App 侧唯一口子）；不改全机 dalvik 属性、不装模块。
- 存量那两条 1294 万字符字段：由本层的瘦身按 1200 万上限保尾截断，`journal` 那份在后续写入时自动去重。


================================================================
 第十九层：文本库崩溃修复（2026-10-10）
================================================================
对应产物：新建包/Eta-3.3.0-73-文本库崩溃修复-release.apk（10,063,986 B，versionCode 2026101029）
补丁：19-文本库崩溃修复.patch（3 个文件 = 2 改 + 1 构建，93 行 / 4,976 B）
源码：源码-19/（完整文件 + 文件清单.txt）

1. AgentConversationCodec.kt
   · decodeTranscript()：json.decodeFromString 改为 runCatching 包裹，失败记 Log.w
     （带历史前 40 字）并按空历史处理；异常不再抛到 ViewModel 构造与主线程
   · 起因：库里历史 JSON 被写坏（数组头缺失、前导逗号等）时启动即闪退

2. ChunkedTextDao.kt（坏头的根因修复）
   · trimToElementBoundary 重写为四步：合法头 "[{" 幂等返回；'[' 与首个 '{' 之间
     只剩 , } ] 空白等残渣（含 [,{ 、[},{）时丢掉残渣重包；首个 '{' 前还有正文时
     跳到下一个 "},{" 之后；都做不到退回空数组
   · 对象数组边界由 substring(element + 1) 改 + 2：原来会把 '}' 留在数组开头，
     产出 [},{ 这种解析不了的坏头
   · 逗号边界要求后面必须是引号或对象，否则原样返回，不再切出半个元素
   · restoreTextTail 的任意位置截断统一过 trimToElementBoundary


================================================================
 第二十层：备份周期自定义（2026-10-10）
================================================================
对应产物：新建包/Eta-3.3.0-74-备份周期自定义-release.apk（10,066,694 B，versionCode 2026101030）
补丁：20-备份周期自定义.patch（8 个文件 = 7 改 + 1 构建，377 行 / 20,538 B）
源码：源码-20/（完整文件 + 文件清单.txt）

1. Settings.kt
   · 新增 autoBackupIntervalMinutes（0 = 沿用老的小时字段）
   · AutoBackupInterval：MIN_MINUTES 5 / MAX_MINUTES 43200（30 天）/
     DEFAULT_MINUTES 1440 / ALWAYS_WRITE_MAX_MINUTES 60 / 常用预设 10、30、60、180、1440
2. SettingsDataStore.kt
   · 新增键 auto_backup_interval_minutes；分钟 > 0 时优先，老的小时键保留兼容
3. AutoBackupAlarm.kt
   · 周期按分钟折算；canScheduleExactAlarms() 为真时用 setExactAndAllowWhileIdle，
     否则退回 setAndAllowWhileIdle（不报错、不弹窗）
4. AndroidManifest.xml
   · 新增 SCHEDULE_EXACT_ALARM、USE_EXACT_ALARM 两条权限
5. AutoBackupRunner.kt
   · 周期按分钟算；周期 ≤ 60 分钟时每次都写一份，不再被内容指纹跳过
6. DataBackupScreen.kt
   · 「备份周期」「保留份数」由循环切换改为对话框填数字，带区间与常用值提示
7. strings.xml（新增 15 条）
   · 周期对话框标题/说明/标签/已保存/输入不合法、保留份数同套文案、单位（分钟/小时/天/小时分钟）
8. app/build.gradle.kts
   · versionCode 2026101029 → 2026101030（构建层）

验证：两层 patch 的 --dry-run 与实打均 rc=0，无 FAILED/Reversed，打完与工作树逐字节一致。
设备实测：10-10 15:49 装 74，15:53 诊断包「崩溃记录 0 条」；自动备份 15:50:25 → 16:00:54（10 分 29 秒）。

================================================================
 第二十一层：极致自动排查（2026-10-10）
================================================================
对应产物：新建包/Eta-3.3.0-75~78-release.apk（versionCode 2026101031 ~ 2026101034，含 73/74）
补丁：21-极致自动排查（巡检+自定义检测项）.patch（16 个文件 = 6 增 + 10 改，1815 行 / 91,303 B）
源码：源码-21/（完整文件 + 文件清单.txt）
入口：设置最下面独立分组「极致自动排查」，点开才是详细页

一、这次加的是什么
  · 本地定时巡检：周期 5 ~ 43200 分钟（默认一天），到点排一次闹钟，跑完再排下一次，无常驻进程
  · 一次查 11 项：崩溃记录（增量）、自动备份守约、巡检自身守约、文本库体量、无障碍服务、
    内存与堆、存储余量、目录规模、日志关键字（FATAL/ANR/native 计数）、行为核查账目、EtaTrace 摘要
  · 分级 + 只报异常：正常 / 注意 / 异常三级，默认只在异常时提醒（可改成每次都提醒）
  · 报告留档：报告 md + 结论 json（含与上次的增量对比），保留份数 1~60 可填，
    只清巡检自己的目录（诊断包备份/巡检），手动存的诊断包一份不动；写不进目录静默跳过
  · 崩溃即取证：崩溃落盘后独立线程立刻补一次巡检，不改崩溃原有处理
  · 手动：页面里「立即巡检一次」；最近 10 份报告可点开看全文、一键复制全文
  · 自定义检测项（本层重点）：手输名称 + 检测范围（日志采样｜报告全文｜文件内容）+ 文件路径 +
    关键字（可多个）+ 正则开关 + 等级（注意｜异常）+ 启停 + 备注，存成
    /storage/emulated/0/ai共享文件夹/诊断包备份/自定义检测.json，可直接用文件管理器改，
    改完下次巡检生效 —— 加检测项不用改源码、不用重出包
  · 四次出包：75 首版 → 76 入口移到设置最下面独立分组 → 77 报告页一键复制全文 → 78 自定义检测项

二、改动文件（16 个 = 6 增 + 10 改）
  新增 6：core/DiagnosticsPatrol.kt（+661）、core/DiagnosticsPatrolAlarm.kt（+60）、
          core/DiagnosticsPatrolReceiver.kt（+43）、core/DiagnosticsPatrolRules.kt（+171）、
          ui/screens/selfcheck/DiagnosticsPatrolScreen.kt（+383）、
          ui/screens/selfcheck/DiagnosticsPatrolRulesDialog.kt（+228）
  改动 10：core/CrashRecorder.kt（+2）、data/model/Settings.kt（+19）、data/datastore/SettingsDataStore.kt（+27）、
          ui/navigation/AppRoute.kt（+3）、ui/app/AgentAppRoot.kt（+3）、ui/app/AgentAppShell.kt（+1）、
          ui/SettingsScreen.kt（+20）、res/values/strings.xml（+58）、AndroidManifest.xml（+7）、
          app/build.gradle.kts（+1/-1，versionCode 2026101030 → 2026101034）

三、验证
  · patch -p1 --dry-run 与实打均 rc=0，无 FAILED/Reversed；打完 10 个改动文件与工作树逐字节一致，
    6 个新增文件内容一致（round-trip 通过）
  · Gradle :app:assembleRelease BUILD SUCCESSFUL；签名自查 1（有签名块）
  · 设备侧：已装 74；本层巡检周期 10 分钟，10-10 17:50 手动巡检「异常 1 / 注意 1」，异常为日志 2 行 FATAL（根因未定位）
  · 注：早期 21-极致自动排查.patch（14 文件版）为首版快照，已废弃；该首版快照已于 2026-10-11 删除

四、本层没有的东西
  · 模型侧自动归因没做；没有加常驻进程；没有改数据库迁移；没往自检列表加项（自检总数仍 44 条）

  22-只留自定义模型+草稿落盘+压缩阈值与自检补齐（增量）.patch  第二十二层（预置模型只留自定义／输入草稿按会话落盘／压缩轮次只增不减+文案「已压 x/3 轮」／触发阈值 0.85→0.75／自检通知使用权改官方接口+备份项融合+内置功能补至 52 项）
  23-草稿链路加固（增量）.patch  第二十三层（输入框每敲一个字即落盘／进会话统一入口 enterConversation／盘上草稿为准，修「打字切会话回来偶尔不见」）
  24-自检分组折叠+巡检抓崩溃缓冲与墓碑（增量）.patch  第二十四层（自检页分组折叠：权限/存储默认展开、内置 52 项默认收起可点开／巡检日志项改抓 main+crash 双缓冲并附墓碑清单，修「main 被刷爆后抓不到崩溃」／versionCode 2026101048→2026101049）
  25-胶囊未读实时化（增量）.patch  第二十五层（胶囊完成角标点开会话即减／「最新回复」同步移出／进会话补发单条已读、回 Eta 才整体归零／修 syncRunningSessions 只比较运行列表导致角标要等下一次刷新才动／versionCode 2026101049→2026101050）
  26-恢复容错.patch  第二十六层（AgentToolBatchRecovery.completeInterrupted 两处裸 JSONArray 解析加 runCatching 容错，修坏 JSON 导致中断恢复崩溃／versionCode 2026101050→2026101051）
  27-记忆精简一劳永逸.patch  第二十七层（记忆自动精简机制重写：超 60 KB 触发、循环压缩到 48 KB 目标内；保护段落正文照搬冷区、热区只留标题+一行摘要，不再「保护词命中就搬不动」／versionCode 2026101051→2026101052）
  28-记忆压缩第28层.patch  第二十八层（记忆压缩补盲：MIN_SECTION_BYTES 600→160；compactPrefix 把首个 ## 标题前的无标题旧段整块移冷区留首行摘要+归档标记；compactOnStartup 启动即收敛，存量超限不再挂到下一次写入／versionCode 2026101052→2026101053）
  29-记忆完美收敛.patch  第二十九层（触发线60→48KB、目标48→40KB；归档标记81→41字节；摘要96→48；normalizeSentinels 归一化存量旧标记；compactOldestSection 治本整段下沉，最旧非保护段沉冷区、热区留「更早 N 条已归档」指针行、保护段永不下沉／versionCode 2026101053→2026101054）
   30-胶囊长按回主页+常驻回主页按钮（增量）.patch  第三十层（胶囊单击改 combinedClickable／新增独立长按检测 pointerInput，长按胶囊回 Eta 主页／展开面板动作行新增常驻「回到 Eta 主页」按钮／AgentRuntimeService.openHome() 用 NEW_TASK|CLEAR_TOP 拉起 MainActivity／补 EtaOverlay 日志／versionCode 2026101054→2026101103）
   31-虚拟屏小窗整体移植+帽子内嵌（增量）.patch  第三十一层（xaw 1.0.49 虚拟屏小窗整体移植：半圆小帽子／真正贴边才折叠／气泡态／最小档手势／⋮ 菜单浮层／键盘桥／触控直通；VirtualScreenSurfaceView 换 TextureView 直通版 direct+fallback 双路出帧；新增 VscrDiag 现场记录（init 加 mkdirs，修日志静默不落盘）／VirtualScreenFrameGate／VirtualScreenHostSurfaces／VirtualScreenInputPump／VirtualScreenSurfaceProvider；帽子由独立 overlay 改为内嵌小窗顶部中央 44dp×22dp，不依赖 addView 成败；strings.xml 补全屏／常规大小／就绪三键／versionCode 2026101107→2026101109）


================================================================
 第三十二层起（2026-10-12 → 2026-11-16）
 说明：以下为 31 层补丁包之后的实际开发记录，按时间分段，
       来源为 por-git 提交历史与本轮会话实测结果。
================================================================

----------------------------------------------------------------
 第三十二段（2026-10-31）后台虚拟屏执行体系
----------------------------------------------------------------
 对应提交：feat(agent) 新增后台虚拟屏执行、智能画布分流与顶部胶囊装裱视窗

1. 后台虚拟屏执行
   - GUI 任务在独立 display 执行，主屏不被占用
   - 智能画布分流（CanvasRoutingPolicy）：auto / virtual / main 三档
   - 顶部胶囊装裱视窗

2. 画布分流语义
   - AUTO：跨应用任务优先后台虚拟屏
   - VIRTUAL：显式后台虚拟屏
   - MAIN：显式物理主屏
   - 本轮修复：AUTO 不再因「目标在主屏前台」而短路回主屏
     （旧逻辑导致「用户看着谁，打开谁就留主屏」，主观感受是主屏乱跳）
   - 本轮修复：虚拟屏后端不可用时报 VIRTUAL_DISPLAY_UNAVAILABLE 并弹卡，
     不再静默降级到主屏

3. 已知坑
   - API 33 虚拟屏创建签名差异，已做兼容
   - 虚拟屏内跨应用跳转崩溃，已修
   - 虚拟屏焦点隔离与无障碍观察生命周期，已修

----------------------------------------------------------------
 第三十三段（2026-11-07 → 2026-11-11）虚拟屏打磨与可调参数
----------------------------------------------------------------
 1. 输入法焦点隔离（2026-11-07）
 2. 无障碍观察生命周期修复（2026-11-09）
 3. 灵屏保活：会话期间点亮屏幕，避免 a11y 枚举不到窗口（2026-11-10）
 4. 悬浮小窗：手势分区 + 贴边吸附 + 最小档 72dp 右上角（2026-11-10~11）
 5. 灵屏分辨率/帧率/闲置宽限改为设置项（VirtualDisplayTuning）（2026-11-11）
    - 帧率增加 144fps 档，取帧下限放宽到 6ms
    - 闲置宽限 4 分钟 → 120 分钟
 6. 缩放与拖动改「累积 + 抬手提交」，不再逐帧重排窗口

----------------------------------------------------------------
 第三十四段（2026-11-12）SystemUI 悬浮球 / 通知气泡（已移除）
----------------------------------------------------------------
 该阶段做了大量 SystemUI 侧工作，随后整体回退：

 做过的：
 - BubbleHook（hook SystemUIApplication.onCreate）+ BubbleBallLayer
 - 悬浮球交互对齐 r0 参数（29dp 球体/径向阴影/双层描边/SweepGradient 外环）
 - 通知气泡卡片照抄 C0 视觉（18dp 圆角/三色渐变/1dp 描边/34dp 图标）
 - 状态栏滚动接管（hook PhoneStatusBarView）
 - 应用白名单/黑名单选择页、摆放位置页

 结果：
 - 提交 28dc3cd6 整体移除：删除 SystemUI hook 侧 5 个文件、主进程 overlay、
   设置页与三个子页，回退 Prefs 与路由

 教训：
 - SystemUI 侧改动风险高、与上游 UI 强耦合，最终整体废弃
 - 后续把同类能力改走「应用内胶囊 / 虚拟屏小窗」路线

----------------------------------------------------------------
 第三十五段（2026-11-14）判断质量与台账体系
----------------------------------------------------------------
1. task_ledger 落盘台账
   - 完成必须有回执（命令输出/读回值/trace/after 节点变化）
   - 三态重试：ok 必须配 evidence，否则降为 unconfirmed
   - claim 独占闸门：同一动作不重复执行
   - 数值结论要求两份独立取证
   - 收尾前强制 review，缺回执/单份取证打回重做（最多 2 轮）

2. memory_detail 记忆分层
   - 核心区只留结论、关键参数、指针
   - 长内容落独立主题，核心区留「详见 memory_detail:主题」

3. ask_user 用户裁决卡
   - 来源矛盾、关键参数拿不准时弹卡让用户选
   - 选项回灌到对话，模型按选择继续

4. 自杀型命令拦截（SUICIDAL_COMMAND_BLOCKED）
   - 杀宿主进程、直接改宿主持久配置的命令强制拦下
   - 导流应用内 UI 或 ask_user
   - 本轮修复：只读访问（cat/ls/sqlite3 SELECT/head/tail/grep 等）无条件放行
     （旧规则把 sqlite3 整体算作写操作，导致纯查询也被拦）

5. 新增拦截：terminal 不许裸启动应用（USE_LAUNCH_APP）
   - monkey -p、不带 --display 的 am start 一律拦下
   - 原因：绕过画布分流，把应用直接顶到用户主屏
   - 必须改用 launch_app（默认后台虚拟屏）

----------------------------------------------------------------
 第三十六段（2026-11-14）插队/插话全链路
----------------------------------------------------------------
 这条线改了 6 轮才走通，最终形态：

1. 软通道优先
   - 闪电先走 supplement 注入下一回合，不打断当前输出
   - 软通道失败才回落硬取消 + 排队
   - MSG_STEER 带 replyTo 回发投递结果

2. 关键修复
   - requestSupplement 以显式 runId 取 run（不再只认 displayedRun）
   - 修消息码撞值：MSG_PAUSE 16 与 MSG_QUERY_ACTIVE_RUNS 16 重复 → 改 20/21/22
   - 软通道必须离开主线程（Runtime 同步客户端在主线程直接拒绝）
   - run 已收口时改走续聊开新 run 并回报已受理
   - 失败不再静默吞异常，补 interrupt.wire / interrupt.supplement trace

3. 任务接管模式
   - 插话后模型进入接管模式的提示词
   - 工具间隙插话、流式中断、消息位置切分

4. 子智能体定向插话（11-10）
   - 投递到子智能体自己的 loop，不被主线吃掉

----------------------------------------------------------------
 第三十七段（2026-11-14）会话与 UI 补齐
----------------------------------------------------------------
1. 会话多选删除
2. 提问卡 UI 补齐
3. 审批回灌修复
4. 新增 HANDOFF.md：目录、构建、测试、模块地图、排障关键字、已知限制

----------------------------------------------------------------
 第三十八段（2026-11-16）自动压缩事故与修复
----------------------------------------------------------------
 事故：
 - 某版给 contextWindow 加了 64_000 默认值
 - 自定义/中转提供商不报窗口 → 每轮都被判「已用 85%」
 - 每条消息都触发压缩 → 压缩失败 → 对话直接中断，用户无法使用

 修复：
 - contextWindow 恢复 Int?，只用模型真实上报值，不猜默认值
 - compact() 改为手动模式：默认 false 直接返回
 - 只有 UI 点「压缩上下文」传 manual=true 才真正执行
 - 超限兜底改为「丢最早 25% 消息后重试」（最多 3 次），不再调压缩
   （压缩本身要占上下文，超限时再压必然失败）

----------------------------------------------------------------
 第三十九段（2026-11-16）进程内插件（issue #234）
----------------------------------------------------------------
 目标：把代码注入目标应用进程内执行，直接调它的内部类与方法，
       替代慢且脆弱的截图点击。

一、架构（三层）
 1. 注入层：LSPosed 作用域把模块加载进目标应用进程
 2. 通信层：目标进程内起 loopback HTTP 服务 + token 鉴权
 3. 执行层：进程内现场编译执行 JS（Rhino），Agent 可自由写适配

二、关键实现
 - AppPluginHost：HTTP 服务、/health、/invoke、/list
 - AppPluginEngine：Rhino 编译执行、线程调度、错误三态
 - JavaBridge：javaCall / javaCallOn / javaNew / javaField / javaFind /
                javaPackages / javaLoaders / javaLoad / javaGet /
                javaFindInstances
 - PluginScopeStore / PluginEnrollment：双门写入 + 重启 + 验证
 - BuiltinPluginActions：预置动作集，免写 JS
   env / find / field / call / activity / dump / search / loaders / get / scan / classes

三、踩过的坑（全部已验证修复）
 1. LSPosed staticScope=true
    - 导致数据库 scope 表被完全忽略，只认 APK 内 scope.list
    - 「加入作用域」对列表外应用永远无效
    - 修复：module.prop 改 staticScope=false（需重启一次手机生效）
    - 效果：之后加新应用只需重启目标应用，不用重启手机
 2. 模块包名不一致
    - 设备上同时存在 io.github.mangi.eta 与 io.github.mangi.etax
    - 实际注入的是 etax，往 eta 写 scope 无效
    - 修复：运行时从 modules_state 挑 enabled=1 的那条
 3. 作用域文件位置
    - /data/data/<eta> 700，目标应用进不去
    - /sdcard 能写但目标应用受存储隔离读不到
    - 最终用 /data/local/tmp（shell_data_file，应用可读，Eta 用 root 写）
 4. 插件宿主在子进程启动
    - 用 isCurrentPackageProcess 匹配 pkg 和 pkg:xxx，结果宿主落到 :plugin0
    - 子进程里 ActivityThread.mActivities 永远 size=0，读不到 Activity
    - 修复：只在主进程启动（currentProcessName == packageName）
 5. Rhino ResourceBundle 崩溃
    - .properties 只在 APK 根目录，LSPosed 只注入 dex，资源包找不到
    - MissingResourceException 直接导致脚本无法编译
    - 修复：反射替换 ScriptRuntime.messageProvider 绕过资源包
 6. Kotlin 对象塞进 JS 作用域
    - 被包成 NativeJavaObject，调用报「ft 类型的 JavaScript 值无效」
    - 修复：只注入纯数据 + 用 Rhino 原生 BaseFunction 做桥
 7. 复杂返回值读不出
    - javaToJS 包成 NativeJavaObject，JS 侧读属性/比数值失效
    - 修复中：改为构造普通 JS 对象，带 __class/__str/字段摊平
 8. Java 集合丢引用
    - ArrayList 被 toJs 转成 JS 数组，原始对象丢失
    - 导致 javaCallOn(o,"add",…) 之后 size() 恒为 0
    - 修复：Collection 分支保留原始引用（JAVA_REF）+ 提供 items/size 视图
 9. 对象引用跨调用丢失
    - 修复：__javaRef 藏原始引用 + handle 表（WeakHashMap）
 10. R8 混淆 BaseFunction 子类
    - 名字在、调用报 msg.undef.method.call
    - 修复：proguard 保留 JavaBridge$* 与 BaseFunction.call 签名
 11. 扫堆覆盖不足
    - 16700 个对象时目标类 0 命中
    - 修复：上限放宽到 5,000,000（maxScan 可调）+ 优先扫目标类静态字段
 12. 扫堆卡死主线程
    - 连续扫堆触发 plugin_timeout，宿主锁死需重启应用
    - 修复：探测类方法走工作线程（55s），业务类方法仍走主线程（15s）

四、当前状态（如实）
 - 可注入微信、小布助手、米家，能拿 pid/包名/机型
 - 能调 JDK/Android API、能访问应用私有类
 - 跨 ClassLoader 加载可用（javaLoad + javaLoaders）
 - 未达成：米家「纯内存控制插座」
   * Device 实例扫不到，did/token 取不到
   * HomeManager 主类确认不存在（只有内部类被保留），
     「动态模块加载」的早期判断已被推翻
 - 未达成：javaCall 返回复杂对象仍出现 [object Object]（在修）

五、交付物
 - Skill：app-plugin-inject（教 Agent 用这套能力）
 - 设置页：进程内插件作用域（可搜索应用列表，点选加入）
 - 全新代码：hook/appplugin 2518 行 + agent/plugin 515 行

================================================================
 当前版本：3.4.1-por138
 最后更新：2026-11-16
================================================================
