package com.muse.moonpc.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muse.moonpc.data.NodeStore
import com.muse.moonpc.data.ProxyNode
import com.muse.moonpc.data.nodeProtoLine
import com.muse.moonpc.data.protocolBadge
import kotlinx.coroutines.launch

/**
 * 节点列表页（M2，全新实现，Surge Mac 风格：简洁专业）：
 * - 顶栏：标题 + 节点数；全部测速 / 订阅 / 导入
 * - 列表行：协议徽标 + 名称/地址 + 延迟；点击选中高亮；右键菜单测延迟/删除
 */
@Composable
fun NodeListScreen(store: NodeStore) {
    val scope = rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }
    var showSubs by remember { mutableStateOf(false) }
    var testingAll by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("节点", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                " ${store.nodes.size}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            store.selectedId?.let { sid ->
                store.nodes.firstOrNull { it.id == sid }?.let { n ->
                    Text(
                        " · 当前：${n.name.ifBlank { n.host }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    testingAll = true
                    scope.launch {
                        store.testAllLatencies()
                        testingAll = false
                    }
                },
                enabled = !testingAll && store.nodes.isNotEmpty(),
            ) {
                Text(if (testingAll) "测速中…" else "全部测速")
            }
            Button(onClick = { showSubs = true }) { Text("订阅") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { showImport = true }) { Text("＋ 导入") }
        }
        HorizontalDivider()

        if (store.nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "暂无节点",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "点击右上角「导入」粘贴分享链接，或「订阅」添加订阅",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(store.nodes, key = { it.id }) { node ->
                    NodeRow(
                        node = node,
                        selected = node.id == store.selectedId,
                        latency = store.latencies[node.id],
                        onSelect = { store.select(node.id) },
                        onTest = { scope.launch { store.testLatency(node) } },
                        onDelete = { store.removeNode(node.id) },
                    )
                }
            }
        }
    }

    if (showImport) {
        ImportDialog(
            onClose = { showImport = false },
            onImport = { text -> store.importLinks(text) },
        )
    }
    if (showSubs) {
        SubscriptionDialog(store = store, onClose = { showSubs = false })
    }
}

@Composable
private fun NodeRow(
    node: ProxyNode,
    selected: Boolean,
    latency: Long?,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem("测延迟") { onTest() },
                ContextMenuItem("删除") { onDelete() },
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 协议徽标
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(11.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    protocolBadge(node.protocol),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.name.ifBlank { node.host },
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    "${node.host}:${node.port} · ${nodeProtoLine(node)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            LatencyText(latency)
        }
    }
}

@Composable
private fun LatencyText(latency: Long?) {
    val (text, color) = when {
        latency == null -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
        latency < 0 -> "超时" to MaterialTheme.colorScheme.error
        latency < 300 -> "${latency}ms" to Color(0xFF2E7D32)
        latency < 800 -> "${latency}ms" to Color(0xFFEF6C00)
        else -> "${latency}ms" to MaterialTheme.colorScheme.error
    }
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
    )
}
