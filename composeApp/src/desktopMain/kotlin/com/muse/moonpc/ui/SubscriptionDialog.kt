package com.muse.moonpc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import com.muse.moonpc.data.NodeStore
import kotlinx.coroutines.launch

/**
 * 订阅管理弹窗（M2，全新实现）：
 * - 添加订阅 URL（自动拉取并解析）
 * - 每条订阅：名称、节点数、流量/更新状态；更新 / 删除
 */
@Composable
fun SubscriptionDialog(
    store: NodeStore,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    DialogWindow(onCloseRequest = onClose, title = "订阅管理") {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(24.dp).width(580.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("订阅管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("https://… 订阅链接") },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                val sub = store.addSubscription("", url)
                                if (sub == null) {
                                    err = "仅支持 http/https 订阅链接"
                                    return@launch
                                }
                                url = ""
                                busyId = sub.id
                                err = null
                                val r = store.updateSubscription(sub)
                                busyId = null
                                if (r.error != null) err = "更新失败：${r.error}"
                            }
                        },
                        enabled = url.isNotBlank() && busyId == null,
                    ) { Text("添加并更新") }
                }
                err?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                HorizontalDivider()

                if (store.subscriptions.isEmpty()) {
                    Text(
                        "暂无订阅",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(store.subscriptions, key = { it.id }) { sub ->
                            val count = store.nodes.count { it.subId == sub.id }
                            val updating = busyId == sub.id
                            Surface(
                                tonalElevation = 2.dp,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(sub.name, fontWeight = FontWeight.Medium, maxLines = 1)
                                        Text(
                                            buildString {
                                                if (sub.traffic.isNotEmpty()) append("${sub.traffic} · ")
                                                append("$count 个节点")
                                                if (sub.updatedAt > 0 && sub.traffic.isEmpty()) append(" · 已更新")
                                                if (sub.updatedAt == 0L) append(" · 未更新")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                busyId = sub.id
                                                err = null
                                                val r = store.updateSubscription(sub)
                                                busyId = null
                                                if (r.error != null) err = "更新失败：${r.error}"
                                            }
                                        },
                                        enabled = !updating,
                                    ) { Text(if (updating) "更新中…" else "更新") }
                                    TextButton(
                                        onClick = { store.removeSubscription(sub.id) },
                                        enabled = !updating,
                                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClose) { Text("关闭") }
                }
            }
        }
    }
}
