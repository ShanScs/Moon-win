package com.muse.moonpc.core

import java.io.File

/**
 * TUN 模式前置检查（全新实现）。
 *
 * - TUN + auto_route 需要管理员权限改路由表（Windows/macOS/Linux 都一样）
 * - stack=system 在 Windows 上走 Wintun 驱动，需要 wintun.dll；
 *   gvisor 是用户态协议栈，免驱动（默认推荐）
 */
object TunManager {

    /** Wintun 驱动状态（仅 Windows 有意义） */
    enum class WintunStatus(val label: String) {
        NOT_WINDOWS("非 Windows"),
        FOUND("已安装"),
        MISSING("未安装"),
    }

    fun isWindows(): Boolean = ProxyModeManager.isWindows()
    fun isAdmin(): Boolean = ProxyModeManager.isAdmin()

    /** 检测 wintun.dll 是否存在 */
    fun wintunStatus(): WintunStatus {
        if (!isWindows()) return WintunStatus.NOT_WINDOWS
        val windir = System.getenv("WINDIR") ?: """C:\Windows"""
        val found = listOf(
            File(windir, """System32\wintun.dll"""),
            File(windir, """SysWOW64\wintun.dll"""),
        ).any { it.isFile }
        return if (found) WintunStatus.FOUND else WintunStatus.MISSING
    }

    /**
     * TUN 启动前检查，返回 null 表示通过，否则返回给用户看的错误文案。
     */
    fun preflightCheck(tunStack: String): String? {
        if (!isAdmin()) {
            return "TUN 模式需要管理员权限：请右键 Moon PC，选择“以管理员身份运行”后再试。" +
                "（TUN 要修改系统路由表，普通权限做不到）"
        }
        if (isWindows() && tunStack.equals("system", ignoreCase = true) &&
            wintunStatus() == WintunStatus.MISSING
        ) {
            return "未检测到 Wintun 驱动（C:\\Windows\\System32\\wintun.dll）。" +
                "请安装 Wintun，或把 TUN 协议栈改成 gvisor（免驱动）。"
        }
        return null
    }

    /** TUN 运行诊断：网卡是否存在 */
    fun tunInterfaceExists(interfaceName: String = "moon-tun"): Boolean {
        if (!isWindows()) return false
        return runCatching {
            val p = ProcessBuilder("ipconfig").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            out.contains(interfaceName, ignoreCase = true)
        }.getOrDefault(false)
    }

    /** TUN 运行诊断：劫持路由是否存在（auto_route 是否生效） */
    fun tunRoutesPresent(): Boolean {
        if (!isWindows()) return false
        return runCatching {
            val p = ProcessBuilder("route", "print", "-4").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            // auto_route 会加 0.0.0.0/1 和 128.0.0.0/1（或 route print 显示为 0.0.0.0 mask 128.0.0.0）
            out.contains("0.0.0.0/1") || out.contains("128.0.0.0/1") ||
                (out.contains("0.0.0.0") && out.contains("128.0.0.0") && out.contains("172.19.0."))
        }.getOrDefault(false)
    }

    /** 一句话诊断结论 */
    fun diagnose(interfaceName: String = "moon-tun"): String {
        if (!isWindows()) return "非 Windows，跳过诊断"
        val nic = tunInterfaceExists(interfaceName)
        val routes = tunRoutesPresent()
        return when {
            !nic && !routes -> "TUN 网卡不存在，路由也没有——sing-box 的 TUN 根本没建起来"
            nic && !routes -> "网卡在，但劫持路由缺失——auto_route 没生效，流量不会进 TUN"
            !nic && routes -> "路由在但网卡不在——状态异常，重启代理试试"
            else -> "网卡和路由都在——流量进了 TUN，问题在 sing-box 内部分流规则"
        }
    }
}
