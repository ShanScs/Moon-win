package com.muse.moonpc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import com.muse.moonpc.data.PolicyStore
import com.muse.moonpc.data.RouteRule
import com.muse.moonpc.data.RuleSetSubscription
import com.muse.moonpc.data.RuleStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 分流规则页（P1，全新实现，对标 Surge 规则系统）：
 * - 单条规则：域名/后缀/关键词/IP段/GeoIP/端口 → 代理/直连/拦截/策略组
 * - 远端规则集订阅：URL 拉取解析，定时手动更新
 * - 规则按顺序匹配首条命中；改动重启代理后生效
 */
@Composable
fun RuleScreen(
    ruleStore: RuleStore,
    policyStore: PolicyStore,
) {
    val scope = rememberCoroutineScope()
    var editingRule by remember { mutableStateOf<RouteRule?>(null) }
    var showRuleEditor by remember { mutableStateOf(false) }
    var isNewRule by remember { mutableStateOf(true) }
    var editingSet by remember { mutableStateOf<RuleSetSubscription?>(null) }
    var showSetEditor by remember { mutableStateOf(false) }
    var isNewSet by remember { mutableStateOf(true) }
    var updatingAll by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }

    val groupAliases = remember(policyStore.defs.size) {
        policyStore.defs.map { it.alias.ifBlank { "策略组" } }
    }

    SurgePage {
        PageHeader(
            title = "分流规则",
            subtitle = "${ruleStore.rules.size} 条规则",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            updatingAll = true
                            statusMsg = ""
                            scope.launch {
                                val (ok, fail) = ruleStore.refreshAllRuleSets()
                                statusMsg = "规则集更新完成：成功 $ok，失败 $fail（重启代理后生效）"
                                updatingAll = false
                            }
                        },
                        enabled = !updatingAll && ruleStore.ruleSets.isNotEmpty(),
                    ) {
                        Text(
                            if (updatingAll) "更新中…" else "更新订阅",
                            fontSize = 14.sp,
                            color = MoonColors.Accent,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { isNewSet = true; editingSet = null; showSetEditor = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MoonColors.Accent),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("＋订阅", fontSize = 14.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { isNewRule = true; editingRule = null; showRuleEditor = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MoonColors.Accent),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("＋规则", fontSize = 14.sp)
                    }
                }
            }
        )

        if (statusMsg.isNotEmpty()) {
            Text(
                statusMsg,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            // ---- 规则集订阅 ----
            if (ruleStore.ruleSets.isNotEmpty()) {
                item(key = "sets-header") {
                    Text(
                        "规则集订阅",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(ruleStore.ruleSets, key = { "set-${it.id}" }) { s ->
                    RuleSetCard(
                        sub = s,
                        onUpdate = {
                            scope.launch {
                                runCatching { ruleStore.refreshRuleSet(s.id) }
                                    .onSuccess { n ->
                                        statusMsg = "「${s.name}」更新成功：$n 条（重启代理后生效）"
                                    }
                                    .onFailure { e ->
                                        statusMsg = "「${s.name}」更新失败：${e.message}"
                                    }
                            }
                        },
                        onEdit = { isNewSet = false; editingSet = s; showSetEditor = true },
                        onDelete = { ruleStore.removeRuleSet(s.id) },
                    )
                }
                item(key = "rules-header") {
                    Text(
                        "单条规则（按顺序匹配首条命中）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            // ---- 单条规则 ----
            if (ruleStore.rules.isEmpty()) {
                item(key = "empty") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "暂无规则，点击右上「＋规则」添加\n改动在重启代理后生效",
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(ruleStore.rules, key = { it.id }) { r ->
                    RuleRowCard(
                        rule = r,
                        groupAliases = groupAliases,
                        onEdit = { isNewRule = false; editingRule = r; showRuleEditor = true },
                        onDelete = { ruleStore.removeRule(r.id) },
                        onUp = { ruleStore.moveRule(r.id, -1) },
                        onDown = { ruleStore.moveRule(r.id, 1) },
                    )
                }
            }
            item(key = "footer") {
                Text(
                    "规则改动在重启代理后生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }

    if (showRuleEditor) {
        RuleEditDialog(
            initial = editingRule,
            groupAliases = groupAliases,
            onClose = { showRuleEditor = false },
            onSave = { type, value, action ->
                val ok = if (isNewRule) ruleStore.addRule(type, value, action) != null
                else ruleStore.updateRule(editingRule!!.id, type, value, action)
                if (ok) showRuleEditor = false
                else statusMsg = "保存失败：请检查值格式（如端口 1-65535、IP 段含 /、GeoIP 为两位字母）"
            },
        )
    }
    if (showSetEditor) {
        RuleSetEditDialog(
            initial = editingSet,
            groupAliases = groupAliases,
            onClose = { showSetEditor = false },
            onSave = { name, url, action ->
                val ok = if (isNewSet) ruleStore.addRuleSet(name, url, action) != null
                else ruleStore.updateRuleSetMeta(editingSet!!.id, name, url, action)
                if (ok) {
                    showSetEditor = false
                    if (isNewSet) {
                        val s = ruleStore.ruleSets.lastOrNull()
                        if (s != null) scope.launch {
                            runCatching { ruleStore.refreshRuleSet(s.id) }
                        }
                    }
                } else statusMsg = "保存失败：URL 须以 http:// 或 https:// 开头"
            },
        )
    }
}

@Composable
private fun RuleRowCard(
    rule: RouteRule,
    groupAliases: List<String>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeBadge(RouteRule.typeLabel(rule.type))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    rule.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                ActionBadge(rule.action, groupAliases)
            }
            TextButton(onClick = onUp) { Text("↑") }
            TextButton(onClick = onDown) { Text("↓") }
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RuleSetCard(
    sub: RuleSetSubscription,
    onUpdate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sub.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                ActionBadge(sub.action, emptyList())
            }
            Spacer(Modifier.height(4.dp))
            Text(
                sub.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        sub.error.isNotEmpty() -> "更新失败：${sub.error}"
                        sub.entryCount > 0 -> "${sub.entryCount} 条 · ${
                            if (sub.updatedAt > 0) fmt.format(Date(sub.updatedAt)) else "未更新"
                        }"
                        else -> "尚未更新"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sub.error.isNotEmpty()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                TextButton(onClick = onUpdate) { Text("更新") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun TypeBadge(text: String) {
    Box(
        modifier = Modifier.background(
            MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp),
        ).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ActionBadge(action: String, groupAliases: List<String>) {
    val (text, container, content) = when (action) {
        RouteRule.ACTION_PROXY -> Triple(
            "代理",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        RouteRule.ACTION_DIRECT -> Triple(
            "直连",
            Color(0xFFE8F5E9), Color(0xFF2E7D32),
        )
        RouteRule.ACTION_REJECT -> Triple(
            "拦截",
            Color(0xFFFCE4EC), Color(0xFFC62828),
        )
        else -> Triple(
            if (action in groupAliases) "→ $action" else "$action（组不存在）",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
    Box(
        modifier = Modifier.background(container, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/** 单条规则 新建/编辑弹窗 */
@Composable
private fun RuleEditDialog(
    initial: RouteRule?,
    groupAliases: List<String>,
    onClose: () -> Unit,
    onSave: (type: String, value: String, action: String) -> Unit,
) {
    var type by remember { mutableStateOf(initial?.type ?: RouteRule.TYPE_DOMAIN_SUFFIX) }
    var value by remember { mutableStateOf(initial?.value ?: "") }
    var action by remember { mutableStateOf(initial?.action ?: RouteRule.ACTION_PROXY) }

    DialogWindow(onCloseRequest = onClose, title = if (initial == null) "新建规则" else "编辑规则") {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(24.dp).width(440.dp)) {
                Text(
                    if (initial == null) "新建规则" else "编辑规则",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Text("类型", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Column {
                    RouteRule.TYPES.chunked(3).forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            row.forEach { t ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { type = t }.padding(end = 12.dp),
                                ) {
                                    RadioButton(selected = type == t, onClick = { type = t })
                                    Text(RouteRule.typeLabel(t), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(hintFor(type)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("动作", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Column {
                    listOf(
                        RouteRule.ACTION_PROXY to "代理",
                        RouteRule.ACTION_DIRECT to "直连",
                        RouteRule.ACTION_REJECT to "拦截",
                    ).forEach { (a, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { action = a },
                        ) {
                            RadioButton(selected = action == a, onClick = { action = a })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    groupAliases.forEach { alias ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { action = alias },
                        ) {
                            RadioButton(selected = action == alias, onClick = { action = alias })
                            Text("→ $alias", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(type, value.trim(), action) }) { Text("保存") }
                }
            }
        }
    }
}

private fun hintFor(type: String): String = when (type) {
    RouteRule.TYPE_DOMAIN -> "完整域名，如 mail.google.com"
    RouteRule.TYPE_DOMAIN_SUFFIX -> "域名后缀，如 google.com"
    RouteRule.TYPE_DOMAIN_KEYWORD -> "关键词，如 google"
    RouteRule.TYPE_IP_CIDR -> "IP 段，如 8.8.8.0/24"
    RouteRule.TYPE_GEOIP -> "两位国家码，如 CN"
    RouteRule.TYPE_PORT -> "端口，如 443"
    else -> "值"
}

/** 规则集订阅 新建/编辑弹窗 */
@Composable
private fun RuleSetEditDialog(
    initial: RuleSetSubscription?,
    groupAliases: List<String>,
    onClose: () -> Unit,
    onSave: (name: String, url: String, action: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var action by remember { mutableStateOf(initial?.action ?: RouteRule.ACTION_PROXY) }

    DialogWindow(onCloseRequest = onClose, title = if (initial == null) "添加规则集订阅" else "编辑规则集订阅") {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(24.dp).width(440.dp)) {
                Text(
                    if (initial == null) "添加规则集订阅" else "编辑规则集订阅",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("订阅 URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("动作", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(
                        RouteRule.ACTION_PROXY to "代理",
                        RouteRule.ACTION_DIRECT to "直连",
                        RouteRule.ACTION_REJECT to "拦截",
                    ).forEach { (a, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { action = a }.padding(end = 12.dp),
                        ) {
                            RadioButton(selected = action == a, onClick = { action = a })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (groupAliases.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("策略组：", style = MaterialTheme.typography.bodyMedium)
                        groupAliases.forEach { alias ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { action = alias }.padding(end = 8.dp),
                            ) {
                                RadioButton(selected = action == alias, onClick = { action = alias })
                                Text(alias, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "支持 Clash 经典格式与纯域名列表；保存后自动拉取一次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(name.trim(), url.trim(), action) }) { Text("保存") }
                }
            }
        }
    }
}
