package com.muse.moonpc.core

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * JS 模块（全新实现，对标 Surge 脚本）：
 * - 模块：名称 + 脚本 + 启用开关，持久化 ~/.moon-pc/jsmodules.json
 * - 钩子：`onRequest(req)` / `onResponse(resp)`，req/resp 为普通 JS 对象，
 *   返回修改后的对象（或 null 表示不改）
 * - req: {method, url, host, path, headers:{k:v}}
 * - resp: {url, status, headers:{k:v}, body}
 * - 沙箱：Rhino ClassShutter 全禁 Java 类访问；解释模式；单脚本 3 秒超时
 */
data class JsModule(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var script: String = "",
    var enabled: Boolean = true,
)

/** 传给 JS 钩子的请求/响应（可变，脚本改完拿回） */
data class JsRequest(
    var method: String,
    var url: String,
    var host: String,
    var path: String,
    val headers: MutableMap<String, String>,
)

data class JsResponse(
    var url: String,
    var status: Int,
    val headers: MutableMap<String, String>,
    /** 仅文本类型 body 才填，二进制为 null（脚本不碰） */
    var body: String?,
)

class JsEngine {

    val modules = mutableStateListOf<JsModule>()

    private val dataFile: File
        get() = File(System.getProperty("user.home"), ".moon-pc/jsmodules.json")

    /** 脚本执行线程池（隔离超时杀） */
    private val execPool = Executors.newCachedThreadPool { r ->
        Thread(r, "js-module").apply { isDaemon = true }
    }

    init {
        load()
    }

    // ---------------- 钩子调用 ----------------

    /**
     * 依次跑所有启用模块的 onRequest；任一返回非空对象则采纳其修改。
     * @return 被修改后的 req（至少一个模块改了则 true）
     */
    fun runRequestHooks(req: JsRequest): Boolean {
        var changed = false
        for (m in modules) {
            if (!m.enabled) continue
            val out = runCatching { callHook(m, "onRequest", reqToJs(req)) }.getOrNull()
                ?: continue
            if (applyJsRequest(out, req)) changed = true
        }
        return changed
    }

    fun runResponseHooks(resp: JsResponse): Boolean {
        var changed = false
        for (m in modules) {
            if (!m.enabled) continue
            val out = runCatching { callHook(m, "onResponse", respToJs(resp)) }.getOrNull()
                ?: continue
            if (applyJsResponse(out, resp)) changed = true
        }
        return changed
    }

    /**
     * 调用单个模块的单个钩子，3 秒超时。
     *
     * 超时熔断说明：Rhino 解释器不响应 Thread.interrupt，
     * 单靠 future.cancel(true) 杀不掉死循环（线程泄漏、CPU 空转）。
     * 这里用 ContextFactory + observeInstructionCount 做指令级熔断，
     * 死循环/重计算脚本会被强制抛 Error 终止。
     *
     * @return JS 返回的对象（NativeObject）或 null（无返回/无该函数/出错/超时）
     */
    private fun callHook(module: JsModule, hookName: String, arg: NativeObject): NativeObject? {
        val future = execPool.submit<NativeObject?> {
            val cx = timeoutFactory.enterContext()
            try {
                cx.setClassShutter(ClassShutter { false }) // 全禁 Java 类
                cx.optimizationLevel = -1 // 解释模式
                // 指令级熔断：每 2 万条指令检查一次 wall-clock deadline
                cx.instructionObserverThreshold = 20_000
                cx.putThreadLocal(DEADLINE_KEY, System.currentTimeMillis() + HOOK_TIMEOUT_MS)
                val scope = cx.initStandardObjects()
                // console.log：纯 JS 函数实现（不用 javaToJS，避免 ClassShutter 拦截）
                val consoleObj = NativeObject()
                consoleObj.put("log", consoleObj, LogFunction())
                ScriptableObject.putProperty(scope, "console", consoleObj)
                cx.evaluateString(scope, module.script, module.name, 1, null)
                val fn = scope.get(hookName, scope)
                if (fn !is Function) return@submit null
                val ret = fn.call(cx, scope, scope, arrayOf(arg))
                ret as? NativeObject
            } catch (_: JsTimeoutError) {
                null // 超时熔断：按"无修改"处理
            } finally {
                Context.exit()
            }
        }
        return try {
            future.get(HOOK_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // 指令熔断是主防线；这里是兜底（理论上到不了）
            future.cancel(true)
            null
        } catch (_: Exception) {
            null
        }
    }

    /** JS 超时熔断标记异常（指令计数器抛此错终止脚本） */
    private class JsTimeoutError : Error("JS execution timeout")

    /**
     * 带指令级超时熔断的 Factory。
     * Rhino 1.7.15 里解释器每执行 instructionObserverThreshold 条指令
     * 就调 ContextFactory.observeInstructionCount，这里按 wall-clock 熔断。
     */
    private val timeoutFactory = object : ContextFactory() {
        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val deadline = cx.getThreadLocal(DEADLINE_KEY) as? Long ?: return
            if (System.currentTimeMillis() > deadline) throw JsTimeoutError()
        }
    }

    companion object {
        /** 单脚本超时熔断：3 秒 */
        const val HOOK_TIMEOUT_MS = 3000L

        /** Context 线程本地 deadline 的 key */
        private val DEADLINE_KEY = Any()

        /** 示例脚本（新建模块时的模板） */
        const val TEMPLATE = """// Moon PC JS 模块
// 钩子：onRequest(req) / onResponse(resp)，返回修改后的对象（不改返回 null）
// req:  {method, url, host, path, headers}
// resp: {url, status, headers, body}

function onRequest(req) {
    // 示例：给所有请求加 UA
    // req.headers["User-Agent"] = "MoonPC/1.0";
    // return req;
    return null;
}

function onResponse(resp) {
    return null;
}
"""
    }

    /** console.log 的纯 JS 函数实现（无任何 Java 系统能力） */
    private class LogFunction : org.mozilla.javascript.BaseFunction() {
        override fun call(
            cx: Context,
            scope: org.mozilla.javascript.Scriptable,
            thisObj: org.mozilla.javascript.Scriptable,
            args: Array<Any?>,
        ): Any {
            val msg = args.joinToString(" ") { a ->
                when (a) {
                    null, Context.getUndefinedValue() -> "undefined"
                    is NativeObject -> "[object]"
                    else -> Context.toString(a)
                }
            }
            println("[js] $msg")
            return Context.getUndefinedValue()
        }
    }

    // ---------------- JS <-> Kotlin 转换 ----------------

    private fun reqToJs(req: JsRequest): NativeObject {
        val o = NativeObject()
        o.put("method", o, req.method)
        o.put("url", o, req.url)
        o.put("host", o, req.host)
        o.put("path", o, req.path)
        val h = NativeObject()
        for ((k, v) in req.headers) h.put(k, h, v)
        o.put("headers", o, h)
        return o
    }

    private fun respToJs(resp: JsResponse): NativeObject {
        val o = NativeObject()
        o.put("url", o, resp.url)
        o.put("status", o, resp.status)
        val h = NativeObject()
        for ((k, v) in resp.headers) h.put(k, h, v)
        o.put("headers", o, h)
        o.put("body", o, resp.body)
        return o
    }

    private fun applyJsRequest(o: NativeObject, req: JsRequest): Boolean {
        var changed = false
        (o.get("url", o) as? String)?.let { if (it != req.url) { req.url = it; changed = true } }
        (o.get("method", o) as? String)?.let { if (it != req.method) { req.method = it; changed = true } }
        val h = o.get("headers", o) as? NativeObject
        if (h != null) {
            for (id in h.ids) {
                val k = id.toString()
                val v = h.get(k, h)?.toString() ?: continue
                if (req.headers[k] != v) { req.headers[k] = v; changed = true }
            }
        }
        return changed
    }

    private fun applyJsResponse(o: NativeObject, resp: JsResponse): Boolean {
        var changed = false
        (o.get("status", o) as? Number)?.let {
            val s = it.toInt()
            if (s != resp.status) { resp.status = s; changed = true }
        }
        (o.get("body", o) as? String)?.let {
            if (it != resp.body) { resp.body = it; changed = true }
        }
        val h = o.get("headers", o) as? NativeObject
        if (h != null) {
            for (id in h.ids) {
                val k = id.toString()
                val v = h.get(k, h)?.toString() ?: continue
                if (resp.headers[k] != v) { resp.headers[k] = v; changed = true }
            }
        }
        return changed
    }

    // ---------------- CRUD ----------------

    fun add(m: JsModule) { modules.add(m); save() }
    fun update(m: JsModule) {
        val i = modules.indexOfFirst { it.id == m.id }
        if (i >= 0) { modules[i] = m; save() }
    }
    fun remove(id: String) { modules.removeAll { it.id == id }; save() }
    fun setEnabled(id: String, v: Boolean) {
        modules.firstOrNull { it.id == id }?.let { it.enabled = v; save() }
    }

    // ---------------- 持久化 ----------------

    private fun load() {
        runCatching {
            val f = dataFile
            if (!f.isFile) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                modules.add(
                    JsModule(
                        id = j.optString("id", UUID.randomUUID().toString()),
                        name = j.optString("name"),
                        script = j.optString("script"),
                        enabled = j.optBoolean("enabled", true),
                    ),
                )
            }
        }
    }

    fun save() {
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val arr = JSONArray()
                for (m in modules) {
                    arr.put(
                        JSONObject()
                            .put("id", m.id)
                            .put("name", m.name)
                            .put("script", m.script)
                            .put("enabled", m.enabled),
                    )
                }
                withContext(Dispatchers.IO) {
                    SecureFile.writeText(dataFile, arr.toString(2))
                }
            }
        }
    }

}
