package app.amphora.core.engine.di

import android.content.Context
import app.amphora.core.common.dispatcher.DefaultDispatcherProvider
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.ContainerManager
import app.amphora.core.content.ContentAssetInstaller
import app.amphora.core.content.ContentCatalog
import app.amphora.core.content.ContentReconciler
import app.amphora.core.content.ContentSource
import app.amphora.core.content.ProvisionProgressBus
import app.amphora.core.content.RemoteContentSource
import app.amphora.core.content.RemoteUrlResolver
import app.amphora.core.content.RuntimeAssetProvisioner
import app.amphora.core.content.VerifiedAssetDownloader
import app.amphora.core.content.update.AppUpdater
import app.amphora.core.engine.GameSessionSurfaceProvider
import app.amphora.core.engine.ImageFsRootfsInstaller
import app.amphora.core.engine.WineEngine
import app.amphora.core.engine.WineEngineImpl
import app.amphora.core.engine.WineSessionPreparer
import app.amphora.core.engine.WinlatorContainerManager
import app.amphora.core.engine.WinlatorContentAssetInstaller
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
 * Content pins come from [ContentCatalog] (remote-only `content_manifest.json`);
 * there is no APK-bundled manifest fallback.
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

    @Provides
    @Singleton
    fun provideProvisionProgressBus(): ProvisionProgressBus = ProvisionProgressBus()

    @Provides
    @Singleton
    fun provideContentCatalog(@ApplicationContext context: Context, dispatchers: DispatcherProvider): ContentCatalog =
        ContentCatalog(context, dispatchers)

    @Provides
    @Singleton
    fun provideContentAssetInstaller(impl: WinlatorContentAssetInstaller): ContentAssetInstaller = impl

    @Provides
    @Singleton
    fun provideVerifiedAssetDownloader(
        dispatchers: DispatcherProvider,
        progressBus: ProvisionProgressBus,
    ): VerifiedAssetDownloader = VerifiedAssetDownloader(dispatchers, progressBus)

    @Provides
    @Singleton
    fun provideAppUpdater(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
        downloader: VerifiedAssetDownloader,
    ): AppUpdater = AppUpdater(context, dispatchers, downloader)

    @Provides
    @Singleton
    fun provideRemoteUrlResolver(): RemoteUrlResolver = RemoteUrlResolver()

    @Provides
    @Singleton
    fun provideRuntimeAssetProvisioner(
        @ApplicationContext context: Context,
        catalog: ContentCatalog,
        downloader: VerifiedAssetDownloader,
        progressBus: ProvisionProgressBus,
    ): RuntimeAssetProvisioner = RuntimeAssetProvisioner(context, catalog, downloader, progressBus)

    @Provides
    @Singleton
    fun provideContentSource(
        @ApplicationContext context: Context,
        catalog: ContentCatalog,
        installer: ContentAssetInstaller,
        downloader: VerifiedAssetDownloader,
        urlResolver: RemoteUrlResolver,
        progressBus: ProvisionProgressBus,
    ): ContentSource = RemoteContentSource(
        context = context,
        catalog = catalog,
        installer = installer,
        downloader = downloader,
        urlResolver = urlResolver,
        progressBus = progressBus,
    )

    @Provides
    @Singleton
    fun provideContentReconciler(
        @ApplicationContext context: Context,
        installer: ContentAssetInstaller,
    ): ContentReconciler = ContentReconciler(context, installer)

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
