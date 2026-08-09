package app.amphora.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import app.amphora.ui.theme.AmphoraTheme

@Composable
fun AmphoraApp() {
    AmphoraTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val navController = rememberNavController()
            AmphoraNavHost(navController = navController)
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
                Text(
                    update.notes.orEmpty(),
                    modifier = Modifier.height(40.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.message.orEmpty(),
                    modifier =
                    Modifier
                        .height(20.dp)
                        .alpha(if (state.message == null) 0f else 1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (state.busy) 1f else 0f),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (state.pendingSystemApk != null) {
                        val currentActivity = activity ?: return@TextButton
                        if (viewModel.needsSystemInstallPermission()) {
                            installPermissionLauncher.launch(viewModel.installPermissionIntent())
                        } else {
                            viewModel.launchSystemInstaller(currentActivity)
                        }
                    } else {
                        viewModel.installUpdate()
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.width(176.dp),
            ) {
                Text(
                    when {
                        state.busy -> "Preparing…"
                        state.pendingSystemApk != null -> "Open system installer"
                        else -> "Install update"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = viewModel::dismiss,
                enabled = !state.busy,
                modifier = Modifier.alpha(if (state.busy) 0f else 1f),
            ) {
                Text("Later")
            }
        },
    )
}
