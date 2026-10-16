package com.muse.moonpc.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.muse.moonpc.core.ClashApi
import com.muse.moonpc.core.ConfigGenerator
import com.muse.moonpc.core.JsEngine
import com.muse.moonpc.core.ProxyModeManager
import com.muse.moonpc.core.SingBoxManager
import com.muse.moonpc.core.TunManager
import com.muse.moonpc.core.WinRoundedCorners
import com.muse.moonpc.data.AppSettings
import com.muse.moonpc.data.DnsSettings
import com.muse.moonpc.data.NodeStore
import com.muse.moonpc.data.PRESET_ADGUARD_ID
import com.muse.moonpc.data.PolicyGroup
import com.muse.moonpc.data.PolicyStore
import com.muse.moonpc.data.RewriteStore
import com.muse.moonpc.data.RuleStore
import com.muse.moonpc.data.TrafficMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.awt.event.WindowEvent
import java.awt.event.WindowStateListener

/**
 * 主窗口（P2）：
 * - 窗口规范：iPad 4:3（960×720）、无边框 + Win11 DWM 原生圆角、屏幕居中
 * - 自定义标题栏：拖拽移动 + 最小化/最大化/关闭
 * - 顶栏：sing-box 运行状态 + 启动/停止 + 版本检测
 * - 模式栏：TUN / 系统代理 / 手动 切换 + 管理员状态
 * - 主体 Tab：概览 / 仪表盘 / 活动 / 节点 / 策略组 / 规则 / DNS / 设置
 * - 底栏：状态消息
 * - 系统托盘（图标状态随运行变化）、关闭/最小化到托盘
 */
@Composable
fun MainWindow(onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val manager = remember { SingBoxManager() }
    val store = remember { NodeStore() }
    val policyStore = remember { PolicyStore(nodeStore = store) }
    val ruleStore = remember { RuleStore() }
    val dnsSettings = remember { DnsSettings() }
    val rewriteStore = remember { RewriteStore() }
    val jsEngine = remember { JsEngine() }
    val settings = remember { AppSettings() }
    val trafficMonitor = remember { TrafficMonitor(scope = scope) }
    var running by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf("就绪") }
    var mode by remember { mutableStateOf(ConfigGenerator.ProxyMode.AUTO_ROUTE) }
    var tab by remember { mutableStateOf(0) }
    // 管理员检测会起子进程（Windows 上约 1 秒），放后台做，避免卡首帧
    var isAdmin by remember { mutableStateOf<Boolean?>(null) }
    val wintun = remember { TunManager.wintunStatus() }
    LaunchedEffect(Unit) {
        isAdmin = withContext(Dispatchers.IO) { ProxyModeManager.isAdmin() }
        settings.refreshAutoStart()
        version = withContext(Dispatchers.IO) {
            manager.detectVersion() ?: "未找到 sing-box"
        }
    }
    // 窗口状态：4:3（960×720），屏幕居中
    val windowState = rememberWindowState(
        width = 960.dp,
        height = 720.dp,
        position = WindowPosition(Alignment.Center),
    )
    // 窗口引用 holder（onCloseRequest 不在 WindowScope 内，通过它操作 window）
    val windowHolder = remember { mutableStateOf<java.awt.Window?>(null) }
    // 托盘引用（Window 内容里安装后回填，供 onCloseRequest 使用）
    val trayRef = remember { mutableStateOf<TrayManager?>(null) }

    /** 策略组切换出口：更新存储；运行中经 Clash API 实时生效 */
    val onSelectOutlet: (PolicyGroup, String) -> Unit = { g, nodeId ->
        val ok = policyStore.setSelected(g.id, nodeId)
        if (ok) {
            val nodeName = store.nodes.firstOrNull { it.id == nodeId }
                ?.let { it.name.ifBlank { it.host } } ?: nodeId.take(8)
            if (running) {
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        val groupTag = if (g.id == PolicyStore.BUILTIN_ID) "PROXY"
                        else ConfigGenerator.groupTag(g)
                        val tag = ConfigGenerator.nodeIdToTag(store.nodes)[nodeId]
                            ?: return@withContext Result.failure(
                                IllegalStateException("节点 tag 映射失败"),
                            )
                        ClashApi.selectOutbound(groupTag, tag, manager.clashPort)
                    }
                    statusMsg = if (r.isSuccess) "已切换 ${g.name} → $nodeName（实时生效）"
                    else "已选择 $nodeName（实时切换失败：${r.exceptionOrNull()?.message}，重启后生效）"
                }
            } else {
                statusMsg = "已选择 $nodeName（下次启动生效）"
            }
        }
    }

    /** 启动/停止代理（顶栏按钮与托盘菜单共用） */
    suspend fun toggleProxy() {
        if (running) {
            statusMsg = "正在停止…"
            trafficMonitor.stop()
            manager.stop()
            running = false
            statusMsg = "已停止"
        } else {
            val selId = store.selectedId ?: store.nodes.firstOrNull()?.id
            if (store.nodes.isEmpty()) {
                statusMsg = "没有节点，请先导入"
                return
            }
            statusMsg = "正在启动（${mode.label}）…"
            val r = manager.start(
                store.nodes,
                selId,
                mode,
                policyStore.effectiveCustomGroups(),
                policyStore.proxyOutboundTags(),
                ruleStore.rules.toList(),
                ruleStore.compiledRuleSets(),
                dnsSettings = dnsSettings,
                rewriteStore = rewriteStore,
                jsEngine = jsEngine,
            )
            if (r.isSuccess) {
                running = true
                trafficMonitor.start(manager.clashPort)
                val mitmNote = if (manager.isMitmRunning()) " + MITM(:${manager.mitmPort})" else ""
                statusMsg = when (mode) {
                    ConfigGenerator.ProxyMode.TUN ->
                        "运行中（TUN 全局接管$mitmNote）"
                    ConfigGenerator.ProxyMode.GLOBAL_PROXY ->
                        if (manager.isMitmRunning())
                            "运行中（全局代理 127.0.0.1:${manager.mitmPort} → MITM → :${manager.mixedPort}）"
                        else
                            "运行中（全局代理 127.0.0.1:${manager.mixedPort}）"
                    ConfigGenerator.ProxyMode.AUTO_ROUTE ->
                        if (manager.isMitmRunning())
                            "运行中（自动分流 127.0.0.1:${manager.mitmPort} → MITM → :${manager.mixedPort}）"
                        else
                            "运行中（自动分流 127.0.0.1:${manager.mixedPort}）"
                    ConfigGenerator.ProxyMode.GLOBAL_DIRECT ->
                        "运行中（全局直连 127.0.0.1:${manager.mixedPort}$mitmNote）"
                }
            } else {
                statusMsg = "启动失败：${r.exceptionOrNull()?.message}"
            }
        }
    }

    /** 真正退出：停流量采样、停代理、卸托盘、结束应用 */
    fun doExit() {
        scope.launch {
            runCatching { trafficMonitor.stop() }
            runCatching { manager.stop() }
            trayRef.value?.uninstall()
            onExit()
        }
    }

    /** 关闭请求：按设置最小化到托盘，否则退出 */
    fun handleCloseRequest() {
        val w = windowHolder.value
        val tray = trayRef.value
        if (w != null && settings.closeToTray && tray?.isSupported == true) {
            // 关闭时最小化到托盘：仅隐藏窗口
            w.isVisible = false
        } else {
            doExit()
        }
    }

    Window(
        onCloseRequest = ::handleCloseRequest,
        state = windowState,
        title = "Moon PC",
        undecorated = true,
    ) {
        val window = this.window

        // ---- Win11 DWM 原生圆角（参考 Gome PC） ----
        LaunchedEffect(Unit) {
            try {
                val hwnd = com.sun.jna.Native.getWindowPointer(window)
                WinRoundedCorners.apply(com.sun.jna.Pointer.nativeValue(hwnd))
            } catch (_: Exception) {
            }
        }

        // ---- M5：托盘安装 + 最小化到托盘监听 ----
        val tray = remember {
            TrayManager(
                isWindowVisible = { window.isVisible },
                onShowWindow = {
                    window.isVisible = true
                    window.extendedState = Frame.NORMAL
                    window.toFront()
                },
                onHideWindow = { window.isVisible = false },
                onToggleProxy = { scope.launch { toggleProxy() } },
                onExit = { doExit() },
            )
        }
        DisposableEffect(tray) {
            windowHolder.value = window
            tray.install()
            trayRef.value = tray
            val listener = WindowStateListener { e: WindowEvent ->
                if ((e.newState and Frame.ICONIFIED) != 0 &&
                    settings.minimizeToTray && tray.isSupported
                ) {
                    window.isVisible = false
                    window.extendedState = Frame.NORMAL
                }
            }
            window.addWindowStateListener(listener)
            onDispose {
                window.removeWindowStateListener(listener)
                tray.uninstall()
                trayRef.value = null
                windowHolder.value = null
            }
        }
        LaunchedEffect(running) {
            tray.updateRunning(running)
        }

        MoonTheme {
            Column(Modifier.fillMaxSize().background(MoonColors.Background)) {
                // ---- 自定义标题栏（可拖拽）：macOS 风格红绿灯 + 窗口控制 ----
                WindowDraggableArea {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .height(40.dp)
                            .background(MoonColors.SidebarBg)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "🌙 Moon PC",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MoonColors.TextPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "控制台",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MoonColors.TextSecondary,
                        )
                        Spacer(Modifier.weight(1f))
                        // macOS 风格红/黄/绿圆点（装饰）
                        TrafficDot(Color(0xFFFF5F57))
                        Spacer(Modifier.width(8.dp))
                        TrafficDot(Color(0xFFFFBD2E))
                        Spacer(Modifier.width(8.dp))
                        TrafficDot(Color(0xFF28CA42))
                        Spacer(Modifier.weight(1f))
                        // 最小化
                        TextButton(onClick = {
                            (window as? Frame)?.extendedState = Frame.ICONIFIED
                        }) { Text("—", color = MoonColors.TextSecondary) }
                        // 最大化/还原
                        TextButton(onClick = {
                            windowState.placement =
                                if (windowState.placement == WindowPlacement.Maximized)
                                    WindowPlacement.Floating else WindowPlacement.Maximized
                        }) {
                            Text(
                                if (windowState.placement == WindowPlacement.Maximized) "❐" else "▢",
                                color = MoonColors.TextSecondary,
                            )
                        }
                        // 关闭
                        TextButton(onClick = ::handleCloseRequest) {
                            Text("✕", color = MoonColors.TextSecondary)
                        }
                    }
                }
                HorizontalDivider(color = MoonColors.Divider)

                // ---- 主体：左侧边栏 + 右侧内容 ----
                Row(Modifier.weight(1f)) {
                    // ============ 左侧边栏（220px，Surge 风格） ============
                    Column(
                        modifier = Modifier.width(220.dp).fillMaxHeight()
                            .background(MoonColors.SidebarBg)
                            .padding(vertical = 12.dp),
                    ) {
                        Text(
                            "导航",
                            style = MaterialTheme.typography.labelMedium,
                            color = MoonColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        val tabs: List<Pair<String, ImageVector>> = listOf(
                            "概览" to Icons.Filled.Home,
                            "仪表盘" to Icons.Filled.Info,
                            "活动" to Icons.Filled.List,
                            "节点" to Icons.Filled.Star,
                            "策略组" to Icons.Filled.Edit,
                            "规则" to Icons.Filled.Check,
                            "重写" to Icons.Filled.Refresh,
                            "DNS" to Icons.Filled.Search,
                            "设置" to Icons.Filled.Settings,
                        )
                        tabs.forEachIndexed { i, (t, icon) ->
                            val selected = tab == i
                            Box(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent
                                    )
                                    .clickable { tab = i }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = if (selected) MaterialTheme.colorScheme.primary
                                        else MoonColors.TextSecondary,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        t,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MoonColors.TextPrimary,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        HorizontalDivider(
                            color = MoonColors.Divider,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        // 核心状态
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (version != null && !version!!.startsWith("未找到"))
                                            MoonColors.Success
                                        else MoonColors.TextSecondary
                                    ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (version != null && !version!!.startsWith("未找到")) "核心已就绪" else "核心未就绪",
                                style = MaterialTheme.typography.bodySmall,
                                color = MoonColors.TextSecondary,
                            )
                        }
                        Text(
                            version?.take(28) ?: "正在检测 sing-box…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MoonColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    // 侧边栏与内容区分隔
                    Box(
                        Modifier.width(1.dp).fillMaxHeight()
                            .background(MoonColors.Divider)
                    )
                    // ============ 右侧主内容区 ============
                    Column(Modifier.weight(1f)) {
                        // ---- 内容区顶栏：状态 + 模式分段控件 + 启动按钮 ----
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(MoonColors.SidebarBg)
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 运行状态
                            Box(
                                modifier = Modifier.size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (running) MoonColors.Success else MoonColors.TextSecondary
                                    ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (running) "运行中" else "已停止",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MoonColors.TextPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (running) "代理运行中" else "等待启动",
                                style = MaterialTheme.typography.bodySmall,
                                color = MoonColors.TextSecondary,
                            )
                            Spacer(Modifier.weight(1f))
                            // 模式分段控件
                            Row(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                    .background(MoonColors.Background)
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                ConfigGenerator.ProxyMode.entries.forEach { m ->
                                    val selected = mode == m
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (selected) MoonColors.CardBg else Color.Transparent
                                            )
                                            .clickable {
                                                if (!running) {
                                                    mode = m
                                                    manager.proxyMode = m
                                                } else {
                                                    statusMsg = "请先停止再切换模式"
                                                }
                                            }
                                            .padding(horizontal = 14.dp, vertical = 7.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            m.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (selected) MoonColors.TextPrimary
                                            else MoonColors.TextSecondary,
                                            fontWeight = if (selected) FontWeight.SemiBold
                                            else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            // 启动/停止按钮
                            Button(
                                onClick = { scope.launch { toggleProxy() } },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (running) MoonColors.Danger else MoonColors.Accent,
                                ),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(if (running) "停止" else "启动")
                            }
                        }
                        HorizontalDivider(color = MoonColors.Divider)
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                0 -> OverviewScreen(
                                    onNavigateTab = { tab = it },
                                )
                                1 -> DashboardScreen(running = running, monitor = trafficMonitor)
                                2 -> ActivityScreen(running = running, clashPort = manager.clashPort)
                                3 -> NodeListScreen(store)
                                4 -> PolicyScreen(
                                    store = store,
                                    policyStore = policyStore,
                                    running = running,
                                    clashPort = manager.clashPort,
                                    onSelectOutlet = onSelectOutlet,
                                )
                                5 -> RuleScreen(
                                    ruleStore = ruleStore,
                                    policyStore = policyStore,
                                )
                                6 -> RewriteScreen(
                                    rewriteStore = rewriteStore,
                                    jsEngine = jsEngine,
                                    running = running,
                                    onStatus = { statusMsg = it },
                                )
                                7 -> DnsScreen(dns = dnsSettings)
                                else -> SettingsScreen(settings)
                            }
                        }
                    }
                }

                // ---- 底栏 ----
                HorizontalDivider(color = MoonColors.Divider)
                Text(
                    statusMsg,
                    modifier = Modifier.fillMaxWidth()
                        .background(MoonColors.SidebarBg)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MoonColors.TextSecondary,
                )
            }
        }
    }
}

/** macOS 风格红绿灯圆点（标题栏装饰） */
@Composable
private fun TrafficDot(color: Color) {
    Box(
        modifier = Modifier.size(12.dp)
            .clip(CircleShape)
            .background(color),
    )
}
