package app.amphora.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import app.amphora.ui.theme.AmphoraTheme

@Composable
fun AmphoraApp(startRouteOverride: String? = null) {
    AmphoraTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val navController = rememberNavController()
            AmphoraNavHost(
                navController = navController,
                startRouteOverride = startRouteOverride,
            )
            StartupUpdatePrompt()
        }
    }
}

@Composable
private fun StartupUpdatePrompt(viewModel: StartupUpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val update = state.available ?: return
    if (state.dismissed) return

    val activity = LocalActivity.current
    val installPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            activity?.let(viewModel::launchSystemInstaller)
        }

    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text("Update available") },
        text = {
            Column {
                Text("${update.versionName} (${update.versionCode}) · ${update.channel}")
                update.notes?.let { Text(it) }
                state.message?.let { Text(it) }
                if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            when {
                state.busy -> Unit
                state.pendingSystemApk != null ->
                    TextButton(
                        onClick = {
                            val currentActivity = activity ?: return@TextButton
                            if (viewModel.needsSystemInstallPermission()) {
                                installPermissionLauncher.launch(viewModel.installPermissionIntent())
                            } else {
                                viewModel.launchSystemInstaller(currentActivity)
                            }
                        },
                    ) {
                        Text("Open system installer")
                    }
                else ->
                    TextButton(onClick = viewModel::installUpdate) {
                        Text("Install update")
                    }
            }
        },
        dismissButton = {
            if (!state.busy) {
                TextButton(onClick = viewModel::dismiss) { Text("Later") }
            }
        },
    )
}
