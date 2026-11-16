package io.github.smalltailqwq.shallowdrop.data.db

import java.io.File
import java.security.MessageDigest

/**
 * 附件原件的磁盘位置：filesDir/attachments/{文件名}。
 * 导入的附件沿用归档文件 ID，可能含任意字符；安全的 ID 直接作为文件名，其余取 SHA-256，杜绝路径穿越。
 */
class AttachmentPaths(filesDir: File) {
    /** 正式原件目录 */
    val dir = File(filesDir, "attachments")

    /** 导入暂存的原件，提交后整体换入 dir */
    val stagingDir = File(filesDir, "attachments-staging")

    /** 交换目录时暂放旧原件，交换完成即删除 */
    val oldDir = File(filesDir, "attachments-old")

    /** 导入时 SAF 内容的临时副本（读取 ZIP 需要随机访问），读完即删 */
    val importTempDir = File(filesDir, "import-temp")

    fun file(id: String) = File(dir, fileName(id))

    fun stagingFile(id: String) = File(stagingDir, fileName(id))

    companion object {
        private val SAFE = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$")

        fun fileName(id: String): String =
            if (SAFE.matches(id) && id != "." && id != "..") {
                id
            } else {
                val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
                "h_" + digest.joinToString("") { "%02x".format(it) }
            }
    }
}
