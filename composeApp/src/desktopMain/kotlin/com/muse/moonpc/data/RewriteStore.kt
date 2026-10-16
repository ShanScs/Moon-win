package com.muse.moonpc.data

import androidx.compose.runtime.mutableStateListOf
import com.muse.moonpc.core.SecureFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * URL 重写 + Header 改写规则存储（全新实现，对标 Surge）：
 * - URL 重写：通配符/正则匹配 URL → 302 重定向 / 直接拒绝
 * - Header 改写：请求/响应头 添加/覆盖/删除，可按域名限定
 * - 持久化 ~/.moon-pc/rewrite.json
 */
data class UrlRewriteRule(
    val id: String = UUID.randomUUID().toString(),
    var match: String = "",
    /** "wildcard" / "regex" */
    var matchType: String = MATCH_WILDCARD,
    /** "redirect" / "reject" */
    var action: String = ACTION_REDIRECT,
    var target: String = "",
    var enabled: Boolean = true,
) {
    companion object {
        const val MATCH_WILDCARD = "wildcard"
        const val MATCH_REGEX = "regex"
        const val ACTION_REDIRECT = "redirect"
        const val ACTION_REJECT = "reject"

        fun matchTypeLabel(t: String) = when (t) {
            MATCH_WILDCARD -> "通配符"
            MATCH_REGEX -> "正则"
            else -> t
        }

        fun actionLabel(a: String) = when (a) {
            ACTION_REDIRECT -> "302 重定向"
            ACTION_REJECT -> "拒绝"
            else -> a
        }
    }
}

data class HeaderRewriteRule(
    val id: String = UUID.randomUUID().toString(),
    var direction: String = DIR_REQUEST,
    var domainMatch: String = "",
    var op: String = OP_SET,
    var name: String = "",
    var value: String = "",
    var enabled: Boolean = true,
) {
    companion object {
        const val DIR_REQUEST = "request"
        const val DIR_RESPONSE = "response"
        const val OP_ADD = "add"
        const val OP_SET = "set"
        const val OP_REMOVE = "remove"

        fun directionLabel(d: String) = when (d) {
            DIR_REQUEST -> "请求头"
            DIR_RESPONSE -> "响应头"
            else -> d
        }

        fun opLabel(o: String) = when (o) {
            OP_ADD -> "添加"
            OP_SET -> "覆盖"
            OP_REMOVE -> "删除"
            else -> o
        }
    }
}

class RewriteStore {

    val urlRules = mutableStateListOf<UrlRewriteRule>()
    val headerRules = mutableStateListOf<HeaderRewriteRule>()

    /** MITM 总开关（解密 HTTPS 才需要；关闭则代理只做盲转发+明文 HTTP 改写） */
    @Volatile var mitmEnabled: Boolean = false

    private val dataFile: File
        get() = File(System.getProperty("user.home"), ".moon-pc/rewrite.json")

    init {
        load()
    }

    // ---------------- 匹配逻辑（供 MitmProxy 调用，纯函数） ----------------

    /**
     * URL 重写匹配：返回首个命中的启用规则，无则 null。
     * 通配符：`*`→`.*`、`?`→`.`，其余转义；正则：预编译后匹配。
     *
     * 注意：正则由用户自写，Java 正则无超时机制，理论上有 ReDoS 风险；
     * 威胁模型是用户自己的规则+自己的流量，最坏自伤（单个连接线程卡住），
     * 此处预编译 fail-fast + 仅首命中一条，残留风险低。
     */
    fun matchUrlRewrite(url: String): UrlRewriteRule? {
        for (r in urlRules) {
            if (!r.enabled || r.match.isBlank()) continue
            val hit = when (r.matchType) {
                UrlRewriteRule.MATCH_REGEX -> compiledRegex(r)?.containsMatchIn(url) ?: false
                else -> wildcardMatch(r.match, url)
            }
            if (hit) return r
        }
        return null
    }

    /** 正则预编译缓存（规则增删改时失效）；非法正则返回 null（该规则永不命中） */
    private val regexCache = mutableMapOf<String, Regex?>()

    private fun compiledRegex(r: UrlRewriteRule): Regex? {
        synchronized(regexCache) {
            if (regexCache.containsKey(r.id)) return regexCache[r.id]
            val c = runCatching { Regex(r.match) }.getOrNull()
            // 上限防缓存无限增长（规则数通常很少）
            if (regexCache.size > 512) regexCache.clear()
            regexCache[r.id] = c
            return c
        }
    }

    /** 规则变更时清预编译缓存 */
    private fun invalidateRegexCache() {
        synchronized(regexCache) { regexCache.clear() }
    }

    /**
     * Header 改写：取某方向、某域名适用的启用规则（按添加顺序）。
     */
    fun matchHeaderRules(direction: String, host: String): List<HeaderRewriteRule> {
        val h = host.lowercase()
        return headerRules.filter { r ->
            r.enabled && r.direction == direction && r.name.isNotBlank() &&
                (r.domainMatch.isBlank() || wildcardMatch(r.domainMatch.lowercase(), h))
        }
    }

    companion object {
        /** 通配符匹配（大小写敏感，调用方先 lower） */
        fun wildcardMatch(pattern: String, text: String): Boolean {
            val sb = StringBuilder("^")
            for (c in pattern) {
                when (c) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    '\\', '.', '^', '$', '+', '{', '}', '[', ']', '(', ')', '|' ->
                        sb.append('\\').append(c)
                    else -> sb.append(c)
                }
            }
            sb.append('$')
            return runCatching { Regex(sb.toString()).matches(text) }.getOrDefault(false)
        }
    }

    // ---------------- CRUD ----------------

    fun addUrlRule(r: UrlRewriteRule) { urlRules.add(r); save() }
    fun updateUrlRule(r: UrlRewriteRule) {
        val i = urlRules.indexOfFirst { it.id == r.id }
        if (i >= 0) { urlRules[i] = r; save() }
    }
    fun removeUrlRule(id: String) { urlRules.removeAll { it.id == id }; save() }
    fun moveUrlRule(id: String, up: Boolean) {
        val i = urlRules.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in urlRules.indices) return
        val t = urlRules[i]; urlRules[i] = urlRules[j]; urlRules[j] = t; save()
    }

    fun addHeaderRule(r: HeaderRewriteRule) { headerRules.add(r); save() }
    fun updateHeaderRule(r: HeaderRewriteRule) {
        val i = headerRules.indexOfFirst { it.id == r.id }
        if (i >= 0) { headerRules[i] = r; save() }
    }
    fun removeHeaderRule(id: String) { headerRules.removeAll { it.id == id }; save() }
    fun moveHeaderRule(id: String, up: Boolean) {
        val i = headerRules.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in headerRules.indices) return
        val t = headerRules[i]; headerRules[i] = headerRules[j]; headerRules[j] = t; save()
    }

    // ---------------- 持久化 ----------------

    private fun load() {
        runCatching {
            val f = dataFile
            if (!f.isFile) return
            val o = JSONObject(f.readText())
            mitmEnabled = o.optBoolean("mitmEnabled", false)
            o.optJSONArray("urlRules")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val j = arr.optJSONObject(i) ?: continue
                    urlRules.add(
                        UrlRewriteRule(
                            id = j.optString("id", UUID.randomUUID().toString()),
                            match = j.optString("match"),
                            matchType = j.optString("matchType", UrlRewriteRule.MATCH_WILDCARD),
                            action = j.optString("action", UrlRewriteRule.ACTION_REDIRECT),
                            target = j.optString("target"),
                            enabled = j.optBoolean("enabled", true),
                        ),
                    )
                }
            }
            o.optJSONArray("headerRules")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val j = arr.optJSONObject(i) ?: continue
                    headerRules.add(
                        HeaderRewriteRule(
                            id = j.optString("id", UUID.randomUUID().toString()),
                            direction = j.optString("direction", HeaderRewriteRule.DIR_REQUEST),
                            domainMatch = j.optString("domainMatch"),
                            op = j.optString("op", HeaderRewriteRule.OP_SET),
                            name = j.optString("name"),
                            value = j.optString("value"),
                            enabled = j.optBoolean("enabled", true),
                        ),
                    )
                }
            }
        }
    }

    fun save() {
        invalidateRegexCache()
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val o = JSONObject()
                o.put("mitmEnabled", mitmEnabled)
                val ua = JSONArray()
                for (r in urlRules) {
                    ua.put(
                        JSONObject()
                            .put("id", r.id)
                            .put("match", r.match)
                            .put("matchType", r.matchType)
                            .put("action", r.action)
                            .put("target", r.target)
                            .put("enabled", r.enabled),
                    )
                }
                o.put("urlRules", ua)
                val ha = JSONArray()
                for (r in headerRules) {
                    ha.put(
                        JSONObject()
                            .put("id", r.id)
                            .put("direction", r.direction)
                            .put("domainMatch", r.domainMatch)
                            .put("op", r.op)
                            .put("name", r.name)
                            .put("value", r.value)
                            .put("enabled", r.enabled),
                    )
                }
                o.put("headerRules", ha)
                withContext(Dispatchers.IO) {
                    SecureFile.writeText(dataFile, o.toString(2))
                }
            }
        }
    }
}
