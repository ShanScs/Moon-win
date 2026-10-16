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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.muse.moonpc.core.ClashApi
import com.muse.moonpc.core.ConfigGenerator
import com.muse.moonpc.data.FilterDef
import com.muse.moonpc.data.NodeStore
import com.muse.moonpc.data.PolicyGroup
import com.muse.moonpc.data.PolicyGroupDef
import com.muse.moonpc.data.PolicyGroupType
import com.muse.moonpc.data.PolicyStore
import com.muse.moonpc.data.ProxyNode
import com.muse.moonpc.data.protocolBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 策略组页（P1，复刻 Android 版机制）：
 * - 内建「全球策略」🌐（成员用户自定义）+ 自建组（SELECT/URL_TEST，可嵌套/含直连拦截/筛选器）
 * - 展开查看成员延迟；selector 点击切换出口（运行中经 Clash API 实时生效）
 * - 自建组：编辑 / 删除 / 上移 / 下移；筛选器管理
 */
@Composable
fun PolicyScreen(
    store: NodeStore,
    policyStore: PolicyStore,
    running: Boolean,
    clashPort: Int,
    onSelectOutlet: (PolicyGroup, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showEditor by remember { mutableStateOf(false) }
    var editingDef by remember { mutableStateOf<PolicyGroupDef?>(null) }
    var editBuiltinMembers by remember { mutableStateOf(false) }
    var testingAll by remember { mutableStateOf(false) }
    // sing-box 出站 tag -> 节点 id（Clash API 返回的是 tag，需反查）
    val tagToId = remember(store.nodes) {
        ConfigGenerator.nodeIdToTag(store.nodes).entries.associate { (k, v) -> v to k }
    }

    SurgePage {
        PageHeader(
            title = "策略组",
            subtitle = "${policyStore.allGroups().size} 个策略组",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            testingAll = true
                            scope.launch {
                                store.testAllLatencies()
                                testingAll = false
                            }
                        },
                        enabled = !testingAll && store.nodes.isNotEmpty(),
                    ) {
                        Text(
                            if (testingAll) "测速中…" else "全部测速",
                            fontSize = 14.sp,
                            color = MoonColors.Accent,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { editingDef = null; showEditor = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MoonColors.Accent),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("＋ 新建", fontSize = 14.sp)
                    }
                }
            }
        )

        val groups = policyStore.allGroups()
        if (store.nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无节点，先去「节点」页导入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(groups, key = { it.id }) { g ->
                    val isBuiltin = g.id == PolicyStore.BUILTIN_ID
                    PolicyGroupCard(
                        group = g,
                        store = store,
                        tagToId = tagToId,
                        running = running,
                        clashPort = clashPort,
                        isBuiltin = isBuiltin,
                        onSelectOutlet = onSelectOutlet,
                        onEdit = {
                            if (isBuiltin) editBuiltinMembers = true
                            else {
                                editingDef = policyStore.findDef(g.id)
                                showEditor = true
                            }
                        },
                        onDelete = { policyStore.removeDef(g.id) },
                        onUp = { policyStore.moveDef(g.id, -1) },
                        onDown = { policyStore.moveDef(g.id, 1) },
                        onTestGroup = {
                            scope.launch {
                                val members = g.nodes.mapNotNull { id ->
                                    store.nodes.firstOrNull { it.id == id }
                                }
                                store.testLatencies(members)
                            }
                        },
                    )
                }
            }
        }
    }

    if (showEditor) {
        PolicyGroupEditDialog(
            store = store,
            policyStore = policyStore,
            initial = editingDef,
            onClose = { showEditor = false },
            onSave = { alias, type, testUrl, refs ->
                if (editingDef == null) policyStore.addDef(alias, type, testUrl, refs)
                else policyStore.updateDef(editingDef!!.id, alias, type, testUrl, refs)
                showEditor = false
            },
        )
    }
    if (editBuiltinMembers) {
        BuiltinMembersDialog(
            store = store,
            policyStore = policyStore,
            onClose = { editBuiltinMembers = false },
        )
    }
}

@Composable
private fun PolicyGroupCard(
    group: PolicyGroup,
    store: NodeStore,
    tagToId: Map<String, String>,
    running: Boolean,
    clashPort: Int,
    isBuiltin: Boolean,
    onSelectOutlet: (PolicyGroup, String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onTestGroup: () -> Unit,
) {
    var expanded by remember(group.id) { mutableStateOf(false) }
    val members = remember(group.nodes, store.nodes) {
        group.nodes.mapNotNull { id -> store.nodes.firstOrNull { it.id == id } }
    }
    // 运行中且展开时：从 Clash API 读取该组实际出口（tag -> 节点 id）
    var apiNowId by remember(group.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(expanded, running) {
        apiNowId = if (expanded && running) {
            withContext(Dispatchers.IO) {
                val tag = if (isBuiltin) PolicyStore.TAG_PROXY else ConfigGenerator.groupTag(group)
                ClashApi.currentOutbound(tag, clashPort)?.let { tagToId[it] }
            }
        } else {
            null
        }
    }
    val effectiveSelected = apiNowId
        ?: group.selected.takeIf { id -> members.any { it.id == id } }
    // urltest / fallback / loadbalance 最优：已测延迟中最低者
    val isAutoKind = group.kind == PolicyStore.KIND_URLTEST ||
        group.kind == PolicyStore.KIND_FALLBACK ||
        group.kind == PolicyStore.KIND_LOADBALANCE
    val bestId = remember(members, store.latencies) {
        if (!isAutoKind) null
        else members
            .mapNotNull { m -> (store.latencies[m.id] ?: -1).takeIf { it >= 0 }?.let { m.id to it } }
            .minByOrNull { it.second }
            ?.first
    }
    val currentName = members.firstOrNull { it.id == effectiveSelected }
        ?.let { it.name.ifBlank { it.host } } ?: "—"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (group.flag.isNotEmpty()) {
                            Text(group.flag, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            group.name,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                        KindChip(group.kind)
                        if (isBuiltin) {
                            Spacer(Modifier.width(6.dp))
                            MiniBadge("内建")
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${members.size} 个节点 · 当前：$currentName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (isBuiltin) {
                    TextButton(onClick = onEdit) { Text("成员") }
                } else {
                    TextButton(onClick = onUp) { Text("↑") }
                    TextButton(onClick = onDown) { Text("↓") }
                    TextButton(onClick = onEdit) { Text("编辑") }
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (expanded) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (group.kind) {
                            PolicyStore.KIND_SELECTOR -> "点击成员切换出口"
                            PolicyStore.KIND_FALLBACK -> "故障转移：当前出口失败时自动切换"
                            PolicyStore.KIND_LOADBALANCE -> "负载均衡：按健康检查自动选择"
                            else -> "自动选择延迟最低的节点"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onTestGroup) { Text("测速") }
                }
                if (members.isEmpty()) {
                    Text(
                        "成员为空（节点可能已被删除），请编辑添加成员",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    members.forEach { n ->
                        MemberRow(
                            node = n,
                            selectable = group.kind == PolicyStore.KIND_SELECTOR,
                            isSelected = n.id == effectiveSelected,
                            isBest = n.id == bestId && n.id != effectiveSelected,
                            latency = store.latencies[n.id],
                            onClick = { onSelectOutlet(group, n.id) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun MemberRow(
    node: ProxyNode,
    selectable: Boolean,
    isSelected: Boolean,
    isBest: Boolean,
    latency: Long?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = selectable, onClick = onClick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else Color.Transparent,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            RadioButton(selected = isSelected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(9.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                protocolBadge(node.protocol),
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    node.name.ifBlank { node.host },
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isBest) {
                    Spacer(Modifier.width(6.dp))
                    MiniBadge("最优", MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "${node.host}:${node.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        PolicyLatencyText(latency)
    }
}

@Composable
private fun KindChip(kind: String) {
    val label = PolicyStore.kindLabel(kind)
    val isAuto = kind == PolicyStore.KIND_URLTEST ||
        kind == PolicyStore.KIND_FALLBACK ||
        kind == PolicyStore.KIND_LOADBALANCE
    val container = if (isAuto)
        MaterialTheme.colorScheme.tertiaryContainer
    else
        MaterialTheme.colorScheme.secondaryContainer
    val content = if (isAuto)
        MaterialTheme.colorScheme.onTertiaryContainer
    else
        MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        modifier = Modifier.background(container, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

@Composable
private fun MiniBadge(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun PolicyLatencyText(latency: Long?) {
    val (text, color) = when {
        latency == null -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
        latency < 0 -> "超时" to MaterialTheme.colorScheme.error
        latency < 300 -> "${latency}ms" to Color(0xFF2E7D32)
        latency < 800 -> "${latency}ms" to Color(0xFFEF6C00)
        else -> "${latency}ms" to MaterialTheme.colorScheme.error
    }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
}

// ---------------------------------------------------------------------------
// 成员引用选择器：特殊（直连/拦截）+ 节点 + 策略组 + 筛选器
// ---------------------------------------------------------------------------

/** 成员引用多选面板（编辑弹窗内嵌）；checked 的 key 为 memberRef 编码 */
@Composable
private fun MemberRefPicker(
    store: NodeStore,
    policyStore: PolicyStore,
    checked: MutableMap<String, Boolean>,
    excludeDefId: String? = null,
) {
    var showFilterEditor by remember { mutableStateOf(false) }
    var editingFilter by remember { mutableStateOf<FilterDef?>(null) }

    // 特殊成员
    RefCheckRow(
        label = "直连",
        sub = "builtin:direct",
        checked = checked[PolicyGroupDef.REF_BUILTIN_DIRECT] ?: false,
        onToggle = { checked[PolicyGroupDef.REF_BUILTIN_DIRECT] = !(checked[PolicyGroupDef.REF_BUILTIN_DIRECT] ?: false) },
    )
    RefCheckRow(
        label = "拦截",
        sub = "builtin:reject",
        checked = checked[PolicyGroupDef.REF_BUILTIN_REJECT] ?: false,
        onToggle = { checked[PolicyGroupDef.REF_BUILTIN_REJECT] = !(checked[PolicyGroupDef.REF_BUILTIN_REJECT] ?: false) },
    )

    // 策略组（排除自己，防自引用）
    val otherDefs = policyStore.defs.filter { it.id != excludeDefId }
    if (otherDefs.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text("策略组", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        otherDefs.forEach { d ->
            val ref = PolicyGroupDef.groupRef(d.id)
            RefCheckRow(
                label = d.alias.ifBlank { "策略组" },
                sub = "嵌套引用 · ${policyStore.expandNodeIds(listOf(ref), setOf(d.id)).size} 个节点",
                checked = checked[ref] ?: false,
                onToggle = { checked[ref] = !(checked[ref] ?: false) },
            )
        }
    }

    // 筛选器
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("筛选器", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { editingFilter = null; showFilterEditor = true }) {
            Text("＋ 新建筛选器")
        }
    }
    if (policyStore.filters.isEmpty()) {
        Text(
            "按节点名关键词动态匹配，如关键词\"香港\"匹配所有含香港的节点",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        policyStore.filters.forEach { f ->
            val ref = PolicyGroupDef.filterRef(f.id)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked[ref] ?: false,
                    onCheckedChange = { checked[ref] = it },
                )
                Column(Modifier.weight(1f).clickable { checked[ref] = !(checked[ref] ?: false) }) {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        "关键词「${f.keyword}」· 当前匹配 ${
                            store.nodes.count { it.name.contains(f.keyword, ignoreCase = true) }
                        } 个节点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                TextButton(onClick = { editingFilter = f; showFilterEditor = true }) { Text("编辑") }
                TextButton(onClick = { policyStore.removeFilter(f.id) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    // 节点
    Spacer(Modifier.height(4.dp))
    Text("节点", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    if (store.nodes.isEmpty()) {
        Text(
            "暂无节点，先去「节点」页导入",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        LazyColumn(modifier = Modifier.height(220.dp).fillMaxWidth()) {
            items(store.nodes, key = { it.id }) { n ->
                val ref = PolicyGroupDef.nodeRef(n.id)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { checked[ref] = !(checked[ref] ?: false) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked[ref] ?: false,
                        onCheckedChange = { checked[ref] = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        n.name.ifBlank { n.host },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        protocolBadge(n.protocol),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showFilterEditor) {
        FilterEditDialog(
            initial = editingFilter,
            onClose = { showFilterEditor = false },
            onSave = { name, keyword ->
                if (editingFilter == null) {
                    val f = policyStore.addFilter(name, keyword)
                    checked[PolicyGroupDef.filterRef(f.id)] = true
                } else {
                    policyStore.updateFilter(editingFilter!!.id, name, keyword)
                }
                showFilterEditor = false
            },
        )
    }
}

@Composable
private fun RefCheckRow(
    label: String,
    sub: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 筛选器 新建/编辑弹窗 */
@Composable
private fun FilterEditDialog(
    initial: FilterDef?,
    onClose: () -> Unit,
    onSave: (name: String, keyword: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var keyword by remember { mutableStateOf(initial?.keyword ?: "") }
    var error by remember { mutableStateOf("") }

    DialogWindow(onCloseRequest = onClose, title = if (initial == null) "新建筛选器" else "编辑筛选器") {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(24.dp).width(380.dp)) {
                Text(
                    if (initial == null) "新建筛选器" else "编辑筛选器",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it; error = "" },
                    label = { Text("关键词（节点名包含即匹配，不区分大小写）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (keyword.isBlank()) {
                            error = "关键词不能为空"
                            return@Button
                        }
                        onSave(name.trim().ifBlank { "筛选器" }, keyword.trim())
                    }) { Text("保存") }
                }
            }
        }
    }
}

/** 自建策略组 新建/编辑弹窗 */
@Composable
private fun PolicyGroupEditDialog(
    store: NodeStore,
    policyStore: PolicyStore,
    initial: PolicyGroupDef?,
    onClose: () -> Unit,
    onSave: (alias: String, type: PolicyGroupType, testUrl: String, refs: List<String>) -> Unit,
) {
    var alias by remember { mutableStateOf(initial?.alias ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: PolicyGroupType.SELECT) }
    var testUrl by remember { mutableStateOf(initial?.testUrl ?: PolicyGroupDef.DEFAULT_TEST_URL) }
    var error by remember { mutableStateOf("") }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(initial) {
        checked.clear()
        initial?.memberRefs?.forEach { checked[it] = true }
    }

    DialogWindow(onCloseRequest = onClose, title = if (initial == null) "新建策略组" else "编辑策略组") {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(24.dp).width(480.dp)) {
                Text(
                    if (initial == null) "新建策略组" else "编辑策略组",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it; error = "" },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("类型", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = type == PolicyGroupType.SELECT,
                        onClick = { type = PolicyGroupType.SELECT },
                    )
                    Text("手动选择", modifier = Modifier.clickable { type = PolicyGroupType.SELECT })
                    Spacer(Modifier.width(16.dp))
                    RadioButton(
                        selected = type == PolicyGroupType.URL_TEST,
                        onClick = { type = PolicyGroupType.URL_TEST },
                    )
                    Text("自动测速", modifier = Modifier.clickable { type = PolicyGroupType.URL_TEST })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = type == PolicyGroupType.FALLBACK,
                        onClick = { type = PolicyGroupType.FALLBACK },
                    )
                    Text("故障转移", modifier = Modifier.clickable { type = PolicyGroupType.FALLBACK })
                    Spacer(Modifier.width(16.dp))
                    RadioButton(
                        selected = type == PolicyGroupType.LOAD_BALANCE,
                        onClick = { type = PolicyGroupType.LOAD_BALANCE },
                    )
                    Text("负载均衡", modifier = Modifier.clickable { type = PolicyGroupType.LOAD_BALANCE })
                }
                if (type == PolicyGroupType.URL_TEST || type == PolicyGroupType.FALLBACK) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = testUrl,
                        onValueChange = { testUrl = it },
                        label = { Text("测速 URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "成员（${checked.count { it.value }}）",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.height(320.dp).fillMaxWidth()) {
                    item {
                        MemberRefPicker(
                            store = store,
                            policyStore = policyStore,
                            checked = checked,
                            excludeDefId = initial?.id,
                        )
                    }
                }
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val refs = checked.filter { it.value }.keys.toList()
                        error = when {
                            alias.isBlank() && initial == null -> "名称不能为空"
                            refs.isEmpty() -> "至少选择 1 个成员"
                            else -> ""
                        }
                        if (error.isEmpty()) {
                            onSave(
                                alias.trim().ifBlank { initial?.alias ?: "策略组" },
                                type,
                                testUrl.trim().ifBlank { PolicyGroupDef.DEFAULT_TEST_URL },
                                refs,
                            )
                        }
                    }) { Text("保存") }
                }
            }
        }
    }
}

/** 内建「全球策略」成员编辑弹窗（仅成员） */
@Composable
private fun BuiltinMembersDialog(
    store: NodeStore,
    policyStore: PolicyStore,
    onClose: () -> Unit,
) {
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(Unit) {
        checked.clear()
        // 空 = 全部节点：回填时全选节点 ref
        val refs = if (policyStore.globalRefs.isEmpty())
            store.nodes.map { PolicyGroupDef.nodeRef(it.id) }
        else policyStore.globalRefs.toList()
        refs.forEach { checked[it] = true }
    }

    DialogWindow(onCloseRequest = onClose, title = "全球策略成员") {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(24.dp).width(480.dp)) {
                Text("全球策略成员", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "留空任何成员即代表全部节点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(360.dp).fillMaxWidth()) {
                    item {
                        MemberRefPicker(
                            store = store,
                            policyStore = policyStore,
                            checked = checked,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val refs = checked.filter { it.value }.keys.toList()
                        // 全选节点 == 留空（全部节点语义）
                        val allNodeRefs = store.nodes.map { PolicyGroupDef.nodeRef(it.id) }.toSet()
                        val onlyNodes = refs.all { it.startsWith("node:") }
                        policyStore.setGlobalRefs(
                            if (onlyNodes && refs.toSet() == allNodeRefs) emptyList() else refs,
                        )
                        onClose()
                    }) { Text("保存") }
                }
            }
        }
    }
}
