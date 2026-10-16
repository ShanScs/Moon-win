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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.moonpc.data.NodeStore
import com.muse.moonpc.data.ProxyNode
import com.muse.moonpc.data.nodeProtoLine
import com.muse.moonpc.data.protocolBadge
import kotlinx.coroutines.launch

/**
 * 节点列表页（Surge 风格）：
 * - 全部测速 / 订阅 / 导入
 * - 列表行：协议徽标 + 名称/地址 + 延迟；点击选中高亮；右键菜单测延迟/删除
 */
@Composable
fun NodeListScreen(store: NodeStore) {
    val scope = rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }
    var showSubs by remember { mutableStateOf(false) }
    var testingAll by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader(
            title = "节点",
            subtitle = "${store.nodes.size} 个节点" + (store.selectedId?.let { sid ->
                store.nodes.firstOrNull { it.id == sid }?.let { n -> " · 当前：${n.name.ifBlank { n.host }}" } ?: ""
            } ?: ""),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                        Text(
                            if (testingAll) "测速中…" else "全部测速",
                            fontSize = 14.sp,
                            color = MoonColors.Accent,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { showSubs = true }) {
                        Text("订阅", fontSize = 14.sp, color = MoonColors.Accent)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { showImport = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MoonColors.Accent),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("＋ 导入", fontSize = 14.sp)
                    }
                }
            }
        )

        if (store.nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无节点", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MoonColors.TextSecondary)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "点击右上角「导入」粘贴分享链接，或「订阅」添加订阅",
                        fontSize = 13.sp,
                        color = MoonColors.TextSecondary,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
        MoonCard(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 协议徽标
                Box(
                    modifier = Modifier.size(42.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (selected) MoonColors.Accent else Color(0xFFE5F1FF)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        protocolBadge(node.protocol),
                        color = if (selected) Color.White else MoonColors.Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        node.name.ifBlank { node.host },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MoonColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "${node.host}:${node.port} · ${nodeProtoLine(node)}",
                        fontSize = 12.sp,
                        color = MoonColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected) {
                    MiniBadge(
                        text = "使用中",
                        bgColor = Color(0xFFE5F1FF),
                        textColor = MoonColors.Accent,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                LatencyText(latency)
            }
        }
    }
}

@Composable
private fun LatencyText(latency: Long?) {
    val (text, color) = when {
        latency == null -> "—" to MoonColors.TextSecondary
        latency < 0 -> "超时" to MoonColors.Danger
        latency < 300 -> "${latency}ms" to MoonColors.Success
        latency < 800 -> "${latency}ms" to MoonColors.Warning
        else -> "${latency}ms" to MoonColors.Danger
    }
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}
