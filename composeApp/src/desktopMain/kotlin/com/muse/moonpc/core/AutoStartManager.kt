package com.muse.moonpc.core

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 开机自启管理（M5，全新实现）。
 *
 * Windows：写注册表 HKCU\Software\Microsoft\Windows\CurrentVersion\Run，
 * 值名 MoonPC，值为启动命令（打包后是 exe 路径）。
 * 用 PowerShell 读写注册表，不依赖 JNA（与 ProxyModeManager 同一手法）。
 *
 * 非 Windows 平台：返回失败并说明仅支持 Windows。
 */
object AutoStartManager {

    private const val REG_PATH = """HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val VALUE_NAME = "MoonPC"

    fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    /**
     * 当前进程的启动命令。
     * 打包 exe：直接就是 exe 路径；开发模式（java -jar）：拼出 java -jar 路径。
     */
    fun currentLaunchCommand(): String {
        val cmd = ProcessHandle.current().info().command().orElse("").trim()
        if (cmd.isBlank()) return ""
        val lower = cmd.lowercase()
        val isJava = lower.endsWith("java.exe") || lower.endsWith("javaw.exe")
            || lower.endsWith("/java") || lower == "java"
        return if (isJava) {
            val jar = runCatching {
                File(AutoStartManager::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
            }.getOrNull()
            if (jar != null && jar.endsWith(".jar", ignoreCase = true)) "\"$cmd\" -jar \"$jar\""
            else "\"$cmd\""
        } else {
            "\"$cmd\""
        }
    }

    /** 是否已设置开机自启 */
    fun isEnabled(): Boolean {
        if (!isWindows()) return false
        return try {
            val ps = "(Get-ItemProperty -Path '$REG_PATH' -Name '$VALUE_NAME' " +
                "-ErrorAction SilentlyContinue).$VALUE_NAME"
            val p = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", ps,
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(10, TimeUnit.SECONDS) && out.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    /** 开关开机自启 */
    fun setEnabled(enabled: Boolean): Result<Unit> {
        if (!isWindows()) {
            return Result.failure(UnsupportedOperationException("开机自启目前仅支持 Windows"))
        }
        if (enabled) {
            val cmd = currentLaunchCommand()
            if (cmd.isBlank()) {
                return Result.failure(IllegalStateException("无法获取程序启动路径"))
            }
            // 防注入：启动路径来自系统，仍对单引号做转义（PowerShell 单引号内 '' 表字面单引号）
            val safeCmd = cmd.replace("'", "''")
            val ps = """
                ${'$'}ErrorActionPreference = 'Stop'
                Set-ItemProperty -Path '$REG_PATH' -Name '$VALUE_NAME' -Value '$safeCmd' -Type String
            """.trimIndent()
            return runPowerShell(ps).map { }
        } else {
            val ps = """
                ${'$'}ErrorActionPreference = 'Stop'
                Remove-ItemProperty -Path '$REG_PATH' -Name '$VALUE_NAME' -ErrorAction SilentlyContinue
            """.trimIndent()
            return runPowerShell(ps).map { }
        }
    }

    /** 写临时 ps1 并执行，返回 stdout；非 0 退出码视为失败 */
    private fun runPowerShell(script: String): Result<String> {
        return try {
            val tmp = File.createTempFile("moon-autostart", ".ps1")
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
