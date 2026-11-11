package app.amphora.sdk.internal.engine

import java.io.ByteArrayOutputStream

/**
 * Writes ustar archives block by block for tests, including headers no real
 * tar would produce: [entry] takes an [edit] hook that runs on the header
 * before its checksum is computed.
 */
internal class TarWriter {
    private val out = ByteArrayOutputStream()

    fun dir(path: String, mode: Int = MODE_EXEC): TarWriter = entry(path, type = '5', mode = mode)

    fun file(path: String, content: ByteArray, mode: Int = MODE_FILE): TarWriter =
        entry(path, type = '0', mode = mode, content = content)

    fun file(path: String, text: String, mode: Int = MODE_FILE): TarWriter = file(path, text.encodeToByteArray(), mode)

    fun entry(
        name: String,
        type: Char = '0',
        mode: Int = MODE_FILE,
        content: ByteArray = ByteArray(0),
        prefix: String = "",
        edit: (ByteArray) -> Unit = {},
    ): TarWriter {
        out.write(header(name, type, mode, content.size.toLong(), prefix, edit))
        out.write(content)
        out.write(ByteArray((BLOCK - content.size % BLOCK) % BLOCK))
        return this
    }

    /** Bytes as they are: truncated or corrupted pieces of an archive. */
    fun raw(bytes: ByteArray): TarWriter = apply { out.write(bytes) }

    /** The end-of-archive marker: two zero blocks, or fewer to test tolerance. */
    fun end(blocks: Int = 2): TarWriter = raw(ByteArray(BLOCK * blocks))

    fun bytes(): ByteArray = out.toByteArray()

    companion object {
        const val BLOCK = 512

        /** rw-r--r-- */
        const val MODE_FILE = 0b110_100_100

        /** rwxr-xr-x */
        const val MODE_EXEC = 0b111_101_101

        fun header(
            name: String,
            type: Char,
            mode: Int,
            size: Long,
            prefix: String = "",
            edit: (ByteArray) -> Unit = {},
        ): ByteArray {
            val h = ByteArray(BLOCK)
            put(h, 0, 100, name)
            putOctal(h, 100, 8, mode.toLong())
            putOctal(h, 108, 8, 0) // uid
            putOctal(h, 116, 8, 0) // gid
            putOctal(h, 124, 12, size)
            putOctal(h, 136, 12, 1_700_000_000) // mtime
            h[156] = type.code.toByte()
            put(h, 257, 8, "ustar\u000000")
            put(h, 345, 155, prefix)
            edit(h)
            sealChecksum(h)
            return h
        }

        /** Stores the checksum: the unsigned byte sum with the checksum field counted as spaces. */
        fun sealChecksum(h: ByteArray) {
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xff }
            put(h, 148, 8, sum.toString(8).padStart(6, '0') + "\u0000 ")
        }

        /** Writes [text] into a field, zero-filling the rest of it. */
        fun put(h: ByteArray, offset: Int, length: Int, text: String) {
            val bytes = text.encodeToByteArray()
            require(bytes.size <= length) { "'$text' does not fit in $length bytes" }
            bytes.copyInto(h, offset)
            h.fill(0, offset + bytes.size, offset + length)
        }

        private fun putOctal(h: ByteArray, offset: Int, length: Int, value: Long) =
            put(h, offset, length, value.toString(8).padStart(length - 1, '0') + "\u0000")
    }
}
