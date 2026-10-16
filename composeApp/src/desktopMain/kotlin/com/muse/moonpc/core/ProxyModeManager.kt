package com.muse.moonpc.core

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 系统代理模式管理（全新实现）。
 *
 * Windows：写注册表 HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings
 * （ProxyEnable / ProxyServer / ProxyOverride），再用 PowerShell + P/Invoke
 * 调用 wininet!InternetSetOption 通知系统刷新（不依赖 JNA）。
 *
 * 非 Windows 平台：返回失败并说明仅支持 Windows（macOS/Linux 后续再接）。
 */
object ProxyModeManager {

    private const val REG_PATH = """HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"""
    private const val BYPASS =
        "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;" +
            "172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;" +
            "172.30.*;172.31.*;192.168.*;<local>"

    fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    /**
     * 是否管理员权限。
     * Windows：`net session` 只有管理员能成功；Unix：uid == 0。
     */
    fun isAdmin(): Boolean {
        return try {
            if (isWindows()) {
                val p = ProcessBuilder("cmd", "/c", "net session")
                    .redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText()
                p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
            } else {
                val p = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor(10, TimeUnit.SECONDS) && out == "0"
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 启用系统代理 → host:port（host 仅允许 IP/域名，防止 PowerShell 注入） */
    fun enableSystemProxy(host: String = "127.0.0.1", port: Int = 7890): Result<Unit> {
        if (!isWindows()) return Result.failure(UnsupportedOperationException("系统代理设置目前仅支持 Windows"))
        if (!host.matches(Regex("^[A-Za-z0-9._-]+$")) || port !in 1..65535) {
            return Result.failure(IllegalArgumentException("非法代理地址"))
        }
        val ps = """
            ${'$'}ErrorActionPreference = 'Stop'
            Set-ItemProperty -Path '$REG_PATH' -Name ProxyEnable -Value 1 -Type DWord
            Set-ItemProperty -Path '$REG_PATH' -Name ProxyServer -Value '$host:$port' -Type String
            Set-ItemProperty -Path '$REG_PATH' -Name ProxyOverride -Value '$BYPASS' -Type String
            ${notifySnippet()}
        """.trimIndent()
        return runPowerShell(ps).map { }
    }

    /** 关闭系统代理（保留 ProxyServer 值，只关开关） */
    fun disableSystemProxy(): Result<Unit> {
        if (!isWindows()) return Result.failure(UnsupportedOperationException("系统代理设置目前仅支持 Windows"))
        val ps = """
            ${'$'}ErrorActionPreference = 'Stop'
            Set-ItemProperty -Path '$REG_PATH' -Name ProxyEnable -Value 0 -Type DWord
            ${notifySnippet()}
        """.trimIndent()
        return runPowerShell(ps).map { }
    }

    /** 当前系统代理开关状态 */
    fun isSystemProxyEnabled(): Boolean {
        if (!isWindows()) return false
        return try {
            val ps = "(Get-ItemProperty -Path '$REG_PATH' -Name ProxyEnable -ErrorAction SilentlyContinue).ProxyEnable"
            val p = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", ps,
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(10, TimeUnit.SECONDS) && out == "1"
        } catch (_: Exception) {
            false
        }
    }

    /** 通知系统代理设置已变更（wininet P/Invoke，无需 JNA） */
    private fun notifySnippet(): String = """
        Add-Type @'
        using System;
        using System.Runtime.InteropServices;
        public static class WinINet {
            [DllImport("wininet.dll", SetLastError = true)]
            public static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);
        }
        '@
        [WinINet]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null
        [WinINet]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null
    """.trimIndent()

    /** 写临时 ps1 并执行，返回 stdout；非 0 退出码视为失败 */
    private fun runPowerShell(script: String): Result<String> {
        return try {
            val tmp = File.createTempFile("moon-proxy", ".ps1")
            try {
                tmp.writeText(script, Charsets.UTF_16)
                val p = ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", tmp.absolutePath,
                ).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                val ok = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
                if (ok) Result.success(out.trim())
                else Result.failure(IllegalStateException("PowerShell 执行失败：${out.trim().take(300)}"))
            } finally {
                runCatching { tmp.delete() }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
