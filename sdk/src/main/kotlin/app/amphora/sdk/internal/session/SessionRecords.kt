package app.amphora.sdk.internal.session

import app.amphora.sdk.internal.AmphoraJson
import app.amphora.sdk.internal.AmphoraLog
import app.amphora.sdk.internal.io.readTextOrNull
import app.amphora.sdk.internal.io.writeTextAtomically
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException

/**
 * `sessions/current.json`. Only the `:wine` process writes it; only the app
 * process deletes it, after the session process is gone. Writes are atomic,
 * so a reader never sees half a record.
 */
internal class SessionRecords(private val file: File) {
    fun read(): SessionRecord? {
        val text = file.readTextOrNull() ?: return null
        return try {
            AmphoraJson.decodeFromString(SessionRecord.serializer(), text)
        } catch (e: SerializationException) {
            AmphoraLog.w("unreadable session record, discarding", e)
            file.delete()
            null
        } catch (e: IllegalArgumentException) {
            AmphoraLog.w("unreadable session record, discarding", e)
            file.delete()
            null
        }
    }

    fun write(record: SessionRecord) {
        try {
            writeTextAtomically(file, AmphoraJson.encodeToString(SessionRecord.serializer(), record))
        } catch (e: IOException) {
            AmphoraLog.w("could not write session record", e)
        }
    }

    fun delete() {
        file.delete()
    }
}

/** Liveness of the session process, by pid and process name (pids are reused). */
internal object SessionProcess {
    const val SUFFIX = ":wine"

    fun alive(pid: Int, packageName: String): Boolean {
        if (pid <= 0) return false
        val cmdline = File("/proc/$pid/cmdline").readTextOrNull() ?: return false
        return cmdline.substringBefore('\u0000') == packageName + SUFFIX
    }
}
