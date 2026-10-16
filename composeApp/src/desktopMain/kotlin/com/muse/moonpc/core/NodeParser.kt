package com.muse.moonpc.core

import com.muse.moonpc.data.ProxyNode
import org.json.JSONObject
import java.net.URLDecoder

/**
 * 分享链接解析：ss / vless / vmess / trojan / hysteria2(hy2) / tuic / anytls /
 * http / https / socks5 / ssh。
 *
 * 产出 ProxyNode 的 params key 与 ConfigGenerator 保持一致：
 * shadowsocks(password, method)
 * vless(uuid, encryption, transport, tls, sni, flow, wsPath, wsHost, grpcService,
 *       reality, realityPublicKey, realityShortId, fingerprint, insecure)
 * vmess(uuid, alterId, encryption, transport, tls, sni, wsPath)
 * trojan(password, sni)
 * hysteria2(password, sni, hopPort, obfs, obfsPassword)
 * tuic(uuid, password, sni)
 * anytls(password, sni)
 * http/https(username, password)
 * socks5(username, password)
 * ssh(username, password, privateKey)
 */
object NodeParser {

    /** 批量解析结果：成功节点列表 + 失败行数 */
    data class BatchResult(val nodes: List<ProxyNode>, val failed: Int)

    /**
     * 解析单条分享链接。
     * @param link 完整分享链接（如 ss://… / vless://… / vmess://…）
     * @return 解析出的节点；格式错误或协议不支持时返回 null（不抛异常）
     */
    fun parseShareLink(link: String): ProxyNode? {
        val l = link.trim()
        if (l.isEmpty()) return null
        return try {
            when {
                l.startsWith("ss://", ignoreCase = true) -> parseSS(l)
                l.startsWith("vless://", ignoreCase = true) -> parseVless(l)
                l.startsWith("vmess://", ignoreCase = true) -> parseVmess(l)
                l.startsWith("trojan://", ignoreCase = true) -> parseTrojan(l)
                l.startsWith("hysteria2://", ignoreCase = true) ||
                        l.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(l)
                l.startsWith("tuic://", ignoreCase = true) -> parseTuic(l)
                l.startsWith("anytls://", ignoreCase = true) -> parseAnyTls(l)
                l.startsWith("http://", ignoreCase = true) ||
                        l.startsWith("https://", ignoreCase = true) -> parseHttp(l)
                l.startsWith("socks5://", ignoreCase = true) ||
                        l.startsWith("socks://", ignoreCase = true) -> parseSocks5(l)
                l.startsWith("ssh://", ignoreCase = true) -> parseSsh(l)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 批量解析：文本每行一条分享链接，空行跳过。
     * 超长行（>64KB，防恶意粘贴吃内存）计为失败跳过。
     * @param text 多行文本（可从订阅/剪贴板批量粘贴）
     * @return [BatchResult]：成功节点列表 + 失败行数
     */
    fun parseBatch(text: String): BatchResult {
        val nodes = mutableListOf<ProxyNode>()
        var failed = 0
        text.lines().forEach { line ->
            val l = line.trim()
            if (l.isEmpty()) return@forEach
            if (l.length > 65536) {
                failed++
                return@forEach
            }
            val n = parseShareLink(l)
            if (n != null) nodes.add(n) else failed++
        }
        return BatchResult(nodes, failed)
    }

    // ---------- 通用工具 ----------

    /** 拆 scheme://body#remark，返回 Triple(scheme小写, body, remark已URL解码) */
    private fun splitLink(link: String): Triple<String, String, String>? {
        val schemeEnd = link.indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = link.substring(0, schemeEnd).lowercase()
        var rest = link.substring(schemeEnd + 3)
        var remark = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            remark = urlDecode(rest.substring(hash + 1))
            rest = rest.substring(0, hash)
        }
        return Triple(scheme, rest, remark)
    }

    /** URL 解码（备注名/密码等）；解码失败返回原文 */
    private fun urlDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    private val B64_RE = Regex("^[A-Za-z0-9+/_-]+={0,2}$")

    /** 粗略判断字符串是否像 base64（避免把明文 userinfo 误当 base64 解码） */
    private fun looksLikeBase64(s: String): Boolean =
        s.isNotEmpty() && s.length % 4 != 1 && B64_RE.matches(s)

    /** base64（标准/urlsafe，自动补 =），失败返回 null */
    private fun b64decode(s: String): String? {
        val clean = s.trim().replace('-', '+').replace('_', '/')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return try {
            String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** query 字符串解析为 map，key/value 均 URL 解码 */
    private fun parseQuery(q: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        q.split('&').forEach { kv ->
            if (kv.isEmpty()) return@forEach
            val eq = kv.indexOf('=')
            if (eq < 0) map[urlDecode(kv)] = ""
            else map[urlDecode(kv.substring(0, eq))] = urlDecode(kv.substring(eq + 1))
        }
        return map
    }

    /** host:port，兼容 IPv6 [::1]:port */
    private fun splitHostPort(hp: String): Pair<String, Int>? {
        val host: String
        val portStr: String
        if (hp.startsWith("[")) {
            val end = hp.indexOf(']')
            if (end < 0) return null
            host = hp.substring(1, end)
            portStr = hp.substring(end + 1).removePrefix(":")
        } else {
            val colon = hp.lastIndexOf(':')
            if (colon < 0) return null
            host = hp.substring(0, colon)
            portStr = hp.substring(colon + 1)
        }
        val port = portStr.toIntOrNull() ?: return null
        if (host.isEmpty() || port <= 0 || port > 65535) return null
        return host to port
    }

    /**
     * body 形如 [userinfo@]host:port[?query]，返回 Triple(host, port, query)。
     * 注意：不含 userinfo，调用方自行从 body 取。
     */
    private fun splitAuthority(body: String): Triple<String, Int, String>? {
        val parts = body.split('?', limit = 2)
        val authority = parts[0]
        val query = if (parts.size > 1) parts[1] else ""
        val hostPort = authority.substringAfterLast('@')
        val (host, port) = splitHostPort(hostPort) ?: return null
        return Triple(host, port, query)
    }

    // ---------- 各协议 ----------

    /** 跳过证书校验：insecure=1 / skip-cert-verify=true / allowInsecure=1 */
    private fun parseInsecure(q: Map<String, String>, params: MutableMap<String, String>) {
        val v = q["insecure"] ?: q["skip-cert-verify"] ?: q["allowInsecure"]
        if (v.equals("1", ignoreCase = true) || v.equals("true", ignoreCase = true)) {
            params["insecure"] = "1"
        }
    }

    /** ss://：形式1 userinfo@host:port（userinfo 可 base64 或明文） / 形式2 整个 body base64 */
    private fun parseSS(link: String): ProxyNode? {
        val (_, body, remark) = splitLink(link) ?: return null
        val method: String
        val password: String
        val host: String
        val port: Int
        val atIdx = body.indexOf('@')
        if (atIdx >= 0) {
            // 形式1：ss://BASE64(method:password)@host:port 或明文 method:password@host:port
            var userinfo = body.substring(0, atIdx)
            if (looksLikeBase64(userinfo)) {
                val decoded = b64decode(userinfo)
                if (decoded != null && decoded.contains(':')) userinfo = decoded
                else userinfo = urlDecode(userinfo)
            } else {
                userinfo = urlDecode(userinfo)
            }
            val c = userinfo.indexOf(':')
            if (c < 0) return null
            method = userinfo.substring(0, c)
            password = userinfo.substring(c + 1)
            val hp = splitAuthority(body) ?: return null
            host = hp.first
            port = hp.second
        } else {
            // 形式2：ss://BASE64(method:password@host:port)
            // 兼容带插件参数的写法：ss://BASE64/?plugin=...
            // 注意 base64 字母表本身含 '/'，不能直接按 '/' 截断：
            // 先去掉 ?查询 整体解码，失败时再去掉末尾 '/'（路径分隔符）重试。
            val noQuery = body.substringBefore('?')
            val decoded = b64decode(noQuery)
                ?: (if (noQuery.endsWith('/')) b64decode(noQuery.dropLast(1)) else null)
                ?: return null
            val at2 = decoded.lastIndexOf('@')
            if (at2 < 0) return null
            val userinfo = decoded.substring(0, at2)
            val c = userinfo.indexOf(':')
            if (c < 0) return null
            method = userinfo.substring(0, c)
            password = userinfo.substring(c + 1)
            val hp = splitHostPort(decoded.substring(at2 + 1)) ?: return null
            host = hp.first
            port = hp.second
        }
        if (method.isEmpty() || password.isEmpty()) return null
        return ProxyNode(
            protocol = "shadowsocks", name = remark, host = host, port = port,
            params = mutableMapOf("method" to method, "password" to password)
        )
    }

    /** vless://uuid@host:port?encryption=&security=&sni=&flow=&type=&path=&host=&serviceName= */
    private fun parseVless(link: String): ProxyNode? {
        val (_, body, remark) = splitLink(link) ?: return null
        val atIdx = body.lastIndexOf('@')
        if (atIdx < 0) return null
        val uuid = urlDecode(body.substring(0, atIdx))
        if (uuid.isEmpty()) return null
        val (host, port, query) = splitAuthority(body) ?: return null
        val q = parseQuery(query)
        val params = mutableMapOf("uuid" to uuid)
        params["encryption"] = q["encryption"]?.ifEmpty { "none" } ?: "none"
        val security = q["security"].orEmpty().lowercase()
        params["tls"] = (security == "tls" || security == "reality").toString()
        q["sni"]?.takeIf { it.isNotEmpty() }?.let { params["sni"] = it }
        q["flow"]?.takeIf { it.isNotEmpty() }?.let { params["flow"] = it }
        val type = q["type"].orEmpty().lowercase().ifEmpty { "tcp" }
        params["transport"] = when (type) {
            "ws", "grpc", "tcp" -> type
            else -> type // httpupgrade 等：原样保留，SingBoxConfig 暂按 tcp 处理
        }
        q["path"]?.takeIf { it.isNotEmpty() }?.let { params["wsPath"] = it }
        q["host"]?.takeIf { it.isNotEmpty() }?.let { params["wsHost"] = it }
        q["serviceName"]?.takeIf { it.isNotEmpty() }?.let { params["grpcService"] = it }
        // REALITY：公钥 pbk / 短 id sid（spx 兼容）/ uTLS 指纹 fp
        if (security == "reality") {
            params["reality"] = "1"
            q["pbk"]?.takeIf { it.isNotEmpty() }?.let { params["realityPublicKey"] = it }
            (q["sid"] ?: q["spx"])?.takeIf { it.isNotEmpty() }
                ?.let { params["realityShortId"] = it }
        }
        q["fp"]?.takeIf { it.isNotEmpty() }?.let { params["fingerprint"] = it }
        parseInsecure(q, params)
        return ProxyNode(
            protocol = "vless", name = remark, host = host, port = port, params = params
        )
    }

    /** vmess://base64(json)；节点名取 JSON 的 ps 字段，缺省用 #remark */
    private fun parseVmess(link: String): ProxyNode? {
        val (_, body, remark) = splitLink(link) ?: return null
        val b64 = body.split('?', limit = 2)[0]
        val json = b64decode(b64) ?: return null
        val o = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        val host = o.optString("add")
        val port = o.optString("port").toIntOrNull() ?: 0
        if (host.isEmpty() || port !in 1..65535) return null
        val uuid = o.optString("id")
        if (uuid.isEmpty()) return null
        val params = mutableMapOf("uuid" to uuid)
        params["alterId"] = o.optString("aid", "0").ifEmpty { "0" }
        params["encryption"] = o.optString("scy", "auto").ifEmpty { "auto" }
        params["tls"] = (o.optString("tls") == "tls").toString()
        val net = o.optString("net", "tcp").lowercase().ifEmpty { "tcp" }
        params["transport"] = when (net) {
            "ws", "grpc", "tcp" -> net
            else -> net
        }
        o.optString("path").takeIf { it.isNotEmpty() }?.let { params["wsPath"] = it }
        o.optString("host").takeIf { it.isNotEmpty() }?.let { params["wsHost"] = it }
        o.optString("sni").takeIf { it.isNotEmpty() }?.let { params["sni"] = it }
        val name = o.optString("ps").ifEmpty { remark }
        return ProxyNode(
            protocol = "vmess", name = name, host = host, port = port, params = params
        )
    }

    /** trojan://password@host:port?sni=&peer=（sni 优先，peer 兼容老链接） */
    private fun parseTrojan(link: String): ProxyNode? {
        val (_, body, remark) = splitLink(link) ?: return null
        val atIdx = body.lastIndexOf('@')
        if (atIdx < 0) return null
        val password = urlDecode(body.substring(0, atIdx))
        if (password.isEmpty()) return null
        val (host, port, query) = splitAuthority(body) ?: return null
        val q = parseQuery(query)
        val params = mutableMapOf("password" to password)
        val sni = q["sni"]?.takeIf { it.isNotEmpty() }
            ?: q["peer"]?.takeIf { it.isNotEmpty() }
        sni?.let { params["sni"] = it }
        parseInsecure(q, params)
        return ProxyNode(
            protocol = "trojan", name = remark, host = host, port = port, params = params
        )
    }

    /** hysteria2://（兼容 hy2://）：userinfo 整体即密码；mport 记为 hopPort（端口跳跃） */
    private fun parseHysteria2(link: String): ProxyNode? {
        val (_, body, remark) = splitLink(link) ?: return null
        val atIdx = body.lastIndexOf('@')
        if (atIdx < 0) return null
        // hy2 的 userinfo 整体即密码（如 u-389-h:xxx 含冒号也是密码的一部分，
        // 服务端做字符串比对；FlClash/mihomo 同样取完整 userinfo）
        val password = urlDecode(body.substring(0, atIdx))
        if (password.isEmpty()) return null
        val (host, port, query) = splitAuthority(body) ?: return null
        val q = parseQuery(query)
        val params = mutableMapOf("password" to password)
        q["sni"]?.takeIf { it.isNotEmpty() }?.let { params["sni"] = it }
        q["obfs"]?.takeIf { it.isNotEmpty() }?.let { params["obfs"] = it }
        (q["obfs-password"] ?: q["obfsPassword"])?.takeIf { it.isNotEmpty() }
            ?.let { params["obfsPassword"] = it }
        q["mport"]?.takeIf { it.isNotEmpty() }?.let { params["hopPort"] = it }
        // 带宽参数：透传给 sing-box 启用 Brutal CC（不解析就只能用慢速 BBR）
        q["upmbps"]?.takeIf { it.isNotEmpty() }?.let { params["upMbps"] = it }
        q["downmbps"]?.takeIf { it.isNotEmpty() }?.let { params["downMbps"] = it }
        (q["up"] ?: q["bandwidth_up"])?.takeIf { it.isNotEmpty() }?.let { params["upMbps"] = it }
        (q["down"] ?: q["bandwidth_down"])?.takeIf { it.isNotEmpty() }?.let { params["downMbps"] = it }
        parseInsecure(q, params)
        return ProxyNode(
            protocol = "hysteria2", name = remark, host = host, port = port, params = params
        )
    }

    /** tuic://uuid:password@host:port?sni= */
    private fun parseTuic(link: String): ProxyNode? {
        val (_, body, remark) = splitLink(link) ?: return null
        val atIdx = body.lastIndexOf('@')
        if (atIdx < 0) return null
        val userinfo = urlDecode(body.substring(0, atIdx))
        val c = userinfo.indexOf(':')
        if (c < 0) return null
        val uuid = userinfo.substring(0, c)
        val password = userinfo.substring(c + 1)
        if (uuid.isEmpty()) return null
        val (host, port, query) = splitAuthority(body) ?: return null
        val q = parseQuery(query)
        val params = mutableMapOf("uuid" to uuid, "password" to password)
        q["sni"]?.takeIf { it.isNotEmpty() }?.let { params["sni"] = it }
        parseInsecure(q, params)
        return ProxyNode(
            protocol = "tuic", name = remark, host = host, port = port, params = params
        )
    }

    /** anytls://password@host:port?sni= */
    private fun parseAnyTls(link: String): ProxyNode? {
        val (_, body, remark) = splitLink(link) ?: return null
        val atIdx = body.lastIndexOf('@')
        if (atIdx < 0) return null
        val password = urlDecode(body.substring(0, atIdx))
        if (password.isEmpty()) return null
        val (host, port, query) = splitAuthority(body) ?: return null
        val q = parseQuery(query)
        val params = mutableMapOf("password" to password)
        q["sni"]?.takeIf { it.isNotEmpty() }?.let { params["sni"] = it }
        parseInsecure(q, params)
        return ProxyNode(
            protocol = "anytls", name = remark, host = host, port = port, params = params
        )
    }

    /**
     * http(s)://[username:password@]host:port
     * 无 userinfo 时为无认证代理。
     */
    private fun parseHttp(link: String): ProxyNode? {
        val (scheme, body, remark) = splitLink(link) ?: return null
        val atIdx = body.lastIndexOf('@')
        val userinfo = if (atIdx >= 0) urlDecode(body.substring(0, atIdx)) else ""
        val (host, port, _) = splitAuthority(body) ?: return null
        val params = mutableMapOf<String, String>()
        if (userinfo.isNotEmpty()) {
            val c = userinfo.indexOf(':')
            if (c >= 0) {
                params["username"] = userinfo.substring(0, c)
                params["password"] = userinfo.substring(c + 1)
            } else {
                params["username"] = userinfo
            }
        }
        return ProxyNode(
            protocol = scheme, name = remark, host = host, port = port, params = params
        )
    }

    /**
     * socks5://[username:password@]host:port（兼容 socks:// 前缀）。
     * 无 userinfo 时为无认证代理。
     */
    private fun parseSocks5(link: String): ProxyNode? {
        val (_, body, remark) = splitLink(link) ?: return null
        val atIdx = body.lastIndexOf('@')
        val userinfo = if (atIdx >= 0) urlDecode(body.substring(0, atIdx)) else ""
        val (host, port, _) = splitAuthority(body) ?: return null
        val params = mutableMapOf<String, String>()
        if (userinfo.isNotEmpty()) {
            val c = userinfo.indexOf(':')
            if (c >= 0) {
                params["username"] = userinfo.substring(0, c)
                params["password"] = userinfo.substring(c + 1)
            } else {
                params["username"] = userinfo
            }
        }
        return ProxyNode(
            protocol = "socks5", name = remark, host = host, port = port, params = params
        )
    }

    /**
     * ssh://username[:password]@host:port[?private-key=...]
     * - password 认证：ssh://user:pass@host:port
     * - 私钥认证：ssh://user@host:port?private-key=<URL编码或base64的私钥文本>
     * 端口缺省 22。
     */
    private fun parseSsh(link: String): ProxyNode? {
        val (_, body, remark) = splitLink(link) ?: return null
        val atIdx = body.lastIndexOf('@')
        if (atIdx < 0) return null
        val userinfo = urlDecode(body.substring(0, atIdx))
        val c = userinfo.indexOf(':')
        val username = if (c >= 0) userinfo.substring(0, c) else userinfo
        val password = if (c >= 0) userinfo.substring(c + 1) else ""
        if (username.isEmpty()) return null
        // 端口缺省 22
        val rest = body.substring(atIdx + 1)
        val hp = splitAuthority(rest)
        val (host, port) = if (hp != null) {
            hp.first to hp.second
        } else {
            // 无端口：host[?query]，兼容 IPv6 [::1] 写法
            val qIdx = rest.indexOf('?')
            var h = if (qIdx >= 0) rest.substring(0, qIdx) else rest
            if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
            if (h.isEmpty()) return null
            h to 22
        }
        val query = rest.substringAfter('?', "")
        val q = parseQuery(query)
        val params = mutableMapOf("username" to username)
        if (password.isNotEmpty()) params["password"] = password
        (q["private-key"] ?: q["privateKey"])?.takeIf { it.isNotEmpty() }
            ?.let { params["privateKey"] = it }
        return ProxyNode(
            protocol = "ssh", name = remark, host = host, port = port, params = params
        )
    }
}
