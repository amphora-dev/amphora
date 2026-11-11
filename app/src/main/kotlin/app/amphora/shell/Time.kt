package app.amphora.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/** The wall clock, updated every [periodMillis]: for elapsed times and "3 分钟前". */
@Composable
fun rememberNow(periodMillis: Long = 1_000): State<Long> = produceState(System.currentTimeMillis(), periodMillis) {
    while (true) {
        value = System.currentTimeMillis()
        delay(periodMillis - value % periodMillis)
    }
}
