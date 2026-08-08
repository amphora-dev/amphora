package app.amphora.feature.launcher

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.amphora.core.content.ContentCatalog
import java.io.File

private val Ink = Color(0xFF0B0A0F)
private val Panel = Color(0xFF17151D)
private val PanelRaised = Color(0xFF211E29)
private val Ember = Color(0xFFFF774A)
private val EmberBright = Color(0xFFFFA36F)
private val Grape = Color(0xFF9A7CFF)
private val Mint = Color(0xFF58D6A5)
private val SoftWhite = Color(0xFFF8F4F1)
private val Muted = Color(0xFFB8AFB9)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernLauncherScreen(
    onLaunch: (exePath: String, width: Int, height: Int) -> Unit,
    onOpenExplorer: (width: Int, height: Int) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LauncherViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val pickExe =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.onExePicked(uri)
        }
    val runtimeReady =
        state.catalogStatus is ContentCatalog.Status.Ready &&
            !state.staging &&
            !state.driverBusy
    val openExplorer = {
        onOpenExplorer(state.resolution.width, state.resolution.height)
    }
    val launchSelected = {
        state.stagedExePath?.let {
            onLaunch(it, state.resolution.width, state.resolution.height)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            PremiumTopBar(onOpenSettings)
        },
    ) { padding ->
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF100E15), Ink, Color(0xFF0D0B11)),
                    ),
                ).drawBehind {
                    drawCircle(
                        color = Ember.copy(alpha = 0.08f),
                        radius = size.minDimension * 0.55f,
                        center = Offset(size.width * 0.05f, size.height * 0.15f),
                    )
                    drawCircle(
                        color = Grape.copy(alpha = 0.06f),
                        radius = size.minDimension * 0.48f,
                        center = Offset(size.width, size.height * 0.7f),
                    )
                },
        ) {
            Column(
                modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.provisionProgress?.let { ProvisionProgressBlock(it) }
                StorageAccessBlock()

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    if (maxWidth >= 840.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            ExplorerHero(
                                enabled = runtimeReady,
                                busy = state.contentBusy,
                                onOpen = openExplorer,
                                modifier = Modifier.weight(1.55f),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                SectionLabel("QUICK LAUNCH")
                                ProgramTile(
                                    state = state,
                                    runtimeReady = runtimeReady,
                                    onChoose = { pickExe.launch(arrayOf("*/*")) },
                                    onLaunch = launchSelected,
                                )
                                ProfileTile(state, onOpenSettings)
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ExplorerHero(
                                enabled = runtimeReady,
                                busy = state.contentBusy,
                                onOpen = openExplorer,
                            )
                            SectionLabel("QUICK LAUNCH")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                ProgramTile(
                                    state = state,
                                    runtimeReady = runtimeReady,
                                    onChoose = { pickExe.launch(arrayOf("*/*")) },
                                    onLaunch = launchSelected,
                                    modifier = Modifier.weight(1f),
                                )
                                ProfileTile(
                                    state = state,
                                    onOpenSettings = onOpenSettings,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                RuntimeStrip(
                    state = state,
                    onRefresh = viewModel::refreshContentInfo,
                )
                state.stageError?.let { ErrorNotice(it) }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PremiumTopBar(onOpenSettings: () -> Unit) {
    TopAppBar(
        colors =
        TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF100E15),
            titleContentColor = SoftWhite,
        ),
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AmphoraMark(Modifier.size(36.dp))
                Column(verticalArrangement = Arrangement.spacedBy((-2).dp)) {
                    Text(
                        "AMPHORA",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.2.sp,
                    )
                    Text(
                        "PLAY WINDOWS YOUR WAY",
                        color = Muted,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.1.sp,
                    )
                }
            }
        },
        actions = {
            Surface(
                modifier =
                Modifier
                    .padding(end = 12.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenSettings),
                color = Color.White.copy(alpha = 0.07f),
                shape = CircleShape,
            ) {
                Text(
                    "TUNE  ⚙",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    color = SoftWhite,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
            }
        },
    )
}

@Composable
private fun AmphoraMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        drawCircle(
            color = Ember.copy(alpha = 0.18f),
            radius = size.minDimension / 2,
        )
        drawRoundRect(
            color = Ember,
            topLeft = Offset(size.width * 0.38f, size.height * 0.16f),
            size = Size(size.width * 0.24f, size.height * 0.22f),
        )
        drawOval(
            color = Ember,
            topLeft = Offset(size.width * 0.27f, size.height * 0.31f),
            size = Size(size.width * 0.46f, size.height * 0.48f),
        )
        drawArc(
            color = EmberBright,
            startAngle = 92f,
            sweepAngle = 176f,
            useCenter = false,
            topLeft = Offset(size.width * 0.11f, size.height * 0.29f),
            size = Size(size.width * 0.36f, size.height * 0.34f),
            style = Stroke(stroke),
        )
        drawArc(
            color = EmberBright,
            startAngle = 272f,
            sweepAngle = 176f,
            useCenter = false,
            topLeft = Offset(size.width * 0.53f, size.height * 0.29f),
            size = Size(size.width * 0.36f, size.height * 0.34f),
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = EmberBright,
            topLeft = Offset(size.width * 0.43f, size.height * 0.76f),
            size = Size(size.width * 0.14f, size.height * 0.14f),
        )
    }
}

@Composable
private fun ExplorerHero(enabled: Boolean, busy: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier =
        modifier
            .fillMaxWidth()
            .heightIn(min = 310.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.linearGradient(
                    colors =
                    listOf(
                        Color(0xFF3B1B19),
                        Color(0xFF251723),
                        Color(0xFF17151D),
                    ),
                    start = Offset.Zero,
                    end = Offset(1_200f, 900f),
                ),
            ).border(
                width = 1.dp,
                brush =
                Brush.linearGradient(
                    listOf(
                        EmberBright.copy(alpha = 0.65f),
                        Color.White.copy(alpha = 0.08f),
                        Grape.copy(alpha = 0.4f),
                    ),
                ),
                shape = RoundedCornerShape(30.dp),
            ).drawBehind {
                drawCircle(
                    brush =
                    Brush.radialGradient(
                        listOf(Ember.copy(alpha = 0.38f), Color.Transparent),
                    ),
                    radius = size.minDimension * 0.62f,
                    center = Offset(size.width * 0.9f, size.height * 0.15f),
                )
                drawCircle(
                    brush =
                    Brush.radialGradient(
                        listOf(Grape.copy(alpha = 0.2f), Color.Transparent),
                    ),
                    radius = size.minDimension * 0.5f,
                    center = Offset(size.width * 0.72f, size.height),
                )
            }.padding(26.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusPill(enabled)
            Spacer(Modifier.height(4.dp))
            Text(
                "WINE",
                color = EmberBright,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.4.sp,
            )
            Text(
                "EXPLORER",
                color = SoftWhite,
                fontSize = 38.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1.3).sp,
            )
            Text(
                "C:\\WINDOWS\\explorer.exe",
                color = Grape.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Your full Windows desktop. Browse drives, run installers, and discover what works.",
                modifier = Modifier.fillMaxWidth(0.78f),
                color = SoftWhite.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onOpen,
                enabled = enabled,
                shape = CircleShape,
                colors =
                ButtonDefaults.buttonColors(
                    containerColor = Ember,
                    contentColor = Color(0xFF24100A),
                    disabledContainerColor = Color.White.copy(alpha = 0.1f),
                    disabledContentColor = Muted,
                ),
                modifier = Modifier.height(48.dp),
            ) {
                Text(
                    when {
                        busy -> "PREPARING RUNTIME…"
                        enabled -> "ENTER DESKTOP   ↗"
                        else -> "RUNTIME UNAVAILABLE"
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                )
            }
        }

        if (maxWidth >= 460.dp) {
            ExplorerOrb(
                modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(122.dp),
            )
        }
    }
}

@Composable
private fun StatusPill(ready: Boolean) {
    Row(
        modifier =
        Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), CircleShape)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (ready) Mint else Muted),
        )
        Text(
            if (ready) "RUNTIME ONLINE" else "RUNTIME CHECK",
            color = SoftWhite.copy(alpha = 0.84f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun ExplorerOrb(modifier: Modifier = Modifier) {
    Box(
        modifier =
        modifier
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        Color.White.copy(alpha = 0.16f),
                        Grape.copy(alpha = 0.22f),
                        Color.Black.copy(alpha = 0.1f),
                    ),
                ),
            ).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .border(1.dp, EmberBright.copy(alpha = 0.42f), CircleShape),
        )
        Text(
            "W",
            color = SoftWhite,
            fontSize = 42.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Muted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
    )
}

@Composable
private fun ProgramTile(
    state: LauncherUiState,
    runtimeReady: Boolean,
    onChoose: () -> Unit,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedName = state.stagedExePath?.let { File(it).name }
    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Panel)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Ember.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("＋", color = EmberBright, fontSize = 24.sp, fontWeight = FontWeight.Light)
        }
        Text(
            selectedName ?: "ADD PROGRAM",
            color = SoftWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (selectedName == null) {
                "Pick any Windows executable"
            } else {
                "Ready with your active profile"
            },
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        Spacer(Modifier.height(4.dp))
        if (selectedName == null) {
            TextButton(onClick = onChoose, enabled = !state.staging) {
                Text(
                    if (state.staging) "PREPARING…" else "CHOOSE .EXE  ↗",
                    color = EmberBright,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        } else {
            Button(
                onClick = onLaunch,
                enabled = runtimeReady,
                shape = CircleShape,
                colors =
                ButtonDefaults.buttonColors(
                    containerColor = Ember,
                    contentColor = Ink,
                ),
            ) {
                Text("LAUNCH  ↗", fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
            TextButton(onClick = onChoose, enabled = !state.staging) {
                Text("CHANGE", color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun ProfileTile(state: LauncherUiState, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(PanelRaised, Color(0xFF201A2B)),
                ),
            ).border(1.dp, Grape.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
            .clickable(onClick = onOpenSettings)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Grape.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("⌁", color = Grape, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "ACTIVE PROFILE",
            color = SoftWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            state.resolution.label,
            color = Grape,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            state.graphicsDriver.label,
            color = Muted,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "TUNE PROFILE  →",
            color = Grape,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun RuntimeStrip(state: LauncherUiState, onRefresh: () -> Unit) {
    val ready = state.catalogStatus is ContentCatalog.Status.Ready
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (ready) Mint else Muted),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (ready) "Runtime ready" else "Checking runtime",
                color = SoftWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Proton · Box64 · ${state.graphicsDriver.label}  /  ${state.appVersion}",
                color = Muted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRefresh, enabled = !state.contentBusy) {
            Text(
                if (state.contentBusy) "SYNCING" else "SYNC",
                color = EmberBright,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp,
            )
        }
    }
}

@Composable
private fun ErrorNotice(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF3A171B),
        contentColor = Color(0xFFFFDAD9),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("RUNTIME NEEDS ATTENTION", fontSize = 10.sp, fontWeight = FontWeight.Black)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
