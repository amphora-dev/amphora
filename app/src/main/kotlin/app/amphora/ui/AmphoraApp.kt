package app.amphora.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
            Column(modifier = Modifier.animateContentSize()) {
                Text("${update.versionName} (${update.versionCode}) · ${update.channel}")
                update.notes?.let {
                    Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                state.message?.let {
                    Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
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
            TextButton(onClick = viewModel::dismiss, enabled = !state.busy) {
                Text("Later")
            }
        },
    )
}
