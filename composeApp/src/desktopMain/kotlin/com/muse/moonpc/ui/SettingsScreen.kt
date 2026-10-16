package com.muse.moonpc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import com.muse.moonpc.core.AutoStartManager
import com.muse.moonpc.core.SingBoxManager
import com.muse.moonpc.core.TunManager
import com.muse.moonpc.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（Surge 风格）：
 * - 通用：开机自启、最小化到托盘、关闭时最小化到托盘
 * - 关于：版本信息
 */
@Composable
fun SettingsScreen(settings: AppSettings, manager: SingBoxManager? = null) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    val winSupported = remember { AutoStartManager.isWindows() }
    var diagResult by remember { mutableStateOf<String?>(null) }
    var diagBusy by remember { mutableStateOf(false) }

    SurgePage {
        PageHeader(
            title = "设置",
            subtitle = "应用偏好设置",
        )

        // ---- 通用 ----
        MoonCard {
            Text(
                "通用",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MoonColors.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))

            SettingSwitchRow(
                title = "开机自启",
                desc = if (winSupported) "开机自动启动 Moon PC" else "仅支持 Windows",
                checked = settings.autoStart,
                enabled = !busy && winSupported,
                onChange = { on ->
                    busy = true
                    errMsg = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { AutoStartManager.setEnabled(on) }
                        if (r.isSuccess) {
                            settings.autoStart = on
                            settings.save()
                        } else {
                            errMsg = "设置失败：${r.exceptionOrNull()?.message}"
                        }
                        busy = false
                    }
                },
            )

            HorizontalDivider(color = MoonColors.Divider)

            SettingSwitchRow(
                title = "最小化到托盘",
                desc = "最小化窗口时隐藏到系统托盘",
                checked = settings.minimizeToTray,
                enabled = !busy,
                onChange = {
                    settings.minimizeToTray = it
                    settings.save()
                },
            )

            HorizontalDivider(color = MoonColors.Divider)

            SettingSwitchRow(
                title = "关闭时最小化到托盘",
                desc = "点击关闭按钮时隐藏到托盘，而不是退出程序",
                checked = settings.closeToTray,
                enabled = !busy,
                onChange = {
                    settings.closeToTray = it
                    settings.save()
                },
            )
        }

        if (busy) {
            Text(
                "处理中…",
                fontSize = 13.sp,
                color = MoonColors.TextSecondary,
            )
        }
        errMsg?.let {
            Text(
                it,
                fontSize = 13.sp,
                color = MoonColors.Danger,
            )
        }

        // ---- TUN 诊断 ----
        if (manager != null) {
            MoonCard {
                Text(
                    "TUN 诊断",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MoonColors.TextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "sing-box 进程：${if (manager.isProcessAlive()) "运行中" else "未运行/已崩溃"}",
                    fontSize = 14.sp,
                    color = MoonColors.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        diagBusy = true
                        diagResult = null
                        scope.launch(Dispatchers.IO) {
                            val r = TunManager.diagnose()
                            withContext(Dispatchers.Main) {
                                diagResult = r
                                diagBusy = false
                            }
                        }
                    },
                    enabled = !diagBusy,
                ) {
                    Text(if (diagBusy) "诊断中…" else "运行诊断")
                }
                diagResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        fontSize = 14.sp,
                        color = MoonColors.TextPrimary,
                    )
                }
                val logs = remember(diagResult) { manager.recentLogs().takeLast(30) }
                if (logs.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MoonColors.Divider)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "sing-box 最近日志（${logs.size} 行）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MoonColors.TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    logs.forEach { line ->
                        Text(
                            line.take(120),
                            fontSize = 11.sp,
                            color = MoonColors.TextSecondary,
                        )
                    }
                }
            }
        }

        // ---- 关于 ----
        MoonCard {
            Text(
                "关于",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MoonColors.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "版本",
                    fontSize = 14.sp,
                    color = MoonColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Moon PC 0.2.13",
                    fontSize = 14.sp,
                    color = MoonColors.TextPrimary,
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MoonColors.Divider)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "内核",
                    fontSize = 14.sp,
                    color = MoonColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "sing-box 1.14.3",
                    fontSize = 14.sp,
                    color = MoonColors.TextPrimary,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MoonColors.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                fontSize = 13.sp,
                color = MoonColors.TextSecondary,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}
