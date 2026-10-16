package com.muse.moonpc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muse.moonpc.data.TrafficMonitor

/**
 * 仪表盘（P2，全新实现，对标 Surge 活动页）：
 * - 实时上下行速度 + 速度曲线图（最近 120 秒）
 * - 本次会话 / 今日 / 历史累计流量
 * - 活动连接数
 */
@Composable
fun DashboardScreen(
    running: Boolean,
    monitor: TrafficMonitor,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 实时速度卡片 ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        if (running) "实时速度" else "实时速度（未运行）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "活动连接：${monitor.activeConns}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    SpeedStat(
                        label = "↓ 下载",
                        value = TrafficMonitor.formatSpeed(monitor.downSpeed),
                        color = Color(0xFF2F7CF6),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    SpeedStat(
                        label = "↑ 上传",
                        value = TrafficMonitor.formatSpeed(monitor.upSpeed),
                        color = Color(0xFF34C759),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                SpeedGraph(
                    history = monitor.history.toList(),
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
        }

        // ---- 累计流量卡片 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("流量统计", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TrafficRow("本次会话", monitor.sessionDown, monitor.sessionUp)
                TrafficRow("今日", monitor.todayDown, monitor.todayUp)
                TrafficRow("历史累计", monitor.totalDown, monitor.totalUp)
            }
        }
    }
}

@Composable
private fun SpeedStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun TrafficRow(label: String, down: Long, up: Long) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "↓ ${TrafficMonitor.formatBytes(down)}  ↑ ${TrafficMonitor.formatBytes(up)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 速度曲线图：下载蓝线、上传绿线，最近 120 个采样点 */
@Composable
private fun SpeedGraph(history: List<Pair<Long, Long>>, modifier: Modifier = Modifier) {
    val downColor = Color(0xFF2F7CF6)
    val upColor = Color(0xFF34C759)
    Canvas(modifier = modifier) {
        if (history.size < 2) {
            return@Canvas
        }
        val maxV = (history.maxOf { maxOf(it.first, it.second) }).coerceAtLeast(1L).toFloat()
        val n = history.size
        fun x(i: Int) = size.width * i / (n - 1).coerceAtLeast(1).toFloat()
        fun y(v: Long) = size.height - (size.height - 8f) * v / maxV

        // 网格线
        val gridColor = Color.Gray.copy(alpha = 0.25f)
        for (g in 1..3) {
            val gy = size.height * g / 4f
            drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }

        // 下载曲线
        val downPath = Path().apply {
            moveTo(x(0), y(history[0].second))
            for (i in 1 until n) lineTo(x(i), y(history[i].second))
        }
        drawPath(downPath, downColor, style = Stroke(width = 2.5f))
        // 上传曲线
        val upPath = Path().apply {
            moveTo(x(0), y(history[0].first))
            for (i in 1 until n) lineTo(x(i), y(history[i].first))
        }
        drawPath(upPath, upColor, style = Stroke(width = 2.5f))
    }
}
