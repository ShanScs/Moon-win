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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.unit.sp
import com.muse.moonpc.core.ClashApi
import com.muse.moonpc.data.TrafficMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 请求活动页（Surge 风格）：
 * - 每秒轮询 Clash API /connections，展示实时连接
 * - 搜索过滤
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

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader(
            title = "活动",
            subtitle = if (running) "${filtered.size} 个活动连接" else "实时连接监控",
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索域名", fontSize = 14.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MoonColors.Accent,
                unfocusedBorderColor = MoonColors.Divider,
            ),
        )

        when {
            !running -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("代理未运行", fontSize = 14.sp, color = MoonColors.TextSecondary)
            }
            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    lastError ?: if (query.isNotBlank()) "无匹配连接" else "暂无活动连接",
                    fontSize = 14.sp,
                    color = MoonColors.TextSecondary,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
    MoonCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    c.host,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MoonColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "规则 ${c.rule}",
                    fontSize = 12.sp,
                    color = MoonColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (c.outbound.isNotEmpty()) {
                    Text(
                        c.outbound,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MoonColors.Accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "↓${TrafficMonitor.formatBytes(c.download)} ↑${TrafficMonitor.formatBytes(c.upload)} · ${formatDuration(durSec)}",
                    fontSize = 11.sp,
                    color = MoonColors.TextSecondary,
                )
            }
        }
    }
}

/** 秒 -> "1:23" / "12s" */
private fun formatDuration(sec: Long): String = when {
    sec < 60 -> "${sec}s"
    sec < 3600 -> "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
    else -> "${sec / 3600}h${(sec % 3600) / 60}m"
}
