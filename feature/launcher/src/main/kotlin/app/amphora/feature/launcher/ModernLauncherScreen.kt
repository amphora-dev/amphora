package app.amphora.feature.launcher

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.amphora.core.content.ContentCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernLauncherScreen(
    onLaunch: (exePath: String, width: Int, height: Int) -> Unit,
    onOpenExplorer: (width: Int, height: Int) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LauncherViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var desktopSelected by rememberSaveable { mutableStateOf(true) }
    var compactDetailVisible by rememberSaveable { mutableStateOf(false) }
    val pickExe =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.onExePicked(uri)
        }
    val runtimeReady = state.runtimeReady()

    LaunchedEffect(state.stagedExePath) {
        if (state.stagedExePath != null) {
            desktopSelected = false
            compactDetailVisible = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LauncherTopBar(
                state = state,
                runtimeReady = runtimeReady,
                onAddProgram = { pickExe.launch(arrayOf("*/*")) },
                onRefresh = viewModel::refreshContentInfo,
                onOpenSettings = onOpenSettings,
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val expanded = maxWidth >= 760.dp
            if (expanded) {
                Row(modifier = Modifier.fillMaxSize()) {
                    ProgramListPane(
                        state = state,
                        desktopSelected = desktopSelected,
                        onSelectDesktop = { desktopSelected = true },
                        onSelectProgram = {
                            desktopSelected = false
                            viewModel.selectProgram(it)
                        },
                        onAddProgram = { pickExe.launch(arrayOf("*/*")) },
                        modifier =
                        Modifier
                            .fillMaxHeight()
                            .width(320.dp),
                    )
                    VerticalDivider(
                        modifier =
                        Modifier
                            .fillMaxHeight()
                            .width(1.dp),
                    )
                    DetailPane(
                        state = state,
                        desktopSelected = desktopSelected,
                        runtimeReady = runtimeReady,
                        onLaunchProgram = {
                            state.stagedExePath?.let { path ->
                                viewModel.markProgramLaunched()
                                onLaunch(path, state.resolution.width, state.resolution.height)
                            }
                        },
                        onOpenExplorer = {
                            onOpenExplorer(state.resolution.width, state.resolution.height)
                        },
                        onAddProgram = { pickExe.launch(arrayOf("*/*")) },
                        onOpenSettings = onOpenSettings,
                        onRefresh = viewModel::refreshContentInfo,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else if (compactDetailVisible) {
                BackHandler { compactDetailVisible = false }
                DetailPane(
                    state = state,
                    desktopSelected = desktopSelected,
                    runtimeReady = runtimeReady,
                    onLaunchProgram = {
                        state.stagedExePath?.let { path ->
                            viewModel.markProgramLaunched()
                            onLaunch(path, state.resolution.width, state.resolution.height)
                        }
                    },
                    onOpenExplorer = {
                        onOpenExplorer(state.resolution.width, state.resolution.height)
                    },
                    onAddProgram = { pickExe.launch(arrayOf("*/*")) },
                    onOpenSettings = onOpenSettings,
                    onRefresh = viewModel::refreshContentInfo,
                    onBack = { compactDetailVisible = false },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ProgramListPane(
                    state = state,
                    desktopSelected = desktopSelected,
                    onSelectDesktop = {
                        desktopSelected = true
                        compactDetailVisible = true
                    },
                    onSelectProgram = {
                        desktopSelected = false
                        viewModel.selectProgram(it)
                        compactDetailVisible = true
                    },
                    onAddProgram = { pickExe.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherTopBar(
    state: LauncherUiState,
    runtimeReady: Boolean,
    onAddProgram: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val compact =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width.toDp() < 600.dp
        }
    TopAppBar(
        colors =
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AmphoraMark(Modifier.size(if (compact) 30.dp else 34.dp))
                Column {
                    Text(
                        "Amphora",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                    )
                    if (!compact) {
                        Text(
                            "Windows runtime",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        actions = {
            RuntimeStatusButton(
                ready = runtimeReady,
                busy = state.contentBusy,
                compact = compact,
                onClick = onRefresh,
            )
            TextButton(
                onClick = onAddProgram,
                enabled = !state.staging,
                modifier = Modifier.width(if (compact) 56.dp else 112.dp),
            ) {
                Text(if (compact) "Add" else "+ Add program")
            }
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.width(88.dp),
            ) {
                Text("Settings")
            }
            Spacer(Modifier.width(8.dp))
        },
    )
}

@Composable
private fun RuntimeStatusButton(ready: Boolean, busy: Boolean, compact: Boolean, onClick: () -> Unit) {
    val statusColor =
        when {
            busy -> MaterialTheme.colorScheme.outline
            ready -> Color(0xFF58D6A5)
            else -> MaterialTheme.colorScheme.error
        }
    TextButton(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.width(if (compact) 84.dp else 176.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            when {
                busy -> "Checking"
                ready -> if (compact) "Ready" else "Environment ready"
                else -> if (compact) "Issue" else "Needs attention"
            },
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProgramListPane(
    state: LauncherUiState,
    desktopSelected: Boolean,
    onSelectDesktop: () -> Unit,
    onSelectProgram: (String) -> Unit,
    onAddProgram: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier =
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                "PROGRAMS",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.4.sp,
            )
        }
        item {
            ProgramRow(
                title = "Windows Desktop",
                subtitle = "Explorer and installed programs",
                monogram = "W",
                selected = desktopSelected,
                onClick = onSelectDesktop,
            )
        }
        if (state.recentPrograms.isNotEmpty()) {
            item {
                Text(
                    "RECENT",
                    modifier = Modifier.padding(start = 12.dp, top = 20.dp, end = 12.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.4.sp,
                )
            }
            items(state.recentPrograms, key = RecentProgram::path) { program ->
                ProgramRow(
                    title = program.name.removeSuffix(".exe"),
                    subtitle = relativeTime(program.lastUsedAt),
                    monogram = program.name.firstOrNull()?.uppercase() ?: "A",
                    selected = !desktopSelected && state.stagedExePath == program.path,
                    onClick = { onSelectProgram(program.path) },
                )
            }
        } else {
            item {
                EmptyProgramList(onAddProgram)
            }
        }
        item {
            TextButton(
                onClick = onAddProgram,
                enabled = !state.staging,
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            ) {
                Text(if (state.staging) "Preparing program…" else "+ Add Windows program")
            }
        }
    }
}

@Composable
private fun ProgramRow(title: String, subtitle: String, monogram: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
            ).clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            color =
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor =
            if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            shape = RoundedCornerShape(12.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(monogram, fontWeight = FontWeight.Black)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyProgramList(onAddProgram: () -> Unit) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "No programs yet",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Choose a Windows executable to keep it here for quick access.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAddProgram, contentPadding = PaddingValues(0.dp)) {
            Text("Choose an .exe")
        }
    }
}

@Composable
private fun DetailPane(
    state: LauncherUiState,
    desktopSelected: Boolean,
    runtimeReady: Boolean,
    onLaunchProgram: () -> Unit,
    onOpenExplorer: () -> Unit,
    onAddProgram: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val selectedProgram =
        state.recentPrograms.firstOrNull {
            !desktopSelected && it.path == state.stagedExePath
        }
    Box(
        modifier =
        modifier.background(
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.background,
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
                ),
            ),
        ),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (onBack != null) {
                item {
                    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                        Text("← Programs")
                    }
                }
            }
            item {
                if (desktopSelected) {
                    DesktopDetail(
                        state = state,
                        runtimeReady = runtimeReady,
                        onOpenExplorer = onOpenExplorer,
                        onOpenSettings = onOpenSettings,
                    )
                } else if (selectedProgram != null) {
                    ProgramDetail(
                        program = selectedProgram,
                        state = state,
                        runtimeReady = runtimeReady,
                        onLaunch = onLaunchProgram,
                        onOpenSettings = onOpenSettings,
                    )
                } else {
                    MissingProgramDetail(onAddProgram)
                }
            }
            item {
                RuntimeSummary(
                    state = state,
                    ready = runtimeReady,
                    onRefresh = onRefresh,
                )
            }
            item {
                StorageAccessBlock()
            }
        }
        Box(
            modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            when {
                state.stageError != null ->
                    ErrorNotice(message = state.stageError, onRefresh = onRefresh)
                state.provisionProgress != null ->
                    NoticeSurface {
                        Text(
                            "Preparing Windows environment",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        ProvisionProgressBlock(state.provisionProgress)
                    }
            }
        }
    }
}

@Composable
private fun DesktopDetail(
    state: LauncherUiState,
    runtimeReady: Boolean,
    onOpenExplorer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        DetailIdentity(
            monogram = "W",
            eyebrow = "WINDOWS ENVIRONMENT",
            title = "Windows Desktop",
            subtitle = "Browse drives, run installers, and manage programs in Explorer.",
        )
        Button(
            onClick = onOpenExplorer,
            enabled = runtimeReady,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 15.dp),
        ) {
            Text(if (state.contentBusy) "Preparing environment…" else "Open desktop")
        }
        ConfigurationCard(state = state, onOpenSettings = onOpenSettings)
    }
}

@Composable
private fun ProgramDetail(
    program: RecentProgram,
    state: LauncherUiState,
    runtimeReady: Boolean,
    onLaunch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        DetailIdentity(
            monogram = program.name.firstOrNull()?.uppercase() ?: "A",
            eyebrow = "WINDOWS PROGRAM",
            title = program.name.removeSuffix(".exe"),
            subtitle = program.path,
        )
        Button(
            onClick = onLaunch,
            enabled = runtimeReady,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 15.dp),
        ) {
            Text(if (runtimeReady) "Launch program" else "Environment unavailable")
        }
        Text(
            "Last used ${relativeTime(program.lastUsedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ConfigurationCard(state = state, onOpenSettings = onOpenSettings)
    }
}

@Composable
private fun DetailIdentity(monogram: String, eyebrow: String, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(20.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    monogram,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                eyebrow,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.2.sp,
            )
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (subtitle.contains('/')) FontFamily.Monospace else FontFamily.Default,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConfigurationCard(state: LauncherUiState, onOpenSettings: () -> Unit) {
    Surface(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSettings),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Current configuration",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text("Edit →", color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
            ConfigurationRow("Display", state.resolution.label)
            ConfigurationRow("Graphics", state.graphicsDriver.label)
            ConfigurationRow("DirectDraw", state.directDrawWrapper.label)
        }
    }
}

@Composable
private fun ConfigurationRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            modifier = Modifier.width(88.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun RuntimeSummary(state: LauncherUiState, ready: Boolean, onRefresh: () -> Unit) {
    val unhealthyComponents =
        state.components.count { it.pinned == null || it.installed == null || !it.matchesPin }
    val unhealthyAssets = state.runtimeAssets.count { !it.healthy }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        if (ready) Color(0xFF58D6A5) else MaterialTheme.colorScheme.error,
                    ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (ready) "Windows environment is ready" else "Environment needs attention",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (ready) {
                        "Proton · Box64 · ${state.graphicsDriver.label}"
                    } else {
                        "$unhealthyComponents components and $unhealthyAssets files need attention"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefresh, enabled = !state.contentBusy) {
                Text(if (state.contentBusy) "Checking…" else "Refresh")
            }
        }
    }
}

@Composable
private fun ErrorNotice(message: String, onRefresh: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Environment needs attention",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRefresh) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun NoticeSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun MissingProgramDetail(onAddProgram: () -> Unit) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Choose a Windows program", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Amphora will copy the executable into its Windows environment.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAddProgram) {
            Text("Choose an .exe")
        }
    }
}

@Composable
private fun AmphoraMark(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val highlight = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        drawCircle(color = primary.copy(alpha = 0.18f), radius = size.minDimension / 2)
        drawRoundRect(
            color = primary,
            topLeft = Offset(size.width * 0.38f, size.height * 0.16f),
            size = Size(size.width * 0.24f, size.height * 0.22f),
        )
        drawOval(
            color = primary,
            topLeft = Offset(size.width * 0.27f, size.height * 0.31f),
            size = Size(size.width * 0.46f, size.height * 0.48f),
        )
        drawArc(
            color = highlight,
            startAngle = 92f,
            sweepAngle = 176f,
            useCenter = false,
            topLeft = Offset(size.width * 0.11f, size.height * 0.29f),
            size = Size(size.width * 0.36f, size.height * 0.34f),
            style = Stroke(stroke),
        )
        drawArc(
            color = highlight,
            startAngle = 272f,
            sweepAngle = 176f,
            useCenter = false,
            topLeft = Offset(size.width * 0.53f, size.height * 0.29f),
            size = Size(size.width * 0.36f, size.height * 0.34f),
            style = Stroke(stroke),
        )
    }
}

private fun LauncherUiState.runtimeReady(): Boolean = catalogStatus is ContentCatalog.Status.Ready &&
    !contentBusy &&
    !staging &&
    !driverBusy &&
    components.none { it.pinned == null || it.installed == null || !it.matchesPin } &&
    runtimeAssets.none { !it.healthy } &&
    !imagefsResidue

private fun relativeTime(timestamp: Long): String = if (timestamp <= 0L) {
    "Recently added"
} else {
    DateUtils
        .getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
}
