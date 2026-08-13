package app.amphora.core.engine

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Raw launch choices shared by Launcher and Settings. */
data class LaunchRuntimeSettings(
    val resolutionName: String? = null,
    val graphicsDriverId: String? = null,
    val directDrawWrapperId: String? = null,
    val dxvkFlavorId: String? = null,
)

/**
 * Single source of truth for the three launch choices shared by Launcher and Settings.
 *
 * Feature modules retain ownership of mapping these persisted strings to their enums.
 */
@Singleton
class RuntimeSettingsStore private constructor(private val preferences: SharedPreferences) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.getSharedPreferences(GraphicsDriverIds.PREFS_NAME, Context.MODE_PRIVATE))

    private val mutableSettings = MutableStateFlow(readSettings())
    val settings: StateFlow<LaunchRuntimeSettings> = mutableSettings.asStateFlow()

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in launchSettingKeys) {
                mutableSettings.value = readSettings()
            }
        }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setResolutionName(value: String) {
        preferences.edit { putString(KEY_RESOLUTION_NAME, value) }
    }

    fun setGraphicsDriverId(value: String) {
        preferences.edit { putString(GraphicsDriverIds.PREFS_KEY_DRIVER_ID, value) }
    }

    fun setDirectDrawWrapperId(value: String) {
        preferences.edit { putString(DirectDrawWrapperIds.PREFS_KEY_WRAPPER_ID, value) }
    }

    fun setDxvkFlavorId(value: String) {
        preferences.edit { putString(DxvkFlavorIds.PREFS_KEY_FLAVOR, value) }
    }

    fun clearLaunchSettings() {
        preferences.edit {
            remove(KEY_RESOLUTION_NAME)
            remove(GraphicsDriverIds.PREFS_KEY_DRIVER_ID)
            remove(DirectDrawWrapperIds.PREFS_KEY_WRAPPER_ID)
            remove(DxvkFlavorIds.PREFS_KEY_FLAVOR)
        }
    }

    private fun readSettings(): LaunchRuntimeSettings = LaunchRuntimeSettings(
        resolutionName = preferences.getString(KEY_RESOLUTION_NAME, null),
        graphicsDriverId =
        preferences.getString(GraphicsDriverIds.PREFS_KEY_DRIVER_ID, null),
        directDrawWrapperId =
        preferences.getString(DirectDrawWrapperIds.PREFS_KEY_WRAPPER_ID, null),
        dxvkFlavorId = preferences.getString(DxvkFlavorIds.PREFS_KEY_FLAVOR, null),
    )

    companion object {
        const val KEY_RESOLUTION_NAME = "display_resolution"

        internal fun createForTest(preferences: SharedPreferences): RuntimeSettingsStore =
            RuntimeSettingsStore(preferences)

        private val launchSettingKeys =
            setOf(
                KEY_RESOLUTION_NAME,
                GraphicsDriverIds.PREFS_KEY_DRIVER_ID,
                DirectDrawWrapperIds.PREFS_KEY_WRAPPER_ID,
                DxvkFlavorIds.PREFS_KEY_FLAVOR,
            )
    }
}
