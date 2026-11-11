package app.amphora.sdk.internal.engine

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** A malformed or unexpected archive. The message names the entry. */
internal class TarFormatException(message: String) : IOException(message)

internal enum class TarEntryType { File, Directory }

internal class TarEntry(
    /** Normalized relative path, without a trailing slash. */
    val path: String,
    val type: TarEntryType,
    val mode: Int,
    val size: Long,
) {
    val executable: Boolean get() = mode and 0b001_001_001 != 0
}

/**
 * A deliberately strict ustar reader for runtime archives. amphora-engine
 * writes plain ustar with only directories and regular files, relative paths,
 * no `./` prefix; anything else (links, devices, pax or GNU extensions,
 * absolute or `..` paths, bad checksums) is rejected rather than interpreted,
 * because an archive that surprises us is not the one we built.
 */
internal class TarReader(private val input: InputStream) {
    private val header = ByteArray(BLOCK)
    private var remaining = 0L
    private var padding = 0L
    private var finished = false

    /** The next entry, or null at the end-of-archive marker. Skips unread data of the previous entry. */
    fun next(): TarEntry? {
        if (finished) return null
        skipFully(remaining + padding)
        remaining = 0
        padding = 0

        if (!readBlock(header)) throw TarFormatException("archive ends without an end-of-archive marker")
        if (header.all { it == 0.toByte() }) {
            // End of archive is two zero blocks; tolerate a missing second one only at EOF.
            readBlock(header)
            finished = true
            return null
        }
        verifyChecksum()
        val magic = String(header, 257, 6, Charsets.US_ASCII)
        if (magic != "ustar\u0000" && magic != "ustar ") throw TarFormatException("not a ustar header (magic '$magic')")

        val name = field(0, 100)
        val prefix = field(345, 155)
        val rawPath = if (prefix.isEmpty()) name else "$prefix/$name"
        val type =
            when (val flag = header[156].toInt().toChar()) {
                '0', '\u0000' -> TarEntryType.File
                '5' -> TarEntryType.Directory
                else -> throw TarFormatException("$rawPath: unsupported entry type '$flag'")
            }
        val path = normalize(rawPath, type)
        val mode = octal(100, 8, rawPath).toInt()
        val size = octal(124, 12, rawPath)
        if (type == TarEntryType.Directory && size != 0L) throw TarFormatException("$path: directory with data")
        remaining = size
        padding = (BLOCK - size % BLOCK) % BLOCK
        return TarEntry(path, type, mode, size)
    }

    /** Data of the current entry; reading past its end returns -1. */
    val data: InputStream =
        object : InputStream() {
            override fun read(): Int {
                if (remaining <= 0) return -1
                val b = input.read()
                if (b < 0) throw EOFException("archive truncated")
                remaining--
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
                if (n < 0) throw EOFException("archive truncated")
                remaining -= n
                return n
            }
        }

    private fun verifyChecksum() {
        val stored = octal(148, 8, "header")
        var sum = 0L
        for (i in 0 until BLOCK) {
            sum += if (i in 148 until 156) 0x20 else header[i].toInt() and 0xff
        }
        if (sum != stored) throw TarFormatException("header checksum mismatch near '${field(0, 100)}'")
    }

    private fun field(offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun octal(offset: Int, length: Int, what: String): Long {
        if (header[offset].toInt() and 0x80 != 0) throw TarFormatException("$what: base-256 numbers are not ustar")
        val text = field(offset, length).trim(' ', '\u0000')
        if (text.isEmpty()) return 0
        return text.toLongOrNull(8) ?: throw TarFormatException("$what: bad octal field '$text'")
    }

    private fun readBlock(into: ByteArray): Boolean {
        var off = 0
        while (off < BLOCK) {
            val n = input.read(into, off, BLOCK - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("archive truncated inside a header")
            }
            off += n
        }
        return true
    }

    private fun skipFully(count: Long) {
        var left = count
        val scratch = ByteArray(8192)
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) throw EOFException("archive truncated")
            left -= n
        }
    }

    companion object {
        const val BLOCK = 512

        /**
         * Validates and normalizes an entry path: relative, no empty, `.` or
         * `..` components, no backslashes or NULs. A trailing slash is allowed
         * on directories only.
         */
        fun normalize(raw: String, type: TarEntryType): String {
            var path = raw
            if (type == TarEntryType.Directory && path.endsWith("/")) path = path.dropLast(1)
            if (path.isEmpty()) throw TarFormatException("empty path")
            if (path.length > 255) throw TarFormatException("$path: path too long")
            if (path.startsWith("/")) throw TarFormatException("$path: absolute path")
            if ('\\' in path || '\u0000' in path) throw TarFormatException("$path: illegal character")
            for (part in path.split('/')) {
                if (part.isEmpty() || part == "." ||
                    part == ".."
                ) {
                    throw TarFormatException("$path: illegal path component")
                }
            }
            return path
        }
    }
}
