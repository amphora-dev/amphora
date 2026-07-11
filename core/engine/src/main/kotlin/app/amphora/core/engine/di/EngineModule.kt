package app.amphora.core.engine.di

import app.amphora.core.common.dispatcher.DefaultDispatcherProvider
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.engine.StubWineEngine
import app.amphora.core.engine.WineEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Engine DI bindings (RFC §6). [WineEngine] is bound to [StubWineEngine] for the
 * scaffold; swap to the ported runtime implementation once `com.winlator.cmod`
 * lands in :core:native / :core:engine.
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun provideWineEngine(stub: StubWineEngine): WineEngine = stub

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()
}
