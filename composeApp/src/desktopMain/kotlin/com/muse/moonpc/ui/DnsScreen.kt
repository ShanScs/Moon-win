package com.muse.moonpc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.moonpc.data.DnsSettings

/**
 * DNS 设置页（Surge 风格）：
 * - 总开关
 * - DNS 服务器列表（IP / tls:// / DoH URL），可排序
 * - hosts 映射（域名 -> IP）
 * - 分域名 DNS 服务器
 */
@Composable
fun DnsScreen(dns: DnsSettings) {
    var newServer by remember { mutableStateOf("") }
    var newHostDomain by remember { mutableStateOf("") }
    var newHostIp by remember { mutableStateOf("") }
    var newRuleDomain by remember { mutableStateOf("") }
    var newRuleServer by remember { mutableStateOf("") }

    SurgePage {
        PageHeader(
            title = "DNS",
            subtitle = "修改后重启代理生效",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", fontSize = 14.sp, color = MoonColors.TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = dns.enabled,
                        onCheckedChange = { dns.enabled = it; dns.save() },
                    )
                }
            }
        )

        // ---- DNS 服务器 ----
        MoonCard {
            Text(
                "DNS 服务器",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MoonColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "支持 IP（8.8.8.8）、TLS（tls://8.8.8.8）、DoH（https://dns.google/dns-query）",
                fontSize = 13.sp,
                color = MoonColors.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newServer,
                    onValueChange = { newServer = it },
                    label = { Text("添加服务器", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (dns.addServer(newServer) != null) newServer = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MoonColors.Accent),
                ) { Text("添加", fontSize = 14.sp) }
            }
            Spacer(Modifier.height(8.dp))
            dns.servers.toList().forEachIndexed { idx, s ->
                if (idx > 0) HorizontalDivider(color = MoonColors.Divider)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.address,
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        color = MoonColors.TextPrimary,
                    )
                    TextButton(onClick = { dns.moveServer(s.id, -1) }) {
                        Text("↑", color = MoonColors.Accent, fontSize = 14.sp)
                    }
                    TextButton(onClick = { dns.moveServer(s.id, 1) }) {
                        Text("↓", color = MoonColors.Accent, fontSize = 14.sp)
                    }
                    TextButton(
                        onClick = { dns.removeServer(s.id) },
                        enabled = dns.servers.size > 1,
                    ) {
                        Text("删除", color = MoonColors.Danger, fontSize = 14.sp)
                    }
                }
            }
        }

        // ---- Hosts 映射 ----
        MoonCard {
            Text(
                "Hosts 映射",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MoonColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "等价 /etc/hosts：域名 -> IP",
                fontSize = 13.sp,
                color = MoonColors.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newHostDomain,
                    onValueChange = { newHostDomain = it },
                    label = { Text("域名", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = newHostIp,
                    onValueChange = { newHostIp = it },
                    label = { Text("IP", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (dns.addHost(newHostDomain, newHostIp) != null) {
                            newHostDomain = ""
                            newHostIp = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MoonColors.Accent),
                ) { Text("添加", fontSize = 14.sp) }
            }
            Spacer(Modifier.height(8.dp))
            dns.hosts.toList().forEachIndexed { idx, h ->
                if (idx > 0) HorizontalDivider(color = MoonColors.Divider)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${h.domain} → ${h.ip}",
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        color = MoonColors.TextPrimary,
                    )
                    TextButton(onClick = { dns.removeHost(h.id) }) {
                        Text("删除", color = MoonColors.Danger, fontSize = 14.sp)
                    }
                }
            }
        }

        // ---- 分域名 DNS ----
        MoonCard {
            Text(
                "分域名 DNS",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MoonColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "指定域名后缀走特定 DNS 服务器",
                fontSize = 13.sp,
                color = MoonColors.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newRuleDomain,
                    onValueChange = { newRuleDomain = it },
                    label = { Text("域名后缀", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = newRuleServer,
                    onValueChange = { newRuleServer = it },
                    label = { Text("DNS 服务器", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (dns.addDomainRule(newRuleDomain, newRuleServer) != null) {
                            newRuleDomain = ""
                            newRuleServer = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MoonColors.Accent),
                ) { Text("添加", fontSize = 14.sp) }
            }
            Spacer(Modifier.height(8.dp))
            dns.domainRules.toList().forEachIndexed { idx, r ->
                if (idx > 0) HorizontalDivider(color = MoonColors.Divider)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "*.${r.domainSuffix} → ${r.serverAddress}",
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        color = MoonColors.TextPrimary,
                    )
                    TextButton(onClick = { dns.removeDomainRule(r.id) }) {
                        Text("删除", color = MoonColors.Danger, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
