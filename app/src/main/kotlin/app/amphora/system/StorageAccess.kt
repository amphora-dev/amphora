package app.amphora.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.core.net.toUri

/**
 * "All files access": games live wherever the user put them, and Wine maps
 * their directory as D:. Opens the system page for this app, falling back to
 * the general list on devices that lack the per-app page.
 */
object StorageAccess {
    fun granted(): Boolean = Environment.isExternalStorageManager()

    fun request(context: Context) {
        val perApp =
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(perApp)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
