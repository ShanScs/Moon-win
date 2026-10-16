package com.muse.moonpc.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.muse.moonpc.core.SecureFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * DNS 服务器条目：address 可以是 IP（8.8.8.8 / tls://8.8.8.8）
 * 或 DoH URL（https://dns.google/dns-query）
 */
data class DnsServerEntry(
    val id: String = UUID.randomUUID().toString(),
    var address: String = "",
)

/** hosts 映射：域名 -> IP */
data class HostEntry(
    val id: String = UUID.randomUUID().toString(),
    var domain: String = "",
    var ip: String = "",
)

/** 分域名 DNS：域名后缀走指定 DNS 服务器 */
data class DnsDomainRule(
    val id: String = UUID.randomUUID().toString(),
    var domainSuffix: String = "",
    /** 引用 DnsServerEntry.address */
    var serverAddress: String = "",
)

/**
 * DNS 套件设置（P2，全新实现，对标 Surge DNS）：
 * - 自定义 DNS 服务器（IP / TLS / DoH）
 * - hosts 映射（等价 /etc/hosts）
 * - 分域名 DNS 服务器
 * - 持久化 ~/.moon-pc/dns.json
 */
class DnsSettings(
    private val dir: File = File(System.getProperty("user.home"), ".moon-pc"),
) {
    var enabled by mutableStateOf(true)

    val servers = mutableStateListOf<DnsServerEntry>()
    val hosts = mutableStateListOf<HostEntry>()
    val domainRules = mutableStateListOf<DnsDomainRule>()

    private val file: File get() = File(dir, "dns.json")

    init {
        load()
        if (servers.isEmpty()) {
            // 默认 DNS：Google + Cloudflare
            servers.add(DnsServerEntry(address = "8.8.8.8"))
            servers.add(DnsServerEntry(address = "1.1.1.1"))
            save()
        }
    }

    // ---------------- 服务器 CRUD ----------------

    fun addServer(address: String): DnsServerEntry? {
        val a = address.trim()
        if (a.isEmpty() || a.contains(" ")) return null
        val e = DnsServerEntry(address = a)
        servers.add(e)
        save()
        return e
    }

    fun updateServer(id: String, address: String): Boolean {
        val i = servers.indexOfFirst { it.id == id }
        if (i < 0) return false
        val a = address.trim()
        if (a.isEmpty() || a.contains(" ")) return false
        servers[i] = servers[i].copy(address = a)
        save()
        return true
    }

    fun removeServer(id: String) {
        servers.removeAll { it.id == id }
        save()
    }

    fun moveServer(id: String, delta: Int): Boolean {
        val i = servers.indexOfFirst { it.id == id }
        if (i < 0) return false
        val to = (i + delta).coerceIn(0, servers.size - 1)
        if (to == i) return false
        val e = servers.removeAt(i)
        servers.add(to, e)
        save()
        return true
    }

    // ---------------- hosts CRUD ----------------

    fun addHost(domain: String, ip: String): HostEntry? {
        val d = domain.trim().lowercase()
        val p = ip.trim()
        if (d.isEmpty() || p.isEmpty() || d.contains(" ") || p.contains(" ")) return null
        val e = HostEntry(domain = d, ip = p)
        hosts.add(e)
        save()
        return e
    }

    fun removeHost(id: String) {
        hosts.removeAll { it.id == id }
        save()
    }

    // ---------------- 分域名规则 CRUD ----------------

    fun addDomainRule(domainSuffix: String, serverAddress: String): DnsDomainRule? {
        val d = domainSuffix.trim().trim('.').lowercase()
        val s = serverAddress.trim()
        if (d.isEmpty() || s.isEmpty() || d.contains(" ")) return null
        val e = DnsDomainRule(domainSuffix = d, serverAddress = s)
        domainRules.add(e)
        save()
        return e
    }

    fun removeDomainRule(id: String) {
        domainRules.removeAll { it.id == id }
        save()
    }

    // ---------------- 持久化 ----------------

    fun save() {
        runCatching {
            dir.mkdirs()
            val o = JSONObject()
            o.put("enabled", enabled)
            o.put("servers", JSONArray().apply {
                servers.forEach { put(JSONObject().put("id", it.id).put("address", it.address)) }
            })
            o.put("hosts", JSONArray().apply {
                hosts.forEach { put(JSONObject().put("id", it.id).put("domain", it.domain).put("ip", it.ip)) }
            })
            o.put("domainRules", JSONArray().apply {
                domainRules.forEach {
                    put(
                        JSONObject().put("id", it.id)
                            .put("domainSuffix", it.domainSuffix)
                            .put("serverAddress", it.serverAddress),
                    )
                }
            })
            SecureFile.writeText(file, o.toString(2))
        }
    }

    private fun load() {
        runCatching {
            val f = file
            if (!f.isFile) return
            val o = JSONObject(f.readText())
            enabled = o.optBoolean("enabled", true)
            o.optJSONArray("servers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val jo = arr.getJSONObject(i)
                    val a = jo.optString("address")
                    if (a.isNotBlank()) servers.add(
                        DnsServerEntry(
                            id = jo.optString("id").ifBlank { UUID.randomUUID().toString() },
                            address = a,
                        ),
                    )
                }
            }
            o.optJSONArray("hosts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val jo = arr.getJSONObject(i)
                    val d = jo.optString("domain")
                    val p = jo.optString("ip")
                    if (d.isNotBlank() && p.isNotBlank()) hosts.add(
                        HostEntry(
                            id = jo.optString("id").ifBlank { UUID.randomUUID().toString() },
                            domain = d,
                            ip = p,
                        ),
                    )
                }
            }
            o.optJSONArray("domainRules")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val jo = arr.getJSONObject(i)
                    val d = jo.optString("domainSuffix")
                    val s = jo.optString("serverAddress")
                    if (d.isNotBlank() && s.isNotBlank()) domainRules.add(
                        DnsDomainRule(
                            id = jo.optString("id").ifBlank { UUID.randomUUID().toString() },
                            domainSuffix = d,
                            serverAddress = s,
                        ),
                    )
                }
            }
        }
    }
}
