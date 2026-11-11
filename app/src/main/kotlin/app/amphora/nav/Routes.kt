package app.amphora.nav

import kotlinx.serialization.Serializable

// Tabs are nested graphs, so the rail can tell which tab a screen belongs to.

@Serializable object LibraryGraph

@Serializable object SettingsGraph

@Serializable object SystemGraph

@Serializable object LibraryRoute

@Serializable data class GameRoute(val id: String)

@Serializable data class GameSettingsRoute(val id: String)

@Serializable object AddGameRoute

@Serializable object SettingsRoute

@Serializable object SystemRoute

@Serializable object LogsRoute

@Serializable data class LogRoute(val id: String)
