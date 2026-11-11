package app.amphora.shell

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.amphora.LocalAppGraph
import app.amphora.Message
import app.amphora.files.AddGameScreen
import app.amphora.game.GameScreen
import app.amphora.library.LibraryScreen
import app.amphora.logs.LogScreen
import app.amphora.logs.LogsScreen
import app.amphora.nav.AddGameRoute
import app.amphora.nav.GameRoute
import app.amphora.nav.GameSettingsRoute
import app.amphora.nav.LibraryGraph
import app.amphora.nav.LibraryRoute
import app.amphora.nav.LogRoute
import app.amphora.nav.LogsRoute
import app.amphora.nav.SettingsGraph
import app.amphora.nav.SettingsRoute
import app.amphora.nav.SystemGraph
import app.amphora.nav.SystemRoute
import app.amphora.sdk.engine.RuntimeState
import app.amphora.sdk.session.SessionState
import app.amphora.settings.SettingsScreen
import app.amphora.system.SystemScreen
import app.amphora.ui.components.AmphoraMark
import app.amphora.ui.components.Backdrop
import app.amphora.ui.components.StatusDot
import app.amphora.ui.components.Tone
import app.amphora.ui.theme.Amphora

private enum class Tab(val label: String, val icon: ImageVector, val graph: Any) {
    Library("游戏库", Icons.Outlined.SportsEsports, LibraryGraph),
    Settings("设置", Icons.Outlined.Tune, SettingsGraph),
    System("系统", Icons.Outlined.Memory, SystemGraph),
}

/** The tab a destination belongs to; null for screens outside the tabs (logs). */
private fun NavDestination.tab(): Tab? = hierarchy.firstNotNullOfOrNull { destination ->
    when {
        destination.hasRoute<LibraryGraph>() -> Tab.Library
        destination.hasRoute<SettingsGraph>() -> Tab.Settings
        destination.hasRoute<SystemGraph>() -> Tab.System
        else -> null
    }
}

@Composable
fun AmphoraAppUi() {
    val graph = LocalAppGraph.current
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(graph) {
        graph.messages.events.collect { message ->
            snackbar.showSnackbar(ToneVisuals(message))
        }
    }

    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.tab()
    // Screens outside the tabs keep the tab they were opened from highlighted.
    var lastTab by rememberSaveable { mutableStateOf(Tab.Library) }
    LaunchedEffect(current) { if (current != null) lastTab = current }
    val selected = current ?: lastTab
    val onTab: (Tab) -> Unit = { tab -> nav.openTab(tab) }

    val messages: @Composable (Modifier) -> Unit = { modifier ->
        SnackbarHost(snackbar, modifier) { data ->
            MessageBar(data.visuals.message, (data.visuals as? ToneVisuals)?.tone ?: Message.Tone.Info)
        }
    }
    Backdrop(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val system = WindowInsets.safeDrawing.asPaddingValues()
            if (maxWidth >= 600.dp) {
                Row(
                    Modifier.fillMaxSize().windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    ),
                ) {
                    Rail(selected, onTab)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        ScreenInsets(
                            PaddingValues(top = system.calculateTopPadding(), bottom = system.calculateBottomPadding()),
                        ) {
                            AppNavHost(nav)
                        }
                        messages(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                                .padding(bottom = 16.dp),
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // The bottom bar takes the navigation bar inset.
                        ScreenInsets(PaddingValues(top = system.calculateTopPadding())) { AppNavHost(nav) }
                        messages(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
                    }
                    BottomBar(selected, onTab)
                }
            }
        }
    }
}

private fun NavHostController.openTab(tab: Tab) {
    navigate(tab.graph) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppNavHost(nav: NavHostController) {
    NavHost(
        navController = nav,
        startDestination = LibraryGraph,
        enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 28 } },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { it / 28 } },
    ) {
        navigation<LibraryGraph>(startDestination = LibraryRoute) {
            composable<LibraryRoute> {
                LibraryScreen(
                    onOpenGame = { nav.navigate(GameRoute(it)) },
                    onAddGame = { nav.navigate(AddGameRoute) },
                    onOpenLog = { nav.navigate(LogRoute(it)) },
                )
            }
            composable<GameRoute> { entry ->
                val id = entry.toRoute<GameRoute>().id
                GameScreen(
                    id = id,
                    onBack = nav::popBackStack,
                    onOpenSettings = { nav.navigate(GameSettingsRoute(id)) },
                    onOpenLog = { nav.navigate(LogRoute(it)) },
                )
            }
            composable<GameSettingsRoute> { entry ->
                SettingsScreen(gameId = entry.toRoute<GameSettingsRoute>().id, onBack = nav::popBackStack)
            }
            composable<AddGameRoute> {
                AddGameScreen(
                    onBack = nav::popBackStack,
                    onAdded = { id ->
                        nav.navigate(GameRoute(id)) { popUpTo<AddGameRoute> { inclusive = true } }
                    },
                )
            }
        }
        navigation<SettingsGraph>(startDestination = SettingsRoute) {
            composable<SettingsRoute> { SettingsScreen(gameId = null, onBack = null) }
        }
        navigation<SystemGraph>(startDestination = SystemRoute) {
            composable<SystemRoute> {
                SystemScreen(onOpenLogs = { nav.navigate(LogsRoute) })
            }
        }
        composable<LogsRoute> {
            LogsScreen(onBack = nav::popBackStack, onOpen = { nav.navigate(LogRoute(it)) })
        }
        composable<LogRoute> { entry ->
            LogScreen(id = entry.toRoute<LogRoute>().id, onBack = nav::popBackStack)
        }
    }
}

@Composable
private fun Rail(selected: Tab, onSelect: (Tab) -> Unit) {
    val colors = Amphora.colors
    Column(
        Modifier
            .fillMaxHeight()
            .width(88.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AmphoraMark(Modifier.width(22.dp))
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Tab.entries.forEach { tab -> RailItem(tab, tab == selected) { onSelect(tab) } }
        }
        Spacer(Modifier.weight(1f))
        StatusLights()
    }
    Box(
        Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(colors.hairline),
    )
}

@Composable
private fun RailItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val colors = Amphora.colors
    Column(
        Modifier
            .width(76.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 30.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) colors.accentSoft else colors.canvas.copy(alpha = 0f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                tab.icon,
                contentDescription = null,
                tint = if (selected) colors.accent else colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.text else colors.textMuted,
        )
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val colors = Amphora.colors
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                )
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Tab.entries.forEach { tab -> RailItem(tab, tab == selected) { onSelect(tab) } }
        }
    }
}

/** Runtime and session at a glance, at the foot of the rail. */
@Composable
private fun StatusLights() {
    val graph = LocalAppGraph.current
    val runtime by graph.sdk.runtime.state.collectAsStateWithLifecycle()
    val session by graph.sdk.sessions.state.collectAsStateWithLifecycle()
    val colors = Amphora.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val live = session as? SessionState.Live
        if (live != null || session is SessionState.Launching) {
            Column(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(enabled = live != null, onClickLabel = "回到会话") { graph.launcher.resume() }
                    .border(1.dp, colors.ok.copy(alpha = 0.25f), MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StatusDot(Tone.Ok, pulse = true, size = 6.dp)
                Text("会话", style = Amphora.type.monoSmall, color = colors.ok)
            }
        }
        val (tone, label) =
            when (runtime) {
                is RuntimeState.Ready -> Tone.Ok to "WINE"
                is RuntimeState.Working -> Tone.Accent to "安装"
                is RuntimeState.Failed -> Tone.Danger to "错误"
                is RuntimeState.Missing -> Tone.Warn to "未装"
                RuntimeState.Checking -> Tone.Neutral to "…"
            }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StatusDot(tone, pulse = runtime is RuntimeState.Working, size = 6.dp)
            Text(label, style = Amphora.type.overline, color = colors.textFaint)
        }
    }
}

private class ToneVisuals(message: Message) : SnackbarVisuals {
    val tone: Message.Tone = message.tone
    override val message: String = message.text
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration =
        if (message.tone == Message.Tone.Error) SnackbarDuration.Long else SnackbarDuration.Short
}

@Composable
private fun MessageBar(text: String, tone: Message.Tone) {
    val colors = Amphora.colors
    val accent =
        when (tone) {
            Message.Tone.Info -> colors.info
            Message.Tone.Ok -> colors.ok
            Message.Tone.Warn -> colors.warn
            Message.Tone.Error -> colors.danger
        }
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .widthIn(max = 560.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.hairlineStrong, MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(accent))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.text)
    }
}
