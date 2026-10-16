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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.muse.moonpc.core.JsEngine
import com.muse.moonpc.core.MitmCa
import com.muse.moonpc.core.ProxyModeManager
import com.muse.moonpc.data.HeaderRewriteRule
import com.muse.moonpc.data.RewriteStore
import com.muse.moonpc.data.UrlRewriteRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 重写页（P4，全新实现，对标 Surge）：
 * - MITM 总开关 + CA 证书状态/安装/卸载
 * - Tab：URL 重写 / Header 改写
 * - URL 重写：通配符/正则 → 302 重定向 / 拒绝
 * - Header 改写：请求/响应头 添加/覆盖/删除，可按域名限定
 */
@Composable
fun RewriteScreen(
    rewriteStore: RewriteStore,
    jsEngine: JsEngine,
    running: Boolean,
    onStatus: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var innerTab by remember { mutableStateOf(0) }
    var mitmOn by remember { mutableStateOf(rewriteStore.mitmEnabled) }
    var caInstalled by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }

    var editingUrl by remember { mutableStateOf<UrlRewriteRule?>(null) }
    var showUrlEditor by remember { mutableStateOf(false) }
    var isNewUrl by remember { mutableStateOf(true) }
    var editingHeader by remember { mutableStateOf<HeaderRewriteRule?>(null) }
    var showHeaderEditor by remember { mutableStateOf(false) }
    var isNewHeader by remember { mutableStateOf(true) }

    fun refreshCaStatus() {
        scope.launch(Dispatchers.IO) {
            val v = runCatching { MitmCa.isInstalledOnWindows() }.getOrNull()
            withContext(Dispatchers.Main) { caInstalled = v }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { refreshCaStatus() }

    SurgePage {
        PageHeader(
            title = "重写",
            subtitle = "MITM 解密、URL 重写、Header 改写、JS 模块",
        )
        // ---- MITM 总开关卡 ----
        MoonCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MITM 解密", fontWeight = FontWeight.Bold)
                        Text(
                            "解密 HTTPS 流量以应用重写/JS 模块；需安装 CA 证书",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = mitmOn,
                        onCheckedChange = {
                            mitmOn = it
                            rewriteStore.mitmEnabled = it
                            rewriteStore.save()
                            onStatus(if (it) "MITM 已开启（重启代理后生效，需安装 CA 证书）" else "MITM 已关闭（重启代理后生效）")
                        },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (caInstalled) {
                            null -> "CA 状态：检测中…"
                            true -> "CA 状态：已安装到系统信任根"
                            false -> "CA 状态：未安装（HTTPS 会报证书错误）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (ProxyModeManager.isWindows()) {
                        TextButton(
                            onClick = {
                                busy = true
                                scope.launch(Dispatchers.IO) {
                                    val r = MitmCa.installOnWindows()
                                    withContext(Dispatchers.Main) {
                                        busy = false
                                        onStatus(
                                            if (r.isSuccess) "CA 证书安装成功"
                                            else "安装失败：${r.exceptionOrNull()?.message}",
                                        )
                                        refreshCaStatus()
                                    }
                                }
                            },
                            enabled = !busy,
                        ) { Text("安装证书") }
                        TextButton(
                            onClick = {
                                busy = true
                                scope.launch(Dispatchers.IO) {
                                    val r = MitmCa.uninstallFromWindows()
                                    withContext(Dispatchers.Main) {
                                        busy = false
                                        onStatus(
                                            if (r.isSuccess) "CA 证书已卸载"
                                            else "卸载失败：${r.exceptionOrNull()?.message}",
                                        )
                                        refreshCaStatus()
                                    }
                                }
                            },
                            enabled = !busy,
                        ) { Text("卸载") }
                    } else {
                        Text(
                            "请手动将 ~/.moon-pc/mitm/ca.crt 加入系统信任",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (running && mitmOn) {
                    Text(
                        "代理运行中：改动重启代理后生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
        }

        // ---- 内页 Tab ----
        TabRow(selectedTabIndex = innerTab) {
            Tab(selected = innerTab == 0, onClick = { innerTab = 0 }, text = { Text("URL 重写") })
            Tab(selected = innerTab == 1, onClick = { innerTab = 1 }, text = { Text("Header 改写") })
            Tab(selected = innerTab == 2, onClick = { innerTab = 2 }, text = { Text("JS 模块") })
        }

        Box(Modifier.fillMaxWidth()) {
            when (innerTab) {
                0 -> UrlRuleList(
                    rewriteStore = rewriteStore,
                    onAdd = {
                        editingUrl = UrlRewriteRule(); isNewUrl = true; showUrlEditor = true
                    },
                    onEdit = { editingUrl = it.copy(); isNewUrl = false; showUrlEditor = true },
                )
                1 -> HeaderRuleList(
                    rewriteStore = rewriteStore,
                    onAdd = {
                        editingHeader = HeaderRewriteRule(); isNewHeader = true; showHeaderEditor = true
                    },
                    onEdit = { editingHeader = it.copy(); isNewHeader = false; showHeaderEditor = true },
                )
                else -> JsModuleScreen(
                    jsEngine = jsEngine,
                    mitmEnabled = mitmOn,
                    onStatus = onStatus,
                )
            }
        }
    }

    if (showUrlEditor && editingUrl != null) {
        UrlRuleEditor(
            rule = editingUrl!!,
            isNew = isNewUrl,
            onDismiss = { showUrlEditor = false },
            onSave = { r ->
                if (isNewUrl) rewriteStore.addUrlRule(r) else rewriteStore.updateUrlRule(r)
                showUrlEditor = false
                onStatus("URL 重写规则已保存（重启代理后生效）")
            },
        )
    }
    if (showHeaderEditor && editingHeader != null) {
        HeaderRuleEditor(
            rule = editingHeader!!,
            isNew = isNewHeader,
            onDismiss = { showHeaderEditor = false },
            onSave = { r ->
                if (isNewHeader) rewriteStore.addHeaderRule(r) else rewriteStore.updateHeaderRule(r)
                showHeaderEditor = false
                onStatus("Header 改写规则已保存（重启代理后生效）")
            },
        )
    }
}

@Composable
private fun UrlRuleList(
    rewriteStore: RewriteStore,
    onAdd: () -> Unit,
    onEdit: (UrlRewriteRule) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${rewriteStore.urlRules.size} 条规则", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = onAdd) { Text("＋ 添加") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rewriteStore.urlRules, key = { it.id }) { r ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    onClick = { onEdit(r) },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                r.match.ifBlank { "（空匹配）" },
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "${UrlRewriteRule.matchTypeLabel(r.matchType)} · " +
                                    "${UrlRewriteRule.actionLabel(r.action)}" +
                                    if (r.action == UrlRewriteRule.ACTION_REDIRECT && r.target.isNotBlank())
                                        " → ${r.target.take(60)}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = r.enabled,
                            onCheckedChange = {
                                r.enabled = it; rewriteStore.save()
                            },
                        )
                        TextButton(onClick = { rewriteStore.moveUrlRule(r.id, true) }) { Text("↑") }
                        TextButton(onClick = { rewriteStore.moveUrlRule(r.id, false) }) { Text("↓") }
                        TextButton(onClick = { rewriteStore.removeUrlRule(r.id) }) { Text("删除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderRuleList(
    rewriteStore: RewriteStore,
    onAdd: () -> Unit,
    onEdit: (HeaderRewriteRule) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${rewriteStore.headerRules.size} 条规则", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = onAdd) { Text("＋ 添加") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rewriteStore.headerRules, key = { it.id }) { r ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    onClick = { onEdit(r) },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${HeaderRewriteRule.opLabel(r.op)} ${r.name}" +
                                    if (r.op != HeaderRewriteRule.OP_REMOVE) ": ${r.value.take(40)}" else "",
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "${HeaderRewriteRule.directionLabel(r.direction)}" +
                                    if (r.domainMatch.isNotBlank()) " · ${r.domainMatch}" else " · 全部域名",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = r.enabled,
                            onCheckedChange = {
                                r.enabled = it; rewriteStore.save()
                            },
                        )
                        TextButton(onClick = { rewriteStore.moveHeaderRule(r.id, true) }) { Text("↑") }
                        TextButton(onClick = { rewriteStore.moveHeaderRule(r.id, false) }) { Text("↓") }
                        TextButton(onClick = { rewriteStore.removeHeaderRule(r.id) }) { Text("删除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlRuleEditor(
    rule: UrlRewriteRule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (UrlRewriteRule) -> Unit,
) {
    var r by remember { mutableStateOf(rule) }
    DialogWindow(onCloseRequest = onDismiss, title = if (isNew) "添加 URL 重写" else "编辑 URL 重写") {
        Column(Modifier.padding(20.dp).width(480.dp)) {
            Text(if (isNew) "添加 URL 重写" else "编辑 URL 重写", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = r.match, onValueChange = { r = r.copy(match = it) },
                label = { Text("匹配模式（如 *.ads.com/*）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("匹配方式：")
                FilterChip(
                    selected = r.matchType == UrlRewriteRule.MATCH_WILDCARD,
                    onClick = { r = r.copy(matchType = UrlRewriteRule.MATCH_WILDCARD) },
                    label = { Text("通配符") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = r.matchType == UrlRewriteRule.MATCH_REGEX,
                    onClick = { r = r.copy(matchType = UrlRewriteRule.MATCH_REGEX) },
                    label = { Text("正则") },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("动作：")
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = r.action == UrlRewriteRule.ACTION_REDIRECT,
                    onClick = { r = r.copy(action = UrlRewriteRule.ACTION_REDIRECT) },
                )
                Text("302 重定向")
                Spacer(Modifier.width(16.dp))
                RadioButton(
                    selected = r.action == UrlRewriteRule.ACTION_REJECT,
                    onClick = { r = r.copy(action = UrlRewriteRule.ACTION_REJECT) },
                )
                Text("拒绝")
            }
            if (r.action == UrlRewriteRule.ACTION_REDIRECT) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = r.target, onValueChange = { r = r.copy(target = it) },
                    label = { Text("重定向目标 URL") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(r) },
                    enabled = r.match.isNotBlank(),
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun HeaderRuleEditor(
    rule: HeaderRewriteRule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (HeaderRewriteRule) -> Unit,
) {
    var r by remember { mutableStateOf(rule) }
    DialogWindow(onCloseRequest = onDismiss, title = if (isNew) "添加 Header 改写" else "编辑 Header 改写") {
        Column(Modifier.padding(20.dp).width(480.dp)) {
            Text(if (isNew) "添加 Header 改写" else "编辑 Header 改写", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("方向：")
                FilterChip(
                    selected = r.direction == HeaderRewriteRule.DIR_REQUEST,
                    onClick = { r = r.copy(direction = HeaderRewriteRule.DIR_REQUEST) },
                    label = { Text("请求头") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = r.direction == HeaderRewriteRule.DIR_RESPONSE,
                    onClick = { r = r.copy(direction = HeaderRewriteRule.DIR_RESPONSE) },
                    label = { Text("响应头") },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("操作：")
                listOf(
                    HeaderRewriteRule.OP_SET to "覆盖",
                    HeaderRewriteRule.OP_ADD to "添加",
                    HeaderRewriteRule.OP_REMOVE to "删除",
                ).forEach { (op, label) ->
                    RadioButton(selected = r.op == op, onClick = { r = r.copy(op = op) })
                    Text(label)
                    Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = r.domainMatch, onValueChange = { r = r.copy(domainMatch = it) },
                label = { Text("域名限定（通配符，空=全部）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = r.name, onValueChange = { r = r.copy(name = it) },
                label = { Text("Header 名") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (r.op != HeaderRewriteRule.OP_REMOVE) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = r.value, onValueChange = { r = r.copy(value = it) },
                    label = { Text("Header 值") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(r) },
                    enabled = r.name.isNotBlank(),
                ) { Text("保存") }
            }
        }
    }
}
