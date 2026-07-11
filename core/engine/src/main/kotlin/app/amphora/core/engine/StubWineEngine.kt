package app.amphora.core.engine

import app.amphora.core.engine.model.AudioSink
import app.amphora.core.engine.model.InputSink
import app.amphora.core.engine.model.LaunchSpec
import app.amphora.core.engine.model.SessionHandle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fallback [WineEngine] (RFC §7 / D9). [WineEngineImpl] is now the bound
 * implementation; this stub is retained so the binding can be flipped back in
 * [app.amphora.core.engine.di.EngineModule] if the ported-runtime facade needs
 * to be disabled. [launch] throws to make the not-yet-implemented state explicit
 * at the call site. Sinks ([StubInputSink] / [StubAudioSink]) are shared with
 * [WineEngineImpl].
 */
@Singleton
class StubWineEngine @Inject constructor() : WineEngine {
    override suspend fun launch(spec: LaunchSpec): SessionHandle =
        TODO("WineEngine implementation pending runtime port (spec=$spec) - see RFC §7/D9")

    override fun inputFeed(): InputSink = StubInputSink
    override fun audioSink(): AudioSink = StubAudioSink
}
