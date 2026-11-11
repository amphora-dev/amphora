package app.amphora.sdk.library

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Minimal PE header reader: is this a Windows executable, and for which machine. */
internal object PeInfo {
    /** The machine type, or null when [file] is not a PE executable. */
    fun arch(file: File): PeArch? = try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 0x40) return null
            if (raf.readUnsignedByte() != 'M'.code || raf.readUnsignedByte() != 'Z'.code) return null
            raf.seek(0x3c)
            val peOffset = Integer.reverseBytes(raf.readInt()).toLong()
            if (peOffset <= 0 || peOffset > raf.length() - 24) return null
            raf.seek(peOffset)
            if (Integer.reverseBytes(raf.readInt()) != 0x00004550) return null
            when (java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xffff) {
                0x014c -> PeArch.X86
                0x8664 -> PeArch.X64
                0xaa64 -> PeArch.Arm64
                0xa641 -> PeArch.Arm64ec
                else -> PeArch.Unknown
            }
        }
    } catch (_: IOException) {
        null
    }
}
