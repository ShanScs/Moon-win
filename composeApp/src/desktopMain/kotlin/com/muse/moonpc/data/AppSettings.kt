package com.muse.moonpc.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.muse.moonpc.core.AutoStartManager
import com.muse.moonpc.core.SecureFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 应用设置（M5，全新实现）：
 * - 内存：Compose 可观察状态
 * - 磁盘：~/.moon-pc/settings.json，每次变更即时落盘
 *
 * 注意：autoStart 的初始值以注册表实际状态为准（Windows），
 * 避免配置文件与注册表不一致。
 */
class AppSettings(
    private val dir: File = File(System.getProperty("user.home"), ".moon-pc"),
) {
    var autoStart by mutableStateOf(false)
    var minimizeToTray by mutableStateOf(true)
    var closeToTray by mutableStateOf(true)

    private val dataFile: File get() = File(dir, "settings.json")

    init {
        load()
    }

    fun save() {
        runCatching {
            dir.mkdirs()
            val o = JSONObject()
            o.put("autoStart", autoStart)
            o.put("minimizeToTray", minimizeToTray)
            o.put("closeToTray", closeToTray)
            SecureFile.writeText(dataFile, o.toString(2))
        }
    }

    private fun load() {
        runCatching {
            val f = dataFile
            if (f.isFile) {
                val o = JSONObject(f.readText())
                minimizeToTray = o.optBoolean("minimizeToTray", true)
                closeToTray = o.optBoolean("closeToTray", true)
            }
            // 注意：开机自启以注册表实际状态为准，但注册表查询会起 PowerShell
            // （约 1 秒），不能在 init 里同步做（会卡 UI 线程）。
            // 调用方在后台协程里调 refreshAutoStart()。
        }
    }

    /**
     * 从注册表刷新开机自启状态（IO 线程调用；Windows 才查询注册表）。
     * 避免配置文件与注册表不一致。
     */
    suspend fun refreshAutoStart() = withContext(Dispatchers.IO) {
        autoStart = if (AutoStartManager.isWindows()) AutoStartManager.isEnabled() else false
    }
}
