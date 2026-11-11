package app.amphora

import android.app.Application

class AmphoraApp : Application() {
    /**
     * Created on first use. The `:wine` session process runs this class too
     * and must not build the SDK, so nothing here is eager.
     */
    val graph: AppGraph by lazy { AppGraph(this) }
}
