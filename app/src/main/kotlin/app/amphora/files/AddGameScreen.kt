package app.amphora.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Adb
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.amphora.LocalAppGraph
import app.amphora.sdk.engine.Component
import app.amphora.sdk.library.PeArch
import app.amphora.shell.FixedScreen
import app.amphora.shell.adbPath
import app.amphora.system.StorageAccess
import app.amphora.ui.components.AmphoraButton
import app.amphora.ui.components.ButtonKind
import app.amphora.ui.components.Chip
import app.amphora.ui.components.CodeWell
import app.amphora.ui.components.EmptyState
import app.amphora.ui.components.Hairline
import app.amphora.ui.components.IconAction
import app.amphora.ui.components.IconTile
import app.amphora.ui.components.ListRow
import app.amphora.ui.components.Panel
import app.amphora.ui.components.ProgressTrack
import app.amphora.ui.components.Tag
import app.amphora.ui.components.Tone
import app.amphora.ui.components.panelSlice
import app.amphora.ui.format.Formats
import app.amphora.ui.theme.Amphora
import app.amphora.ui.theme.MonoFamily
import java.io.File

/**
 * Picks a game's executable. The file stays where it is: the library keeps
 * its path and fingerprint, and the session maps its folder as D:.
 */
@Composable
fun AddGameScreen(onBack: () -> Unit, onAdded: (String) -> Unit) {
    val graph = LocalAppGraph.current
    val model = viewModel { AddGameViewModel(graph) }
    val dir by model.dir.collectAsStateWithLifecycle()
    val roots by model.roots.collectAsStateWithLifecycle()
    val browse by model.browse.collectAsStateWithLifecycle()
    val inLibrary by model.inLibrary.collectAsStateWithLifecycle()
    val adding by model.adding.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val canRunX86 = remember(graph) { Component.Fex in graph.sdk.recipe.components }

    val root = StorageRoot.of(roots, dir)
    val canGoUp = root != null && dir.path != root.dir.path
    BackHandler(enabled = canGoUp) { model.up() }
    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose {}
    }
    val added by rememberUpdatedState(onAdded)
    LaunchedEffect(model) { model.added.collect { added(it) } }

    FixedScreen(
        title = "添加游戏",
        overline = "Add game",
        subtitle = "选一个 .exe。文件留在原处，amphora 只记下路径和指纹。",
        onBack = onBack,
        actions = { IconAction(Icons.Outlined.Refresh, "刷新", model::refresh) },
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            roots.forEach { option ->
                Chip(
                    label = option.label,
                    selected = option == root,
                    onClick = { model.open(option.dir) },
                    icon =
                    when (option.kind) {
                        StorageRoot.Kind.Internal -> Icons.Outlined.PhoneAndroid
                        StorageRoot.Kind.Removable -> Icons.Outlined.SdCard
                        StorageRoot.Kind.Sideload -> Icons.Outlined.Adb
                    },
                )
            }
        }
        Breadcrumb(crumbs(root, dir), onOpen = model::open)

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (val state = browse) {
                Browse.Loading -> ProgressTrack(null, ticks = false, height = 3.dp)
                is Browse.NeedsAccess ->
                    AccessPanel(
                        sideload = roots.firstOrNull { it.kind == StorageRoot.Kind.Sideload },
                        onGrant = { StorageAccess.request(context) },
                        onOpen = model::open,
                    )
                is Browse.Unreadable ->
                    Panel(Modifier.fillMaxWidth()) {
                        EmptyState(Icons.Outlined.FolderOff, "读不了这个目录", detail = state.dir.path) {
                            if (root != null) AmphoraButton("回到${root.label}", { model.open(root.dir) })
                        }
                    }
                is Browse.Ready ->
                    Entries(
                        state = state,
                        stale = state.listing.dir != dir,
                        parent = if (canGoUp) dir.parentFile else null,
                        inLibrary = inLibrary,
                        adding = adding,
                        canRunX86 = canRunX86,
                        onOpen = model::open,
                        onAdd = model::add,
                        onShow = onAdded,
                    )
            }
        }
    }
}

/** "$ 内部存储 / Games / Foo": every segment but the last is a shortcut back up. */
@Composable
private fun Breadcrumb(crumbs: List<Crumb>, onOpen: (File) -> Unit) {
    val colors = Amphora.colors
    val scroll = rememberScrollState()
    // Keep the deepest segment in view as the path grows.
    LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collect { scroll.animateScrollTo(it) } }
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.hairline, shape)
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$", style = Amphora.type.mono, color = colors.accent, modifier = Modifier.padding(horizontal = 4.dp))
        crumbs.forEachIndexed { index, crumb ->
            val last = index == crumbs.lastIndex
            if (index > 0) Text("/", style = Amphora.type.mono, color = colors.textFaint)
            Text(
                crumb.label,
                style = Amphora.type.mono,
                color = if (last) colors.text else colors.textMuted,
                maxLines = 1,
                modifier =
                Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable(enabled = !last) { onOpen(crumb.dir) }
                    .padding(horizontal = 4.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun Entries(
    state: Browse.Ready,
    stale: Boolean,
    parent: File?,
    inLibrary: Map<String, String>,
    adding: File?,
    canRunX86: Boolean,
    onOpen: (File) -> Unit,
    onAdd: (File) -> Unit,
    onShow: (String) -> Unit,
) {
    val listing = state.listing
    val colors = Amphora.colors
    if (listing.isEmpty && parent == null) {
        Panel(Modifier.fillMaxWidth()) {
            EmptyState(Icons.Outlined.FolderOff, "这里没有文件夹或 .exe", detail = footnote(listing))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        val rows = (if (parent != null) 1 else 0) + listing.folders.size + listing.exes.size + 1
        var index = 0

        fun LazyListScope.slice(key: Any, content: @Composable () -> Unit) {
            val at = index++
            item(key = key) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .panelSlice(
                            first = at == 0,
                            last = at == rows - 1,
                            fill = colors.surface,
                            edge = colors.hairline,
                        )
                        .padding(horizontal = 6.dp)
                        .padding(top = if (at == 0) 6.dp else 0.dp, bottom = if (at == rows - 1) 6.dp else 0.dp),
                ) {
                    if (at > 0) Hairline()
                    content()
                }
            }
        }

        if (parent != null) {
            slice("..") {
                ListRow(
                    title = "..",
                    detail = "上一级 · ${parent.name.ifEmpty { parent.path }}",
                    detailMono = true,
                    leading = { IconTile(Icons.Outlined.ArrowUpward) },
                    onClick = { onOpen(parent) },
                )
            }
        }
        listing.folders.forEach { folder ->
            slice(folder.file.path) {
                ListRow(
                    title = folder.name,
                    leading = { IconTile(Icons.Outlined.Folder) },
                    trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = colors.textFaint) },
                    onClick = { onOpen(folder.file) },
                )
            }
        }
        listing.exes.forEach { exe ->
            slice(exe.file.path) {
                val known = inLibrary[exe.file.path]
                val inspected = exe.file in state.archs
                ExeRow(
                    exe = exe,
                    arch = state.archs[exe.file],
                    inspected = inspected,
                    known = known != null,
                    busy = adding == exe.file,
                    enabled = adding == null,
                    canRunX86 = canRunX86,
                    onClick = { if (known != null) onShow(known) else onAdd(exe.file) },
                )
            }
        }
        slice("footer") {
            Text(
                footnote(listing),
                style = Amphora.type.monoSmall,
                color = colors.textFaint,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
    }
    if (stale) ProgressTrack(null, ticks = false, height = 3.dp)
}

@Composable
private fun ExeRow(
    exe: DirEntry,
    arch: PeArch?,
    inspected: Boolean,
    known: Boolean,
    busy: Boolean,
    enabled: Boolean,
    canRunX86: Boolean,
    onClick: () -> Unit,
) {
    val colors = Amphora.colors
    val detail =
        when {
            busy -> "正在读取并计算指纹…"
            !inspected -> Formats.bytes(exe.bytes)
            arch == null -> "${Formats.bytes(exe.bytes)} · 不是 Windows 程序"
            else -> Formats.bytes(exe.bytes)
        }
    ListRow(
        title = exe.name,
        detail = detail,
        detailMono = true,
        leading = { ExeTile(dim = inspected && arch == null) },
        trailing = {
            if (arch != null) {
                val blocked = !arch.native && !canRunX86
                Tag(
                    if (blocked) "${arch.label} · 需 FEX" else arch.label,
                    tone = if (blocked) {
                        Tone.Warn
                    } else if (arch.native) {
                        Tone.Ok
                    } else {
                        Tone.Info
                    },
                )
            }
            when {
                busy -> CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
                known -> Tag("已在库中", tone = Tone.Ok)
                else -> Icon(Icons.Outlined.Add, "添加", tint = if (enabled) colors.accent else colors.textFaint)
            }
        },
        onClick = if (enabled && (arch != null || !inspected)) onClick else null,
    )
}

/** The leading tile of an executable: a mono "EXE" stamp instead of a generic file icon. */
@Composable
private fun ExeTile(dim: Boolean) {
    val colors = Amphora.colors
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier
            .size(36.dp)
            .clip(shape)
            .background(if (dim) colors.surfaceRaised else colors.accentSoft)
            .border(1.dp, if (dim) colors.hairline else colors.accent.copy(alpha = 0.3f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "EXE",
            color = if (dim) colors.textFaint else colors.accent,
            fontFamily = MonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun AccessPanel(sideload: StorageRoot?, onGrant: () -> Unit, onOpen: (File) -> Unit) {
    val colors = Amphora.colors
    Panel(Modifier.fillMaxWidth(), border = colors.warn.copy(alpha = 0.3f)) {
        Row(verticalAlignment = Alignment.Top) {
            IconTile(Icons.Outlined.Key, tint = colors.warn)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("需要「所有文件访问」权限", style = MaterialTheme.typography.titleSmall, color = colors.text)
                Text(
                    "游戏在哪儿，amphora 就从哪儿启动它，并把它的文件夹映射成 D: 盘；这要能读整个存储。授权后回到这里即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
        Spacer(Modifier.size(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AmphoraButton("去授权", onGrant, kind = ButtonKind.Primary, icon = Icons.Outlined.Key)
            if (sideload !=
                null
            ) {
                AmphoraButton("浏览侧载目录", {
                    onOpen(sideload.dir)
                }, kind = ButtonKind.Ghost, icon = Icons.Outlined.Adb)
            }
        }
        if (sideload != null) {
            Spacer(Modifier.size(14.dp))
            Text("不想授权？用 adb 把游戏放进侧载目录：", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            Spacer(Modifier.size(6.dp))
            CodeWell(listOf("$ adb push <游戏目录> ${adbPath(sideload.dir)}"))
        }
    }
}

private fun footnote(listing: DirListing): String = buildList {
    add("${listing.folders.size} 个文件夹")
    add("${listing.exes.size} 个 .exe")
    if (listing.others > 0) add("${listing.others} 个其他文件")
    if (listing.hidden > 0) add("${listing.hidden} 个隐藏")
}.joinToString("  ·  ")
