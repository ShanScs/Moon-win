package com.muse.moonpc.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * sing-box Clash API 最小客户端（M4，全新实现）：
 * - 切换 selector 出口：PUT /proxies/{group} {"name": proxy}
 * - 读取 selector/urltest 当前出口：GET /proxies -> proxies.{group}.now
 *
 * 全部 best-effort：失败返回 Result.failure / null，不抛异常。
 */
object ClashApi {

    /**
     * 实时切换 selector 组的出口（sing-box 运行中生效）。
     * @param groupTag 策略组出站 tag（如 "PROXY"）
     * @param proxyTag 目标出站 tag（如 "node-0-abc123" 或 "direct"）
     */
    fun selectOutbound(groupTag: String, proxyTag: String, port: Int = 9090): Result<Unit> =
        runCatching {
            val url = URL("http://127.0.0.1:$port/proxies/${encodePath(groupTag)}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use {
                    it.write(JSONObject().put("name", proxyTag).toString().toByteArray())
                }
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("Clash API 返回 $code")
            } finally {
                conn.disconnect()
            }
        }

    /**
     * 读取某组当前实际出口 tag；sing-box 未运行或失败时返回 null。
     */
    fun currentOutbound(groupTag: String, port: Int = 9090): String? = runCatching {
        val url = URL("http://127.0.0.1:$port/proxies")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
        }
        try {
            val text = conn.inputStream.bufferedReader().readText()
            JSONObject(text).optJSONObject("proxies")
                ?.optJSONObject(groupTag)
                ?.optString("now")
                ?.takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** path segment 编码：空格转 %20（URLEncoder 默认转 +，用于 path 会出错） */
    private fun encodePath(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---------------- 流量统计（仪表盘） ----------------

    /**
     * GET /connections：全部连接的累计上传/下载字节（sing-box 返回 Clash 兼容格式，
     * connections[i].upload / .download，含已关闭连接、直连与代理流量）。
     * @return (uploadBytes, downloadBytes)，失败返回 null
     */
    fun connectionTotals(port: Int = 9090): Pair<Long, Long>? = runCatching {
        val url = URL("http://127.0.0.1:$port/connections")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
        }
        try {
            val text = conn.inputStream.bufferedReader().readText()
            var up = 0L
            var down = 0L
            val arr = JSONObject(text).optJSONArray("connections") ?: return@runCatching null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                up += o.optLong("upload", 0L)
                down += o.optLong("download", 0L)
            }
            up to down
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 活动连接数（/connections 数组长度）；失败返回 null */
    fun activeConnections(port: Int = 9090): Int? = runCatching {
        val url = URL("http://127.0.0.1:$port/connections")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
        }
        try {
            val text = conn.inputStream.bufferedReader().readText()
            JSONObject(text).optJSONArray("connections")?.length()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    // ---------------- 请求活动页（P3） ----------------

    /** 单条活动连接 */
    data class ConnectionInfo(
        val id: String,
        /** 展示用目标：域名优先，无则 IP:端口 */
        val host: String,
        /** 命中的规则（如 "DomainSuffix" / "Match"） */
        val rule: String,
        /** 出口链最后一个（实际出口 tag） */
        val outbound: String,
        val upload: Long,
        val download: Long,
        /** 开始时间戳（毫秒）；解析失败为 0 */
        val startMs: Long,
    )

    /**
     * GET /connections：全部活动连接详情（sing-box Clash 兼容格式）。
     * 失败返回 null。
     */
    fun connections(port: Int = 9090): List<ConnectionInfo>? = runCatching {
        val url = URL("http://127.0.0.1:$port/connections")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 5000
        }
        try {
            val text = conn.inputStream.bufferedReader().readText()
            val arr = JSONObject(text).optJSONArray("connections") ?: return@runCatching null
            val out = mutableListOf<ConnectionInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val md = o.optJSONObject("metadata")
                val host = md?.optString("host")?.takeIf { it.isNotEmpty() }
                    ?: listOfNotNull(
                        md?.optString("destinationIP")?.takeIf { it.isNotEmpty() },
                        md?.optInt("destinationPort")?.takeIf { it > 0 }?.toString(),
                    ).joinToString(":").ifEmpty { "未知" }
                val chains = o.optJSONArray("chains")
                val outbound = if (chains != null && chains.length() > 0)
                    chains.optString(chains.length() - 1) else ""
                out.add(
                    ConnectionInfo(
                        id = o.optString("id").ifEmpty { "$i" },
                        host = host,
                        rule = o.optString("rule").ifEmpty { "—" },
                        outbound = outbound,
                        upload = o.optLong("upload"),
                        download = o.optLong("download"),
                        startMs = parseTime(o.optString("start")),
                    ),
                )
            }
            out
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 解析 sing-box 的 RFC3339 时间；失败返回 0 */
    private fun parseTime(s: String): Long = runCatching {
        java.time.Instant.parse(s).toEpochMilli()
    }.getOrDefault(0L)
}
