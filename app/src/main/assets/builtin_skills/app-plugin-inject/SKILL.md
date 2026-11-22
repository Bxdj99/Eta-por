---
name: app-plugin-inject
description: 用 app_plugin_call 把代码注入目标应用进程内执行，直接调它的内部类与方法（搜索、读列表、调私有 API），替代慢且脆弱的截图点击。当应用没有公开接口、或界面操作不稳定时使用。含作用域配置、插件写法、Java 桥 API、常见失败对照表。
---

# 进程内插件（app_plugin_call）

把 JS 代码注入目标应用**自己的进程**里跑，直接用反射调它的类。适用于：应用没公开接口、界面点击太慢或经常点错。

## 先决条件

目标应用必须在**插件作用域**内，且它当前**正在运行**。

作用域配置在 `/data/local/tmp/eta-app-plugins/scope.json`（JSON 数组）：

```json
["com.example.app", "com.other.app"]
```

写入后**重启目标应用**才生效。检查是否生效：看 trace 或日志里有没有
`AppPlugin check pkg=<包名> ... enabled=true`。

## 调用方式（源码即调用，推荐）

`source` 传 JS 源码，最后一句必须**求值成一个对象**，属性名就是方法名：

```javascript
({ 方法名: function(args) { ...战 return {...}; } })
```

完整工具调用示例：

```json
{
  "package": "com.example.app",
  "plugin":  "inline",
  "method":  "search",
  "args":    { "keyword": "hello" },
  "source":  "({search: function(args){ return { ok:true, n:args.keyword.length }; }})"
}
```

- `plugin` 在源码模式下只是标识，随便填
- `args` 原样作为第一个参数传给方法

## Java 桥（核心能力）

注入四个函数到 JS 作用域：

| 函数 | 用途 | 示例 |
|---|---|---|
| `javaCall(类, 方法, ...参数)` | 调静态方法 | `javaCall("android.os.Build","getRadioVersion")` |
| `javaField(类, 字段)` | 读静态字段 | `javaField("android.os.Build","MODEL")` |
| `javaNew(类, [参数...])` | 构造实例 | `javaNew("java.util.ArrayList", [])` |
| `javaCallOn(实例, 方法, [参数...])` | 调实例方法 | `javaCallOn(list,"size",[])` |
| `javaFind(类, 关键词)` | **列类的方法与字段**（带类型） | `javaFind("android.os.Build","manuf")` |

**`javaFind` 是最重要的一步**：不知道方法名/签名时先问它，不要猜。

```
javaFind("android.os.Build", "manuf")
→ {"class":"android.os.Build","methods":[],"fields":["MANUFACTURER:String", ...]}
```

## 标准工作流

1. **定位类名** —— 用 APK 分析（`apktool`/`jadx`）或 `javaFind` 试探常见命名
2. **列成员** —— `javaFind(类, "")` 看有哪些方法可用
3. **写插件调用** —— 用 `javaCall`/`javaCallOn` 调目标方法
4. **验证返回** —— 结果不对就回到第 2 步，看签名是不是对不上

## 写插件要点

- **只注入纯数据**：`packageName`、`pluginId` 可用；**不要往 JS 里塞 Kotlin/Java 对象**，会报「ft 类型的 JavaScript 值无效」
- **返回值可 JSON 序列化**：直接返回普通对象；复杂 Java 对象会转成描述字符串
- **异常可捕获**：桥接失败返回 `{__javaError:"..."}`，可用 `try/catch` 兜住
- **超时 15 秒**：插件执行超过会被中止

## 失败对照表

| 返回 | 含义 | 处理 |
|---|---|---|
| `PLUGIN_HOST_NOT_RUNNING` | 宿主没起 | 检查作用域是否配、重启目标应用 |
| `plugin_host_not_ready` | 引擎未初始化 | 同上，宿主刚起可能还在初始化，等 1-2 秒重试 |
| `plugin_compile_failed` | JS 语法错 | 看 `message` 里的行号 |
| `method_not_found` | 方法名不对 | 用 `javaFind` 查真实方法名 |
| `{__javaError:"ClassNotFoundException"}` | 类不存在 | 类名写错，或该应用用了加固/多 dex |
| `{__javaError:"NoSuchMethodException"}` | 方法签名不匹配 | 用 `javaFind` 看参数类型与个数 |
| 超时 | 方法阻塞 | 换更轻的方法，或拆成多次调用 |

## 边界（如实说明）

- 插件跑在目标应用**同一 uid** 下，能做的都是该应用自己权限内的事，没有额外提权
- 代码注入依赖 LSPosed 作用域；没装 LSPosed 或没勾选该应用时不可用
- 目标应用没运行就没有宿主进程，必须先拉起
- 加固应用（如某些加固壳）拿不到真实类名，`javaFind` 会失败

## 何时别用它

- 应用有公开 API/Intent 能完成任务 → 优先用那些
- 只是简单点击/输入 → `observe_screen` + `tap_element` 更快
- 目标应用在作用域外且用户不想加 → 回退截图路径
