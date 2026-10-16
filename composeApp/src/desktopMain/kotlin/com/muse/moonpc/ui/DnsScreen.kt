package com.muse.moonpc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
 * DNS 设置页（P2，全新实现，对标 Surge DNS）：
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
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
        }

        // ---- DNS 服务器 ----
        item {
            Text("DNS 服务器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "支持 IP（8.8.8.8）、TLS（tls://8.8.8.8）、DoH（https://dns.google/dns-query）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newServer,
                    onValueChange = { newServer = it },
                    label = { Text("添加服务器") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (dns.addServer(newServer) != null) newServer = ""
                }) { Text("添加") }
            }
        }
        items(dns.servers.toList(), key = { it.id }) { s ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.address, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { dns.moveServer(s.id, -1) }) { Text("↑") }
                    TextButton(onClick = { dns.moveServer(s.id, 1) }) { Text("↓") }
                    TextButton(
                        onClick = { dns.removeServer(s.id) },
                        enabled = dns.servers.size > 1,
                    ) { Text("删除") }
                }
            }
        }

        // ---- hosts 映射 ----
        item {
            Spacer(Modifier.height(4.dp))
            Text("Hosts 映射", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "等价 /etc/hosts：域名 -> IP",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newHostDomain,
                    onValueChange = { newHostDomain = it },
                    label = { Text("域名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = newHostIp,
                    onValueChange = { newHostIp = it },
                    label = { Text("IP") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (dns.addHost(newHostDomain, newHostIp) != null) {
                        newHostDomain = ""
                        newHostIp = ""
                    }
                }) { Text("添加") }
            }
        }
        items(dns.hosts.toList(), key = { it.id }) { h ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${h.domain} → ${h.ip}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { dns.removeHost(h.id) }) { Text("删除") }
                }
            }
        }

        // ---- 分域名 DNS ----
        item {
            Spacer(Modifier.height(4.dp))
            Text("分域名 DNS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "指定域名后缀走特定 DNS 服务器",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newRuleDomain,
                    onValueChange = { newRuleDomain = it },
                    label = { Text("域名后缀") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = newRuleServer,
                    onValueChange = { newRuleServer = it },
                    label = { Text("DNS 服务器") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (dns.addDomainRule(newRuleDomain, newRuleServer) != null) {
                        newRuleDomain = ""
                        newRuleServer = ""
                    }
                }) { Text("添加") }
            }
        }
        items(dns.domainRules.toList(), key = { it.id }) { r ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "*.${r.domainSuffix} → ${r.serverAddress}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { dns.removeDomainRule(r.id) }) { Text("删除") }
                }
            }
        }
    }
}
