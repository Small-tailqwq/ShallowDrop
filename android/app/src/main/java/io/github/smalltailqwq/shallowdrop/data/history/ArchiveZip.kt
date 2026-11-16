package io.github.smalltailqwq.shallowdrop.data.history

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipEntry
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
 * 读取 ZIP（STORE / DEFLATE），对照 zipReader.ts：
 * - 先读中央目录，按本地文件头偏移排序，逐条核对本地文件头与数据描述符，再逐块解压并校验大小与 CRC；
 * - 拒绝 ZIP64、加密、分卷、符号链接、重复条目（目录与同名文件也不能互相覆盖）与非法路径；
 * - 中央目录结束记录的条目数、大小与位置必须自洽，截断的文件因此会被拒绝。
 *
 * 官方导出的图片条目是「STORE + 数据描述符」，本地文件头里的大小与 CRC 都是 0，只能从中央目录得知，
 * 因此必须随机访问，不能只顺序读取本地文件头。
 *
 * 与 Web 版的差异：未置 UTF-8 标志的非 ASCII 文件名按 UTF-8 解码（非法字节仍拒绝）。
 *
 * @param deleteOnClose 关闭时删除 [file]（来自 [ArchiveZipReader.spool] 的临时副本）
 */
internal class ArchiveZipReader(
    private val file: File,
    private val deleteOnClose: Boolean = false,
    private val maxExpandedBytes: Long = MAX_ARCHIVE_BYTES,
) : Closeable {
    private val raf = if (file.length() > MAX_ARCHIVE_BYTES) {
        invalid("归档文件超出大小限制")
    } else {
        RandomAccessFile(file, "r")
    }
    private val length = raf.length()
    private var directory: Directory? = null
    private var index = 0
    private var current: Entry? = null
    private var expandedBytes = 0L

    /** 是否以 ZIP 本地文件头开始 */
    val isZip: Boolean = length >= 4 && read(0, 4).let {
        it[0] == 0x50.toByte() && it[1] == 0x4b.toByte() && it[2] == 3.toByte() && it[3] == 4.toByte()
    }

    internal class Record(
        val path: String,
        val name: ByteArray,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val offset: Long,
    )

    private class Directory(val records: List<Record>, val offset: Long)

    inner class Entry internal constructor(val path: String, val size: Long, private val record: Record, private val start: Long) :
        InputStream() {
        internal var done = false
        private var written = 0L
        private var consumed = 0L
        private val crc = CRC32()
        private val inflater = if (record.method == ZipEntry.DEFLATED) Inflater(true) else null
        private val input = ByteArray(READ_CHUNK)
        private var dummyFed = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (len == 0) return 0
            val n = if (inflater == null) readStored(b, off, len) else readDeflated(inflater, b, off, len)
            if (n < 0) {
                finishEntry()
                return -1
            }
            expandedBytes += n
            if (expandedBytes > maxExpandedBytes) invalid("归档解压大小超出限制")
            written += n
            if (written > size) invalid("条目大小不符")
            crc.update(b, off, n)
            return n
        }

        private fun readStored(b: ByteArray, off: Int, len: Int): Int {
            val remaining = record.compressedSize - consumed
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            readFully(start + consumed, b, off, n)
            consumed += n
            return n
        }

        private fun readDeflated(inflater: Inflater, b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                if (inflater.finished()) return -1
                val n = try {
                    inflater.inflate(b, off, len)
                } catch (error: DataFormatException) {
                    throw ArchiveFormatException("压缩数据损坏", error)
                }
                if (n > 0) return n
                if (inflater.finished()) return -1
                if (inflater.needsDictionary()) invalid("压缩数据损坏")
                if (inflater.needsInput()) {
                    val remaining = record.compressedSize - consumed
                    if (remaining > 0) {
                        val chunk = minOf(input.size.toLong(), remaining).toInt()
                        readFully(start + consumed, input, 0, chunk)
                        consumed += chunk
                        inflater.setInput(input, 0, chunk)
                    } else if (!dummyFed) {
                        // nowrap 模式下 zlib 可能需要一个额外的哑字节才能结束
                        dummyFed = true
                        inflater.setInput(ByteArray(1), 0, 1)
                    } else {
                        invalid("压缩数据被截断")
                    }
                }
            }
        }

        private fun finishEntry() {
            done = true
            inflater?.end()
            if (written != size || crc.value != record.crc) invalid("条目大小或 CRC 不符")
        }

        /** 读完整个条目：会话 JSON 需要完整解析 */
        fun readBytes(maxBytes: Int = MAX_JSON_BYTES): ByteArray {
            if (size > maxBytes) invalid("JSON 条目超出大小限制")
            val out = ByteArrayOutputStream(minOf(maxBytes, 8192))
            val buffer = ByteArray(8192)
            while (true) {
                val n = read(buffer)
                if (n < 0) break
                if (out.size().toLong() + n > maxBytes) invalid("JSON 条目超出大小限制")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        fun release() { inflater?.end() }

        /** 不需要的条目也要读完，以校验大小与 CRC */
        fun skip() {
            val buffer = ByteArray(64 * 1024)
            while (read(buffer, 0, buffer.size) >= 0) {
                // 丢弃数据
            }
        }

        override fun close() = Unit
    }

    /** 下一个文件条目（按物理顺序，跳过目录）；没有更多条目时返回 null。上一个条目未读完时先读完并校验。 */
    fun next(): Entry? {
        current?.let { if (!it.done) it.skip() }
        current = null
        val dir = directory ?: readDirectory().also { directory = it }
        while (index < dir.records.size) {
            val record = dir.records[index]
            val boundary = dir.records.getOrNull(index + 1)?.offset ?: dir.offset
            index++
            val start = checkLocalHeader(record, boundary)
            if (record.path.endsWith('/')) continue
            return Entry(record.path, record.size, record, start).also { current = it }
        }
        return null
    }

    private fun readDirectory(): Directory {
        if (length < 22) invalid("缺少中央目录")
        val tailStart = maxOf(0L, length - 22 - 0xffff)
        val tail = read(tailStart, (length - tailStart).toInt())
        var end = tail.size - 22
        while (end >= 0 && (u32(tail, end) != 0x06054b50L || end + 22 + u16(tail, end + 20) != tail.size)) end--
        if (end < 0) invalid("缺少中央目录")
        val count = u16(tail, end + 10)
        val size = u32(tail, end + 12)
        val offset = u32(tail, end + 16)
        if (
            u16(tail, end + 4) != 0 ||
            u16(tail, end + 6) != 0 ||
            u16(tail, end + 8) != count ||
            count == 0xffff ||
            size == 0xffffffffL ||
            offset == 0xffffffffL ||
            offset + size != tailStart + end
        ) {
            invalid("中央目录与条目不一致")
        }
        val records = ArrayList<Record>(count)
        val paths = HashSet<String>()
        var cursor = 0L
        repeat(count) {
            if (cursor + 46 > size) invalid("中央目录损坏")
            val header = read(offset + cursor, 46)
            if (u32(header, 0) != 0x02014b50L) invalid("中央目录损坏")
            val flags = u16(header, 8)
            val method = u16(header, 10)
            val compressedSize = u32(header, 20)
            val entrySize = u32(header, 24)
            val nameLength = u16(header, 28)
            val extraLength = u16(header, 30)
            val next = cursor + 46 + nameLength + extraLength + u16(header, 32)
            val entryOffset = u32(header, 42)
            // 只允许 deflate 选项、数据描述符与 UTF-8 标志；拒绝分卷与符号链接。
            if (
                flags and 0x80e.inv() != 0 ||
                (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) ||
                compressedSize == 0xffffffffL ||
                entrySize == 0xffffffffL ||
                (method == ZipEntry.STORED && compressedSize != entrySize) ||
                entryOffset >= offset ||
                u16(header, 34) != 0 ||
                ((u32(header, 38) ushr 16) and 0xf000) == 0xa000L ||
                next > size
            ) {
                invalid("不支持的条目")
            }
            val name = read(offset + cursor + 46, nameLength)
            val path = checkEntryPath(decodeName(name))
            // 目录和同名文件也不能互相覆盖。
            val identity = if (path.endsWith('/')) path.dropLast(1) else path
            if (!paths.add(identity) || (path.endsWith('/') && entrySize != 0L)) invalid("重复条目")
            checkExtra(read(offset + cursor + 46 + nameLength, extraLength))
            records += Record(path, name, flags, method, u32(header, 16), compressedSize, entrySize, entryOffset)
            cursor = next
        }
        if (cursor != size) invalid("中央目录损坏")
        records.sortBy { it.offset }
        return Directory(records, offset)
    }

    /** 核对本地文件头（数据描述符条目的 CRC 与大小可为 0）与数据描述符，返回数据起点 */
    private fun checkLocalHeader(record: Record, boundary: Long): Long {
        if (record.offset + 30 > boundary) invalid("条目越界")
        val header = read(record.offset, 30)
        val nameLength = u16(header, 26)
        val extraLength = u16(header, 28)
        val start = record.offset + 30 + nameLength + extraLength
        val end = start + record.compressedSize
        if (
            u32(header, 0) != 0x04034b50L ||
            u16(header, 6) != record.flags ||
            u16(header, 8) != record.method ||
            nameLength != record.name.size ||
            end > boundary
        ) {
            invalid("本地文件头与中央目录不一致")
        }
        val descriptor = record.flags and 8 != 0
        for ((position, expected) in listOf(14 to record.crc, 18 to record.compressedSize, 22 to record.size)) {
            val actual = u32(header, position)
            if (actual != expected && (!descriptor || actual != 0L)) invalid("本地文件头与中央目录不一致")
        }
        val metadata = read(record.offset + 30, nameLength + extraLength)
        if (!record.name.contentEquals(metadata.copyOfRange(0, nameLength))) invalid("本地文件头与中央目录不一致")
        checkExtra(metadata.copyOfRange(nameLength, metadata.size))
        if (descriptor) {
            if (end + 12 > boundary) invalid("数据描述符缺失")
            val bytes = read(end, minOf(16L, boundary - end).toInt())
            val shift = if (u32(bytes, 0) == 0x08074b50L) 4 else 0
            if (
                shift + 12 > bytes.size ||
                u32(bytes, shift) != record.crc ||
                u32(bytes, shift + 4) != record.compressedSize ||
                u32(bytes, shift + 8) != record.size
            ) {
                invalid("数据描述符与中央目录不一致")
            }
        }
        return start
    }

    /** ZIP64、分卷与加密文件不属于导出格式，一律拒绝。 */
    private fun checkExtra(extra: ByteArray) {
        var offset = 0
        while (offset < extra.size) {
            if (offset + 4 > extra.size || u16(extra, offset) == 1) invalid("不支持的扩展字段")
            offset += 4 + u16(extra, offset + 2)
            if (offset > extra.size) invalid("扩展字段损坏")
        }
    }

    private fun decodeName(bytes: ByteArray): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw ArchiveFormatException("非法条目名", error)
        }

    private fun read(position: Long, size: Int): ByteArray = ByteArray(size).also { readFully(position, it, 0, size) }

    private fun readFully(position: Long, b: ByteArray, off: Int, len: Int) {
        if (position < 0 || len < 0 || position + len > length) invalid("ZIP 被截断")
        raf.seek(position)
        raf.readFully(b, off, len)
    }

    override fun close() {
        current?.release()
        current = null
        raf.close()
        if (deleteOnClose) file.delete()
    }

    companion object {
        // JSON 会整体解析；图片逐块保存，归档总量采用独立限制。
        private const val MAX_JSON_BYTES = 16 * 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        private const val READ_CHUNK = 256 * 1024

        private fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

        private fun u32(bytes: ByteArray, at: Int): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)

        /**
         * 把输入流（SAF Uri 等）复制到 [dir] 下的临时文件后打开，关闭时删除副本。
         * 读取 ZIP 需要随机访问中央目录，内容 URI 通常只能顺序读取。
         */
        fun spool(input: InputStream, dir: File, maxBytes: Long = MAX_ARCHIVE_BYTES): ArchiveZipReader {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建临时目录：$dir")
            val file = File.createTempFile("import-", ".zip", dir)
            try {
                input.use { source ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = source.read(buffer)
                            if (n < 0) break
                            copied += n
                            if (copied > maxBytes) invalid("归档文件超出大小限制")
                            output.write(buffer, 0, n)
                        }
                    }
                }
                return ArchiveZipReader(file, deleteOnClose = true)
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
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
