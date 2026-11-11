package app.amphora

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import app.amphora.sdk.AmphoraSdk
import app.amphora.sdk.engine.Component
import app.amphora.sdk.session.LaunchResult
import app.amphora.sdk.session.LaunchTarget
import app.amphora.sdk.settings.SettingLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app's object graph: the SDK plus the few things screens share. Manual
 * DI, one per process, created on first use (never in the `:wine` process).
 */
class AppGraph(val app: Application) {
    /** Work that must outlive the screen that started it: launches, removals. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sdk: AmphoraSdk = AmphoraSdk.get(app)
    val messages = Messages()
    val prefs = UiPrefs(app)
    val launcher = Launcher(sdk, messages, scope)
}

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("no AppGraph") }

/** One-line notices for the snackbar. */
class Messages {
    private val flow = MutableSharedFlow<Message>(extraBufferCapacity = 8)
    val events: SharedFlow<Message> = flow.asSharedFlow()

    fun show(text: String, tone: Message.Tone = Message.Tone.Info) {
        flow.tryEmit(Message(text, tone))
    }
}

data class Message(val text: String, val tone: Tone) {
    enum class Tone { Info, Ok, Warn, Error }
}

/** UI preferences: not settings (those belong to the SDK), just how screens are shown. */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _settingsLevel = MutableStateFlow(readLevel())
    val settingsLevel: StateFlow<SettingLevel> = _settingsLevel.asStateFlow()

    fun setSettingsLevel(level: SettingLevel) {
        prefs.edit { putString(KEY_LEVEL, level.name) }
        _settingsLevel.value = level
    }

    /** Where the add-game browser was last, so it reopens there. */
    var browseDir: String?
        get() = prefs.getString(KEY_BROWSE, null)
        set(value) = prefs.edit { putString(KEY_BROWSE, value) }

    private fun readLevel(): SettingLevel =
        prefs.getString(KEY_LEVEL, null)?.let { name -> SettingLevel.entries.firstOrNull { it.name == name } }
            ?: SettingLevel.Basic

    private companion object {
        const val KEY_LEVEL = "settings.level"
        const val KEY_BROWSE = "browse.dir"
    }
}

/**
 * Starts sessions and turns every outcome into something the user can read.
 * Launches run in the app scope: leaving the screen must not cancel one
 * halfway through handing over to the session process.
 */
class Launcher(private val sdk: AmphoraSdk, private val messages: Messages, private val scope: CoroutineScope) {
    /** The component [target] needs that this recipe lacks (x86 without FEX), if any. */
    fun missingComponent(target: LaunchTarget): Component? {
        val game = (target as? LaunchTarget.Play)?.game ?: return null
        return Component.Fex.takeIf { !game.arch.native && it !in sdk.recipe.components }
    }

    fun launch(target: LaunchTarget) {
        scope.launch {
            when (val result = sdk.sessions.launch(target)) {
                is LaunchResult.Started -> result.warnings.forEach { messages.show(it, Message.Tone.Warn) }
                is LaunchResult.Busy -> messages.show("「${result.session.title}」还在运行，先结束它", Message.Tone.Warn)
                LaunchResult.RuntimeNotReady -> messages.show("运行时还没装好", Message.Tone.Warn)
                is LaunchResult.Failed -> messages.show("启动失败：${result.message}", Message.Tone.Error)
            }
        }
    }

    fun resume() {
        if (!sdk.sessions.resume()) messages.show("会话已经结束", Message.Tone.Info)
    }

    fun end() {
        scope.launch {
            sdk.sessions.end()
            messages.show("会话已结束", Message.Tone.Ok)
        }
    }
}
