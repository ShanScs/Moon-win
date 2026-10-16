package com.muse.moonpc.core

import com.muse.moonpc.data.CompiledRuleSet
import com.muse.moonpc.data.DnsDomainRule
import com.muse.moonpc.data.PolicyGroup
import com.muse.moonpc.data.PolicyStore
import com.muse.moonpc.data.ProxyNode
import com.muse.moonpc.data.RouteRule
import org.json.JSONArray
import org.json.JSONObject

/**
 * sing-box JSON 配置生成器（全新实现，不复用 Android 代码）。
 *
 * 生成最小可用配置：
 * - inbounds：mixed 入站（127.0.0.1:7890），供本机应用走代理；
 *   TUN 模式下追加 tun 入站（auto_route，需管理员权限）
 * - outbounds：每个节点一个出站 + direct + PROXY selector
 * - route：final 走 PROXY（无节点时走 direct）
 * - experimental.clash_api：127.0.0.1:9090，供延迟测试/策略组切换
 */
object ConfigGenerator {

    /** 代理模式（对标 Surge Mac 的模式切换） */
    enum class ProxyMode(val label: String) {
        /** TUN 全局接管（需管理员权限） */
        TUN("TUN"),
        /** 全局代理：全部流量走代理 */
        GLOBAL_PROXY("全局代理"),
        /** 自动分流：按规则分流 */
        AUTO_ROUTE("自动分流"),
        /** 全局直连：全部流量直连 */
        GLOBAL_DIRECT("全局直连"),
    }

    /** 生成选项 */
    data class Options(
        val mixedPort: Int = 7890,
        val clashPort: Int = 9090,
        val logLevel: String = "info",
        val mode: ProxyMode = ProxyMode.AUTO_ROUTE,
        // --- TUN ---
        val tunInterfaceName: String = "moon-tun",
        /**
         * TUN 协议栈：
         * - gvisor：用户态协议栈，免驱动（默认，推荐）
         * - system：系统协议栈；Windows 上走 Wintun 驱动（需安装 wintun.dll）
         */
        val tunStack: String = "gvisor",
        val tunMtu: Int = 9000,
        // --- DNS 套件（P2） ---
        /** DNS 总开关；关闭时按旧逻辑（仅 TUN 模式内置 DNS） */
        val dnsEnabled: Boolean = false,
        /** 自定义 DNS 服务器：IP / tls:// / DoH URL */
        val dnsServers: List<String> = emptyList(),
        /** hosts 映射：域名 -> IP */
        val dnsHosts: Map<String, String> = emptyMap(),
        /** 分域名 DNS 规则 */
        val dnsDomainRules: List<DnsDomainRule> = emptyList(),
    )

    /**
     * 生成 sing-box 配置 JSON 字符串（格式化 2 空格）。
     * @param nodes 节点列表
     * @param selectedId 默认选中的节点 id；为空或找不到时选第一个
     * @param policyGroups 自建策略组（PolicyStore.effectiveCustomGroups()）；
     *   每个组生成一个 selector / urltest 出站，并挂到 PROXY 的候选项里
     * @param proxyOutbounds 主 PROXY selector 的候选出站（PolicyStore.proxyOutboundTags()）；
     *   为空时回退旧行为（自建组 + 全部节点 + direct）
     * @param routeRules 单条分流规则（RuleStore.rules）
     * @param compiledRuleSets 已解析的远端规则集（RuleStore.compiledRuleSets()）
     */
    fun generate(
        nodes: List<ProxyNode>,
        selectedId: String?,
        opts: Options = Options(),
        policyGroups: List<PolicyGroup> = emptyList(),
        proxyOutbounds: List<String> = emptyList(),
        routeRules: List<RouteRule> = emptyList(),
        compiledRuleSets: List<CompiledRuleSet> = emptyList(),
    ): String {
        val idToTag = nodeIdToTag(nodes)
        val nodeTags = nodes.mapIndexed { i, n -> nodeTag(i, n.id) }
        val outbounds = JSONArray()

        nodes.forEachIndexed { i, n ->
            outbounds.put(buildOutbound(n, nodeTags[i]))
        }
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        outbounds.put(JSONObject().put("type", "block").put("tag", "block"))

        // 自建策略组出站（selector / urltest）；无有效成员的组跳过
        val groupTags = mutableListOf<String>()
        policyGroups.forEach { g ->
            val tag = groupTag(g)
            if (tag == "PROXY" || tag == "direct" || tag == "block" || tag in groupTags) return@forEach
            val ob = buildGroupOutbound(g, tag, idToTag) ?: return@forEach
            groupTags.add(tag)
            outbounds.put(ob)
        }

        // PROXY selector：默认用传入的候选列表；回退旧行为
        val proxyMembers = if (proxyOutbounds.isNotEmpty()) proxyOutbounds
        else (groupTags + nodeTags + "direct").distinct()
        val selIdx = nodes.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
        val selector = JSONObject()
            .put("type", "selector")
            .put("tag", "PROXY")
            .put("outbounds", JSONArray(proxyMembers))
        if (nodeTags.isNotEmpty()) {
            val defTag = nodeTags[selIdx.coerceIn(nodeTags.indices)]
            if (defTag in proxyMembers) selector.put("default", defTag)
            else proxyMembers.firstOrNull()?.let { selector.put("default", it) }
        }
        outbounds.put(selector)

        // 全局代理/全局直连：不带规则；自动分流/TUN：带规则
        val useRules = opts.mode == ProxyMode.AUTO_ROUTE || opts.mode == ProxyMode.TUN
        val rulesJson = buildRouteRules(routeRules, compiledRuleSets, groupTags)
        val routeFinal = when (opts.mode) {
            ProxyMode.GLOBAL_DIRECT -> "direct"
            else -> if (nodeTags.isNotEmpty()) "PROXY" else "direct"
        }
        val route = JSONObject()
            .put("final", routeFinal)
            .put("auto_detect_interface", true)
        // sing-box 1.14 必填：出站域名解析器（替代旧 outbound DNS rule）。
        // 必须用直连 DNS，避免"解析代理服务器域名→走代理→又需解析"的循环依赖。
        val domainResolverTag = when {
            opts.dnsEnabled && opts.dnsServers.isNotEmpty() ->
                if (opts.mode == ProxyMode.TUN && opts.dnsServers.size > 1) "dns-1" else "dns-0"
            opts.mode == ProxyMode.TUN -> "local-dns"
            else -> null
        }
        if (domainResolverTag != null) route.put("default_domain_resolver", domainResolverTag)
        if (useRules && rulesJson.array.length() > 0) route.put("rules", rulesJson.array)
        if (useRules && rulesJson.usesGeoip) {
            route.put(
                "geoip", JSONObject()
                    .put("path", "geoip.db")
                    .put("download_url", "https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db"),
            )
        }

        val config = JSONObject()
            .put("log", JSONObject().put("level", opts.logLevel))
            .put("inbounds", buildInbounds(opts))
            .put("outbounds", outbounds)
            .put("route", route)
            .put(
                "experimental", JSONObject()
                    .put(
                        "clash_api", JSONObject()
                            .put("external_controller", "127.0.0.1:${opts.clashPort}"),
                    ),
            )
        // DNS：自定义 DNS 套件优先；TUN 模式无自定义时用内置防泄漏 DNS
        val dnsJson = buildDns(opts)
        if (dnsJson != null) config.put("dns", dnsJson)
        return config.toString(2)
    }

    // ---------- tag 映射 ----------

    /** 单个节点的 sing-box 出站 tag；规则与 generate() 内部一致 */
    fun nodeTag(index: Int, id: String): String = "node-$index-${id.take(8)}"

    /** 全部节点的 id -> tag 映射（按 nodes 列表顺序） */
    fun nodeIdToTag(nodes: List<ProxyNode>): Map<String, String> =
        nodes.mapIndexed { i, n -> n.id to nodeTag(i, n.id) }.toMap()

    /** 策略组出站 tag：直接用组名（sing-box 允许任意字符串 tag） */
    fun groupTag(g: PolicyGroup): String = g.name.ifBlank { "group-${g.id.take(8)}" }

    /**
     * 策略组出站：
     * - urltest：自动测速选择延迟最低（排除 direct/block，避免自动选中直连/拦截）
     * - fallback：sing-box 无原生 fallback，用高 tolerance 的 urltest 近似故障转移语义
     *   （tolerance=3000：当前出口未严重劣化/失败时不切换）
     * - loadbalance：sing-box 无原生负载均衡，降级为 urltest；
     *   真负载均衡需等 sing-box 支持或更换内核
     * - selector：手动选择；memberTags 为空时回退用 nodes 推导并追加 direct
     * 无有效成员时返回 null（调用方跳过该组）。
     */
    private fun buildGroupOutbound(
        g: PolicyGroup,
        tag: String,
        idToTag: Map<String, String>,
    ): JSONObject? {
        val memberTags = if (g.memberTags.isNotEmpty()) g.memberTags
        else g.nodes.mapNotNull { idToTag[it] }
        if (memberTags.isEmpty()) return null
        if (g.kind == PolicyStore.KIND_URLTEST ||
            g.kind == PolicyStore.KIND_FALLBACK ||
            g.kind == PolicyStore.KIND_LOADBALANCE
        ) {
            val testable = memberTags.filter { it != "direct" && it != "block" }
            if (testable.isEmpty()) return null
            // fallback 用高 tolerance 实现"粘性"：只有当前出口失败/严重劣化才切换
            val tolerance = if (g.kind == PolicyStore.KIND_FALLBACK) 3000 else 50
            return JSONObject()
                .put("type", "urltest")
                .put("tag", tag)
                .put("outbounds", JSONArray(testable))
                .put(
                    "url",
                    g.testUrl.ifBlank { "https://www.gstatic.com/generate_204" },
                )
                .put("interval", "10m")
                .put("tolerance", tolerance)
        }
        val all = if ("direct" in memberTags) memberTags else memberTags + "direct"
        val o = JSONObject()
            .put("type", "selector")
            .put("tag", tag)
            .put("outbounds", JSONArray(all))
        val defTag = idToTag[g.selected]?.takeIf { it in all }
            ?: all.firstOrNull()
        if (defTag != null) o.put("default", defTag)
        return o
    }

    /** 动作名 → sing-box 出站 tag：proxy/direct/reject 或自建组 tag（不存在则回退 PROXY） */
    private fun actionOutbound(action: String, groupTags: List<String>): String = when (action) {
        RouteRule.ACTION_PROXY -> "PROXY"
        RouteRule.ACTION_DIRECT -> "direct"
        RouteRule.ACTION_REJECT -> "block"
        else -> if (action in groupTags) action else "PROXY"
    }

    private class RulesJson(val array: JSONArray, val usesGeoip: Boolean)

    /**
     * 分流规则 → sing-box route.rules。
     * 顺序：远端规则集在前，单条规则在后（sing-box 按顺序匹配首条命中）。
     */
    private fun buildRouteRules(
        routeRules: List<RouteRule>,
        compiledRuleSets: List<CompiledRuleSet>,
        groupTags: List<String>,
    ): RulesJson {
        val arr = JSONArray()
        var usesGeoip = false
        compiledRuleSets.forEach { cs ->
            val o = JSONObject()
            if (cs.domains.isNotEmpty()) o.put("domain", JSONArray(cs.domains))
            if (cs.domainSuffixes.isNotEmpty()) o.put("domain_suffix", JSONArray(cs.domainSuffixes))
            if (cs.keywords.isNotEmpty()) o.put("domain_keyword", JSONArray(cs.keywords))
            if (cs.ipCidrs.isNotEmpty()) o.put("ip_cidr", JSONArray(cs.ipCidrs))
            if (cs.geoips.isNotEmpty()) {
                o.put("geoip", JSONArray(cs.geoips.map { it.lowercase() }))
                usesGeoip = true
            }
            if (o.length() == 0) return@forEach
            o.put("outbound", actionOutbound(cs.action, groupTags))
            arr.put(o)
        }
        routeRules.forEach { r ->
            val o = JSONObject()
            when (r.type) {
                RouteRule.TYPE_DOMAIN -> o.put("domain", JSONArray(listOf(r.value)))
                RouteRule.TYPE_DOMAIN_SUFFIX -> o.put("domain_suffix", JSONArray(listOf(r.value)))
                RouteRule.TYPE_DOMAIN_KEYWORD -> o.put("domain_keyword", JSONArray(listOf(r.value)))
                RouteRule.TYPE_IP_CIDR -> o.put("ip_cidr", JSONArray(listOf(r.value)))
                RouteRule.TYPE_GEOIP -> {
                    o.put("geoip", JSONArray(listOf(r.value.lowercase())))
                    usesGeoip = true
                }
                RouteRule.TYPE_PORT -> {
                    val p = r.value.toIntOrNull() ?: return@forEach
                    o.put("port", p)
                }
                else -> return@forEach
            }
            o.put("outbound", actionOutbound(r.action, groupTags))
            arr.put(o)
        }
        return RulesJson(arr, usesGeoip)
    }

    /** 入站：mixed 恒有；TUN 模式追加 tun 入站 */
    private fun buildInbounds(opts: Options): JSONArray {
        val arr = JSONArray().put(
            JSONObject()
                .put("type", "mixed")
                .put("tag", "mixed-in")
                .put("listen", "127.0.0.1")
                .put("listen_port", opts.mixedPort),
        )
        if (opts.mode == ProxyMode.TUN) {
            arr.put(
                JSONObject()
                    .put("type", "tun")
                    .put("tag", "tun-in")
                    .put("interface_name", opts.tunInterfaceName)
                    .put("mtu", opts.tunMtu)
                    .put("address", JSONArray().put("172.19.0.1/30"))
                    .put("auto_route", true)
                    .put("strict_route", true)
                    .put("stack", opts.tunStack),
            )
        }
        return arr
    }

    /**
     * DNS 配置（P2）：
     * - 自定义 DNS 套件启用时：按用户配置生成 servers/rules/hosts；
     *   TUN 模式下首个服务器走 PROXY（防 DNS 泄漏），其余走 direct
     * - 未启用但 TUN 模式：内置默认（全部走代理 DNS，防污染/劫持）
     * - 其他情况：返回 null（不写 dns 节）
     */
    private fun buildDns(opts: Options): JSONObject? {
        if (opts.dnsEnabled && opts.dnsServers.isNotEmpty()) {
            val servers = JSONArray()
            opts.dnsServers.forEachIndexed { i, addr ->
                val o = dnsServerObject("dns-$i", addr)
                // TUN 模式：首个 DNS 走代理防泄漏；其余不写 detour（默认直连）。
                // 注意：sing-box 1.12+ 禁止 detour:"direct"（空 direct 出站会 FATAL），省略即直连。
                if (opts.mode == ProxyMode.TUN && i == 0) {
                    o.put("detour", "PROXY")
                }
                servers.put(o)
            }
            // 分域名规则可能引用用户填写的任意服务器地址：为其建 tag
            val extraTags = mutableMapOf<String, String>()
            opts.dnsDomainRules.forEach { r ->
                val addr = r.serverAddress
                if (addr.isNotBlank() && addr !in opts.dnsServers && addr !in extraTags) {
                    val tag = "dns-extra-${extraTags.size}"
                    extraTags[addr] = tag
                    servers.put(dnsServerObject(tag, addr))
                }
            }
            val dns = JSONObject()
                .put("servers", servers)
                .put("final", "dns-0")
                .put("strategy", "prefer_ipv4")
            val rules = JSONArray()
            opts.dnsDomainRules.forEach { r ->
                val tag = when {
                    r.serverAddress in opts.dnsServers ->
                        "dns-${opts.dnsServers.indexOf(r.serverAddress)}"
                    r.serverAddress in extraTags -> extraTags[r.serverAddress]!!
                    else -> return@forEach
                }
                rules.put(
                    JSONObject()
                        .put("domain_suffix", JSONArray(listOf(r.domainSuffix)))
                        .put("server", tag),
                )
            }
            if (rules.length() > 0) dns.put("rules", rules)
            if (opts.dnsHosts.isNotEmpty()) {
                val hosts = JSONObject()
                opts.dnsHosts.forEach { (d, ip) -> hosts.put(d, ip) }
                dns.put("hosts", hosts)
            }
            return dns
        }
        // TUN 模式内置 DNS：全部走代理 DNS（防污染/劫持），直连兜底
        if (opts.mode == ProxyMode.TUN) {
            return JSONObject()
                .put(
                    "servers", JSONArray()
                        .put(
                            dnsServerObject("proxy-dns", "tls://8.8.8.8")
                                .put("detour", "PROXY"),
                        )
                        .put(
                            dnsServerObject("local-dns", "223.5.5.5"),
                        ),
                )
                .put("final", "proxy-dns")
                .put("strategy", "prefer_ipv4")
        }
        return null
    }

    /**
     * sing-box 1.14 DNS 服务器对象（新格式）：
     * {"type": "udp|tcp|tls|https|quic", "tag": "...", "server": "...", "server_port": ...}
     * 旧的 {"address": "..."} 格式在 1.14 已移除。
     */
    private fun dnsServerObject(tag: String, addr: String): JSONObject {
        val o = JSONObject().put("tag", tag)
        val a = addr.trim()
        when {
            a.startsWith("https://", ignoreCase = true) -> {
                o.put("type", "https")
                // https://host[:port][/path] -> server=host, server_port=port
                val withoutScheme = a.substringAfter("://")
                val hostPort = withoutScheme.substringBefore("/")
                val host = hostPort.substringBefore(":")
                val port = hostPort.substringAfter(":", "").toIntOrNull()
                o.put("server", host)
                if (port != null) o.put("server_port", port)
                // 保留完整 URL 的 path（DoH 需要）
                if ("/" in withoutScheme) o.put("path", "/" + withoutScheme.substringAfter("/"))
            }
            a.startsWith("tls://", ignoreCase = true) -> {
                o.put("type", "tls")
                val hostPort = a.substringAfter("://").substringBefore("/")
                o.put("server", hostPort.substringBefore(":"))
                hostPort.substringAfter(":", "").toIntOrNull()?.let { o.put("server_port", it) }
            }
            a.startsWith("tcp://", ignoreCase = true) -> {
                o.put("type", "tcp")
                val hostPort = a.substringAfter("://").substringBefore("/")
                o.put("server", hostPort.substringBefore(":"))
                hostPort.substringAfter(":", "").toIntOrNull()?.let { o.put("server_port", it) }
            }
            a.startsWith("quic://", ignoreCase = true) -> {
                o.put("type", "quic")
                val hostPort = a.substringAfter("://").substringBefore("/")
                o.put("server", hostPort.substringBefore(":"))
                hostPort.substringAfter(":", "").toIntOrNull()?.let { o.put("server_port", it) }
            }
            else -> {
                // 裸 IP 或 hostname：默认 UDP
                o.put("type", "udp")
                // 支持 "1.2.3.4:53" 形式
                val host = a.substringBefore(":")
                val port = a.substringAfter(":", "").toIntOrNull()
                o.put("server", host)
                if (port != null) o.put("server_port", port)
            }
        }
        return o
    }

    // ---------- 出站构造 ----------

    private fun buildOutbound(n: ProxyNode, tag: String): JSONObject {
        val base = JSONObject()
            .put("tag", tag)
            .put("server", n.host)
            .put("server_port", n.port)
        val tlsOn = n.params["tls"] == "true"
        val sni = n.params["sni"]?.takeIf { it.isNotEmpty() } ?: n.host
        when (n.protocol.lowercase()) {
            "shadowsocks" -> {
                base.put("type", "shadowsocks")
                base.put("method", n.params["method"] ?: "aes-256-gcm")
                base.put("password", n.params["password"] ?: "")
            }
            "vless" -> {
                base.put("type", "vless")
                base.put("uuid", n.params["uuid"] ?: "")
                if (tlsOn) {
                    // xtls flow 仅在 TLS 开启时合法，无 TLS 带 flow 会导致配置校验失败
                    n.params["flow"]?.takeIf { it.isNotEmpty() }?.let { base.put("flow", it) }
                    base.put("tls", tlsObj(sni, n))
                }
                applyTransport(base, n)
            }
            "vmess" -> {
                base.put("type", "vmess")
                base.put("uuid", n.params["uuid"] ?: "")
                base.put("alter_id", n.params["alterId"]?.toIntOrNull() ?: 0)
                base.put("security", n.params["encryption"] ?: "auto")
                if (tlsOn) base.put("tls", tlsObj(sni, n))
                applyTransport(base, n)
            }
            "trojan" -> {
                base.put("type", "trojan")
                base.put("password", n.params["password"] ?: "")
                base.put("tls", tlsObj(sni, n))
            }
            "hysteria2" -> {
                base.put("type", "hysteria2")
                base.put("password", n.params["password"] ?: "")
                base.put("tls", tlsObj(sni, n))
                n.params["obfs"]?.takeIf { it.isNotEmpty() }?.let {
                    base.put(
                        "obfs", JSONObject().put("type", it)
                            .put("password", n.params["obfsPassword"] ?: ""),
                    )
                }
                n.params["upMbps"]?.toIntOrNull()?.let { base.put("up_mbps", it) }
                n.params["downMbps"]?.toIntOrNull()?.let { base.put("down_mbps", it) }
            }
            "tuic" -> {
                base.put("type", "tuic")
                base.put("uuid", n.params["uuid"] ?: "")
                base.put("password", n.params["password"] ?: "")
                base.put("tls", tlsObj(sni, n))
            }
            "anytls" -> {
                base.put("type", "anytls")
                base.put("password", n.params["password"] ?: "")
                base.put("tls", tlsObj(sni, n))
            }
            "http", "https" -> {
                base.put("type", "http")
                n.params["username"]?.takeIf { it.isNotEmpty() }?.let { base.put("username", it) }
                n.params["password"]?.takeIf { it.isNotEmpty() }?.let { base.put("password", it) }
                // https 代理 = HTTP CONNECT over TLS
                if (n.protocol.lowercase() == "https") {
                    base.put("tls", JSONObject().put("enabled", true).put("server_name", sni))
                }
            }
            "socks5" -> {
                base.put("type", "socks")
                base.put("version", "5")
                n.params["username"]?.takeIf { it.isNotEmpty() }?.let { base.put("username", it) }
                n.params["password"]?.takeIf { it.isNotEmpty() }?.let { base.put("password", it) }
            }
            "ssh" -> {
                base.put("type", "ssh")
                base.put("user", n.params["username"] ?: "")
                n.params["password"]?.takeIf { it.isNotEmpty() }?.let { base.put("password", it) }
                n.params["privateKey"]?.takeIf { it.isNotEmpty() }?.let { base.put("private_key", it) }
            }
            else -> base.put("type", "direct")
        }
        return base
    }

    /**
     * TLS 对象：uTLS 指纹（fp 参数）、REALITY（security=reality 时）、
     * 跳过证书校验（insecure=1 时）统一在此处理。
     */
    private fun tlsObj(sni: String, n: ProxyNode? = null): JSONObject {
        val o = JSONObject().put("enabled", true).put("server_name", sni)
        if (n != null) {
            n.params["fingerprint"]?.takeIf { it.isNotEmpty() }?.let { fp ->
                o.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
            }
            if (n.params["reality"] == "1") {
                val r = JSONObject().put("enabled", true)
                n.params["realityPublicKey"]?.takeIf { it.isNotEmpty() }
                    ?.let { r.put("public_key", it) }
                n.params["realityShortId"]?.takeIf { it.isNotEmpty() }
                    ?.let { r.put("short_id", it) }
                o.put("reality", r)
            }
            if (n.params["insecure"] == "1") o.put("insecure", true)
        }
        return o
    }

    /** ws / grpc 传输层 */
    private fun applyTransport(base: JSONObject, n: ProxyNode) {
        when (n.params["transport"]?.lowercase()) {
            "ws", "websocket" -> base.put(
                "transport", JSONObject()
                    .put("type", "ws")
                    .put("path", n.params["wsPath"] ?: "/")
                    .put(
                        "headers",
                        JSONObject().put(
                            "Host",
                            n.params["wsHost"]?.takeIf { it.isNotEmpty() } ?: n.host,
                        ),
                    ),
            )
            "grpc" -> base.put(
                "transport", JSONObject()
                    .put("type", "grpc")
                    .put("service_name", n.params["grpcService"] ?: ""),
            )
        }
    }
}
