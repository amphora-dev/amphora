package app.amphora.core.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Android storage access required by Wine drive symlinks.
 *
 * A granted legacy READ_EXTERNAL_STORAGE permission is not sufficient on Android 11+:
 * the platform may still report it as granted while denying traversal of shared storage.
 * Wine needs real filesystem paths, so SAF-only URI grants cannot back these drive links.
 */
object GuestStorageAccess {
    @JvmStatic
    fun isGranted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    fun manageIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${context.packageName}".toUri(),
        )
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri(),
        )
    }
}
