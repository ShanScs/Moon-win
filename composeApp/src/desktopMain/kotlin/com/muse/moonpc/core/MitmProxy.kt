package com.muse.moonpc.core

import com.muse.moonpc.data.HeaderRewriteRule
import com.muse.moonpc.data.RewriteStore
import com.muse.moonpc.data.UrlRewriteRule
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 本地 MITM 代理（全新实现）：
 * - 监听 127.0.0.1:8888，处理普通 HTTP 请求 + CONNECT 隧道
 * - MITM 开启时：CONNECT 到 443 用 CA 动态签发证书做 TLS 终止解密，
 *   流量依次过 URL重写 → Header改写 → JS模块，再经上游（sing-box :7890）重新 TLS 到源站
 * - 非解密域名：经上游盲转发（性能）
 * - 明文 HTTP：同样过规则管线，经上游 HTTP 代理转发
 *
 * 上游固定为 sing-box mixed 入站（HTTP 代理协议），保证分流规则依然生效。
 */
class MitmProxy(
    val listenPort: Int = 8888,
    val upstreamHost: String = "127.0.0.1",
    val upstreamPort: Int = 7890,
    private val rewriteStore: RewriteStore,
    private val jsEngine: JsEngine,
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "mitm-conn").apply { isDaemon = true }
    }

    /**
     * 上游连接抽象。
     * @param socket 真实 Socket；测试替身可为 null（此时需要 Socket 的操作不可用）
     */
    class UpstreamConn(
        val input: InputStream,
        val output: OutputStream,
        val close: () -> Unit,
        val socket: Socket?,
    )

    /**
     * 上游连接器（测试 seam）：(host, port, tunnel) -> UpstreamConn?
     * 为 null 时走默认 TCP 直连 upstreamHost:upstreamPort（tunnel=true 时先做 CONNECT 握手）。
     * 生产环境始终为 null；单测可注入假上游。
     */
    @Volatile var upstreamOpener: ((host: String, port: Int, tunnel: Boolean) -> UpstreamConn?)? = null

    /** 统计（UI 可展示） */
    @Volatile var decryptedCount: Long = 0
    @Volatile var relayedCount: Long = 0
    @Volatile var rewrittenCount: Long = 0

    fun isRunning(): Boolean = running.get()

    fun start(): Result<Unit> = runCatching {
        if (running.get()) return@runCatching
        if (rewriteStore.mitmEnabled) {
            // 预生成 CA，避免首个 CONNECT 时卡顿
            MitmCa.ensureCa().getOrThrow()
        }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("127.0.0.1", listenPort))
        serverSocket = ss
        running.set(true)
        Thread({
            while (running.get()) {
                try {
                    val c = ss.accept()
                    pool.execute { handleClient(c) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }, "mitm-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    // ---------------- 连接处理 ----------------

    private fun handleClient(client: Socket) {
        runCatching {
            client.soTimeout = 30_000
            handleConnection(
                input = client.getInputStream(),
                output = client.getOutputStream(),
                close = { runCatching { client.close() } },
                rawSocket = client,
            )
        }.onFailure { runCatching { client.close() } }
    }

    /**
     * 连接处理（基于流抽象，便于无 TCP 环境测试）。
     * @param rawSocket 生产环境为真实 client Socket（MITM TLS 包装需要）；测试可传 null（此时 MITM 解密不可用）。
     */
    fun handleConnection(input: InputStream, output: OutputStream, close: () -> Unit, rawSocket: Socket?) {
        runCatching {
            val req = readHttpHead(input) ?: run { close(); return }
            val parts = req.requestLine.split(" ", limit = 3)
            if (parts.size < 2) { close(); return }
            val method = parts[0].uppercase()
            val target = parts[1]
            if (method == "CONNECT") {
                handleConnect(input, output, close, rawSocket, req, target)
            } else {
                handlePlainHttp(input, output, close, req, method, target)
            }
        }.onFailure { close() }
    }

    // ---------------- CONNECT ----------------

    private fun handleConnect(
        input: InputStream,
        output: OutputStream,
        close: () -> Unit,
        rawSocket: Socket?,
        req: HttpHead,
        target: String,
    ) {
        val hostPort = target.substringBefore("/")
        val host = hostPort.substringBefore(":").lowercase()
        // 端口非法（负数/超范围/非数字）一律按 443 处理，避免畸形输入透传给上游
        val port = hostPort.substringAfter(":", "443").toIntOrNull()
            ?.takeIf { it in 1..65535 } ?: 443

        val shouldDecrypt = rewriteStore.mitmEnabled && port == 443 && host.isNotBlank()
        if (!shouldDecrypt) {
            // 盲转发：经上游建隧道后双向 relay
            val up = openUpstream(host, port, tunnel = true) ?: run {
                writeStatus(output, 502, "Bad Gateway")
                close(); return
            }
            writeStatus(output, 200, "Connection Established")
            relayedCount++
            relayBidirectional(input, output, up.input, up.output) { close(); up.close() }
            return
        }
        if (rawSocket == null) {
            // 无真实 Socket（测试环境），无法做 TLS 包装
            writeStatus(output, 502, "Bad Gateway")
            close(); return
        }

        // ---- MITM 解密 ----
        writeStatus(output, 200, "Connection Established")
        val tlsClient = runCatching {
            val ctx = MitmCa.serverSslContext(host)
            val s = ctx.socketFactory.createSocket(
                rawSocket, rawSocket.inetAddress.hostAddress, rawSocket.port, true,
            ) as SSLSocket
            s.useClientMode = false
            s.startHandshake()
            s
        }.getOrNull() ?: run { close(); return }
        decryptedCount++
        val tlsIn = tlsClient.getInputStream()
        val tlsOut = tlsClient.getOutputStream()

        try {
            tlsClient.soTimeout = 30_000
            // 解密后读 HTTP 请求（origin-form）
            val inner = readHttpHead(tlsIn)
                ?: run { tlsClient.close(); return }
            val iparts = inner.requestLine.split(" ", limit = 3)
            if (iparts.size < 2) { tlsClient.close(); return }
            val imethod = iparts[0].uppercase()
            val ipath = iparts[1]
            val url = "https://$host$ipath"

            // 管线：URL重写
            when (val hit = rewriteStore.matchUrlRewrite(url)) {
                null -> {}
                else -> {
                    rewrittenCount++
                    if (hit.action == UrlRewriteRule.ACTION_REJECT) {
                        writeStatus(tlsOut, 403, "Forbidden")
                        tlsClient.close(); return
                    }
                    val loc = sanitizeHeaderValue(hit.target.ifBlank { url })
                    tlsOut.write(
                        "HTTP/1.1 302 Found\r\nLocation: $loc\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(),
                    )
                    tlsOut.flush(); tlsClient.close(); return
                }
            }

            // 组装可改写的请求对象
            val jsReq = JsRequest(
                method = imethod, url = url, host = host, path = ipath,
                headers = inner.headers.toMutableMap(),
            )
            applyRequestHeaderRules(jsReq.headers, host)
            jsEngine.runRequestHooks(jsReq)

            // 经上游隧道 + TLS 到源站（必须做主机名校验，否则中间人可用任意有效证书冒充源站）
            val tunnelHost = jsReq.hostForTunnel(host)
            val tunnel = openUpstream(tunnelHost, port, tunnel = true) ?: run {
                writeStatus(tlsOut, 502, "Bad Gateway")
                tlsClient.close(); return
            }
            val tunnelSocket = tunnel.socket ?: run {
                writeStatus(tlsOut, 502, "Bad Gateway")
                tlsClient.close(); tunnel.close(); return
            }
            val tlsOrigin = runCatching {
                val sf = SSLContext.getDefault().socketFactory as SSLSocketFactory
                val s = sf.createSocket(tunnelSocket, tunnelHost, port, true) as SSLSocket
                // 关键：原生 SSLSocket 默认不校验主机名，必须显式开启（否则任意有效证书都能冒充源站）
                val params = s.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                s.sslParameters = params
                s.startHandshake()
                s
            }.getOrNull() ?: run {
                writeStatus(tlsOut, 502, "Bad Gateway")
                tlsClient.close(); tunnel.close(); return
            }

            // 发请求到源站（body 过大/分块时流式转发，不缓冲）
            val originOut = tlsOrigin.getOutputStream()
            val reqBody = readBody(inner, tlsIn)
            writeRequestHead(originOut, jsReq, inner, reqBody)
            forwardBodyRemainder(inner, tlsIn, originOut, reqBody)

            // 读源站响应
            val originIn = tlsOrigin.getInputStream()
            val resp = readHttpHead(originIn)
            if (resp == null) {
                writeStatus(tlsOut, 502, "Bad Gateway")
                tlsClient.close(); tlsOrigin.close(); return
            }
            val respBody = readBody(resp, originIn)
            val jsResp = JsResponse(
                url = jsReq.url, status = resp.statusCode(),
                headers = resp.headers.toMutableMap(), body = textBodyOrNull(resp, respBody.buffered),
            )
            applyResponseHeaderRules(jsResp.headers, host)
            val jsChanged = jsEngine.runResponseHooks(jsResp)
            // body 被 JS 改了则以 JS 为准（流式模式下 body 修改被忽略：body 未进 JS）
            val finalBody: ByteArray? = if (!respBody.streamRest && jsChanged && jsResp.body != null &&
                jsResp.body != textBodyOrNull(resp, respBody.buffered)
            ) {
                jsResp.body!!.toByteArray(Charsets.UTF_8)
            } else respBody.buffered

            if (respBody.streamRest) {
                writeResponseHead(tlsOut, jsResp.status, jsResp.headers, streaming = true)
                streamBody(resp, originIn, tlsOut)
            } else {
                writeResponse(tlsOut, jsResp.status, jsResp.headers, finalBody)
            }
            tlsClient.close()
            tlsOrigin.close()
        } catch (_: Exception) {
            runCatching { tlsClient.close() }
        }
    }

    /** JS 改 url 里的 host 时，隧道目标跟着变 */
    private fun JsRequest.hostForTunnel(fallback: String): String {
        return runCatching {
            val u = java.net.URI(url)
            u.host ?: fallback
        }.getOrDefault(fallback)
    }

    // ---------------- 明文 HTTP ----------------

    private fun handlePlainHttp(
        input: InputStream,
        output: OutputStream,
        close: () -> Unit,
        req: HttpHead,
        method: String,
        target: String,
    ) {
        // 代理式 absolute-URI 或 origin-form（后者用 Host 头补）
        val url = if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
            target
        } else {
            val h = req.headers.entries.firstOrNull {
                it.key.equals("host", true)
            }?.value ?: ""
            "http://$h$target"
        }
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("").lowercase()

        // 管线：URL重写
        when (val hit = rewriteStore.matchUrlRewrite(url)) {
            null -> {}
            else -> {
                rewrittenCount++
                if (hit.action == UrlRewriteRule.ACTION_REJECT) {
                    writeStatus(output, 403, "Forbidden"); close(); return
                }
                val loc = sanitizeHeaderValue(hit.target.ifBlank { url })
                output.write(
                    "HTTP/1.1 302 Found\r\nLocation: $loc\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(),
                )
                output.flush(); close(); return
            }
        }

        val jsReq = JsRequest(
            method = method, url = url, host = host,
            path = runCatching { java.net.URI(url).rawPath ?: "/" }.getOrDefault("/"),
            headers = req.headers.toMutableMap(),
        )
        applyRequestHeaderRules(jsReq.headers, host)
        jsEngine.runRequestHooks(jsReq)
        val reqBody = readBody(req, input)

        // 经上游 HTTP 代理转发（absolute-URI 形式）
        val up = openUpstream("", 0, tunnel = false) ?: run {
            writeStatus(output, 502, "Bad Gateway"); close(); return
        }
        try {
            writeRequestHead(up.output, jsReq, req, reqBody, absoluteUri = true)
            forwardBodyRemainder(req, input, up.output, reqBody)
            val resp = readHttpHead(up.input)
            if (resp == null) {
                writeStatus(output, 502, "Bad Gateway"); return
            }
            val respBody = readBody(resp, up.input)
            val jsResp = JsResponse(
                url = jsReq.url, status = resp.statusCode(),
                headers = resp.headers.toMutableMap(), body = textBodyOrNull(resp, respBody.buffered),
            )
            applyResponseHeaderRules(jsResp.headers, host)
            val jsChanged = jsEngine.runResponseHooks(jsResp)
            val finalBody: ByteArray? = if (!respBody.streamRest && jsChanged && jsResp.body != null &&
                jsResp.body != textBodyOrNull(resp, respBody.buffered)
            ) {
                jsResp.body!!.toByteArray(Charsets.UTF_8)
            } else respBody.buffered
            if (respBody.streamRest) {
                writeResponseHead(output, jsResp.status, jsResp.headers, streaming = true)
                streamBody(resp, up.input, output)
            } else {
                writeResponse(output, jsResp.status, jsResp.headers, finalBody)
            }
        } finally {
            runCatching { up.close() }
            close()
        }
    }

    /** 去掉 CR/LF，防响应头拆分注入（规则值来自用户输入） */
    private fun sanitizeHeaderValue(v: String): String =
        v.replace("\r", "").replace("\n", "")

    // ---------------- Header 改写应用 ----------------

    private fun applyRequestHeaderRules(headers: MutableMap<String, String>, host: String) {
        for (r in rewriteStore.matchHeaderRules(HeaderRewriteRule.DIR_REQUEST, host)) {
            val name = sanitizeHeaderValue(r.name)
            val value = sanitizeHeaderValue(r.value)
            if (name.isBlank()) continue
            when (r.op) {
                HeaderRewriteRule.OP_REMOVE -> headers.keys.removeAll { it.equals(name, true) }
                HeaderRewriteRule.OP_ADD -> {
                    if (headers.keys.none { it.equals(name, true) }) headers[name] = value
                }
                else -> { // set：覆盖（大小写保留原 key）
                    val k = headers.keys.firstOrNull { it.equals(name, true) } ?: name
                    headers[k] = value
                }
            }
        }
    }

    private fun applyResponseHeaderRules(headers: MutableMap<String, String>, host: String) {
        for (r in rewriteStore.matchHeaderRules(HeaderRewriteRule.DIR_RESPONSE, host)) {
            val name = sanitizeHeaderValue(r.name)
            val value = sanitizeHeaderValue(r.value)
            if (name.isBlank()) continue
            when (r.op) {
                HeaderRewriteRule.OP_REMOVE -> headers.keys.removeAll { it.equals(name, true) }
                HeaderRewriteRule.OP_ADD -> {
                    if (headers.keys.none { it.equals(name, true) }) headers[name] = value
                }
                else -> {
                    val k = headers.keys.firstOrNull { it.equals(name, true) } ?: name
                    headers[k] = value
                }
            }
        }
    }

    // ---------------- 转发/读写 ----------------

    /** 上游连接：默认 TCP 直连 upstreamHost:upstreamPort；tunnel=true 时先做 CONNECT 握手 */
    private fun openUpstream(host: String, port: Int, tunnel: Boolean): UpstreamConn? {
        upstreamOpener?.let { return it(host, port, tunnel) }
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(upstreamHost, upstreamPort), 10_000)
            s.soTimeout = 30_000
            if (tunnel) {
                val out = s.getOutputStream()
                out.write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray())
                out.flush()
                val resp = readHttpHead(s.getInputStream()) ?: throw IllegalStateException("上游无响应")
                if (resp.statusCode() != 200) throw IllegalStateException("上游隧道失败：${resp.requestLine}")
            }
            UpstreamConn(s.getInputStream(), s.getOutputStream(), { runCatching { s.close() } }, s)
        } catch (_: Exception) { null }
    }

    /** 双向盲转发（基于流） */
    private fun relayBidirectional(
        aIn: InputStream,
        aOut: OutputStream,
        bIn: InputStream,
        bOut: OutputStream,
        onDone: () -> Unit,
    ) {
        val t1 = Thread({
            runCatching { relay(aIn, bOut) }
        }, "mitm-relay-1").apply { isDaemon = true }
        val t2 = Thread({
            runCatching { relay(bIn, aOut) }
        }, "mitm-relay-2").apply { isDaemon = true }
        t1.start(); t2.start()
        t1.join(120_000); t2.join(120_000)
        runCatching { onDone() }
    }

    private fun relay(`in`: InputStream, out: OutputStream) {
        val buf = ByteArray(8192)
        while (true) {
            val n = `in`.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            out.flush()
        }
    }

    /** 向源站/上游发请求头（body 由调用方随后写/流式转发） */
    private fun writeRequestHead(
        out: OutputStream,
        req: JsRequest,
        head: HttpHead,
        body: BodyRead,
        absoluteUri: Boolean = false,
    ) {
        val target = if (absoluteUri) req.url else req.path.ifBlank { "/" }
        val sb = StringBuilder()
        sb.append("${req.method} $target HTTP/1.1\r\n")
        val headers = req.headers.toMutableMap()
        //  hop-by-hop 头去掉
        headers.keys.removeAll {
            it.equals("proxy-connection", true) || it.equals("connection", true) ||
                it.equals("keep-alive", true)
        }
        // Host 修正（含非默认端口）
        val uri = runCatching { java.net.URI(req.url) }.getOrNull()
        val uriHost = uri?.host ?: req.host
        val uriPort = uri?.port ?: -1
        headers["Host"] = if (uriPort > 0) "$uriHost:$uriPort" else uriHost
        // body framing：缓冲则按实际长度；流式则保留原始 framing
        headers.remove("Content-Length")
        headers.remove("Transfer-Encoding")
        when {
            body.buffered != null ->
                headers["Content-Length"] = body.buffered.size.toString()
            body.streamRest && head.isChunked() ->
                headers["Transfer-Encoding"] = head.headers.entries
                    .firstOrNull { it.key.equals("transfer-encoding", true) }?.value ?: "chunked"
            body.streamRest ->
                head.contentLength().takeIf { it > 0 }?.let { headers["Content-Length"] = it.toString() }
        }
        for ((k, v) in headers) {
            sb.append("$k: $v\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 写响应头；streaming=true 时保留分块传输（不重写 Content-Length） */
    private fun writeResponseHead(
        out: OutputStream,
        status: Int,
        headers: MutableMap<String, String>,
        streaming: Boolean,
        bodyLen: Int = 0,
    ) {
        val h = headers.toMutableMap()
        if (streaming) {
            h.keys.removeAll { it.equals("connection", true) || it.equals("keep-alive", true) }
            if (h.keys.any { it.equals("transfer-encoding", true) }) {
                h.keys.removeAll { it.equals("content-length", true) }
            }
        } else {
            h.keys.removeAll {
                it.equals("transfer-encoding", true) || it.equals("connection", true) ||
                    it.equals("keep-alive", true)
            }
            h["Content-Length"] = bodyLen.toString()
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status ${reasonOf(status)}\r\n")
        for ((k, v) in h) sb.append("$k: $v\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 写响应给客户端（body 如被改过则按 Content-Length 重写） */
    private fun writeResponse(
        out: OutputStream,
        status: Int,
        headers: MutableMap<String, String>,
        body: ByteArray?,
    ) {
        writeResponseHead(out, status, headers, streaming = false, bodyLen = body?.size ?: 0)
        if (body != null) {
            out.write(body)
            out.flush()
        }
    }

    /** body 读取结果：buffered 有值=已缓冲；streamRest=true=需流式转发剩余部分 */
    private data class BodyRead(val buffered: ByteArray?, val streamRest: Boolean)

    /**
     * 读 body：小 body 缓冲返回；过大（>10MB）或分块超限则返回 streamRest=true
     * （此时流位置停在 body 起始/当前 chunk 边界，可继续流式转发，不截断）。
     */
    private fun readBody(head: HttpHead, input: InputStream): BodyRead {
        if (head.isChunked()) {
            val b = readChunked(input, MAX_BODY)
            return if (b != null) BodyRead(b, false) else BodyRead(null, true)
        }
        val len = head.contentLength()
        if (len <= 0) return BodyRead(null, false)
        if (len > MAX_BODY) return BodyRead(null, true)
        return try {
            BodyRead(readExactly(input, len), false)
        } catch (_: Exception) {
            BodyRead(null, false)
        }
    }

    /** 按 BodyRead 把 body 发出去：缓冲的直接写，需流式的透传 */
    private fun forwardBodyRemainder(head: HttpHead, src: InputStream, dst: OutputStream, br: BodyRead) {
        if (br.buffered != null) {
            dst.write(br.buffered)
            dst.flush()
            return
        }
        if (br.streamRest) streamBody(head, src, dst)
    }

    /** 流式转发 body（分块或定长），不缓冲 */
    private fun streamBody(head: HttpHead, src: InputStream, dst: OutputStream) {
        try {
            if (head.isChunked()) {
                while (true) {
                    val line = readLine(src) ?: break
                    dst.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
                    dst.flush()
                    val size = line.trim().substringBefore(";").toLongOrNull(16) ?: break
                    if (size == 0L) {
                        while (true) {
                            val t = readLine(src) ?: break
                            dst.write((t + "\r\n").toByteArray(Charsets.ISO_8859_1))
                            dst.flush()
                            if (t.isBlank()) break
                        }
                        break
                    }
                    relayExact(src, dst, size)
                    val crlf = ByteArray(2)
                    readFully(src, crlf)
                    dst.write(crlf)
                    dst.flush()
                }
            } else {
                val len = head.contentLength()
                if (len > 0) relayExact(src, dst, len)
            }
        } catch (_: Exception) {
        }
    }

    private fun relayExact(src: InputStream, dst: OutputStream, len: Long) {
        var left = len
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            dst.write(buf, 0, n)
            left -= n
        }
        dst.flush()
    }

    private fun readFully(src: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = src.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    private fun writeStatus(out: OutputStream, code: Int, msg: String) {
        runCatching {
            out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }
    }

    private fun reasonOf(code: Int): String = when (code) {
        200 -> "OK"; 302 -> "Found"; 400 -> "Bad Request"
        403 -> "Forbidden"; 502 -> "Bad Gateway"; else -> ""
    }

    // ---------------- HTTP 解析 ----------------

    /** HTTP 头（请求行/状态行 + headers，保持插入顺序） */
    data class HttpHead(
        val requestLine: String,
        val headers: LinkedHashMap<String, String>,
    ) {
        fun statusCode(): Int =
            requestLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0

        fun contentLength(): Long =
            headers.entries.firstOrNull { it.key.equals("content-length", true) }
                ?.value?.toLongOrNull() ?: -1

        fun isChunked(): Boolean =
            headers.entries.firstOrNull { it.key.equals("transfer-encoding", true) }
                ?.value?.contains("chunked", true) == true
    }

    /** 读到 \r\n\r\n 为止；返回 null 表示连接已关/非法 */
    private fun readHttpHead(`in`: InputStream): HttpHead? {
        val buf = ByteArrayOutputStream()
        val one = ByteArray(1)
        // 逐字节找头结束（简单可靠，头一般很小）
        var matched = 0
        val target = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (buf.size() < 65536) {
            val n = try { `in`.read(one) } catch (_: Exception) { -1 }
            if (n < 0) return null
            buf.write(one, 0, 1)
            matched = if (one[0] == target[matched]) matched + 1 else {
                if (one[0] == target[0]) 1 else 0
            }
            if (matched == 4) break
        }
        if (matched != 4) return null
        val text = buf.toString(Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        if (lines.isEmpty() || lines[0].isBlank()) return null
        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                // 重复头合并（Set-Cookie 除外简单追加）
                headers[k] = if (headers.containsKey(k)) headers[k] + ", " + v else v
            }
        }
        return HttpHead(lines[0], headers)
    }

    private fun readExactly(`in`: InputStream, len: Long): ByteArray {
        val out = ByteArrayOutputStream(len.toInt().coerceAtLeast(0))
        val buf = ByteArray(8192)
        var left = len
        while (left > 0) {
            val n = `in`.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    private fun readChunked(`in`: InputStream, max: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(`in`) ?: return null
            val size = line.trim().substringBefore(";").toLongOrNull(16) ?: return null
            if (size == 0L) {
                // 读掉尾部头
                while (true) {
                    val t = readLine(`in`) ?: break
                    if (t.isBlank()) break
                }
                break
            }
            if (out.size() + size > max) return null
            val chunk = readExactly(`in`, size)
            out.write(chunk)
            readLine(`in`) // 块后 CRLF
        }
        return out.toByteArray()
    }

    private fun readLine(`in`: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = try { `in`.read() } catch (_: Exception) { -1 }
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** 文本类型 body 才给 JS（json/文本等）；二进制返回 null */
    private fun textBodyOrNull(head: HttpHead, body: ByteArray?): String? {
        if (body == null) return null
        if (body.size > MAX_JS_BODY) return null
        val ct = head.headers.entries.firstOrNull { it.key.equals("content-type", true) }
            ?.value?.lowercase() ?: ""
        val text = ct.startsWith("text/") || "json" in ct || "javascript" in ct ||
            "xml" in ct || ct.startsWith("application/x-www-form-urlencoded") ||
            ct.isBlank()
        if (!text) return null
        return runCatching { body.toString(Charsets.UTF_8) }.getOrNull()
    }

    companion object {
        /** body 检查上限 10MB */
        const val MAX_BODY = 10L * 1024 * 1024

        /** 给 JS 的 body 上限 2MB */
        const val MAX_JS_BODY = 2 * 1024 * 1024
    }
}
