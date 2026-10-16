package com.muse.moonpc.data

import java.util.UUID

/** 代理节点：字段为通用子集，协议特有参数放在 params 里 */
data class ProxyNode(
    val id: String = UUID.randomUUID().toString(),
    var protocol: String = "shadowsocks",
    var name: String = "",
    var host: String = "",
    var port: Int = 0,
    var params: MutableMap<String, String> = mutableMapOf(),
    /** 所属订阅 id；"" 视为本地分组 */
    var subId: String = ""
)

/** 订阅：节点按订阅分组展示，一张卡片 = 一个订阅，点进去展开节点 */
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var url: String = "",
    /** 最后更新时间戳（毫秒）；0 = 未更新过 */
    var updatedAt: Long = 0L,
    /** 流量信息，如 "18.85GB/192.00GB"；"" 则显示更新时间 */
    var traffic: String = ""
)

/** 策略组：后续由 sing-box 的 selector 出站刷新（见 PolicyRepository） */
data class PolicyGroup(
    val id: String,
    val name: String,
    /** 国旗 emoji（如 "🇺🇸"）；为空则用 badgeText 文字徽标 */
    val flag: String = "",
    val badgeText: String? = null,
    val badgeColor: String? = null,
    /** 展开后的节点 id 列表（UI 展示/选中用） */
    val nodes: List<String> = emptyList(),
    var selected: String = "",
    /** "selector" 可手动切换；"urltest" 自动测速 */
    val kind: String = "selector",
    /** 自建策略组的 defId；内建组（全球策略）为 null */
    val defId: String? = null,
    /**
     * 解析后的 sing-box 出站标签（含节点 tag、direct/block、嵌套组 tag），
     * 供 ConfigGenerator 直接使用；为空时回退用 nodes 推导。
     */
    val memberTags: List<String> = emptyList(),
    /** urltest 测速地址 */
    val testUrl: String = "",
)

/** 自建策略组类型：sing-box 原生支持 select / url-test；
 * FALLBACK（故障转移）/ LOAD_BALANCE（负载均衡）为 UI 层类型，
 * 配置生成时映射为 urltest（sing-box 无原生 fallback/loadbalance，见 ConfigGenerator） */
enum class PolicyGroupType { SELECT, URL_TEST, FALLBACK, LOAD_BALANCE }

/**
 * 用户自建策略组定义（复刻 Android 版 PolicyGroupDef）。
 *
 * memberRefs 编码：
 * - "builtin:direct" / "builtin:reject"
 * - "group:{策略组defId}"
 * - "node:{节点id}"
 * - "filter:{筛选器id}"
 */
data class PolicyGroupDef(
    val id: String = UUID.randomUUID().toString(),
    val alias: String = "",
    val type: PolicyGroupType = PolicyGroupType.SELECT,
    val testUrl: String = DEFAULT_TEST_URL,
    val memberRefs: List<String> = emptyList(),
) {
    companion object {
        /** url-test 类型默认测速地址 */
        const val DEFAULT_TEST_URL = "http://www.gstatic.com/generate_204"

        /** 内建成員引用：直连出站 */
        const val REF_BUILTIN_DIRECT = "builtin:direct"

        /** 内建成員引用：拦截出站 */
        const val REF_BUILTIN_REJECT = "builtin:reject"

        /** 构造"引用另一个策略组"的成员引用 */
        fun groupRef(id: String) = "group:$id"

        /** 构造"引用单个节点"的成员引用 */
        fun nodeRef(id: String) = "node:$id"

        /** 构造"引用筛选器"的成员引用 */
        fun filterRef(id: String) = "filter:$id"
    }
}

/**
 * 节点筛选器：按节点名包含关键词（contains 语义，不区分大小写）
 * 动态匹配节点，节点增删时自动跟随。
 */
data class FilterDef(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val keyword: String = "",
)

/** 仪表页快捷方式卡片 */
data class ShortcutItem(
    val id: String,
    val title: String,
    val count: String?,
    val iconRes: Int
) {
    companion object {
        const val ID_REQUESTS = "requests"
        const val ID_DNS = "dns"
        const val ID_CONFIG = "config"
        const val ID_PLUGIN = "plugin"
        const val ID_NODES = "nodes"
        const val ID_RULES = "rules"
        val DEFAULT_ORDER = listOf(
            ID_REQUESTS, ID_DNS, ID_CONFIG, ID_PLUGIN, ID_NODES, ID_RULES
        )
    }
}

/** 配置页行 */
data class ConfigRow(
    val title: String,
    val badgeColor: String,
    val iconRes: Int,
    val action: RowAction
)

enum class RowAction {
    ALL_NODES, FILTER_NODES, TEST_URL, RULES,
    DNS_SERVER, DNS_HOSTS, REMOTE_RULESET, VIEW_CONFIG, EXPORT_LOG, APP_ICON
}

/** 用户自定义分流规则 */
data class CustomRule(
    val id: String,
    val type: String,   // "domain" 或 "ip"
    val value: String,  // 域名后缀 或 IP CIDR
    val action: String  // "proxy" / "direct" / "block"
)

/** 外部资源：远程规则集 URL */
data class RemoteRuleset(
    val id: String,
    val url: String,
    val action: String  // "proxy" / "direct" / "block"
)

/** 支持的协议：key 为 sing-box 出站 type 兼容名 */
val PROTOCOLS = listOf(
    "shadowsocks" to "Shadowsocks",
    "vless" to "VLESS",
    "vmess" to "VMess",
    "trojan" to "Trojan",
    "hysteria2" to "Hysteria2",
    "tuic" to "TUIC",
    "anytls" to "AnyTLS",
    "http" to "HTTP",
    "https" to "HTTPS",
    "socks5" to "SOCKS5",
    "ssh" to "SSH",
)

/** 协议 key -> 显示名（如 "vless" -> "VLESS"）；未知 key 原样返回 */
fun protocolDisplayName(key: String): String =
    PROTOCOLS.firstOrNull { it.first == key }?.second ?: key

/** 协议徽标缩写（如 "shadowsocks" -> "SS"），用于节点卡片小徽标 */
fun protocolBadge(key: String): String = when (key) {
    "shadowsocks" -> "SS"
    "vless" -> "VL"
    "vmess" -> "VM"
    "trojan" -> "TJ"
    "hysteria2" -> "H2"
    "tuic" -> "TU"
    "anytls" -> "AT"
    "http" -> "HT"
    "https" -> "HS"
    "socks5" -> "S5"
    "ssh" -> "SH"
    else -> key.take(2).uppercase()
}

/** 提取字符串中的第一个国旗 emoji（区域指示符对）；无则返回 "" */
fun extractFlag(text: String): String {
    // 注意：不能用 Regex("\uD83C[\uDDE6-\uDDFF]...") 写法——
    // Java 正则对 pattern 里的孤立代理项（lone surrogate）匹配行为不可靠，
    // 实测对 "🇺🇸" 返回 false。直接按 code point 扫描最稳妥。
    val cps = text.codePoints().toArray()
    for (i in 0 until cps.size - 1) {
        val a = cps[i]
        val b = cps[i + 1]
        if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
            return String(Character.toChars(a)) + String(Character.toChars(b))
        }
    }
    return ""
}

/** 按国家/地区关键词猜国旗；无匹配返回 "" */
fun guessFlag(name: String): String {
    val t = name.lowercase()
    val table = listOf(
        "美国" to "\uD83C\uDDFA\uD83C\uDDF8", "us-" to "\uD83C\uDDFA\uD83C\uDDF8",
        "新加坡" to "\uD83C\uDDF8\uD83C\uDDEC", "sg" to "\uD83C\uDDF8\uD83C\uDDEC",
        "香港" to "\uD83C\uDDED\uD83C\uDDF0", "hk" to "\uD83C\uDDED\uD83C\uDDF0",
        "台湾" to "\uD83C\uDDF9\uD83C\uDDFC", "tw" to "\uD83C\uDDF9\uD83C\uDDFC",
        "日本" to "\uD83C\uDDEF\uD83C\uDDF5", "jp" to "\uD83C\uDDEF\uD83C\uDDF5",
        "韩国" to "\uD83C\uDDF0\uD83C\uDDF7", "kr" to "\uD83C\uDDF0\uD83C\uDDF7",
        "澳洲" to "\uD83C\uDDE6\uD83C\uDDFA", "澳大利亚" to "\uD83C\uDDE6\uD83C\uDDFA",
        "英国" to "\uD83C\uDDEC\uD83C\uDDE7", "德国" to "\uD83C\uDDE9\uD83C\uDDEA",
        "法国" to "\uD83C\uDDEB\uD83C\uDDF7", "加拿大" to "\uD83C\uDDE8\uD83C\uDDE6",
        "荷兰" to "\uD83C\uDDF3\uD83C\uDDF1", "印度" to "\uD83C\uDDEE\uD83C\uDDF3",
    )
    for ((k, f) in table) if (t.contains(k)) return f
    return ""
}

/** 节点第二行协议文案：vmess+websocket / hysteria2 … */
fun nodeProtoLine(n: ProxyNode): String {
    val base = n.protocol.lowercase()
    val net = n.params["net"]?.lowercase() ?: n.params["type"]?.lowercase() ?: ""
    return if (net.isNotEmpty() && net != "tcp" && net != "none") "$base+$net" else base
}

/** 是否支持 UDP（显示 U 徽标） */
fun nodeSupportsUdp(n: ProxyNode): Boolean =
    n.protocol.lowercase() in setOf("hysteria2", "tuic", "socks5")
