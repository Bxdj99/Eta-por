# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# libxposed 通过 META-INF/xposed/java_init.list 中的类名字符串加载模块入口；
# 允许入口类混淆时，需要同步改写 java_init.list，避免 release 裁剪后模块失效。
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation class io.github.mangi.eta.ModuleMain {
    public <init>();
}

# R8 默认规则已覆盖 Compose 运行时；Miuix 图标是普通 Kotlin 代码，允许 R8 裁掉未使用图标。
# -dontwarn 仅抑制 KMP 依赖在 Android 侧可能出现的可选平台 warning，不阻止裁剪。
-dontwarn top.yukonga.miuix.**

# libxposed service 通过静态调用和 manifest provider 接入，交给 R8/Android 默认规则保留可达代码。
-dontwarn io.github.libxposed.service.**

# 配置 key 是字符串常量并通过静态调用访问，不需要保留类名或成员名。

# ── Release 日志策略 ────────────────────────────────────────────────────────
# 仅删除 Eta 自有代码中的 Android VERBOSE/DEBUG 调用；INFO/WARN/ERROR 必须保留，
# 第三方依赖的日志策略由依赖自身决定。
-maximumremovedandroidloglevel 3 class io.github.mangi.eta.** { *; }

# XposedModule.log 不是 android.util.Log，R8 无法通过上面的规则识别。
# debug supplier 是纯观察 API；禁止在 supplier 内执行任何业务副作用。
-assumenosideeffects interface io.github.mangi.eta.core.AgentLogger {
    public abstract void debug(kotlin.jvm.functions.Function0);
}
-assumenosideeffects class io.github.mangi.eta.core.AndroidAgentLogger {
    public void debug(kotlin.jvm.functions.Function0);
}
-assumenosideeffects class io.github.mangi.eta.core.ModuleLogger {
    public void debug(kotlin.jvm.functions.Function0);
}

# ── 序列化与网络依赖 ─────────────────────────────────────────────────────────
# DataStore、kotlinx.serialization、OkHttp 与 Okio 均自带精确的 consumer rules；
# 不在 App 层重复保留整个类或包，避免阻断裁剪、内联和混淆。
# Nimbus 内嵌 Gson 携带此 CLASS 级静态检查注解，运行时不依赖它。
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue

# 保留源码与行号属性，便于使用 release mapping 还原线上堆栈。
-keepattributes SourceFile,LineNumberTable

# app_process 通过固定类名启动受控的一方应用与虚拟屏操作入口。
-keep class io.github.mangi.eta.agent.phone.PhoneCommandMain {
    public static void main(java.lang.String[]);
}
-keep class io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayCommandMain {
    public static void main(java.lang.String[]);
}

# Rhino（issue #234 进程内插件脚本引擎）
# Rhino 引用了 java.beans 等 Android 上不存在的 JDK 类；这些路径只在
# 「Java Bean 内省」时走到，Android 插件场景不会触碰，直接 dontwarn。
-dontwarn java.beans.**
-dontwarn java.lang.invoke.**
-dontwarn sun.misc.**
# Rhino 脚本上下文/对象保留类名：插件脚本通过字符串引用它的 API。
-keep class org.mozilla.javascript.** { *; }
# Rhino 的 JVM dynalink 优化路径（Android 无 jdk.dynalink），插件场景不走。
-dontwarn jdk.dynalink.**

# 插件 Java 桥：所有 BaseFunction 子类必须保留 call/call 的签名，
# 否则 R8 改名后 Rhino 找不到入口，表现为「名字在、调用报 msg.undef.method.call」。
-keep class io.github.mangi.eta.hook.appplugin.JavaBridge$* {
    public *;
}
-keepclassmembers class * extends org.mozilla.javascript.BaseFunction {
    public java.lang.Object call(org.mozilla.javascript.Context, org.mozilla.javascript.Scriptable, org.mozilla.javascript.Scriptable, java.lang.Object[]);
    public java.lang.String getFunctionName();
}

# DexMaker：运行时用反射生成抽象类子类，类名与成员必须保留，否则 R8 会整包裁掉。
-keep class com.android.dx.** { *; }
-keep class com.linkedin.dexmaker.** { *; }
-dontwarn com.android.dx.**
-dontwarn com.linkedin.dexmaker.**
