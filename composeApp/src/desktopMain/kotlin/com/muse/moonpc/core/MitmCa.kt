package com.muse.moonpc.core

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import java.io.File
import java.io.FileReader
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager

/**
 * MITM CA 证书管理（全新实现，BouncyCastle 1.67）：
 * - 首次启动生成 RSA-2048 CA（自签，10 年有效期），存 ~/.moon-pc/mitm/ca.crt + ca.key（600 权限）
 * - 为每个域名动态签发叶子证书（SAN=域名，1 年有效期），内存缓存
 * - Windows：一键 certutil 安装到"受信任的根证书颁发机构"（需管理员）+ 安装状态检测
 */
object MitmCa {

    private const val CA_CN = "Moon PC MITM CA"

    private val mitmDir: File
        get() = File(System.getProperty("user.home"), ".moon-pc/mitm").apply { mkdirs() }
    private val caCrtFile: File get() = File(mitmDir, "ca.crt")
    private val caKeyFile: File get() = File(mitmDir, "ca.key")

    @Volatile private var caKeyPair: KeyPair? = null
    @Volatile private var caCert: X509Certificate? = null

    /** 域名 → 叶子证书 KeyStore（含私钥），内存缓存（LRU 上限防无限制增长） */
    private val leafCache = object : LinkedHashMap<String, KeyStore>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KeyStore>?): Boolean {
            return size > MAX_LEAF_CACHE
        }
    }

    /** 叶子缓存上限 */
    private const val MAX_LEAF_CACHE = 256

    init {
        runCatching { Security.addProvider(BouncyCastleProvider()) }
    }

    /** CA 是否已生成 */
    fun isGenerated(): Boolean = caCrtFile.isFile && caKeyFile.isFile

    /** 确保 CA 存在（不存在则生成），返回 CA 证书 */
    @Synchronized
    fun ensureCa(): Result<X509Certificate> = runCatching {
        caCert?.let { return@runCatching it }
        if (isGenerated()) {
            loadCa()
        } else {
            generateCa()
        }
        caCert!!
    }

    /** 生成新 CA（覆盖旧的，叶子缓存清空） */
    @Synchronized
    fun regenerateCa(): Result<X509Certificate> = runCatching {
        synchronized(leafCache) { leafCache.clear() }
        caKeyPair = null
        caCert = null
        runCatching { caCrtFile.delete() }
        runCatching { caKeyFile.delete() }
        generateCa()
        caCert!!
    }

    private fun generateCa() {
        val kpg = KeyPairGenerator.getInstance("RSA", "BC")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$CA_CN"),
            BigInteger(64, SecureRandom()),
            Date(now - TimeUnit.DAYS.toMillis(1)),
            Date(now + TimeUnit.DAYS.toMillis(3650)),
            X500Name("CN=$CA_CN"),
            kp.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.digitalSignature or KeyUsage.cRLSign),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.private)
        val holder: X509CertificateHolder = builder.build(signer)
        val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)

        // 落盘（PEM，600 权限）
        SecureFile.writeText(caCrtFile, pemEncode(cert))
        SecureFile.writeText(caKeyFile, pemEncode(kp.private))
        caKeyPair = kp
        caCert = cert
    }

    private fun loadCa() {
        val cert = FileReader(caCrtFile).use { r ->
            val holder = PEMParser(r).readObject() as X509CertificateHolder
            JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
        }
        val key = FileReader(caKeyFile).use { r ->
            val obj = PEMParser(r).readObject()
            val converter = org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().setProvider("BC")
            when (obj) {
                is PKCS8EncryptedPrivateKeyInfo ->
                    throw IllegalStateException("CA 私钥不应加密存储")
                is org.bouncycastle.asn1.pkcs.PrivateKeyInfo ->
                    converter.getPrivateKey(obj)
                // JcaPEMWriter 写 RSA 私钥默认是 PKCS#1 "RSA PRIVATE KEY"，读回为 PEMKeyPair
                is org.bouncycastle.openssl.PEMKeyPair ->
                    converter.getKeyPair(obj).private
                else -> throw IllegalStateException("未知私钥格式：${obj?.javaClass}")
            }
        }
        caKeyPair = KeyPair(cert.publicKey, key)
        caCert = cert
    }

    private fun pemEncode(obj: Any): String {
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(obj) }
        return sw.toString()
    }

    /** CA 证书 PEM 文本（供用户手动安装/查看） */
    fun caCertPem(): String? = runCatching {
        ensureCa().getOrThrow(); caCrtFile.readText()
    }.getOrNull()

    /**
     * 为域名签发叶子证书，返回含私钥的 KeyStore（供 SSLContext 用）。
     * 结果内存缓存；同一域名复用。
     */
    fun leafKeyStore(host: String): KeyStore {
        val h = sanitizeHost(host) ?: throw IllegalArgumentException("非法域名：$host")
        synchronized(leafCache) {
            return leafCache.getOrPut(h) { issueLeaf(h) }
        }
    }

    /**
     * 域名消毒：去空白/转小写/去尾点；拒绝空、超长（>253）、含非法字符。
     * host 来自用户浏览器的 CONNECT 请求，正常情况下可信，
     * 这里防的是畸形输入污染证书缓存或触发 BC 异常。
     */
    private fun sanitizeHost(host: String): String? {
        val h = host.lowercase().trim().removeSuffix(".")
        if (h.isEmpty() || h.length > 253) return null
        // 域名或 IP 字面量
        val ok = h.matches(Regex("^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$")) ||
            h.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) ||
            h.matches(Regex("^[0-9a-fA-F:]+$"))
        return if (ok) h else null
    }

    private fun issueLeaf(host: String): KeyStore {
        val ca = ensureCa().getOrThrow()
        val caKp = caKeyPair ?: throw IllegalStateException("CA 未加载")
        val kpg = KeyPairGenerator.getInstance("RSA", "BC")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name(ca.subjectX500Principal.name),
            BigInteger(64, SecureRandom()),
            Date(now - TimeUnit.DAYS.toMillis(1)),
            Date(now + TimeUnit.DAYS.toMillis(365)),
            X500Name("CN=$host"),
            kp.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        // SAN：域名；IP 字面量走 iPAddress
        val san = if (host.matches(Regex("^[0-9a-fA-F:.]+$")) && (':' in host || host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")))) {
            GeneralNames(GeneralName(GeneralName.iPAddress, host))
        } else {
            GeneralNames(GeneralName(GeneralName.dNSName, host))
        }
        builder.addExtension(Extension.subjectAlternativeName, false, san)
        // EKU：serverAuth
        builder.addExtension(
            Extension.extendedKeyUsage, false,
            org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth,
            ),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(caKp.private)
        val holder = builder.build(signer)
        val leaf = JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)

        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setKeyEntry("leaf", kp.private, CharArray(0), arrayOf<Certificate>(leaf, ca))
        return ks
    }

    /** 为某域名构建服务端 SSLContext（MITM TLS 终止用） */
    fun serverSslContext(host: String): SSLContext {
        val ks = leafKeyStore(host)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))
        // 只做服务端，不校验客户端
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx
    }

    // ---------------- Windows 安装 ----------------

    /**
     * 检测 CA 是否已安装到 Windows"受信任的根证书颁发机构"。
     * 用 certutil -store Root 查指纹。
     */
    fun isInstalledOnWindows(): Boolean {
        if (!ProxyModeManager.isWindows()) return false
        return try {
            val ca = ensureCa().getOrThrow()
            val fp = sha1Hex(ca.encoded)
            val p = ProcessBuilder("certutil", "-store", "Root")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(15, TimeUnit.SECONDS)
            out.replace(":", "").replace(" ", "").uppercase().contains(fp.uppercase())
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 安装 CA 到 Windows 受信任根（需管理员权限）。
     * 用 certutil -addstore Root ca.crt。
     */
    fun installOnWindows(): Result<Unit> {
        if (!ProxyModeManager.isWindows()) {
            return Result.failure(UnsupportedOperationException("仅支持 Windows"))
        }
        if (!ProxyModeManager.isAdmin()) {
            return Result.failure(IllegalStateException("需要管理员权限：请以管理员身份运行 Moon PC 后再安装证书"))
        }
        return runCatching {
            ensureCa().getOrThrow()
            val p = ProcessBuilder(
                "certutil", "-addstore", "Root", caCrtFile.absolutePath,
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
            if (!ok) throw IllegalStateException("certutil 安装失败：${out.trim().take(300)}")
        }
    }

    /** 从 Windows 受信任根卸载 CA */
    fun uninstallFromWindows(): Result<Unit> {
        if (!ProxyModeManager.isWindows()) {
            return Result.failure(UnsupportedOperationException("仅支持 Windows"))
        }
        if (!ProxyModeManager.isAdmin()) {
            return Result.failure(IllegalStateException("需要管理员权限"))
        }
        return runCatching {
            val ca = ensureCa().getOrThrow()
            val fp = sha1Hex(ca.encoded)
            // certutil -delstore Root <指纹>
            val p = ProcessBuilder("certutil", "-delstore", "Root", fp)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
            if (!ok) throw IllegalStateException("certutil 卸载失败：${out.trim().take(300)}")
        }
    }

    private fun sha1Hex(data: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    fun clearMemoryForTest() {
        caKeyPair = null
        caCert = null
        synchronized(leafCache) { leafCache.clear() }
    }
}
