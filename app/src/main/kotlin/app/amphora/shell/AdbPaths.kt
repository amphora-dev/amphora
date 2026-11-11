package app.amphora.shell

import android.annotation.SuppressLint
import java.io.File

/**
 * [file] the way someone types it into `adb push`: `/sdcard/...` rather than
 * `/storage/emulated/0/...`. For display only; never used for file access.
 */
@SuppressLint("SdCardPath")
fun adbPath(file: File): String = file.path.replaceFirst("/storage/emulated/0/", "/sdcard/").removeSuffix("/") + "/"
