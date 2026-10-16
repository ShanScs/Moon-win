package com.muse.moonpc.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.muse.moonpc.core.SecureFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 策略组存储（P1 重写，复刻 Android 版机制，全新实现）：
 * - 内建「全球策略」组（🌐 flag），成员引用用户自定义（空 = 全部节点）
 * - 自建策略组走 PolicyGroupDef：SELECT / URL_TEST，可嵌套、可含直连/拦截/筛选器成员
 * - 筛选器 FilterDef：节点名 contains 语义，动态匹配
 * - 持久化 ~/.moon-pc/policies.json（v2）；兼容读取旧版 v1（groups 纯节点 id）
 * - 节点增删时展开结果自动跟随；仅清理失效的 selector 选中态
 */
class PolicyStore(
    private val dir: File = File(System.getProperty("user.home"), ".moon-pc"),
    private val nodeStore: NodeStore,
) {
    /** 自建策略组定义（Compose 可观察，顺序 = 展示顺序） */
    val defs = mutableStateListOf<PolicyGroupDef>()

    /** 节点筛选器 */
    val filters = mutableStateListOf<FilterDef>()

    /** 内建全球策略成员引用；空 = 全部节点 */
    val globalRefs = mutableStateListOf<String>()

    /** selector 组 defId -> 选中的节点 id */
    private val selections = mutableStateMapOf<String, String>()

    private val file: File get() = File(dir, "policies.json")

    init {
        load()
        nodeStore.addChangeListener { pruneSelections() }
    }

    companion object {
        /** 内建全球策略组 id（展示用，不持久化为 def） */
        const val BUILTIN_ID = "builtin-global"

        /** 内建全球策略 sing-box 出站 tag（与旧版 PROXY 兼容） */
        const val TAG_PROXY = "PROXY"

        /** 手动选择 */
        const val KIND_SELECTOR = "selector"

        /** 自动测速 */
        const val KIND_URLTEST = "urltest"

        /** 故障转移（sing-box 映射为高 tolerance urltest） */
        const val KIND_FALLBACK = "fallback"

        /** 负载均衡（sing-box 无原生支持，降级为 urltest） */
        const val KIND_LOADBALANCE = "loadbalance"

        /** 类型中文标签 */
        fun kindLabel(kind: String): String = when (kind) {
            KIND_URLTEST -> "自动"
            KIND_FALLBACK -> "故障转移"
            KIND_LOADBALANCE -> "负载均衡"
            else -> "手动"
        }

        /** 与 ConfigGenerator.nodeTag 保持一致的本地 tag 推导（data 层避免依赖 core） */
        fun nodeOutboundTag(index: Int, id: String): String = "node-$index-${id.take(8)}"

        /** 策略组出站 tag：直接用别名 */
        fun groupOutboundTag(alias: String, id: String): String =
            alias.ifBlank { "group-${id.take(8)}" }
    }

    // ---------------- 展示用视图 ----------------

    /** 内建全球策略：成员用户自定义（空=全部节点）；选中态映射 NodeStore.selectedId */
    fun builtinGlobal(): PolicyGroup {
        val refs = if (globalRefs.isEmpty())
            nodeStore.nodes.map { PolicyGroupDef.nodeRef(it.id) }
        else globalRefs.toList()
        val nodeIds = expandNodeIds(refs)
        return PolicyGroup(
            id = BUILTIN_ID,
            name = "全球策略",
            flag = "🌐",
            nodes = nodeIds,
            selected = nodeStore.selectedId?.takeIf { it in nodeIds } ?: "",
            kind = KIND_SELECTOR,
            defId = null,
            memberTags = resolveOutboundTags(refs, emptySet()),
        )
    }

    /** 展示用全量组：内建在前，自建按 defs 顺序在后 */
    fun allGroups(): List<PolicyGroup> = listOf(builtinGlobal()) + defs.map { defToGroup(it) }

    fun findGroup(id: String): PolicyGroup? =
        if (id == BUILTIN_ID) builtinGlobal() else defs.firstOrNull { it.id == id }?.let { defToGroup(it) }

    fun findDef(id: String): PolicyGroupDef? = defs.firstOrNull { it.id == id }

    /**
     * 供 ConfigGenerator：自建组视图（memberTags 已解析），
     * 无有效出站成员的组会被过滤掉。
     */
    fun effectiveCustomGroups(): List<PolicyGroup> =
        defs.map { defToGroup(it) }.filter { it.memberTags.isNotEmpty() }

    /** 自建组出站 tag 列表（供路由动作校验） */
    fun customGroupTags(): List<String> =
        defs.map { groupOutboundTag(it.alias, it.id) }

    /**
     * 主 PROXY selector 的候选出站：自建组 tag + 全球策略成员 tag + direct。
     * 传给 ConfigGenerator.generate 的 proxyOutbounds 参数。
     */
    fun proxyOutboundTags(): List<String> {
        val tags = LinkedHashSet<String>()
        tags.addAll(effectiveCustomGroups().map { groupOutboundTag(it.name, it.defId ?: it.id) })
        tags.addAll(builtinGlobal().memberTags)
        tags.add("direct")
        return tags.toList()
    }

    // ---------------- 引用展开 ----------------

    /**
     * 将成员引用展开为节点 id 列表（UI 展示用）。
     * direct/reject 不对应节点，会被跳过；visited 防循环嵌套。
     */
    fun expandNodeIds(refs: List<String>, visited: Set<String> = emptySet()): List<String> {
        val out = LinkedHashSet<String>()
        val nodeIds = nodeStore.nodes.map { it.id }.toSet()
        refs.forEach { ref ->
            when {
                ref == PolicyGroupDef.REF_BUILTIN_DIRECT ||
                    ref == PolicyGroupDef.REF_BUILTIN_REJECT -> Unit // 非节点，跳过
                ref.startsWith("node:") ->
                    ref.removePrefix("node:").takeIf { it in nodeIds }?.let { out.add(it) }
                ref.startsWith("filter:") -> {
                    val f = filters.firstOrNull { it.id == ref.removePrefix("filter:") }
                    if (f != null && f.keyword.isNotBlank()) {
                        nodeStore.nodes
                            .filter { it.name.contains(f.keyword, ignoreCase = true) }
                            .forEach { out.add(it.id) }
                    }
                }
                ref.startsWith("group:") -> {
                    val gid = ref.removePrefix("group:")
                    if (gid !in visited) {
                        defs.firstOrNull { it.id == gid }?.let { d ->
                            out.addAll(expandNodeIds(d.memberRefs, visited + gid))
                        }
                    }
                }
            }
        }
        return out.toList()
    }

    /**
     * 将成员引用解析为 sing-box 出站标签：
     * 节点→节点 tag，直连→direct，拦截→block，嵌套组→组 tag（组出站另行生成）。
     */
    fun resolveOutboundTags(refs: List<String>, visited: Set<String> = emptySet()): List<String> {
        val out = LinkedHashSet<String>()
        val idToTag = nodeStore.nodes.mapIndexed { i, n -> n.id to nodeOutboundTag(i, n.id) }.toMap()
        val nodeIds = idToTag.keys
        refs.forEach { ref ->
            when {
                ref == PolicyGroupDef.REF_BUILTIN_DIRECT -> out.add("direct")
                ref == PolicyGroupDef.REF_BUILTIN_REJECT -> out.add("block")
                ref.startsWith("node:") ->
                    idToTag[ref.removePrefix("node:")]?.let { out.add(it) }
                ref.startsWith("filter:") -> {
                    val f = filters.firstOrNull { it.id == ref.removePrefix("filter:") }
                    if (f != null && f.keyword.isNotBlank()) {
                        nodeStore.nodes
                            .filter { it.id in nodeIds && it.name.contains(f.keyword, ignoreCase = true) }
                            .forEach { n -> idToTag[n.id]?.let { out.add(it) } }
                    }
                }
                ref.startsWith("group:") -> {
                    val gid = ref.removePrefix("group:")
                    if (gid !in visited) {
                        defs.firstOrNull { it.id == gid }?.let { d ->
                            out.add(groupOutboundTag(d.alias, d.id))
                        }
                    }
                }
            }
        }
        return out.toList()
    }

    private fun defToGroup(d: PolicyGroupDef): PolicyGroup {
        val nodeIds = expandNodeIds(d.memberRefs, setOf(d.id))
        val kind = when (d.type) {
            PolicyGroupType.URL_TEST -> KIND_URLTEST
            PolicyGroupType.FALLBACK -> KIND_FALLBACK
            PolicyGroupType.LOAD_BALANCE -> KIND_LOADBALANCE
            else -> KIND_SELECTOR
        }
        val sel = if (kind == KIND_SELECTOR) {
            selections[d.id]?.takeIf { it in nodeIds } ?: nodeIds.firstOrNull() ?: ""
        } else ""
        return PolicyGroup(
            id = d.id,
            name = d.alias.ifBlank { "策略组" },
            flag = guessFlag(d.alias),
            nodes = nodeIds,
            selected = sel,
            kind = kind,
            defId = d.id,
            memberTags = resolveOutboundTags(d.memberRefs, setOf(d.id)),
            testUrl = d.testUrl,
        )
    }

    // ---------------- 自建组 CRUD ----------------

    fun addDef(
        alias: String,
        type: PolicyGroupType,
        testUrl: String,
        memberRefs: List<String>,
    ): PolicyGroupDef {
        val d = PolicyGroupDef(
            id = UUID.randomUUID().toString(),
            alias = uniqueAlias(alias.trim().ifBlank { "策略组" }),
            type = type,
            testUrl = testUrl.trim().ifBlank { PolicyGroupDef.DEFAULT_TEST_URL },
            memberRefs = memberRefs.distinct(),
        )
        defs.add(d)
        save()
        return d
    }

    fun updateDef(
        id: String,
        alias: String,
        type: PolicyGroupType,
        testUrl: String,
        memberRefs: List<String>,
    ) {
        val idx = defs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val old = defs[idx]
        val cleanAlias = alias.trim().ifBlank { old.alias }
        defs[idx] = old.copy(
            alias = if (cleanAlias == old.alias) old.alias else uniqueAlias(cleanAlias, excludeId = id),
            type = type,
            testUrl = testUrl.trim().ifBlank { PolicyGroupDef.DEFAULT_TEST_URL },
            memberRefs = memberRefs.distinct(),
        )
        // 类型切换/成员变化后修正选中态
        val nodeIds = expandNodeIds(defs[idx].memberRefs, setOf(id))
        if (type != PolicyGroupType.SELECT) selections.remove(id)
        else selections[id]?.takeIf { it in nodeIds } ?: run { selections.remove(id) }
        save()
    }

    fun removeDef(id: String) {
        defs.removeAll { it.id == id }
        selections.remove(id)
        // 清理其他组对它的嵌套引用
        for (i in defs.indices) {
            val d = defs[i]
            val ref = PolicyGroupDef.groupRef(id)
            if (ref in d.memberRefs) {
                defs[i] = d.copy(memberRefs = d.memberRefs - ref)
            }
        }
        save()
    }

    /** 上移/下移排序（代替拖拽） */
    fun moveDef(id: String, delta: Int): Boolean {
        val idx = defs.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val to = (idx + delta).coerceIn(0, defs.size - 1)
        if (to == idx) return false
        val d = defs.removeAt(idx)
        defs.add(to, d)
        save()
        return true
    }

    /** 内建全球策略成员引用设置（空 = 全部节点） */
    fun setGlobalRefs(refs: List<String>) {
        globalRefs.clear()
        globalRefs.addAll(refs.distinct())
        save()
    }

    // ---------------- 筛选器 CRUD ----------------

    fun addFilter(name: String, keyword: String): FilterDef {
        val f = FilterDef(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "筛选器" },
            keyword = keyword.trim(),
        )
        filters.add(f)
        save()
        return f
    }

    fun updateFilter(id: String, name: String, keyword: String) {
        val idx = filters.indexOfFirst { it.id == id }
        if (idx < 0) return
        filters[idx] = filters[idx].copy(
            name = name.trim().ifBlank { filters[idx].name },
            keyword = keyword.trim(),
        )
        save()
    }

    fun removeFilter(id: String) {
        filters.removeAll { it.id == id }
        val ref = PolicyGroupDef.filterRef(id)
        for (i in defs.indices) {
            val d = defs[i]
            if (ref in d.memberRefs) defs[i] = d.copy(memberRefs = d.memberRefs - ref)
        }
        val gref = PolicyGroupDef.filterRef(id)
        if (gref in globalRefs) globalRefs.removeAll { it == gref }
        save()
    }

    // ---------------- 选中 ----------------

    /**
     * selector 手动切换出口；urltest 组调用返回 false。
     * 内建组走 NodeStore.select。
     */
    fun setSelected(groupId: String, nodeId: String): Boolean {
        if (groupId == BUILTIN_ID) {
            if (nodeStore.nodes.none { it.id == nodeId }) return false
            nodeStore.select(nodeId)
            return true
        }
        val d = defs.firstOrNull { it.id == groupId } ?: return false
        if (d.type != PolicyGroupType.SELECT) return false
        if (nodeId !in expandNodeIds(d.memberRefs, setOf(groupId))) return false
        selections[groupId] = nodeId
        save()
        return true
    }

    // ---------------- 内部 ----------------

    /** 别名唯一：冲突时追加 " (2)"；"全球策略"/"PROXY" 为内建保留名 */
    private fun uniqueAlias(base: String, excludeId: String? = null): String {
        val taken = defs.filter { it.id != excludeId }.map { it.alias }.toSet() +
            setOf("全球策略", "PROXY")
        if (base !in taken) return base
        var i = 2
        while ("$base ($i)" in taken) i++
        return "$base ($i)"
    }

    /** 节点被删除时清理失效的 selector 选中态 */
    private fun pruneSelections() {
        var changed = false
        val nodeIds = nodeStore.nodes.map { it.id }.toSet()
        val it = selections.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value !in nodeIds) {
                it.remove()
                changed = true
            }
        }
        if (changed) save()
    }

    private fun save() {
        runCatching {
            dir.mkdirs()
            val o = JSONObject()
            val ja = JSONArray()
            defs.forEach { d ->
                ja.put(
                    JSONObject()
                        .put("id", d.id)
                        .put("alias", d.alias)
                        .put("type", d.type.name)
                        .put("testUrl", d.testUrl)
                        .put("memberRefs", JSONArray(d.memberRefs)),
                )
            }
            o.put("defs", ja)
            val fa = JSONArray()
            filters.forEach { f ->
                fa.put(JSONObject().put("id", f.id).put("name", f.name).put("keyword", f.keyword))
            }
            o.put("filters", fa)
            o.put("globalRefs", JSONArray(globalRefs.toList()))
            val so = JSONObject()
            selections.forEach { (k, v) -> so.put(k, v) }
            o.put("selections", so)
            SecureFile.writeText(file, o.toString(2))
        }
    }

    private fun load() {
        runCatching {
            val f = file
            if (!f.isFile) return
            val root = JSONObject(f.readText())
            // v2
            if (root.has("defs")) {
                val ja = root.optJSONArray("defs") ?: JSONArray()
                for (i in 0 until ja.length()) {
                    val jo = ja.getJSONObject(i)
                    val refs = mutableListOf<String>()
                    val ra = jo.optJSONArray("memberRefs")
                    if (ra != null) for (j in 0 until ra.length()) refs.add(ra.optString(j))
                    defs.add(
                        PolicyGroupDef(
                            id = jo.optString("id").ifBlank { UUID.randomUUID().toString() },
                            alias = jo.optString("alias").ifBlank { "策略组" },
                            type = runCatching { PolicyGroupType.valueOf(jo.optString("type")) }
                                .getOrDefault(PolicyGroupType.SELECT),
                            testUrl = jo.optString("testUrl")
                                .ifBlank { PolicyGroupDef.DEFAULT_TEST_URL },
                            memberRefs = refs.distinct(),
                        ),
                    )
                }
                val fa = root.optJSONArray("filters") ?: JSONArray()
                for (i in 0 until fa.length()) {
                    val jo = fa.getJSONObject(i)
                    filters.add(
                        FilterDef(
                            id = jo.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = jo.optString("name").ifBlank { "筛选器" },
                            keyword = jo.optString("keyword"),
                        ),
                    )
                }
                val ga = root.optJSONArray("globalRefs") ?: JSONArray()
                for (i in 0 until ga.length()) globalRefs.add(ga.optString(i))
                val so = root.optJSONObject("selections")
                if (so != null) {
                    so.keys().forEach { k -> selections[k] = so.optString(k) }
                }
                // 重名兜底
                val seen = mutableSetOf<String>()
                for (i in defs.indices) {
                    if (defs[i].alias in seen) {
                        defs[i] = defs[i].copy(alias = uniqueAlias(defs[i].alias, excludeId = defs[i].id))
                    }
                    seen.add(defs[i].alias)
                }
                return
            }
            // v1 迁移：groups 纯节点 id → def
            val ja = root.optJSONArray("groups") ?: return
            for (i in 0 until ja.length()) {
                val jo = ja.getJSONObject(i)
                val ids = mutableListOf<String>()
                val na = jo.optJSONArray("nodes")
                if (na != null) for (j in 0 until na.length()) ids.add(na.optString(j))
                val kind = jo.optString("kind")
                defs.add(
                    PolicyGroupDef(
                        id = jo.optString("id").ifBlank { UUID.randomUUID().toString() },
                        alias = jo.optString("name").ifBlank { "策略组" },
                        type = if (kind == KIND_URLTEST) PolicyGroupType.URL_TEST else PolicyGroupType.SELECT,
                        memberRefs = ids.map { PolicyGroupDef.nodeRef(it) },
                    ),
                )
                val sel = jo.optString("selected")
                if (sel.isNotBlank()) selections[defs.last().id] = sel
            }
            save() // 落盘为 v2
        }
    }
}
