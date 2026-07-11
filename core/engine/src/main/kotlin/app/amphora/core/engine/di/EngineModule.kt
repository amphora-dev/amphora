package app.amphora.core.engine.di

import app.amphora.core.common.dispatcher.DefaultDispatcherProvider
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.ContainerManager
import app.amphora.core.engine.ImageFsRootfsInstaller
import app.amphora.core.engine.StubContainerManager
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
 * [RootfsInstaller] is bound to its real concretion [ImageFsRootfsInstaller]
 * (P2: imagefs extract/version via the ported `com.winlator.cmod` kernel --
 * `native_content_io.cpp` extraction restored with zstd+xz, curl/download
 * stubbed per D4). The concretion lives in `:core:engine` next to the kernel it
 * adapts because the dep graph is `engine -> rootfs` and `:core:rootfs` cannot
 * see `TarCompressorUtils`/`ImageFs`; the *contract* stays in `:core:rootfs`
 * (Dependency Inversion -- see `docs/03-TRACKING.md`). The remaining
 * sibling-interface stubs ([ContainerManager] P4 / [WineSessionPreparer] P2-P3)
 * are still @Provides here until their real impls land.
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

    // --- sibling-interface bindings --------------------------------------------

    @Provides
    @Singleton
    fun provideRootfsInstaller(impl: ImageFsRootfsInstaller): RootfsInstaller = impl

    // Stubs below (P4 / P2-P3) -- replaced by real impls when their phases land.

    @Provides
    @Singleton
    fun provideContainerManager(): ContainerManager = StubContainerManager()

    @Provides
    @Singleton
    fun provideWineSessionPreparer(): WineSessionPreparer = StubWineSessionPreparer()
}
