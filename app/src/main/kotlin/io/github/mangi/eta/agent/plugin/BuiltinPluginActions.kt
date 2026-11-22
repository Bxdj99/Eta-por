package io.github.mangi.eta.agent.plugin

/**
 * 预置插件动作（issue #234）。
 *
 * 起因：让模型每次现写一段 JS 探针再解析一屏输出，既慢又容易错——
 * 「每次都得解析一大堆」是真实痛点。这里把高频操作固化成脚本，
 * 模型只要传 action 名，参数走 args。
 *
 * 设计原则：
 *  - 一个动作只做一件事，返回结构扁平、字段名自解释；
 *  - 出错时返回 {ok:false, code, message}，不抛一屏堆栈；
 *  - 名称短，好记。
 */
internal object BuiltinPluginActions {

    fun names(): List<String> = ACTIONS.keys.toList()

    fun sourceFor(action: String): String? = ACTIONS[action.trim()]?.source

    fun methodFor(action: String): String = ACTIONS[action.trim()]?.method.orEmpty()

    private data class Action(val method: String, val source: String)

    private val ACTIONS: Map<String, Action> = mapOf(

        /** 环境自检：确认桥可用、宿主身份正确。一个调用看全。 */
        "env" to Action("env", """
            ({env: function() {
                return {
                    ok: true,
                    pkg: packageName,
                    brand: javaField("android.os.Build", "BRAND"),
                    model: javaField("android.os.Build", "MODEL"),
                    sdk: javaField("android.os.Build", "SDK_INT"),
                    pid: javaCall("android.os.Process", "myPid")
                };
            }})
        """.trimIndent()),

        /** 列类成员：args = {cls, keyword?}。替代手写 javaFind + 解析。 */
        "find" to Action("find", """
            ({find: function(a) {
                if (!a || !a.cls) return {ok:false, code:"NEED_CLS"};
                return {ok:true, result: javaFind(a.cls, a.keyword || "")};
            }})
        """.trimIndent()),

        /** 读静态字段：args = {cls, field}。 */
        "field" to Action("field", """
            ({field: function(a) {
                if (!a || !a.cls || !a.field) return {ok:false, code:"NEED_CLS_FIELD"};
                return {ok:true, value: String(javaField(a.cls, a.field))};
            }})
        """.trimIndent()),

        /** 调静态方法：args = {cls, method, args?}。 */
        "call" to Action("call", """
            ({call: function(a) {
                if (!a || !a.cls || !a.method) return {ok:false, code:"NEED_CLS_METHOD"};
                var args = a.args || [];
                var r = javaCall.apply(null, [a.cls, a.method].concat(args));
                return {ok:true, value: String(r)};
            }})
        """.trimIndent()),

        /**
         * 当前 Activity：args={members?}。
         *
         * 这是「不反编译」路线的入口——拿到 Activity 实例后，
         * 直接用 dump 动作列出它的字段和方法，运行时看真实类名，
         * 比解 200MB 的 APK 找混淆类快得多、也准得多。
         */
        "activity" to Action("activity", """
            ({activity: function(a) {
                var out = {ok:false};
                try {
                    // 1) 从 ActivityThread.mActivities 里翻正在 resumed 的那个
                    var at = javaCall("android.app.ActivityThread", "currentActivityThread");
                    if (at === null) { out.code = "NO_ACTIVITY_THREAD"; return out; }
                    var maps = javaField(at, "mActivities");
                    if (maps === null) { out.code = "NO_M_ACTIVITIES"; return out; }
                    var n = javaCallOn(maps, "size", []);
                    out.count = n;
                    if (n === 0) { out.code = "EMPTY_ACTIVITIES"; return out; }
                    var vals = javaCallOn(maps, "values", []);
                    var it = javaCallOn(vals, "iterator", []);
                    var best = null;
                    while (javaCallOn(it, "hasNext", [])) {
                        var rec = javaCallOn(it, "next", []);
                        if (rec === null) continue;
                        var act = javaField(rec, "activity");
                        if (act !== null) { best = act; break; }
                    }
                    if (best === null) { out.code = "NO_ACTIVITY_IN_RECORDS"; return out; }
                    out.ok = true;
                    out.activityClass = javaCallOn(best, "getClass", []) ? String(best) : String(best);
                    out.className = String(javaCallOn(best, "getClass", []));
                    if (a && a.members) {
                        out.members = javaFind(out.className, a.members);
                    }
                    return out;
                } catch (e) {
                    out.code = "ERROR";
                    out.err = String(e);
                    return out;
                }
            }})
        """.trimIndent()),

        /**
         * 列对象成员：args={expr?, cls?, keyword?}。
         * 给类名就列类，给 cls+keyword 就过滤，用于运行时探明混淆后的真实方法。
         */
        "dump" to Action("dump", """
            ({dump: function(a) {
                if (!a || !a.cls) return {ok:false, code:"NEED_CLS"};
                return {ok:true, result: javaFind(a.cls, a.keyword || "")};
            }})
        """.trimIndent()),

        /** 按路径读字段：args={handle|obj, path}。path 用 "->" 分段更安全（如 "a->b->did"）。 */
        "get" to Action("get", """
            ({get: function(a) {
                if (!a) return {ok:false, code:"NEED_ARGS"};
                // 支持三种写法：
                //   {handle:"h0", field:"did"}      ← 扫堆返回的 handle
                //   {handle:"h0", path:"a->b->did"} ← 多级一次走完
                //   {obj:..., path:"a.b"}
                if (a.handle) {
                    return {ok:true, value: javaGet({
                        handle: a.handle,
                        field: a.field || "",
                        path: a.path || ""
                    })};
                }
                if (a.obj && a.path) return {ok:true, value: javaGet(a.obj, a.path)};
                return {ok:false, code:"NEED_HANDLE_OR_OBJ"};
            }})
        """.trimIndent()),

        /** 按类型扫堆：args={cls, limit?, maxScan?}。解决「对象在内存但不知路径」。 */
        "scan" to Action("scan", """
            ({scan: function(a) {
                if (!a || !a.cls) return {ok:false, code:"NEED_CLS"};
                return javaFindInstances({
                    cls: a.cls,
                    limit: a.limit || 20,
                    maxScan: a.maxScan || 400000
                });
            }})
        """.trimIndent()),

        /**
         * 类加载器排查：args={cls?}。
         *
         * 动态模块应用（米家/微信等）的类挂在独立 ClassLoader 上，
         * base loader 找不到是正常的。这个动作用来看清进程里到底有几个 loader，
         * 并直接告诉你某个类**哪个 loader 能加载**——不用再猜。
         */
        "loaders" to Action("loaders", """
            ({loaders: function(a) {
                var out = {ok:true};
                out.loaders = javaLoaders();
                if (a && a.cls) out.target = javaLoad(a.cls);
                return out;
            }})
        """.trimIndent()),

        /**
         * 搜索已加载的类：args={keyword}。
         *
         * **这是「不反编译」的主力**：应用跑起来后类都在内存里，
         * 按关键词（如 Device、Manager、Socket）扫一遍，
         * 直接拿到混淆后的真实类名，比解 200MB APK 快几个数量级。
         */
        "search" to Action("search", """
            ({search: function(a) {
                var kw = ((a && a.keyword) || "").toLowerCase();
                if (!kw) return {ok:false, code:"NEED_KEYWORD"};
                var list = javaPackages("");
                var hit = [];
                for (var i = 0; i < list.length && hit.length < 80; i++) {
                    var n = String(list[i]);
                    if (n.toLowerCase().indexOf(kw) >= 0) hit.push(n);
                }
                return {ok:true, keyword:kw, total:list.length, hits:hit};
            }})
        """.trimIndent()),

        /** 已加载类探测：args={prefix}。用于确认某包下有哪些类真的可见。 */
        "classes" to Action("classes", """
            ({classes: function(a) {
                var prefix = (a && a.prefix) || "";
                return {ok:true, list: javaPackages(prefix)};
            }})
        """.trimIndent()),
    )
}
