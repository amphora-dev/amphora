package app.amphora.core.engine.di

import app.amphora.core.common.dispatcher.DefaultDispatcherProvider
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.ContainerManager
import app.amphora.core.engine.StubContainerManager
import app.amphora.core.engine.StubRootfsInstaller
import app.amphora.core.engine.StubWineSessionPreparer
import app.amphora.core.engine.WineEngine
import app.amphora.core.engine.WineEngineImpl
import app.amphora.core.engine.WineSessionPreparer
import app.amphora.core.rootfs.RootfsInstaller
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Engine DI bindings (RFC §6). [WineEngine] is bound to [WineEngineImpl] (the
 * ported-runtime facade); [app.amphora.core.engine.StubWineEngine] is retained
 * as a fallback (swap the param/return below to revert).
 *
 * Sibling-interface stubs ([ContainerManager] / [RootfsInstaller] /
 * [WineSessionPreparer]) are provisionally @Provides here because `:core:engine`
 * is the only Hilt-equipped module in P1. When P2/P4 add Hilt + real impls to
 * `:core:rootfs` / `:core:container`, move those bindings into owning modules
 * and delete the three stub @Provides lines below (see `docs/03-TRACKING.md`).
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun provideWineEngine(impl: WineEngineImpl): WineEngine = impl

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    // --- provisional sibling-interface stubs (move to owning modules in P2/P4) ---

    @Provides
    @Singleton
    fun provideContainerManager(): ContainerManager = StubContainerManager()

    @Provides
    @Singleton
    fun provideRootfsInstaller(): RootfsInstaller = StubRootfsInstaller()

    @Provides
    @Singleton
    fun provideWineSessionPreparer(): WineSessionPreparer = StubWineSessionPreparer()
}
