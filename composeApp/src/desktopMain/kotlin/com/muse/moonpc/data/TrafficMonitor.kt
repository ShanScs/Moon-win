package com.muse.moonpc.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.muse.moonpc.core.ClashApi
import com.muse.moonpc.core.SecureFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 流量监控（仪表盘数据源，全新实现）：
 * - 每秒轮询 Clash API /connections，取累计 upload/download
 * - 速度 = 相邻两次采样的差值 / 间隔
 * - 会话累计 + 按天持久化（~/.moon-pc/traffic.json）
 * - 保留最近 120 个速度采样点，供曲线图绘制
 */
class TrafficMonitor(
    private val dir: File = File(System.getProperty("user.home"), ".moon-pc"),
    private val scope: CoroutineScope,
) {
    /** 当前上传速度 bytes/s */
    var upSpeed by mutableStateOf(0L)
    /** 当前下载速度 bytes/s */
    var downSpeed by mutableStateOf(0L)
    /** 本次会话累计上传 */
    var sessionUp by mutableStateOf(0L)
    /** 本次会话累计下载 */
    var sessionDown by mutableStateOf(0L)
    /** 今日累计上传（含历史） */
    var todayUp by mutableStateOf(0L)
    /** 今日累计下载（含历史） */
    var todayDown by mutableStateOf(0L)
    /** 历史总上传 */
    var totalUp by mutableStateOf(0L)
    /** 历史总下载 */
    var totalDown by mutableStateOf(0L)
    /** 活动连接数 */
    var activeConns by mutableStateOf(0)
    /** 最近速度采样：(up, down)，最多保留 120 点 */
    val history = mutableStateListOf<Pair<Long, Long>>()

    private var job: Job? = null
    private var lastUp = -1L
    private var lastDown = -1L
    private var lastTime = 0L
    private var currentDate = dayString()

    private val file: File get() = File(dir, "traffic.json")

    init {
        load()
    }

    /** 开始采样（代理启动时调用） */
    fun start(clashPort: Int) {
        stop()
        lastUp = -1L
        lastDown = -1L
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val t = ClashApi.connectionTotals(clashPort)
                if (t != null) onSample(t.first, t.second)
                // 连接数采样失败不影响速度
                ClashApi.activeConnections(clashPort)?.let { activeConns = it }
                delay(1000)
            }
        }
    }

    /** 停止采样（代理停止时调用），速度清零 */
    fun stop() {
        job?.cancel()
        job = null
        upSpeed = 0L
        downSpeed = 0L
        activeConns = 0
        save()
    }

    private suspend fun onSample(up: Long, down: Long) {
        val now = System.currentTimeMillis()
        // 跨天：把今日计数归档到历史
        val day = dayString()
        if (day != currentDate) {
            currentDate = day
        }
        if (lastUp >= 0 && lastDown >= 0 && now > lastTime) {
            // sing-box 重启会导致计数器归零：差值为负时只取正值部分
            val du = (up - lastUp).coerceAtLeast(0L)
            val dd = (down - lastDown).coerceAtLeast(0L)
            val dt = ((now - lastTime) / 1000.0).coerceAtLeast(0.5)
            val us = (du / dt).toLong()
            val ds = (dd / dt).toLong()
            withContext(Dispatchers.Main) {
                upSpeed = us
                downSpeed = ds
                sessionUp += du
                sessionDown += dd
                todayUp += du
                todayDown += dd
                totalUp += du
                totalDown += dd
                history.add(us to ds)
                if (history.size > 120) history.removeAt(0)
            }
            // 每 15 秒落盘一次（有流量变化时）
            if (now - lastSave > 15_000) {
                lastSave = now
                save()
            }
        }
        lastUp = up
        lastDown = down
        lastTime = now
    }

    private var lastSave = 0L

    private fun dayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun save() {
        runCatching {
            dir.mkdirs()
            SecureFile.writeText(
                file,
                JSONObject()
                    .put("date", currentDate)
                    .put("todayUp", todayUp)
                    .put("todayDown", todayDown)
                    .put("totalUp", totalUp)
                    .put("totalDown", totalDown)
                    .toString(2),
            )
        }
    }

    private fun load() {
        runCatching {
            val f = file
            if (!f.isFile) return
            val o = JSONObject(f.readText())
            val savedDate = o.optString("date")
            totalUp = o.optLong("totalUp")
            totalDown = o.optLong("totalDown")
            if (savedDate == dayString()) {
                todayUp = o.optLong("todayUp")
                todayDown = o.optLong("todayDown")
            } else {
                // 隔天启动：今日清零
                todayUp = 0L
                todayDown = 0L
            }
            currentDate = dayString()
        }
    }

    companion object {
        /** 字节数格式化：B / KB / MB / GB */
        fun formatBytes(b: Long): String = when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
            else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
        }

        /** 速度格式化：x KB/s */
        fun formatSpeed(bps: Long): String = formatBytes(bps) + "/s"
    }
}
