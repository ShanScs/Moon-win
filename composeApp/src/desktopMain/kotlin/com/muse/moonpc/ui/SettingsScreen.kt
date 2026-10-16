package com.muse.moonpc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.muse.moonpc.core.AutoStartManager
import com.muse.moonpc.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（M5，全新实现）：
 * - 开机自启（Windows 注册表）
 * - 最小化到托盘
 * - 关闭时最小化到托盘
 */
@Composable
fun SettingsScreen(settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    val winSupported = remember { AutoStartManager.isWindows() }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider()

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

        if (busy) {
            Text(
                "处理中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        errMsg?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.weight(1f))
        HorizontalDivider()
        Text(
            "Moon PC 0.1.0 · sing-box 内核",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}
