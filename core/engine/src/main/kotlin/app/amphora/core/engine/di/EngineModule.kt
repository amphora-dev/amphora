package app.amphora.core.engine.di

import android.content.Context
import app.amphora.core.common.dispatcher.DefaultDispatcherProvider
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.ContainerManager
import app.amphora.core.content.BundledAssetInstaller
import app.amphora.core.content.BundledContentSource
import app.amphora.core.content.ContentManifest
import app.amphora.core.content.ContentSource
import app.amphora.core.engine.ImageFsRootfsInstaller
import app.amphora.core.engine.GameSessionSurfaceProvider
import app.amphora.core.engine.WineEngine
import app.amphora.core.engine.WineEngineImpl
import app.amphora.core.engine.WinlatorBundledAssetInstaller
import app.amphora.core.engine.WinlatorContainerManager
import app.amphora.core.engine.WineSessionPreparer
import app.amphora.core.engine.XServerWineSessionPreparer
import app.amphora.core.rootfs.RootfsInstaller
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Engine DI bindings (RFC §6). [WineEngine] is bound to [WineEngineImpl] (the
 * ported-runtime facade).
 *
 * [RootfsInstaller] is bound to its real concretion [ImageFsRootfsInstaller]
 * (P2: imagefs extract/version via the ported `com.winlator.cmod` kernel --
 * `native_content_io.cpp` extraction restored with zstd+xz, curl/download
 * stubbed per D4). [WineSessionPreparer] is bound to its real concretion
 * [XServerWineSessionPreparer] (P2: the D9 XSDA body extraction -- Steam /
 * recording / shortcut / Activity / arm64ec stripped, compile-only). Both
 * concretions live in `:core:engine` next to the kernel they adapt because the
 * dep graph is `engine -> {rootfs,container}` and `:core:rootfs` cannot see
 * `TarCompressorUtils` / `ImageFs` (they live in the ported `com.winlator.cmod`
 * kernel under `:core:engine`); the *contracts* stay in their low modules
 * (Dependency Inversion -- see `docs/03-TRACKING.md`). [ContainerManager] (P4)
 * is now bound to its real concretion [WinlatorContainerManager] (bridges the
 * ported `com.winlator.cmod.runtime.container.ContainerManager`); all three
 * sibling interfaces have graduated -- no stubs remain.
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun provideWineEngine(impl: WineEngineImpl): WineEngine = impl

    @Provides
    @Singleton
    fun provideGameSessionSurfaceProvider(impl: WineEngineImpl): GameSessionSurfaceProvider = impl

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    // --- content (P2: BundledContentSource) ------------------------------------

    @Provides
    @Singleton
    fun provideContentManifest(@ApplicationContext context: Context): ContentManifest =
        ContentManifest.load(context)

    @Provides
    @Singleton
    fun provideBundledAssetInstaller(impl: WinlatorBundledAssetInstaller): BundledAssetInstaller = impl

    @Provides
    @Singleton
    fun provideContentSource(
        @ApplicationContext context: Context,
        manifest: ContentManifest,
        installer: BundledAssetInstaller,
        dispatchers: DispatcherProvider,
    ): ContentSource = BundledContentSource(context, manifest, installer, dispatchers)

    // --- sibling-interface bindings --------------------------------------------

    @Provides
    @Singleton
    fun provideRootfsInstaller(impl: ImageFsRootfsInstaller): RootfsInstaller = impl

    @Provides
    @Singleton
    fun provideWineSessionPreparer(impl: XServerWineSessionPreparer): WineSessionPreparer = impl

    @Provides
    @Singleton
    fun provideContainerManager(impl: WinlatorContainerManager): ContainerManager = impl
}
