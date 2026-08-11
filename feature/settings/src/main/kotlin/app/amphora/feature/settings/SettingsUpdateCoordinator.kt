package app.amphora.feature.settings

/**
 * Pure state machine for the update controls on the settings screen.
 *
 * The generic update and artifact types keep this coordinator independent of Android and of the
 * concrete update implementation. [SettingsViewModel] owns and executes every returned effect.
 */
internal class SettingsUpdateCoordinator<Update, Artifact> {
    private var state = SettingsUpdateState<Update, Artifact>()

    @Synchronized
    fun dispatch(event: SettingsUpdateEvent<Update, Artifact>): SettingsUpdateTransition<Update, Artifact> {
        val transition = reduce(state, event)
        state = transition.state
        return transition
    }
}

internal data class SettingsUpdateState<Update, Artifact>(
    val checking: Boolean = false,
    val installing: Boolean = false,
    val availableUpdate: Update? = null,
    val availableVersionName: String? = null,
    val pendingArtifact: Artifact? = null,
    val permissionPhase: PermissionPhase = PermissionPhase.NONE,
    val permissionReadyObserved: Boolean = false,
    val message: String? = null,
) {
    val busy: Boolean get() = checking || installing
    val waitingForPermission: Boolean get() = permissionPhase == PermissionPhase.WAITING
}

internal enum class PermissionPhase {
    NONE,
    REQUESTING,
    WAITING,
}

internal sealed interface SettingsUpdateEvent<out Update, out Artifact> {
    data object CheckRequested : SettingsUpdateEvent<Nothing, Nothing>

    data class CheckCompleted<Update>(val outcome: UpdateCheckOutcome<Update>) :
        SettingsUpdateEvent<Update, Nothing>

    data class InstallRequested(val permissionRequired: Boolean) : SettingsUpdateEvent<Nothing, Nothing>

    data class PermissionRequestCompleted(val requestStarted: Boolean, val permissionReady: Boolean = false) :
        SettingsUpdateEvent<Nothing, Nothing>

    data object PermissionReady : SettingsUpdateEvent<Nothing, Nothing>

    data object PermissionWaitExpired : SettingsUpdateEvent<Nothing, Nothing>

    data object InstallStarted : SettingsUpdateEvent<Nothing, Nothing>

    data class SystemInstallerRequired<Artifact>(val artifact: Artifact, val reason: String) :
        SettingsUpdateEvent<Nothing, Artifact>

    data class InstallFailed(val message: String) : SettingsUpdateEvent<Nothing, Nothing>
}

internal sealed interface UpdateCheckOutcome<out Update> {
    data class UpToDate(val remoteVersionName: String) : UpdateCheckOutcome<Nothing>

    data class UpdateAvailable<Update>(
        val update: Update,
        val installedVersionCode: Long,
        val remoteVersionCode: Long,
        val remoteVersionName: String,
    ) : UpdateCheckOutcome<Update>

    data class Unavailable(val reason: String) : UpdateCheckOutcome<Nothing>

    data class Failed(val message: String) : UpdateCheckOutcome<Nothing>
}

internal sealed interface SettingsUpdateEffect<out Update> {
    data object CheckForUpdate : SettingsUpdateEffect<Nothing>

    data object RequestPermission : SettingsUpdateEffect<Nothing>

    data object SchedulePermissionTimeout : SettingsUpdateEffect<Nothing>

    data class DownloadAndInstall<Update>(val update: Update) : SettingsUpdateEffect<Update>
}

internal data class SettingsUpdateTransition<Update, Artifact>(
    val state: SettingsUpdateState<Update, Artifact>,
    val effects: List<SettingsUpdateEffect<Update>> = emptyList(),
)

private fun <Update, Artifact> reduce(
    state: SettingsUpdateState<Update, Artifact>,
    event: SettingsUpdateEvent<Update, Artifact>,
): SettingsUpdateTransition<Update, Artifact> = when (event) {
    SettingsUpdateEvent.CheckRequested ->
        state.transition(
            state.copy(
                checking = true,
                pendingArtifact = null,
                permissionPhase = PermissionPhase.NONE,
                permissionReadyObserved = false,
                message = null,
            ),
            SettingsUpdateEffect.CheckForUpdate,
        )
    is SettingsUpdateEvent.CheckCompleted ->
        when (val outcome = event.outcome) {
            is UpdateCheckOutcome.UpToDate ->
                state.copy(
                    checking = false,
                    availableUpdate = null,
                    availableVersionName = null,
                    message = "Up to date (${outcome.remoteVersionName})",
                ).transition()
            is UpdateCheckOutcome.UpdateAvailable ->
                state.copy(
                    checking = false,
                    availableUpdate = outcome.update,
                    availableVersionName = outcome.remoteVersionName,
                    message =
                    "Update available: ${outcome.remoteVersionName} " +
                        "(${outcome.installedVersionCode} → ${outcome.remoteVersionCode})",
                ).transition()
            is UpdateCheckOutcome.Unavailable ->
                state.copy(
                    checking = false,
                    availableUpdate = null,
                    availableVersionName = null,
                    message = outcome.reason,
                ).transition()
            is UpdateCheckOutcome.Failed ->
                state.copy(
                    checking = false,
                    availableUpdate = null,
                    availableVersionName = null,
                    message = outcome.message,
                ).transition()
        }
    is SettingsUpdateEvent.InstallRequested ->
        when {
            state.busy || state.permissionPhase != PermissionPhase.NONE ||
                state.availableUpdate == null -> state.transition()
            event.permissionRequired ->
                state.copy(permissionPhase = PermissionPhase.REQUESTING).transition(
                    SettingsUpdateEffect.RequestPermission,
                )
            else -> state.beginInstall()
        }
    is SettingsUpdateEvent.PermissionRequestCompleted ->
        when {
            state.permissionPhase != PermissionPhase.REQUESTING -> state.transition()
            !event.requestStarted || event.permissionReady || state.permissionReadyObserved ->
                state.beginInstall()
            else ->
                state.copy(
                    permissionPhase = PermissionPhase.WAITING,
                    permissionReadyObserved = false,
                    message = "Grant Shizuku access to install automatically.",
                ).transition(SettingsUpdateEffect.SchedulePermissionTimeout)
        }
    SettingsUpdateEvent.PermissionReady ->
        when (state.permissionPhase) {
            PermissionPhase.REQUESTING ->
                state.copy(permissionReadyObserved = true).transition()
            PermissionPhase.WAITING -> state.beginInstall()
            PermissionPhase.NONE -> state.transition()
        }
    SettingsUpdateEvent.PermissionWaitExpired ->
        if (state.permissionPhase == PermissionPhase.WAITING) {
            state.beginInstall()
        } else {
            state.transition()
        }
    SettingsUpdateEvent.InstallStarted ->
        if (state.installing) {
            state.copy(
                message = "Install started. Amphora will reopen automatically.",
            ).transition()
        } else {
            state.transition()
        }
    is SettingsUpdateEvent.SystemInstallerRequired ->
        if (state.installing) {
            state.copy(
                installing = false,
                pendingArtifact = event.artifact,
                message = event.reason,
            ).transition()
        } else {
            state.transition()
        }
    is SettingsUpdateEvent.InstallFailed ->
        if (state.installing) {
            state.copy(
                installing = false,
                pendingArtifact = null,
                message = event.message,
            ).transition()
        } else {
            state.transition()
        }
}

private fun <Update, Artifact> SettingsUpdateState<Update, Artifact>.beginInstall():
    SettingsUpdateTransition<Update, Artifact> {
    val update = availableUpdate ?: return transition()
    val versionName = requireNotNull(availableVersionName)
    return copy(
        installing = true,
        pendingArtifact = null,
        permissionPhase = PermissionPhase.NONE,
        permissionReadyObserved = false,
        message = "Downloading and verifying $versionName…",
    ).transition(SettingsUpdateEffect.DownloadAndInstall(update))
}

private fun <Update, Artifact> SettingsUpdateState<Update, Artifact>.transition(
    vararg effects: SettingsUpdateEffect<Update>,
) = SettingsUpdateTransition(this, effects.toList())
