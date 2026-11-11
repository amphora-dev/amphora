package app.amphora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import app.amphora.shell.AmphoraAppUi
import app.amphora.ui.theme.AmphoraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as AmphoraApp).graph
        setContent {
            CompositionLocalProvider(LocalAppGraph provides graph) {
                AmphoraTheme { AmphoraAppUi() }
            }
        }
    }
}
