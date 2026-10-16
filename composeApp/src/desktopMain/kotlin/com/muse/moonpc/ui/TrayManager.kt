package com.muse.moonpc.ui

import java.awt.AlphaComposite
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage

/**
 * 系统托盘管理（M5，全新实现，对标 Surge Mac 托盘体验）：
 * - 托盘图标：月牙形，运行中绿色 / 已停止灰色
 * - 左键点击：显示/隐藏主窗口
 * - 右键菜单：显示主窗口、启动/停止代理、退出
 * - 关闭窗口时最小化到托盘由 MainWindow 的 onCloseRequest 配合完成
 *
 * 注意：AWT 回调跑在 AWT 事件线程，调用方需自行切到 Compose 协程
 * （MainWindow 里用 scope.launch 包一层）。
 */
class TrayManager(
    private val isWindowVisible: () -> Boolean,
    private val onShowWindow: () -> Unit,
    private val onHideWindow: () -> Unit,
    private val onToggleProxy: () -> Unit,
    private val onExit: () -> Unit,
) {
    val isSupported: Boolean = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    private var trayIcon: TrayIcon? = null
    private var toggleItem: MenuItem? = null
    private var running = false

    /** 安装托盘图标；不支持的平台直接返回 false */
    fun install(): Boolean {
        if (!isSupported) return false
        if (trayIcon != null) return true
        return runCatching {
            val popup = PopupMenu()

            val showItem = MenuItem("显示主窗口")
            showItem.addActionListener { onShowWindow() }
            popup.add(showItem)

            popup.addSeparator()

            val toggle = MenuItem(if (running) "停止代理" else "启动代理")
            toggle.addActionListener { onToggleProxy() }
            toggleItem = toggle
            popup.add(toggle)

            popup.addSeparator()

            val exitItem = MenuItem("退出")
            exitItem.addActionListener { onExit() }
            popup.add(exitItem)

            val icon = TrayIcon(makeIcon(running), "Moon PC", popup).apply {
                isImageAutoSize = true
                // 左键点击：显示/隐藏主窗口
                addActionListener {
                    if (isWindowVisible()) onHideWindow() else onShowWindow()
                }
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            true
        }.getOrDefault(false)
    }

    /** 卸载托盘图标 */
    fun uninstall() {
        runCatching {
            trayIcon?.let { SystemTray.getSystemTray().remove(it) }
            trayIcon = null
            toggleItem = null
        }
    }

    /** 更新运行状态：图标颜色 + 菜单文案 + 悬停提示 */
    fun updateRunning(r: Boolean) {
        running = r
        trayIcon?.let {
            it.image = makeIcon(r)
            it.toolTip = if (r) "Moon PC（运行中）" else "Moon PC（已停止）"
        }
        toggleItem?.label = if (r) "停止代理" else "启动代理"
    }

    /**
     * 生成托盘图标：月牙形。
     * 运行中绿色，未运行灰色。
     */
    private fun makeIcon(running: Boolean): Image {
        val size = 32
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val color = if (running) AwtColor(0x4C, 0xAF, 0x50) else AwtColor(0x9E, 0x9E, 0x9E)
        // 月盘
        g.color = color
        g.fillOval(2, 2, size - 4, size - 4)
        // 用 DST_OUT 挖掉一块形成月牙
        g.composite = AlphaComposite.getInstance(AlphaComposite.DST_OUT)
        g.fillOval(11, 3, size - 6, size - 6)
        g.dispose()
        return img
    }
}
