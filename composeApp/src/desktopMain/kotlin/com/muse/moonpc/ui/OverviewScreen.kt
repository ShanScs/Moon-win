package com.muse.moonpc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 概览页（Surge 风格重做，对标效果图）：
 * - 静态演示数据，后续再接真实数据
 * - 布局：标题行 → 节点/下载/上传卡片行 → 活动连接/规则命中行 → 最近活动/快捷管理行
 */
@Composable
fun OverviewScreen(
    onNavigateTab: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- 页头 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "概览",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MoonColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "网络状态、当前策略与流量摘要",
                    fontSize = 13.sp,
                    color = MoonColors.TextSecondary,
                )
            }
            Spacer(Modifier.weight(1f))
            // 配置已加载徽标
            Box(
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE6F9ED))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    "配置已加载",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MoonColors.Success,
                )
            }
        }

        // ---- 第一行：当前节点 / 实时下载 / 实时上传 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 当前节点卡（宽）
            MoonCard(modifier = Modifier.weight(2f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // HK 徽标
                    Box(
                        modifier = Modifier.size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF2F2F7)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "HK",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MoonColors.TextPrimary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("当前节点", fontSize = 12.sp, color = MoonColors.TextSecondary)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "香港·HKG 01",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MoonColors.TextPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Shadowsocks·亚洲高速",
                            fontSize = 12.sp,
                            color = MoonColors.TextSecondary,
                        )
                    }
                    Text(
                        "86 ms",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MoonColors.Success,
                        modifier = Modifier.align(Alignment.Top),
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MoonColors.Divider)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    StatTriple("入口模式", "TUN", Modifier.weight(1f))
                    StatTriple("路由策略", "规则分流", Modifier.weight(1f))
                    StatTriple("DNS", "Cloudflare", Modifier.weight(1f))
                }
            }
            // 实时下载
            MoonCard(modifier = Modifier.weight(1f)) {
                Text("实时下载", fontSize = 12.sp, color = MoonColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "3.24",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoonColors.Accent,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "MB/s",
                        fontSize = 12.sp,
                        color = MoonColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("今日 1.56 GB", fontSize = 12.sp, color = MoonColors.TextSecondary)
            }
            // 实时上传
            MoonCard(modifier = Modifier.weight(1f)) {
                Text("实时上传", fontSize = 12.sp, color = MoonColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "0.72",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoonColors.Success,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "MB/s",
                        fontSize = 12.sp,
                        color = MoonColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("今日 286 MB", fontSize = 12.sp, color = MoonColors.TextSecondary)
            }
        }

        // ---- 第二行：活动连接 / 规则命中 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MoonCard(modifier = Modifier.weight(1f)) {
                Text("活动连接", fontSize = 12.sp, color = MoonColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "24",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoonColors.TextPrimary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "条",
                        fontSize = 12.sp,
                        color = MoonColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("TCP 18·UDP 6", fontSize = 12.sp, color = MoonColors.TextSecondary)
            }
            MoonCard(modifier = Modifier.weight(1f)) {
                Text("规则命中", fontSize = 12.sp, color = MoonColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "1,248",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MoonColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text("拦截 36 次", fontSize = 12.sp, color = MoonColors.TextSecondary)
            }
        }

        // ---- 第三行：最近活动 / 快捷管理 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 最近活动
            MoonCard(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "最近活动",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoonColors.TextPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("实时", fontSize = 12.sp, color = MoonColors.TextSecondary)
                }
                Spacer(Modifier.height(12.dp))
                ActivityRow("api.github.com", "140.82.112.6:443", "全球策略", "842 KB")
                ActivityRow("www.youtube.com", "142.250.191.46:443", "自动选择", "12.4 MB")
                ActivityRow("apple.com.cn", "17.253.144.10:443", "DIRECT", "1.8 MB", last = true)
            }
            // 快捷管理
            MoonCard(modifier = Modifier.weight(1f)) {
                Text(
                    "快捷管理",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MoonColors.TextPrimary,
                )
                Spacer(Modifier.height(12.dp))
                QuickRow("12", "节点", "8 个可用，1 个超时") { onNavigateTab(3) }
                QuickRow("26", "规则", "3 个远端规则集") { onNavigateTab(5) }
                QuickRow("3", "DNS", "DoH 响应 24 ms", last = true) { onNavigateTab(7) }
            }
        }
    }
}

@Composable
private fun StatTriple(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MoonColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MoonColors.TextPrimary,
        )
    }
}

@Composable
private fun ActivityRow(
    name: String,
    addr: String,
    policy: String,
    size: String,
    last: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MoonColors.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(addr, fontSize = 12.sp, color = MoonColors.TextSecondary)
        }
        Text(
            policy,
            fontSize = 12.sp,
            color = MoonColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(size, fontSize = 12.sp, color = MoonColors.TextSecondary)
    }
    if (!last) HorizontalDivider(color = MoonColors.Divider)
}

@Composable
private fun QuickRow(
    badge: String,
    title: String,
    desc: String,
    last: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFE5F1FF)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                badge,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MoonColors.Accent,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MoonColors.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 12.sp, color = MoonColors.TextSecondary)
        }
        Text(
            "›",
            fontSize = 20.sp,
            color = MoonColors.TextSecondary,
        )
    }
    if (!last) HorizontalDivider(color = MoonColors.Divider)
}
