package com.muse.moonpc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muse.moonpc.core.ClashApi
import com.muse.moonpc.data.TrafficMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 请求活动页（P3，对标 Surge 请求列表）：
 * - 每秒轮询 Clash API /connections，展示实时连接
 * - 每行：目标域名/IP、命中规则、出口策略、持续时长、上下行流量
 * - 按域名搜索过滤
 */
@Composable
fun ActivityScreen(
    running: Boolean,
    clashPort: Int,
) {
    val scope = rememberCoroutineScope()
    val conns = remember { mutableStateListOf<ClashApi.ConnectionInfo>() }
    var query by remember { mutableStateOf("") }
    var lastError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(running, clashPort) {
        conns.clear()
        if (!running) return@LaunchedEffect
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val list = ClashApi.connections(clashPort)
                withContext(Dispatchers.Main) {
                    if (list != null) {
                        conns.clear()
                        // 新连接排前面
                        conns.addAll(list.sortedByDescending { it.startMs })
                        lastError = null
                    } else {
                        lastError = "读取连接失败"
                    }
                }
                delay(1000)
            }
        }
    }

    val filtered = remember(conns.toList(), query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) conns.toList()
        else conns.filter { it.host.lowercase().contains(q) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("活动", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                " ${filtered.size}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索域名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()

        when {
            !running -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "代理未运行",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    lastError ?: if (query.isNotBlank()) "无匹配连接" else "暂无活动连接",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filtered, key = { it.id }) { c ->
                    ConnectionRow(c)
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ConnectionRow(c: ClashApi.ConnectionInfo) {
    val now = System.currentTimeMillis()
    val durSec = if (c.startMs > 0) ((now - c.startMs) / 1000).coerceAtLeast(0) else 0
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.host,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatDuration(durSec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "规则 ${c.rule}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (c.outbound.isNotEmpty()) {
                    Text(
                        c.outbound,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "↓ ${TrafficMonitor.formatBytes(c.download)}  ↑ ${TrafficMonitor.formatBytes(c.upload)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 秒 -> "1:23" / "12s" */
private fun formatDuration(sec: Long): String = when {
    sec < 60 -> "${sec}s"
    sec < 3600 -> "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
    else -> "${sec / 3600}h${(sec % 3600) / 60}m"
}
