package com.muse.moonpc.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import com.muse.moonpc.core.JsEngine
import com.muse.moonpc.core.JsModule

/**
 * JS 模块页（P4，全新实现，对标 Surge 脚本）：
 * - 模块列表：名称 + 启用开关 + 编辑/删除
 * - 代码编辑器：等宽字体多行文本
 * - 钩子 onRequest(req) / onResponse(resp)；沙箱禁 Java 类，3 秒超时
 */
@Composable
fun JsModuleScreen(
    jsEngine: JsEngine,
    mitmEnabled: Boolean,
    onStatus: (String) -> Unit = {},
) {
    var editing by remember { mutableStateOf<JsModule?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var isNew by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        if (!mitmEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "JS 模块需要开启 MITM 解密才能处理 HTTPS 流量（明文 HTTP 可直接用）。\n前往「重写」页开启 MITM。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "JS 模块",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                " ${jsEngine.modules.size}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                editing = JsModule(name = "新模块", script = JsEngine.TEMPLATE)
                isNew = true
                showEditor = true
            }) { Text("＋ 新建") }
        }
        Text(
            "钩子：onRequest(req) / onResponse(resp)，返回修改后的对象；沙箱运行，无系统权限",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(jsEngine.modules, key = { it.id }) { m ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    onClick = { editing = m.copy(); isNew = false; showEditor = true },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name.ifBlank { "（未命名）" }, fontWeight = FontWeight.Medium)
                            Text(
                                "${m.script.lines().size} 行",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = m.enabled,
                            onCheckedChange = { jsEngine.setEnabled(m.id, it) },
                        )
                        TextButton(onClick = {
                            jsEngine.remove(m.id)
                            onStatus("模块已删除")
                        }) { Text("删除") }
                    }
                }
            }
        }
    }

    if (showEditor && editing != null) {
        JsModuleEditor(
            module = editing!!,
            isNew = isNew,
            onDismiss = { showEditor = false },
            onSave = { m ->
                if (isNew) jsEngine.add(m) else jsEngine.update(m)
                showEditor = false
                onStatus("JS 模块已保存（重启代理后生效）")
            },
        )
    }
}

@Composable
private fun JsModuleEditor(
    module: JsModule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (JsModule) -> Unit,
) {
    var m by remember { mutableStateOf(module) }
    DialogWindow(
        onCloseRequest = onDismiss,
        title = if (isNew) "新建 JS 模块" else "编辑 JS 模块",
    ) {
        Column(Modifier.padding(20.dp).width(640.dp)) {
            Text(if (isNew) "新建 JS 模块" else "编辑 JS 模块", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = m.name, onValueChange = { m = m.copy(name = it) },
                label = { Text("模块名称") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "req: {method, url, host, path, headers} · resp: {url, status, headers, body}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = m.script,
                onValueChange = { m = m.copy(script = it) },
                modifier = Modifier.fillMaxWidth().height(380.dp).verticalScroll(rememberScrollState()),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                maxLines = Int.MAX_VALUE,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "沙箱：禁止访问 Java 类（无文件/网络/进程权限），单脚本 3 秒超时",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(m) },
                    enabled = m.script.isNotBlank(),
                ) { Text("保存") }
            }
        }
    }
}
