package app.amphora.sdk.library

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PeInfoTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `reads the machine type of PE executables`() {
        val machines =
            mapOf(
                0x014c to PeArch.X86,
                0x8664 to PeArch.X64,
                0xaa64 to PeArch.Arm64,
                0xa641 to PeArch.Arm64ec,
            )
        for ((machine, arch) in machines) assertEquals(arch, PeInfo.arch(write(pe(machine))))
    }

    @Test
    fun `other machine types are unknown`() {
        for (machine in listOf(0x01c4, 0x0200, 0x0000, 0xffff)) {
            assertEquals(machine.toString(16), PeArch.Unknown, PeInfo.arch(write(pe(machine))))
        }
    }

    @Test
    fun `files without the MZ signature are not executables`() {
        assertNull(PeInfo.arch(write(pe(0x8664).withBytes(0, 0x7f, 'E'.code, 'L'.code, 'F'.code))))
        assertNull(PeInfo.arch(write(pe(0x8664).withBytes(0, 'Z'.code, 'M'.code))))
        assertNull(PeInfo.arch(write("just some text, long enough to pass the size check of sixty-four bytes")))
    }

    @Test
    fun `an MZ header without a PE header is not a PE executable`() {
        assertNull(PeInfo.arch(write(pe(0x014c).withBytes(PE_OFFSET, 0, 0, 0, 0))))
        assertNull(PeInfo.arch(write(pe(0x014c).withBytes(PE_OFFSET, 'N'.code, 'E'.code))))
        assertNull(PeInfo.arch(write(pe(0x014c).withBytes(PE_OFFSET + 3, 1))))
    }

    @Test
    fun `files shorter than a DOS header are rejected`() {
        assertNull(PeInfo.arch(write(ByteArray(0))))
        assertNull(PeInfo.arch(write(pe(0x8664).copyOf(0x3f))))
    }

    @Test
    fun `the PE offset must point inside the file`() {
        assertNull("zero", PeInfo.arch(write(pe(0x8664, peOffset = 0))))
        assertNull("negative", PeInfo.arch(write(pe(0x8664, peOffset = Int.MIN_VALUE))))
        assertNull("past the end", PeInfo.arch(write(pe(0x8664, peOffset = 0x10000))))
    }

    @Test
    fun `the PE header may sit right at the end of a small file`() {
        // A 64-byte file whose PE signature starts 24 bytes before the end is the smallest accepted layout.
        assertEquals(PeArch.X64, PeInfo.arch(write(pe(0x8664, peOffset = 0x28, size = 0x40))))
        assertEquals(PeArch.X64, PeInfo.arch(write(pe(0x8664, peOffset = 0x200 - 24))))
        assertNull(PeInfo.arch(write(pe(0x8664, peOffset = 0x200 - 23))))
    }

    @Test
    fun `missing files and directories are not executables`() {
        assertNull(PeInfo.arch(File(temp.root, "missing.exe")))
        assertNull(PeInfo.arch(temp.newFolder("dir.exe")))
    }

    /** A minimal PE image: MZ, e_lfanew at 0x3c, then "PE\0\0" and the machine field. */
    private fun pe(machine: Int, peOffset: Int = PE_OFFSET, size: Int = 0x200): ByteArray {
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, 'M'.code.toByte()).put(1, 'Z'.code.toByte())
        buffer.putInt(0x3c, peOffset)
        if (peOffset in 0x40..size - 6 || peOffset in 0x04..0x3c - 6) {
            buffer.put(peOffset, 'P'.code.toByte()).put(peOffset + 1, 'E'.code.toByte())
            buffer.putShort(peOffset + 4, machine.toShort())
        }
        return buffer.array()
    }

    private fun ByteArray.withBytes(offset: Int, vararg values: Int): ByteArray = apply {
        values.forEachIndexed { i, v -> this[offset + i] = v.toByte() }
    }

    private fun write(bytes: ByteArray): File = temp.newFile().apply { writeBytes(bytes) }

    private fun write(text: String): File = temp.newFile().apply { writeText(text) }

    private companion object {
        const val PE_OFFSET = 0x80
    }
}
