package app.amphora.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.amphora.BuildConfig
import app.amphora.LocalAppGraph
import app.amphora.runtime.RuntimePanel
import app.amphora.sdk.container.PrefixInfo
import app.amphora.sdk.engine.Component
import app.amphora.sdk.engine.DeviceInfo
import app.amphora.sdk.engine.InstalledRuntime
import app.amphora.sdk.engine.RuntimeState
import app.amphora.sdk.engine.Support
import app.amphora.sdk.engine.VerifyResult
import app.amphora.sdk.logs.LogFile
import app.amphora.sdk.session.SessionState
import app.amphora.shell.ConfirmDialog
import app.amphora.shell.Screen
import app.amphora.ui.components.AmphoraButton
import app.amphora.ui.components.ButtonKind
import app.amphora.ui.components.ButtonSize
import app.amphora.ui.components.KeyValueRow
import app.amphora.ui.components.Panel
import app.amphora.ui.components.ProgressTrack
import app.amphora.ui.components.Readout
import app.amphora.ui.components.SectionHeader
import app.amphora.ui.components.SectionLabel
import app.amphora.ui.components.StatusPill
import app.amphora.ui.components.Tag
import app.amphora.ui.components.Tone
import app.amphora.ui.format.Formats
import app.amphora.ui.theme.Amphora
import java.text.DateFormat
import java.util.Date

@Composable
fun SystemScreen(onOpenLogs: () -> Unit) {
    val graph = LocalAppGraph.current
    val model = viewModel { SystemViewModel(graph) }
    val ui by model.ui.collectAsStateWithLifecycle()
    val runtime by model.runtime.collectAsStateWithLifecycle()
    val session by model.session.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf<Danger?>(null) }
    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose {}
    }
    val idle = session is SessionState.Idle && !ui.busy

    Screen(title = "系统", overline = "System", subtitle = "运行时、设备、前缀与日志") { layout ->
        val runtimePanel: @Composable () -> Unit = {
            when (val state = runtime) {
                is RuntimeState.Ready ->
                    RuntimeReady(
                        state.runtime,
                        model.recipe.id,
                        model.recipe.components,
                        ui.verify,
                        idle,
                        model::verify,
                    ) {
                        confirm = Danger.Uninstall
                    }
                else -> RuntimePanel(state, model.sideloadDir, model::install, model::cancelInstall)
            }
        }
        val prefixPanel: @Composable () -> Unit = { PrefixPanel(ui.prefix, idle) { confirm = Danger.ResetPrefix } }
        val logsPanel: @Composable () -> Unit = { LogsPanel(ui.logs, onOpenLogs) { confirm = Danger.ClearLogs } }
        val devicePanel: @Composable () -> Unit = { DevicePanel(ui.device) }
        val storagePanel: @Composable () -> Unit = { StoragePanel(ui.storageAccess) }
        val aboutPanel: @Composable () -> Unit = { AboutPanel(model.recipe.id, model.dataDir.path) }

        if (layout.wide) {
            item(key = "columns") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        runtimePanel()
                        prefixPanel()
                        logsPanel()
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        devicePanel()
                        storagePanel()
                        aboutPanel()
                    }
                }
            }
        } else {
            item(key = "runtime") { runtimePanel() }
            item(key = "device") { devicePanel() }
            item(key = "prefix") { prefixPanel() }
            item(key = "storage") { storagePanel() }
            item(key = "logs") { logsPanel() }
            item(key = "about") { aboutPanel() }
        }
    }

    when (confirm) {
        Danger.Uninstall ->
            ConfirmDialog(
                title = "卸载运行时？",
                message = "删除已安装的 Wine 运行时和下载缓存（约 ${Formats.bytes(model.recipe.runtime.bytes)}）。前缀、游戏和设置都保留；再次使用要重新安装。",
                confirmLabel = "卸载",
                danger = true,
                onConfirm = model::uninstall,
                onDismiss = { confirm = null },
            )
        Danger.ResetPrefix ->
            ConfirmDialog(
                title = "重建前缀？",
                message = "删除默认前缀：注册表、drive_c 里的安装内容和游戏存在 C: 里的存档都会消失。游戏目录（D:）不受影响。下次启动会重新初始化。",
                confirmLabel = "删除前缀",
                danger = true,
                onConfirm = model::resetPrefix,
                onDismiss = { confirm = null },
            )
        Danger.ClearLogs ->
            ConfirmDialog(
                title = "清空日志？",
                message = "删除所有会话日志。正在运行的会话会继续写它自己的日志。",
                confirmLabel = "清空",
                danger = true,
                onConfirm = model::clearLogs,
                onDismiss = { confirm = null },
            )
        null -> Unit
    }
}

private enum class Danger { Uninstall, ResetPrefix, ClearLogs }

@Composable
private fun RuntimeReady(
    runtime: InstalledRuntime,
    recipeId: String,
    components: Set<Component>,
    verify: app.amphora.system.Load<VerifyResult>?,
    idle: Boolean,
    onVerify: () -> Unit,
    onUninstall: () -> Unit,
) {
    val colors = Amphora.colors
    val manifest = runtime.manifest
    Panel(Modifier.fillMaxWidth()) {
        SectionHeader("运行时", tag = "Runtime") { StatusPill("ready", Tone.Ok) }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Readout("Wine", manifest.wine.version)
            Readout("文件", manifest.files.toString())
            Readout("大小", Formats.bytes(manifest.bytes))
        }
        Spacer(Modifier.height(12.dp))
        KeyValueRow("名称", manifest.name)
        KeyValueRow("提交", Formats.shortHash(manifest.wine.commit))
        KeyValueRow("上游", manifest.wine.upstreamTag)
        KeyValueRow("架构", manifest.wine.archs.joinToString(" "))
        KeyValueRow("ABI", "${manifest.abi} · API ${manifest.minSdk}+")
        KeyValueRow("配方", recipeId)
        Spacer(Modifier.height(10.dp))
        SectionLabel("配方组件")
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Component.entries.forEach { component ->
                if (component in components) {
                    Tag(component.label, tone = Tone.Ok)
                } else {
                    Tag("${component.label} · ${component.milestone}")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        when (verify) {
            null -> Unit
            Load.Loading -> {
                ProgressTrack(null, ticks = false, height = 4.dp)
                Spacer(Modifier.height(12.dp))
            }
            is Load.Done -> {
                Text(
                    (if (verify.value.ok) "✓ " else "✗ ") + verify.value.detail,
                    style = Amphora.type.mono,
                    color = if (verify.value.ok) colors.ok else colors.danger,
                )
                Spacer(Modifier.height(12.dp))
            }
            is Load.Failed -> {
                Text("✗ ${verify.message}", style = Amphora.type.mono, color = colors.danger)
                Spacer(Modifier.height(12.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AmphoraButton(
                "校验文件",
                onVerify,
                kind = ButtonKind.Secondary,
                icon = Icons.AutoMirrored.Outlined.FactCheck,
                enabled =
                verify != Load.Loading,
            )
            AmphoraButton(
                "卸载",
                onUninstall,
                kind = ButtonKind.Danger,
                icon = Icons.Outlined.DeleteOutline,
                enabled = idle,
            )
        }
    }
}

@Composable
private fun DevicePanel(device: Load<DeviceInfo>) {
    val colors = Amphora.colors
    Panel(Modifier.fillMaxWidth()) {
        when (device) {
            Load.Loading -> {
                SectionHeader("设备", tag = "Device")
                ProgressTrack(null, ticks = false, height = 4.dp)
            }
            is Load.Failed -> {
                SectionHeader("设备", tag = "Device")
                Text(device.message, style = MaterialTheme.typography.bodyMedium, color = colors.danger)
            }
            is Load.Done -> {
                val info = device.value
                val support = info.support
                SectionHeader("${info.manufacturer} ${info.model}", tag = "Device") {
                    when (support) {
                        Support.Supported -> StatusPill("supported", Tone.Ok)
                        is Support.Untested -> StatusPill("untested", Tone.Warn)
                        is Support.Unsupported -> StatusPill("unsupported", Tone.Danger)
                    }
                }
                val reason =
                    when (support) {
                        Support.Supported -> null
                        is Support.Untested -> support.reason
                        is Support.Unsupported -> support.reason
                    }
                if (reason != null) {
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (support is Support.Unsupported) colors.danger else colors.warn,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Readout("GPU", info.adreno?.let { "Adreno $it" } ?: "?")
                    Readout("内存", Formats.bytes(info.totalRam))
                    Readout("可用存储", Formats.bytes(info.storageFree))
                }
                Spacer(Modifier.height(12.dp))
                info.soc?.let { KeyValueRow("SoC", it) }
                KeyValueRow("渲染器", info.gpu ?: "未知")
                KeyValueRow("Android", "${info.android} · API ${info.sdk}")
                KeyValueRow("内核", info.kernel)
                KeyValueRow("页大小", Formats.bytes(info.pageSize))
                KeyValueRow("存储", "${Formats.bytes(info.storageFree)} / ${Formats.bytes(info.storageTotal)}")
                KeyValueRow("ABI", info.abis.joinToString(" "))
            }
        }
    }
}

@Composable
private fun PrefixPanel(prefix: Load<PrefixInfo>, idle: Boolean, onReset: () -> Unit) {
    val colors = Amphora.colors
    Panel(Modifier.fillMaxWidth()) {
        when (prefix) {
            Load.Loading -> {
                SectionHeader("前缀", tag = "Prefix")
                ProgressTrack(null, ticks = false, height = 4.dp)
            }
            is Load.Failed -> {
                SectionHeader("前缀", tag = "Prefix")
                Text(prefix.message, style = MaterialTheme.typography.bodyMedium, color = colors.danger)
            }
            is Load.Done -> {
                val info = prefix.value
                SectionHeader("前缀 ${info.id}", tag = "Prefix") {
                    when {
                        info.stale -> StatusPill("stale", Tone.Warn)
                        info.initialized -> StatusPill("ready", Tone.Ok)
                        else -> StatusPill("empty", Tone.Neutral)
                    }
                }
                Text(
                    when {
                        info.stale -> "前缀是旧配方（${info.record?.recipe}）建的。配方之间不做迁移，建议重建。"
                        info.initialized -> "Windows 的 C: 盘和注册表。所有游戏现在共用这一个。"
                        else -> "还没初始化：第一次启动任何程序时 Wine 会建好它，要多花几十秒。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (info.stale) colors.warn else colors.textMuted,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Readout("大小", Formats.bytes(info.bytes))
                    Readout("文件", info.files.toString())
                    Readout("链接", info.links.toString())
                }
                Spacer(Modifier.height(12.dp))
                KeyValueRow("目录", info.dir.path)
                info.record?.let { record ->
                    KeyValueRow(
                        "创建于",
                        DateFormat.getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT,
                        ).format(Date(record.createdAt)),
                        mono = false,
                    )
                    KeyValueRow("配方", record.recipe)
                }
                Spacer(Modifier.height(14.dp))
                AmphoraButton(
                    "重建前缀",
                    onReset,
                    kind = ButtonKind.Danger,
                    icon = Icons.Outlined.RestartAlt,
                    enabled = idle && info.record != null,
                )
            }
        }
    }
}

@Composable
private fun StoragePanel(granted: Boolean) {
    val colors = Amphora.colors
    val context = LocalContext.current
    Panel(Modifier.fillMaxWidth()) {
        SectionHeader("存储访问", tag = "Storage") {
            StatusPill(if (granted) "granted" else "denied", if (granted) Tone.Ok else Tone.Warn)
        }
        Text(
            if (granted) {
                "可以读取任意目录里的游戏，并把游戏目录挂成 D: 盘。"
            } else {
                "要从任意目录添加游戏，需要「所有文件访问」权限：Wine 直接读写游戏目录，系统的文件选择器做不到这一点。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        if (!granted) {
            Spacer(Modifier.height(14.dp))
            AmphoraButton("去授权", {
                StorageAccess.request(context)
            }, kind = ButtonKind.Primary, icon = Icons.Outlined.FolderOpen)
        }
    }
}

@Composable
private fun LogsPanel(logs: Load<List<LogFile>>, onOpen: () -> Unit, onClear: () -> Unit) {
    val colors = Amphora.colors
    Panel(Modifier.fillMaxWidth()) {
        SectionHeader("日志", tag = "Logs")
        val list = (logs as? Load.Done)?.value.orEmpty()
        Text(
            if (list.isEmpty()) {
                "还没有会话日志。每个会话一份，保留最近 20 份。"
            } else {
                "${list.size} 份 · ${Formats.bytes(
                    list.sumOf {
                        it.bytes
                    },
                )} · 每个会话一份，保留最近 20 份"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AmphoraButton(
                "查看日志",
                onOpen,
                kind = ButtonKind.Secondary,
                icon = Icons.AutoMirrored.Outlined.Article,
                enabled = list.isNotEmpty(),
            )
            AmphoraButton(
                "清空",
                onClear,
                kind = ButtonKind.Ghost,
                size = ButtonSize.Regular,
                enabled = list.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun AboutPanel(recipeId: String, dataDir: String) {
    Panel(Modifier.fillMaxWidth()) {
        SectionHeader("关于", tag = "About")
        KeyValueRow(
            "版本",
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (BuildConfig.DEBUG) " · debug" else "",
        )
        KeyValueRow("包名", BuildConfig.APPLICATION_ID)
        KeyValueRow("配方", recipeId)
        KeyValueRow("数据", dataDir)
        KeyValueRow("引擎", "github.com/amphora-dev/amphora-engine")
    }
}
