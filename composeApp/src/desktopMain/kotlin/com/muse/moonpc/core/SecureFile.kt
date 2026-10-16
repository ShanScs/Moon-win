package com.muse.moonpc.core

import java.io.File
import java.nio.file.attribute.PosixFilePermission

/**
 * 敏感文件写工具：节点/配置等含密码的文件落盘后收紧权限。
 *
 * POSIX 上设为 600（仅所有者可读写）；Windows 上尽力设为仅所有者
 * （File.setReadable(false, false) 等），失败静默忽略。
 */
object SecureFile {

    /** 写文本并收紧权限；返回写成功与否 */
    fun writeText(file: File, text: String): Boolean {
        return try {
            file.parentFile?.mkdirs()
            file.writeText(text)
            restrict(file)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 收紧已存在文件的权限（best-effort） */
    fun restrict(file: File) {
        runCatching {
            try {
                val perms = setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                )
                java.nio.file.Files.setPosixFilePermissions(file.toPath(), perms)
            } catch (_: UnsupportedOperationException) {
                // Windows：去掉所有非所有者的读写执行权限
                file.setReadable(false, false)
                file.setWritable(false, false)
                file.setExecutable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
            }
        }
    }
}
