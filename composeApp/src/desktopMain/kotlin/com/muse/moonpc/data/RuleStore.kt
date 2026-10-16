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
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 分流规则（全新实现）：
 * - 单条规则：类型 domain / domain-suffix / domain-keyword / ip-cidr / geoip / port，
 *   动作 proxy / direct / reject / 自建策略组别名
 * - 远端规则集订阅：URL + 动作，手动/批量更新，解析结果缓存到 rulesets/<id>.txt
 * - 持久化 ~/.moon-pc/rules.json
 */
data class RouteRule(
    val id: String = UUID.randomUUID().toString(),
    var type: String = TYPE_DOMAIN_SUFFIX,
    var value: String = "",
    /** "proxy" / "direct" / "reject" / 策略组别名 */
    var action: String = ACTION_PROXY,
) {
    companion object {
        const val TYPE_DOMAIN = "domain"
        const val TYPE_DOMAIN_SUFFIX = "domain-suffix"
        const val TYPE_DOMAIN_KEYWORD = "domain-keyword"
        const val TYPE_IP_CIDR = "ip-cidr"
        const val TYPE_GEOIP = "geoip"
        const val TYPE_PORT = "port"

        val TYPES = listOf(
            TYPE_DOMAIN_SUFFIX, TYPE_DOMAIN, TYPE_DOMAIN_KEYWORD,
            TYPE_IP_CIDR, TYPE_GEOIP, TYPE_PORT,
        )

        fun typeLabel(t: String): String = when (t) {
            TYPE_DOMAIN -> "域名"
            TYPE_DOMAIN_SUFFIX -> "域名后缀"
            TYPE_DOMAIN_KEYWORD -> "域名关键词"
            TYPE_IP_CIDR -> "IP 段"
            TYPE_GEOIP -> "GeoIP"
            TYPE_PORT -> "端口"
            else -> t
        }

        const val ACTION_PROXY = "proxy"
        const val ACTION_DIRECT = "direct"
        const val ACTION_REJECT = "reject"

        fun actionLabel(a: String): String = when (a) {
            ACTION_PROXY -> "代理"
            ACTION_DIRECT -> "直连"
            ACTION_REJECT -> "拦截"
            else -> a
        }
    }
}

/** 远端规则集订阅 */
data class RuleSetSubscription(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var url: String = "",
    /** "proxy" / "direct" / "reject" / 策略组别名 */
    var action: String = RouteRule.ACTION_PROXY,
    var updatedAt: Long = 0L,
    var entryCount: Int = 0,
    var error: String = "",
    /** 开关：关闭时不参与配置生成（去广告总开关用） */
    var enabled: Boolean = true,
)

/** 解析后的规则集条目（供 ConfigGenerator 生成 route.rules） */
data class CompiledRuleSet(
    val id: String,
    val action: String,
    val domains: List<String> = emptyList(),
    val domainSuffixes: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val ipCidrs: List<String> = emptyList(),
    val geoips: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean =
        domains.isEmpty() && domainSuffixes.isEmpty() && keywords.isEmpty() &&
            ipCidrs.isEmpty() && geoips.isEmpty()
}

/** 预置 AdGuard 去广告订阅的固定 id（兜底列表与订阅共用，保证默认启用） */
const val PRESET_ADGUARD_ID = "preset-adguard"
/** AdGuard DNS filter（adblock 语法，parseRuleSetText 兼容 ||domain^ 行） */
const val PRESET_ADGUARD_URL = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt"

class RuleStore(
    private val dir: File = File(System.getProperty("user.home"), ".moon-pc"),
) {
    val rules = mutableStateListOf<RouteRule>()
    val ruleSets = mutableStateListOf<RuleSetSubscription>()

    /** 内存中的解析结果：订阅 id -> 编译条目 */
    private val compiled = mutableMapOf<String, CompiledRuleSet>()

    private val file: File get() = File(dir, "rules.json")
    private val cacheDir: File get() = File(dir, "rulesets")

    init {
        load()
        ensureAdGuardPreset()
    }

    /**
     * 预置 AdGuard 去广告规则集订阅（action=reject），默认启用（P2）。
     * - 首次启动自动添加；用户可删除/在规则页管理
     * - 订阅从未拉取成功时，用内置 adguard-fallback.txt 兜底，保证开箱即拦截
     */
    private fun ensureAdGuardPreset() {
        if (ruleSets.none { it.id == PRESET_ADGUARD_ID || it.url == PRESET_ADGUARD_URL }) {
            val sub = RuleSetSubscription(
                id = PRESET_ADGUARD_ID,
                name = "AdGuard 去广告",
                url = PRESET_ADGUARD_URL,
                action = RouteRule.ACTION_REJECT,
            )
            ruleSets.add(sub)
            save()
        }
        // 兜底：无解析结果时用内置列表
        if (compiled[PRESET_ADGUARD_ID] == null) {
            runCatching {
                val text = javaClass.getResourceAsStream("/adguard-fallback.txt")
                    ?.bufferedReader()?.readText()
                if (!text.isNullOrBlank()) {
                    val c = parseRuleSetText(text, PRESET_ADGUARD_ID, RouteRule.ACTION_REJECT)
                    if (!c.isEmpty()) compiled[PRESET_ADGUARD_ID] = c
                }
            }
        }
        // 后台尝试拉取最新（best-effort，失败则继续用兜底）
        if (ruleSets.any { it.id == PRESET_ADGUARD_ID && it.updatedAt == 0L }) {
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                runCatching { refreshRuleSet(PRESET_ADGUARD_ID) }
            }
        }
    }

    /** 供 ConfigGenerator：已解析的规则集（跳过关闭/失败/空的） */
    fun compiledRuleSets(): List<CompiledRuleSet> =
        ruleSets.filter { it.enabled }.mapNotNull { compiled[it.id]?.takeIf { !it.isEmpty() } }

    /** 订阅开关（去广告总开关用） */
    fun setRuleSetEnabled(id: String, enabled: Boolean) {
        val idx = ruleSets.indexOfFirst { it.id == id }
        if (idx < 0) return
        ruleSets[idx] = ruleSets[idx].copy(enabled = enabled)
        save()
    }

    // ---------------- 单条规则 CRUD ----------------

    fun addRule(type: String, value: String, action: String): RouteRule? {
        val v = value.trim()
        if (v.isEmpty() || !validValue(type, v)) return null
        val r = RouteRule(type = type, value = v, action = action)
        rules.add(r)
        save()
        return r
    }

    fun updateRule(id: String, type: String, value: String, action: String): Boolean {
        val idx = rules.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val v = value.trim()
        if (v.isEmpty() || !validValue(type, v)) return false
        rules[idx] = rules[idx].copy(type = type, value = v, action = action)
        save()
        return true
    }

    fun removeRule(id: String) {
        rules.removeAll { it.id == id }
        save()
    }

    fun moveRule(id: String, delta: Int): Boolean {
        val idx = rules.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val to = (idx + delta).coerceIn(0, rules.size - 1)
        if (to == idx) return false
        val r = rules.removeAt(idx)
        rules.add(to, r)
        save()
        return true
    }

    private fun validValue(type: String, v: String): Boolean = when (type) {
        RouteRule.TYPE_PORT -> v.toIntOrNull()?.let { it in 1..65535 } ?: false
        RouteRule.TYPE_IP_CIDR -> v.contains("/") && v.substringBefore("/").split(".").size == 4
        RouteRule.TYPE_GEOIP -> v.matches(Regex("[A-Za-z]{2}"))
        else -> v.isNotEmpty() && !v.contains(" ")
    }

    // ---------------- 规则集订阅 CRUD ----------------

    fun addRuleSet(name: String, url: String, action: String): RuleSetSubscription? {
        val u = url.trim()
        if (u.isEmpty() || !(u.startsWith("http://") || u.startsWith("https://"))) return null
        val s = RuleSetSubscription(
            name = name.trim().ifBlank { runCatching { URL(u).host }.getOrDefault("规则集") },
            url = u,
            action = action,
        )
        ruleSets.add(s)
        save()
        return s
    }

    fun updateRuleSetMeta(id: String, name: String, url: String, action: String): Boolean {
        val idx = ruleSets.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val u = url.trim()
        if (u.isEmpty() || !(u.startsWith("http://") || u.startsWith("https://"))) return false
        ruleSets[idx] = ruleSets[idx].copy(
            name = name.trim().ifBlank { ruleSets[idx].name },
            url = u,
            action = action,
            error = "",
        )
        save()
        return true
    }

    fun removeRuleSet(id: String) {
        ruleSets.removeAll { it.id == id }
        compiled.remove(id)
        runCatching { File(cacheDir, "${safeCacheName(id)}.txt").delete() }
        save()
    }

    /**
     * 拉取并解析单个规则集。返回成功条目数，失败抛异常（调用方展示）。
     */
    suspend fun refreshRuleSet(id: String): Int = withContext(Dispatchers.IO) {
        val idx = ruleSets.indexOfFirst { it.id == id }
            ?: throw IllegalArgumentException("订阅不存在")
        val sub = ruleSets[idx]
        try {
            val text = httpGet(sub.url)
            val c = parseRuleSetText(text, id, sub.action)
            compiled[id] = c
            cacheDir.mkdirs()
            File(cacheDir, "${safeCacheName(id)}.txt").writeText(text.take(5 * 1024 * 1024))
            ruleSets[idx] = sub.copy(
                updatedAt = System.currentTimeMillis(),
                entryCount = c.domains.size + c.domainSuffixes.size + c.keywords.size +
                    c.ipCidrs.size + c.geoips.size,
                error = "",
            )
            save()
            ruleSets[idx].entryCount
        } catch (e: Exception) {
            ruleSets[idx] = sub.copy(error = e.message ?: "更新失败")
            save()
            throw e
        }
    }

    suspend fun refreshAllRuleSets(): Pair<Int, Int> {
        var ok = 0
        var fail = 0
        ruleSets.toList().forEach { s ->
            runCatching { refreshRuleSet(s.id); ok++ }.onFailure { fail++ }
        }
        return ok to fail
    }

    /**
     * 缓存文件名消毒：id 来自 JSON 文件（用户可手改），只保留安全字符，
     * 防止 "../../" 之类的路径穿越写到 rulesets 目录外面。
     */
    private fun safeCacheName(id: String): String {
        val s = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return s.ifEmpty { "x" }
    }

    private fun httpGet(url: String, timeoutMs: Int = 20000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "MoonPC/1.0")
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 解析规则集文本，兼容：
     * - Clash 经典：DOMAIN-SUFFIX,example.com / DOMAIN, / DOMAIN-KEYWORD, / IP-CIDR, / GEOIP,CN
     * - 纯域名列表（adblock 风格）：每行一个域名
     * - AdBlock 风格：||example.com^
     * 注释行（# ! [）跳过。
     */
    fun parseRuleSetText(text: String, id: String, action: String): CompiledRuleSet {
        val domains = LinkedHashSet<String>()
        val suffixes = LinkedHashSet<String>()
        val keywords = LinkedHashSet<String>()
        val cidrs = LinkedHashSet<String>()
        val geoips = LinkedHashSet<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || line.startsWith("[")) return@forEach
            // AdBlock 风格
            if (line.startsWith("||")) {
                val d = line.removePrefix("||").substringBefore("^").trim().trim('.')
                if (d.contains(".")) suffixes.add(d.lowercase())
                return@forEach
            }
            if ("," in line) {
                val parts = line.split(",", limit = 3)
                val kind = parts[0].trim().uppercase()
                val value = parts.getOrNull(1)?.trim() ?: return@forEach
                when (kind) {
                    "DOMAIN-SUFFIX" -> if (value.isNotEmpty()) suffixes.add(value.trim('.').lowercase())
                    "DOMAIN" -> if (value.isNotEmpty()) domains.add(value.trim('.').lowercase())
                    "DOMAIN-KEYWORD" -> if (value.isNotEmpty()) keywords.add(value.lowercase())
                    "IP-CIDR", "IP-CIDR6" -> if ("/" in value) cidrs.add(value)
                    "GEOIP" -> if (value.matches(Regex("[A-Za-z]{2}"))) geoips.add(value.uppercase())
                }
                return@forEach
            }
            // 纯域名行
            val d = line.trim('.').lowercase()
            when {
                Regex("^\\d{1,3}(\\.\\d{1,3}){3}(/\\d{1,2})?$").matches(d) -> cidrs.add(d)
                d.contains(".") && !d.contains(" ") && !d.contains("/") -> suffixes.add(d)
            }
        }
        return CompiledRuleSet(
            id = id,
            action = action,
            domains = domains.toList(),
            domainSuffixes = suffixes.toList(),
            keywords = keywords.toList(),
            ipCidrs = cidrs.toList(),
            geoips = geoips.toList(),
        )
    }

    // ---------------- 持久化 ----------------

    private fun save() {
        runCatching {
            dir.mkdirs()
            val o = JSONObject()
            val ra = JSONArray()
            rules.forEach { r ->
                ra.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("type", r.type)
                        .put("value", r.value)
                        .put("action", r.action),
                )
            }
            o.put("rules", ra)
            val sa = JSONArray()
            ruleSets.forEach { s ->
                sa.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("name", s.name)
                        .put("url", s.url)
                        .put("action", s.action)
                        .put("updatedAt", s.updatedAt)
                        .put("entryCount", s.entryCount)
                        .put("enabled", s.enabled),
                )
            }
            o.put("rulesets", sa)
            SecureFile.writeText(file, o.toString(2))
        }
    }

    private fun load() {
        runCatching {
            val f = file
            if (!f.isFile) return
            val root = JSONObject(f.readText())
            val ra = root.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until ra.length()) {
                val jo = ra.getJSONObject(i)
                val type = jo.optString("type")
                if (type !in RouteRule.TYPES) continue
                val value = jo.optString("value")
                if (value.isBlank()) continue
                rules.add(
                    RouteRule(
                        id = jo.optString("id").ifBlank { UUID.randomUUID().toString() },
                        type = type,
                        value = value,
                        action = jo.optString("action").ifBlank { RouteRule.ACTION_PROXY },
                    ),
                )
            }
            val sa = root.optJSONArray("rulesets") ?: JSONArray()
            for (i in 0 until sa.length()) {
                val jo = sa.getJSONObject(i)
                val url = jo.optString("url")
                if (url.isBlank()) continue
                val id = jo.optString("id").ifBlank { UUID.randomUUID().toString() }
                val sub = RuleSetSubscription(
                    id = id,
                    name = jo.optString("name").ifBlank { "规则集" },
                    url = url,
                    action = jo.optString("action").ifBlank { RouteRule.ACTION_PROXY },
                    updatedAt = jo.optLong("updatedAt"),
                    entryCount = jo.optInt("entryCount"),
                    enabled = jo.optBoolean("enabled", true),
                )
                ruleSets.add(sub)
                // 从缓存恢复解析结果
                runCatching {
                    val cf = File(cacheDir, "${safeCacheName(id)}.txt")
                    if (cf.isFile) {
                        val c = parseRuleSetText(cf.readText(), id, sub.action)
                        if (!c.isEmpty()) compiled[id] = c
                    }
                }
            }
        }
    }
}
