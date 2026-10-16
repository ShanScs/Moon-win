package com.muse.moonpc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow

/**
 * 分享链接批量导入弹窗（M2，全新实现）：
 * 多行文本框粘贴分享链接（ss://、vless://、vmess://、trojan://、hysteria2://、tuic://、anytls://），
 * 解析后批量导入，显示成功/失败数。
 */
@Composable
fun ImportDialog(
    onClose: () -> Unit,
    /** 返回 Pair(成功数, 失败数) */
    onImport: (String) -> Pair<Int, Int>,
) {
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }

    DialogWindow(onCloseRequest = onClose, title = "导入节点") {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(24.dp).width(520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("导入节点", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "粘贴分享链接，每行一条，支持 ss / vless / vmess / trojan / hysteria2 / tuic / anytls",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; result = null },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    placeholder = { Text("ss://…\nvless://…\nvmess://…") },
                    minLines = 8,
                )
                result?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClose) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val (ok, failed) = onImport(text)
                            result = buildString {
                                append("导入成功 $ok 个")
                                if (failed > 0) append("，失败 $failed 行")
                            }
                            if (ok > 0) text = ""
                        },
                        enabled = text.isNotBlank(),
                    ) { Text("导入") }
                }
            }
        }
    }
}
