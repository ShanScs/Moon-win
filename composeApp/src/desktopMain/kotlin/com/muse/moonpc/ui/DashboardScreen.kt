package com.muse.moonpc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muse.moonpc.data.TrafficMonitor

/**
 * 仪表盘（Surge 风格）：
 * - 实时上下行速度 + 速度曲线图
 * - 本次会话 / 今日 / 历史累计流量
 */
@Composable
fun DashboardScreen(
    running: Boolean,
    monitor: TrafficMonitor,
) {
    SurgePage {
        PageHeader(
            title = "仪表盘",
            subtitle = "实时流量与速度监控",
            trailing = {
                MiniBadge(
                    text = if (running) "运行中" else "已停止",
                    bgColor = if (running) Color(0xFFE6F9ED) else Color(0xFFF2F2F7),
                    textColor = if (running) MoonColors.Success else MoonColors.TextSecondary,
                )
            }
        )

        // ---- 实时速度卡片 ----
        MoonCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "实时速度",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MoonColors.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "活动连接 ${monitor.activeConns}",
                    fontSize = 12.sp,
                    color = MoonColors.TextSecondary,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SpeedStat(
                    label = "下载",
                    value = TrafficMonitor.formatSpeed(monitor.downSpeed),
                    color = MoonColors.Accent,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                SpeedStat(
                    label = "上传",
                    value = TrafficMonitor.formatSpeed(monitor.upSpeed),
                    color = MoonColors.Success,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            SpeedGraph(
                history = monitor.history.toList(),
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
        }

        // ---- 流量统计 ----
        MoonCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                "流量统计",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MoonColors.TextPrimary,
            )
            Spacer(Modifier.height(12.dp))
            TrafficRow("本次会话", monitor.sessionDown, monitor.sessionUp)
            HorizontalDivider(color = MoonColors.Divider, modifier = Modifier.padding(vertical = 8.dp))
            TrafficRow("今日", monitor.todayDown, monitor.todayUp)
            HorizontalDivider(color = MoonColors.Divider, modifier = Modifier.padding(vertical = 8.dp))
            TrafficRow("历史累计", monitor.totalDown, monitor.totalUp, last = true)
        }
    }
}

@Composable
private fun SpeedStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MoonColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value.substringBefore(" "),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            if (" " in value) {
                Spacer(Modifier.width(4.dp))
                Text(
                    value.substringAfter(" "),
                    fontSize = 12.sp,
                    color = MoonColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TrafficRow(label: String, down: Long, up: Long, last: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = MoonColors.TextSecondary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "↓ ${TrafficMonitor.formatBytes(down)}",
            fontSize = 13.sp,
            color = MoonColors.Accent,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "↑ ${TrafficMonitor.formatBytes(up)}",
            fontSize = 13.sp,
            color = MoonColors.Success,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 速度曲线图：下载蓝线、上传绿线 */
@Composable
private fun SpeedGraph(history: List<Pair<Long, Long>>, modifier: Modifier = Modifier) {
    val downColor = MoonColors.Accent
    val upColor = MoonColors.Success
    Canvas(modifier = modifier) {
        if (history.size < 2) return@Canvas
        val maxV = (history.maxOf { maxOf(it.first, it.second) }).coerceAtLeast(1L).toFloat()
        val n = history.size
        fun x(i: Int) = size.width * i / (n - 1).coerceAtLeast(1).toFloat()
        fun y(v: Long) = size.height - (size.height - 8f) * v / maxV

        val gridColor = MoonColors.Divider
        for (g in 1..3) {
            val gy = size.height * g / 4f
            drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }

        val downPath = Path().apply {
            moveTo(x(0), y(history[0].second))
            for (i in 1 until n) lineTo(x(i), y(history[i].second))
        }
        drawPath(downPath, downColor, style = Stroke(width = 2.5f))
        val upPath = Path().apply {
            moveTo(x(0), y(history[0].first))
            for (i in 1 until n) lineTo(x(i), y(history[i].first))
        }
        drawPath(upPath, upColor, style = Stroke(width = 2.5f))
    }
}
