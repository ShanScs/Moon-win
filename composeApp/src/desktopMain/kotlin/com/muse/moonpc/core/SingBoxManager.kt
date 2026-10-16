package com.muse.moonpc.core

import com.muse.moonpc.data.CompiledRuleSet
import com.muse.moonpc.data.PolicyGroup
import com.muse.moonpc.data.ProxyNode
import com.muse.moonpc.data.RouteRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * sing-box 进程管理（M1/M3）：
 * - 查找二进制（应用安装目录优先，其次 ~/.moon-pc/bin/，最后 PATH）
 * - 按代理模式生成配置（TUN / 系统代理 / 手动）
 * - 启动/停止进程，输出日志回调
 * - TUN 模式：启动前做管理员权限 + Wintun 检查
 * - 系统代理模式：启动后写 Windows 系统代理，停止时还原
 */
class SingBoxManager(
    private val workDir: File = File(System.getProperty("user.home"), ".moon-pc"),
    private val binaryName: String = defaultBinaryName(),
) {
    enum class State { STOPPED, STARTING, RUNNING }

    @Volatile
    var state: State = State.STOPPED
        private set

    /** 本地 mixed 入站端口 */
    val mixedPort: Int = 7890

    /** Clash API 端口（M4 策略组/延迟用） */
    val clashPort: Int = 9090

    /** 当前代理模式（UI 可切换） */
    var proxyMode: ConfigGenerator.ProxyMode = ConfigGenerator.ProxyMode.AUTO_ROUTE

    /** TUN 协议栈：gvisor（免驱动）/ system（Windows 走 Wintun） */
    var tunStack: String = "gvisor"

    /** 进程日志回调（UI 线程外调用） */
    var onLog: ((String) -> Unit)? = null

    private var process: Process? = null

    /** 系统代理是否由本次启动打开（停止时负责还原） */
    private var systemProxyOurs = false

    /** MITM 代理（P4）：启用时随 sing-box 一起拉起，监听 8888 */
    private var mitmProxy: MitmProxy? = null

    /** MITM 代理端口（系统代理启用 MITM 时指向这里） */
    val mitmPort: Int = 8888

    /** MITM 是否实际在运行 */
    fun isMitmRunning(): Boolean = mitmProxy?.isRunning() == true

    companion object {
        fun defaultBinaryName(): String {
            val os = System.getProperty("os.name").lowercase()
            return if (os.contains("win")) "sing-box.exe" else "sing-box"
        }
    }

    /** 查找 sing-box：应用安装目录优先，其次 workDir/bin，最后 PATH */
    fun findBinary(): File? {
        // 1. 应用安装目录（jpackage：exe 同目录）
        appDir()?.let { dir ->
            val f = File(dir, binaryName)
            if (f.isFile && f.canExecute()) return f
        }
        // 2. workDir/bin
        val bundled = File(workDir, "bin/$binaryName")
        if (bundled.isFile && bundled.canExecute()) return bundled
        // 3. PATH
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        for (d in pathDirs) {
            val f = File(d, binaryName)
            if (f.isFile && f.canExecute()) return f
        }
        return null
    }

    /** 应用安装目录：jpackage 启动时 exe 所在目录 */
    private fun appDir(): File? {
        return try {
            // java.home 指向 bundled runtime，上一级就是 app 目录
            val javaHome = System.getProperty("java.home") ?: return null
            val runtimeDir = File(javaHome)
            // jpackage 结构：<app>/runtime/bin/java，app 目录是 runtime 的父目录
            // exe 在 <app>/MoonPC.exe
            val appDir = runtimeDir.parentFile
            if (appDir != null && File(appDir, binaryName).isFile) appDir
            else {
                // 兜底：从代码源位置推导
                val codeSource = SingBoxManager::class.java.protectionDomain?.codeSource?.location?.toURI()
                    ?.let { File(it) }
                var dir = if (codeSource?.isFile == true) codeSource.parentFile else codeSource
                // 往上找 sing-box.exe，最多 4 层
                repeat(4) {
                    if (dir == null) return@repeat
                    if (File(dir, binaryName).isFile) return dir
                    dir = dir.parentFile
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 检测版本，失败返回 null */
    suspend fun detectVersion(): String? = withContext(Dispatchers.IO) {
        val bin = findBinary() ?: return@withContext null
        runCatching {
            val p = ProcessBuilder(bin.absolutePath, "version")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(10, TimeUnit.SECONDS)
            out.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /**
     * 按当前 [proxyMode] 生成 sing-box 配置。
     */
    fun generateConfig(
        nodes: List<ProxyNode>,
        selectedId: String?,
        policyGroups: List<PolicyGroup> = emptyList(),
    ): String =
        ConfigGenerator.generate(
            nodes, selectedId,
            ConfigGenerator.Options(
                mixedPort = mixedPort,
                clashPort = clashPort,
                mode = proxyMode,
                tunStack = tunStack,
            ),
            policyGroups,
        )

    /** start/stop 互斥锁：防并发启动导致双进程 */
    private val startStopLock = Any()

    /**
     * 启动：模式前置检查 → 写配置 → 校验 → 拉起进程 → 模式后置动作。
     * @param mode 本次启动用的代理模式（默认用 [proxyMode]）
     * @param policyGroups 自建策略组（PolicyStore.effectiveCustomGroups()）
     * @param proxyOutbounds 主 PROXY selector 候选（PolicyStore.proxyOutboundTags()）
     * @param routeRules 单条分流规则（RuleStore.rules）
     * @param compiledRuleSets 已解析远端规则集（RuleStore.compiledRuleSets()）
     * @param dnsSettings DNS 套件设置（DnsSettings）；null 则按旧逻辑
     */
    suspend fun start(
        nodes: List<ProxyNode>,
        selectedId: String?,
        mode: ConfigGenerator.ProxyMode = proxyMode,
        policyGroups: List<PolicyGroup> = emptyList(),
        proxyOutbounds: List<String> = emptyList(),
        routeRules: List<RouteRule> = emptyList(),
        compiledRuleSets: List<CompiledRuleSet> = emptyList(),
        dnsSettings: com.muse.moonpc.data.DnsSettings? = null,
        rewriteStore: com.muse.moonpc.data.RewriteStore? = null,
        jsEngine: JsEngine? = null,
    ): Result<Unit> {
        // 先在锁里抢状态，避免两个协程同时通过 STOPPED 检查
        val shouldRun = synchronized(startStopLock) {
            if (state != State.STOPPED) false
            else {
                state = State.STARTING
                true
            }
        }
        if (!shouldRun) return Result.success(Unit)
        return withContext(Dispatchers.IO) {

        // ---- TUN 前置检查 ----
        if (mode == ConfigGenerator.ProxyMode.TUN) {
            TunManager.preflightCheck(tunStack)?.let { msg ->
                return@withContext Result.failure(IllegalStateException(msg))
            }
        }

        val bin = findBinary()
            ?: return@withContext Result.failure(IllegalStateException("找不到 sing-box 二进制"))
        val started = runCatching {
            workDir.mkdirs()
            val cfgFile = File(workDir, "config.json")
            SecureFile.writeText(
                cfgFile,
                ConfigGenerator.generate(
                    nodes, selectedId,
                    ConfigGenerator.Options(
                        mixedPort = mixedPort,
                        clashPort = clashPort,
                        mode = mode,
                        tunStack = tunStack,
                        dnsEnabled = dnsSettings?.enabled == true,
                        dnsServers = dnsSettings?.servers?.map { it.address } ?: emptyList(),
                        dnsHosts = dnsSettings?.hosts?.associate { it.domain to it.ip } ?: emptyMap(),
                        dnsDomainRules = dnsSettings?.domainRules?.toList() ?: emptyList(),
                    ),
                    policyGroups,
                    proxyOutbounds,
                    routeRules,
                    compiledRuleSets,
                ),
            )

            // 先校验配置
            val check = ProcessBuilder(bin.absolutePath, "check", "-c", cfgFile.absolutePath)
                .redirectErrorStream(true).start()
            val checkOut = check.inputStream.bufferedReader().readText()
            check.waitFor(15, TimeUnit.SECONDS)
            if (check.exitValue() != 0) {
                throw IllegalStateException("配置校验失败：$checkOut")
            }

            val pb = ProcessBuilder(bin.absolutePath, "run", "-c", cfgFile.absolutePath)
                .redirectErrorStream(true)
            val p = pb.start()
            process = p
            state = State.RUNNING

            // 后台消费日志
            Thread({
                p.inputStream.bufferedReader().forEachLine { line ->
                    onLog?.invoke(line)
                }
            }, "sing-box-log").apply { isDaemon = true }.start()
        }
        if (started.isFailure) {
            state = State.STOPPED
            process = null
            return@withContext Result.failure(started.exceptionOrNull()!!)
        }

        // ---- MITM 代理（P4）：rewrite/js 有任一启用则拉起 ----
        // 注意：即使 mitmEnabled=false（不解密），只要有 URL重写/Header/JS 规则，
        // 明文 HTTP 仍可改写，所以有规则就起代理；无规则则不起。
        val wantMitm = rewriteStore != null && jsEngine != null &&
            (rewriteStore.mitmEnabled ||
                rewriteStore.urlRules.any { it.enabled } ||
                rewriteStore.headerRules.any { it.enabled } ||
                jsEngine.modules.any { it.enabled })
        if (wantMitm) {
            val mp = MitmProxy(
                listenPort = mitmPort,
                upstreamHost = "127.0.0.1",
                upstreamPort = mixedPort,
                rewriteStore = rewriteStore!!,
                jsEngine = jsEngine!!,
            )
            val mr = mp.start()
            if (mr.isFailure) {
                runCatching { stop() }
                return@withContext Result.failure(
                    IllegalStateException("MITM 代理启动失败：${mr.exceptionOrNull()?.message}"),
                )
            }
            mitmProxy = mp
        }

        // ---- 系统代理后置动作 ----
        // MITM 运行时系统代理指向 8888（MITM），否则指向 sing-box mixed（7890）
        // TUN 之外的三种模式（全局代理/自动分流/全局直连）都走系统代理
        if (mode != ConfigGenerator.ProxyMode.TUN) {
            val proxyPort = if (mitmProxy?.isRunning() == true) mitmPort else mixedPort
            val r = ProxyModeManager.enableSystemProxy(port = proxyPort)
            if (r.isFailure) {
                // 系统代理打不开：回滚 sing-box，避免半吊子状态
                runCatching { stop() }
                return@withContext Result.failure(
                    IllegalStateException("sing-box 已启动，但系统代理设置失败：${r.exceptionOrNull()?.message}"),
                )
            }
            systemProxyOurs = true
        }
        proxyMode = mode
        Result.success(Unit)
        }
    }

    /** 停止进程；若系统代理是本次打开的，负责关掉还原 */
    suspend fun stop() = withContext(Dispatchers.IO) {
        synchronized(startStopLock) {
            runCatching { mitmProxy?.stop() }
            mitmProxy = null
            val p = process
            if (p != null) {
                runCatching {
                    p.destroy()
                    if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
                }
            }
            process = null
            if (systemProxyOurs) {
                runCatching { ProxyModeManager.disableSystemProxy() }
                systemProxyOurs = false
            }
            state = State.STOPPED
        }
    }

    // ---------- 出站构造已移至 ConfigGenerator ----------
}