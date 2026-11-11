package app.amphora.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.amphora.Launcher
import app.amphora.LocalAppGraph
import app.amphora.sdk.engine.Component
import app.amphora.sdk.session.LaunchTarget

/**
 * Where screens start sessions. Launches that this recipe cannot run (an x86
 * game before FEX) ask first instead of failing silently in the session.
 */
@Stable
class LaunchGate internal constructor(private val launcher: Launcher) {
    internal var pending: Pending? by mutableStateOf(null)
        private set

    fun request(target: LaunchTarget) {
        val missing = launcher.missingComponent(target)
        if (missing == null) launcher.launch(target) else pending = Pending(target, missing)
    }

    internal fun confirm() {
        pending?.let { launcher.launch(it.target) }
        pending = null
    }

    internal fun dismiss() {
        pending = null
    }

    internal data class Pending(val target: LaunchTarget, val missing: Component)
}

@Composable
fun rememberLaunchGate(): LaunchGate {
    val launcher = LocalAppGraph.current.launcher
    val gate = remember(launcher) { LaunchGate(launcher) }
    gate.pending?.let { (target, missing) ->
        val arch = (target as? LaunchTarget.Play)?.game?.arch?.label ?: "x86"
        ConfirmDialog(
            overline = "需要 ${missing.label}",
            title = "这个配方还跑不了 $arch 程序",
            message =
            "「${target.title}」是 $arch 程序，要靠 ${missing.label} 翻译成 ARM 指令，" +
                "它会在 ${missing.milestone} 加入配方。现在启动多半会直接退出，原因会写进会话日志。",
            confirmLabel = "仍然启动",
            onConfirm = gate::confirm,
            onDismiss = gate::dismiss,
        )
    }
    return gate
}
