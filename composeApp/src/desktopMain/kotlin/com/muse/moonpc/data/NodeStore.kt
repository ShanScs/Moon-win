package com.muse.moonpc.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.muse.moonpc.core.NodeParser
import com.muse.moonpc.core.SecureFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Base64

/**
 * 节点与订阅存储（M2，全新实现）：
 * - 内存：Compose 可观察状态（nodes / subscriptions / selectedId / latencies）
 * - 磁盘：~/.moon-pc/nodes.json，每次变更即时落盘
 *
 * 约定：ProxyNode.subId 为 "" 表示本地手动导入；否则归属对应订阅，
 * 订阅更新时整体替换其旧节点。
 */
class NodeStore(
    private val dir: File = File(System.getProperty("user.home"), ".moon-pc"),
) {
    val nodes = mutableStateListOf<ProxyNode>()
    val subscriptions = mutableStateListOf<Subscription>()
    var selectedId by mutableStateOf<String?>(null)

    /** 节点延迟（毫秒）；-1 = 超时/失败；无 key = 尚未测试 */
    val latencies = mutableStateMapOf<String, Long>()

    private val dataFile: File get() = File(dir, "nodes.json")

    init {
        load()
    }

    // ---------------- 节点 ----------------

    /** 批量添加节点；返回实际添加数 */
    fun addNodes(list: List<ProxyNode>, subId: String = ""): Int {
        if (list.isEmpty()) return 0
        list.forEach { it.subId = subId }
        nodes.addAll(list)
        save()
        return list.size
    }

    fun removeNode(id: String) {
        nodes.removeAll { it.id == id }
        latencies.remove(id)
        if (selectedId == id) selectedId = null
        save()
    }

    fun select(id: String?) {
        selectedId = id
        save()
    }

    /**
     * 解析多行分享链接并导入本地分组。
     * @return Pair(成功数, 失败数)
     */
    fun importLinks(text: String): Pair<Int, Int> {
        val r = NodeParser.parseBatch(text)
        val added = addNodes(r.nodes)
        return added to r.failed
    }

    // ---------------- 订阅 ----------------

    /**
     * 添加订阅。仅允许 http/https（防 file:// 等 scheme 读本地文件）。
     * @return 新订阅；URL 非法时返回 null
     */
    fun addSubscription(name: String, url: String): Subscription? {
        val u = url.trim()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return null
        val sub = Subscription(
            name = name.ifBlank { guessSubName(u) },
            url = u,
        )
        subscriptions.add(sub)
        save()
        return sub
    }

    fun removeSubscription(id: String) {
        subscriptions.removeAll { it.id == id }
        val gone = nodes.filter { it.subId == id }.map { it.id }.toSet()
        nodes.removeAll { it.subId == id }
        gone.forEach { latencies.remove(it) }
        if (selectedId in gone) selectedId = null
        save()
    }

    data class UpdateResult(
        val added: Int,
        val failed: Int,
        val traffic: String,
        val error: String? = null,
    )

    /**
     * 拉取订阅并整体替换其节点（IO 线程执行）。
     * 兼容纯文本与 base64 两种订阅体；顺带解析 subscription-userinfo 流量头。
     */
    suspend fun updateSubscription(sub: Subscription): UpdateResult =
        withContext(Dispatchers.IO) {
            // 纵深防御：入口已校验 scheme，这里再拦一次
            val u = sub.url.trim()
            if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                return@withContext UpdateResult(0, 0, "", "仅支持 http/https 订阅链接")
            }
            var conn: HttpURLConnection? = null
            try {
                conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "Moon-PC/0.1.0")
                conn.connect()
                val userInfo = conn.getHeaderField("subscription-userinfo") ?: ""
                val raw = conn.inputStream.bufferedReader().readText()

                val body = maybeBase64Decode(raw)
                val r = NodeParser.parseBatch(body)

                val gone = nodes.filter { it.subId == sub.id }.map { it.id }.toSet()
                nodes.removeAll { it.subId == sub.id }
                gone.forEach { latencies.remove(it) }
                r.nodes.forEach { it.subId = sub.id }
                nodes.addAll(r.nodes)
                if (selectedId in gone) selectedId = null

                sub.updatedAt = System.currentTimeMillis()
                sub.traffic = parseTraffic(userInfo)
                save()
                UpdateResult(r.nodes.size, r.failed, sub.traffic)
            } catch (e: Exception) {
                UpdateResult(0, 0, "", e.message ?: "更新失败")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

    // ---------------- 延迟 ----------------

    /**
     * TCP 建连延迟（毫秒）；-1 = 超时/失败。
     * 注意：测的是到节点服务器的直连延迟，仅作可达性参考。
     */
    suspend fun testLatency(node: ProxyNode, timeoutMs: Int = 3000): Long =
        withContext(Dispatchers.IO) {
            val ms = runCatching {
                val t0 = System.currentTimeMillis()
                Socket().use { s ->
                    s.connect(InetSocketAddress(node.host, node.port), timeoutMs)
                }
                System.currentTimeMillis() - t0
            }.getOrElse { -1L }
            latencies[node.id] = ms
            ms
        }

    /** 并发测试指定节点列表的延迟 */
    suspend fun testLatencies(list: List<ProxyNode>) {
        val snapshot = list.toList()
        withContext(Dispatchers.IO) {
            snapshot.map { async { testLatency(it) } }.awaitAll()
        }
    }

    /** 并发测试全部节点延迟 */
    suspend fun testAllLatencies() = testLatencies(nodes.toList())

    // ---------------- 变更监听 ----------------

    /**
     * 节点集合变更监听（PolicyStore 用来裁剪策略组成员）。
     * 在每次落盘后触发。
     */
    private val changeListeners = mutableListOf<() -> Unit>()

    fun addChangeListener(l: () -> Unit) {
        changeListeners += l
    }

    private fun notifyChanged() {
        changeListeners.forEach { runCatching { it() } }
    }

    // ---------------- 持久化 ----------------

    private fun save() {
        runCatching {
            dir.mkdirs()
            val o = JSONObject()
            o.put("selectedId", selectedId ?: "")
            val ja = JSONArray()
            nodes.forEach { n ->
                val jo = JSONObject()
                jo.put("id", n.id)
                jo.put("protocol", n.protocol)
                jo.put("name", n.name)
                jo.put("host", n.host)
                jo.put("port", n.port)
                jo.put("subId", n.subId)
                val p = JSONObject()
                n.params.forEach { (k, v) -> p.put(k, v) }
                jo.put("params", p)
                ja.put(jo)
            }
            o.put("nodes", ja)
            val sa = JSONArray()
            subscriptions.forEach { s ->
                val jo = JSONObject()
                jo.put("id", s.id)
                jo.put("name", s.name)
                jo.put("url", s.url)
                jo.put("updatedAt", s.updatedAt)
                jo.put("traffic", s.traffic)
                sa.put(jo)
            }
            o.put("subscriptions", sa)
            SecureFile.writeText(dataFile, o.toString(2))
            notifyChanged()
        }
    }

    private fun load() {
        runCatching {
            val f = dataFile
            if (!f.isFile) return
            val o = JSONObject(f.readText())
            selectedId = o.optString("selectedId").takeIf { it.isNotEmpty() }
            val ja = o.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until ja.length()) {
                val jo = ja.getJSONObject(i)
                val params = mutableMapOf<String, String>()
                val p = jo.optJSONObject("params")
                if (p != null) {
                    val keys = p.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        params[k] = p.optString(k)
                    }
                }
                nodes.add(
                    ProxyNode(
                        id = jo.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                        protocol = jo.optString("protocol"),
                        name = jo.optString("name"),
                        host = jo.optString("host"),
                        port = jo.optInt("port"),
                        params = params,
                        subId = jo.optString("subId"),
                    ),
                )
            }
            val sa = o.optJSONArray("subscriptions") ?: JSONArray()
            for (i in 0 until sa.length()) {
                val jo = sa.getJSONObject(i)
                subscriptions.add(
                    Subscription(
                        id = jo.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                        name = jo.optString("name"),
                        url = jo.optString("url"),
                        updatedAt = jo.optLong("updatedAt"),
                        traffic = jo.optString("traffic"),
                    ),
                )
            }
        }
    }

    companion object {
        /**
         * 订阅体可能是 base64：内容里没有 "://" 且符合 base64 字符集时尝试解码，
         * 解码失败则原样返回。
         */
        fun maybeBase64Decode(raw: String): String {
            val t = raw.trim()
            if (t.contains("://")) return raw
            if (!t.matches(Regex("[A-Za-z0-9+/=\\r\\n]+"))) return raw
            return runCatching {
                String(Base64.getMimeDecoder().decode(t), Charsets.UTF_8)
            }.getOrDefault(raw)
        }

        /**
         * 解析 subscription-userinfo 头：
         * 例：upload=123; download=456; total=789 → "0.00GB/1.00GB"
         */
        fun parseTraffic(header: String): String {
            if (header.isBlank()) return ""
            fun num(k: String): Long {
                val m = Regex("$k=(\\d+)").find(header) ?: return -1
                return m.groupValues[1].toLongOrNull() ?: -1
            }
            val up = num("upload")
            val down = num("download")
            val total = num("total")
            if (up < 0 || down < 0 || total <= 0) return ""
            fun gb(b: Long): String = "%.2fGB".format(b / 1024.0 / 1024.0 / 1024.0)
            return "${gb(up + down)}/${gb(total)}"
        }

        private fun guessSubName(url: String): String = runCatching {
            URL(url).host.ifBlank { "订阅" }
        }.getOrDefault("订阅")
    }
}
