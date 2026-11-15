package io.github.smalltailqwq.shallowdrop.data.history

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP 结构损坏或使用了不支持的特性（对应 zipReader.ts 的 ArchiveError）；导入时映射为 INCOMPLETE。 */
internal class ArchiveFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

private fun invalid(message: String): Nothing = throw ArchiveFormatException(message)

/**
 * 条目名规则，读写两端一致（对照 zipReader.ts decodePath 与 zipWriter.ts entryName）：
 * NFC 规范化；拒绝空名、反斜杠、NUL、盘符、空片段与 . / ..（防路径穿越）。
 */
internal fun checkEntryPath(raw: String): String {
    val path = nfc(raw)
    if (path.isEmpty() || '\\' in path || '\u0000' in path || Regex("^[A-Za-z]:").containsMatchIn(path)) {
        invalid("非法条目名")
    }
    val parts = (if (path.endsWith('/')) path.dropLast(1) else path).split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." }) invalid("非法条目名")
    return path
}

/**
 * 流式读取 ZIP（STORE / DEFLATE），不要求整包进内存，对照 zipReader.ts：
 * - 按物理顺序逐条读取，跳过目录条目；条目数据读到末尾时由 ZipInputStream 校验大小与 CRC；
 * - 拒绝 ZIP64 扩展字段、加密、重复条目（目录与同名文件也不能互相覆盖）与非法路径；
 * - 读完后检查中央目录结束记录：条目数与位置必须与实际读到的一致，用于发现截断的文件。
 *
 * 与 Web 版的差异：ZipInputStream 只读本地文件头，中央目录逐项字段（外部属性、符号链接等）不校验；
 * 未置 UTF-8 标志的非 ASCII 文件名按 UTF-8 解码（非法字节仍拒绝）。
 */
internal class ArchiveZipReader(input: InputStream) : Closeable {
    private val tail = TailCaptureInputStream(input)
    private val zip: ZipInputStream
    private val paths = HashSet<String>()
    private var entriesSeen = 0
    private var current: Entry? = null
    private var finished = false

    /** 是否以 ZIP 本地文件头开始 */
    val isZip: Boolean

    init {
        // 本地文件头签名 PK\x03\x04，用于在解析前区分 ZIP 与其他文件。
        val pushback = PushbackInputStream(tail, 4)
        val magic = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = pushback.read(magic, read, 4 - read)
            if (n < 0) break
            read += n
        }
        if (read > 0) pushback.unread(magic, 0, read)
        isZip = read == 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte() && magic[2] == 3.toByte() && magic[3] == 4.toByte()
        zip = ZipInputStream(pushback, Charsets.UTF_8)
    }

    inner class Entry(val path: String, val size: Long) : InputStream() {
        internal var done = false
        private var written = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (len == 0) return 0
            val n = wrap { zip.read(b, off, len) }
            if (n < 0) {
                done = true
                if (size >= 0 && written != size) invalid("条目大小不符")
                return -1
            }
            written += n
            if (size >= 0 && written > size) invalid("条目大小不符")
            return n
        }

        /** 读完整个条目：会话 JSON 需要完整解析 */
        fun readBytes(): ByteArray {
            val out = ByteArrayOutputStream(if (size in 0..Int.MAX_VALUE.toLong()) size.toInt() else 8192)
            copyTo(out)
            return out.toByteArray()
        }

        /** 不需要的条目也要读完，以校验大小与 CRC */
        fun skip() {
            val buffer = ByteArray(64 * 1024)
            while (read(buffer, 0, buffer.size) >= 0) {
                // 丢弃数据
            }
        }

        override fun close() = Unit
    }

    /** 下一个文件条目；没有更多条目时返回 null。调用方未读完的上一个条目会被读完并校验。 */
    fun next(): Entry? {
        if (finished) return null
        current?.let { if (!it.done) it.skip() }
        current = null
        while (true) {
            val entry: ZipEntry? = wrap { zip.nextEntry }
            if (entry == null) {
                finish()
                return null
            }
            entriesSeen++
            val path = checkEntryPath(entry.name)
            checkExtra(entry.extra)
            if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) invalid("不支持的压缩方式")
            val identity = if (path.endsWith('/')) path.dropLast(1) else path
            if (!paths.add(identity)) invalid("重复条目")
            if (entry.isDirectory) {
                if (entry.size > 0) invalid("目录条目带数据")
                // 目录条目也要读到末尾以完成校验。
                Entry(path, entry.size).skip()
                continue
            }
            return Entry(path, entry.size).also { current = it }
        }
    }

    /** ZIP64、分卷与加密文件不属于导出格式，一律拒绝。 */
    private fun checkExtra(extra: ByteArray?) {
        if (extra == null) return
        var offset = 0
        while (offset < extra.size) {
            if (offset + 4 > extra.size) invalid("扩展字段损坏")
            val id = (extra[offset].toInt() and 0xff) or ((extra[offset + 1].toInt() and 0xff) shl 8)
            if (id == 1) invalid("不支持 ZIP64")
            val length = (extra[offset + 2].toInt() and 0xff) or ((extra[offset + 3].toInt() and 0xff) shl 8)
            offset += 4 + length
            if (offset > extra.size) invalid("扩展字段损坏")
        }
    }

    /** 读到文件末尾，在最后 64 KiB 中查找中央目录结束记录并核对条目数与偏移。 */
    private fun finish() {
        finished = true
        val buffer = ByteArray(64 * 1024)
        while (tail.read(buffer) >= 0) {
            // 丢弃数据
        }
        val bytes = tail.tail()
        val total = tail.total
        var end = bytes.size - 22
        while (end >= 0) {
            if (u32(bytes, end) == 0x06054b50L && end + 22 + u16(bytes, end + 20) == bytes.size) break
            end--
        }
        if (end < 0) invalid("缺少中央目录")
        val count = u16(bytes, end + 10)
        val size = u32(bytes, end + 12)
        val offset = u32(bytes, end + 16)
        val position = total - bytes.size + end
        if (
            u16(bytes, end + 4) != 0 ||
            u16(bytes, end + 6) != 0 ||
            u16(bytes, end + 8) != count ||
            count == 0xffff ||
            size == 0xffffffffL ||
            offset == 0xffffffffL ||
            offset + size != position ||
            count != entriesSeen
        ) {
            invalid("中央目录与条目不一致")
        }
    }

    private fun <T> wrap(block: () -> T): T =
        try {
            block()
        } catch (error: ArchiveFormatException) {
            throw error
        } catch (error: java.util.zip.ZipException) {
            throw ArchiveFormatException(error.message ?: "ZIP 损坏", error)
        } catch (error: java.io.EOFException) {
            throw ArchiveFormatException("ZIP 被截断", error)
        } catch (error: IllegalArgumentException) {
            // 文件名不是合法 UTF-8 等
            throw ArchiveFormatException(error.message ?: "ZIP 损坏", error)
        }

    override fun close() {
        zip.close()
    }

    private companion object {
        fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

        fun u32(bytes: ByteArray, at: Int): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
    }
}

/** 透传读取，并保留最后 22 + 0xffff 字节（中央目录结束记录的最大范围）与已读总字节数。 */
private class TailCaptureInputStream(input: InputStream) : FilterInputStream(input) {
    private val capacity = 22 + 0xffff
    private val ring = ByteArray(capacity)
    private var filled = 0
    private var head = 0
    var total = 0L
        private set

    private fun capture(b: ByteArray, off: Int, len: Int) {
        total += len
        for (i in off until off + len) {
            ring[head] = b[i]
            head = (head + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) capture(byteArrayOf(value.toByte()), 0, 1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) capture(b, off, n)
        return n
    }

    override fun skip(n: Long): Long {
        // 跳过的字节也要记录，改为读取。
        val buffer = ByteArray(minOf(n, 8192L).toInt().coerceAtLeast(1))
        var skipped = 0L
        while (skipped < n) {
            val r = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
            if (r < 0) break
            skipped += r
        }
        return skipped
    }

    override fun markSupported() = false

    fun tail(): ByteArray {
        val out = ByteArray(filled)
        val start = (head - filled + capacity) % capacity
        for (i in 0 until filled) out[i] = ring[(start + i) % capacity]
        return out
    }
}

/** 导出超出 ZIP32 容量（对应 zipWriter.ts 的 ArchiveLimitError） */
internal class ArchiveLimitException : IOException("History archive requires unsupported ZIP64 fields")

/**
 * STORE 流式写入，对照 zipWriter.ts；成品必须能再次导入（同样的路径规则、无 ZIP64）。
 * STORE 需要先知道 CRC 与大小：内存数据直接计算，文件先读一遍计算再写出。
 */
internal class ArchiveZipWriter(output: OutputStream) : Closeable {
    private val zip = ZipOutputStream(output, Charsets.UTF_8)
    private val names = HashSet<String>()
    private var count = 0
    private var offset = 0L

    private fun entryName(name: String): String {
        val normalized = try {
            checkEntryPath(name)
        } catch (error: ArchiveFormatException) {
            throw IllegalArgumentException("Invalid history archive entry: $name", error)
        }
        // 拒绝孤立代理项，避免编码时替换成 U+FFFD 后改变附件的关联路径。
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val length = try {
            encoder.encode(java.nio.CharBuffer.wrap(normalized)).remaining()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Invalid history archive entry: $name", error)
        }
        if (length > 0xffff) throw ArchiveLimitException()
        require(names.add(normalized)) { "Duplicate history archive entry: $name" }
        if (++count >= 0xffff) throw ArchiveLimitException()
        return normalized
    }

    private fun begin(name: String, size: Long, crc: Long) {
        val normalized = entryName(name)
        val nameBytes = normalized.toByteArray(Charsets.UTF_8).size
        if (size >= 0xffffffffL) throw ArchiveLimitException()
        // 本地头 30 + 名称 + 数据，中央目录 46 + 名称；偏移必须保持在 ZIP32 范围内。
        offset += 30 + nameBytes + size + 46 + nameBytes
        if (offset >= 0xffffffffL) throw ArchiveLimitException()
        val entry = ZipEntry(normalized).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc
            time = DOS_EPOCH
        }
        zip.putNextEntry(entry)
    }

    fun add(name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }.value
        begin(name, bytes.size.toLong(), crc)
        zip.write(bytes)
        zip.closeEntry()
    }

    fun add(name: String, file: File) {
        val crc = CRC32()
        var size = 0L
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
                size += n
            }
        }
        begin(name, size, crc.value)
        var written = 0L
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                written += n
                if (written > size) throw IOException("附件在导出期间被修改：$name")
                zip.write(buffer, 0, n)
            }
        }
        if (written != size) throw IOException("附件在导出期间被修改：$name")
        zip.closeEntry()
    }

    fun finish() {
        zip.finish()
        zip.flush()
    }

    override fun close() {
        zip.close()
    }

    private companion object {
        /** 1980-01-01 本地时间，与 Web 版一致，ZIP 不含真实修改时间 */
        val DOS_EPOCH: Long = java.util.GregorianCalendar(1980, 0, 1).timeInMillis
    }
}
