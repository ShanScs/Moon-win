package com.muse.moonpc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muse.moonpc.core.ConfigGenerator
import com.muse.moonpc.data.TrafficMonitor

/**
 * 概览页（P3，对标 Surge 概览）：
 * - 默认首页
 * - 代理总开关 + 模式快捷切换（TUN/系统代理/手动）
 * - 当前状态卡片：运行状态、当前节点、当前模式、今日流量
 * - 功能开关：去广告、DNS
 * - 快捷入口：节点管理、规则、DNS
 */
@Composable
fun OverviewScreen(
    running: Boolean,
    onToggleProxy: () -> Unit,
    mode: ConfigGenerator.ProxyMode,
    onModeChange: (ConfigGenerator.ProxyMode) -> Unit,
    currentNodeName: String,
    monitor: TrafficMonitor,
    dnsEnabled: Boolean,
    onDnsToggle: (Boolean) -> Unit,
    adBlockEnabled: Boolean,
    onAdBlockToggle: (Boolean) -> Unit,
    onNavigateTab: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 总开关卡片 ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "代理",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (running) "运行中" else "已停止",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (running) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onToggleProxy) {
                        Text(if (running) "停止" else "启动")
                    }
                }
                HorizontalDivider()
                // 模式快捷切换（运行中禁用）
                Text("模式", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfigGenerator.ProxyMode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            enabled = !running,
                            onClick = { onModeChange(m) },
                            label = { Text(m.label) },
                        )
                    }
                }
                if (running) {
                    Text(
                        "运行中请先停止再切换模式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- 状态卡片 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("当前状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                StatusRow("状态", if (running) "● 运行中" else "○ 已停止")
                StatusRow("当前节点", currentNodeName)
                StatusRow("模式", mode.label)
                StatusRow(
                    "今日流量",
                    "↓ ${TrafficMonitor.formatBytes(monitor.todayDown)}  ↑ ${TrafficMonitor.formatBytes(monitor.todayUp)}",
                )
            }
        }

        // ---- 功能开关 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("功能", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                SwitchRow(
                    title = "去广告",
                    desc = "AdGuard 规则集拦截广告域名",
                    checked = adBlockEnabled,
                    onCheckedChange = onAdBlockToggle,
                )
                SwitchRow(
                    title = "DNS",
                    desc = "自定义 DNS 套件（DoH/hosts/分域名）",
                    checked = dnsEnabled,
                    onCheckedChange = onDnsToggle,
                )
            }
        }

        // ---- 快捷入口 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("快捷入口", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                EntryRow("节点管理", "导入 / 订阅 / 测速") { onNavigateTab(3) }
                EntryRow("规则", "分流规则与规则集订阅") { onNavigateTab(5) }
                EntryRow("重写", "MITM / URL重写 / Header改写 / JS模块") { onNavigateTab(6) }
                EntryRow("DNS", "DNS 服务器与 hosts 映射") { onNavigateTab(7) }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun EntryRow(title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(4.dp))
}
