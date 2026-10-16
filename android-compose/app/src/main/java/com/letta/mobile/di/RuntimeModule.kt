package com.letta.mobile.di

import com.letta.mobile.data.local.LettaDatabase
import com.letta.mobile.data.local.RoomRuntimeEventOutbox
import com.letta.mobile.runtime.local.AndroidNetworkBridge
import com.letta.mobile.runtime.local.BuildConfigEmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.LiteRtLmOnDeviceChatCompletionEngine
import com.letta.mobile.runtime.local.LocalAndroidNetworkBridge
import com.letta.mobile.runtime.local.LocalOpenAiOnDeviceBridge
import com.letta.mobile.runtime.local.NativeLettaCodeNodeBridge
import com.letta.mobile.runtime.local.AndroidLocalAppServerLaunchSource
import com.letta.mobile.runtime.local.EmbeddedNode
import com.letta.mobile.runtime.local.LibnodeAppServerLauncher
import com.letta.mobile.runtime.local.androidLocalAppServerHost
import com.letta.mobile.data.runtime.host.LocalAppServerHost
import com.letta.mobile.data.runtime.host.LocalAppServerLauncher
import com.letta.mobile.data.transport.appserver.AppServerDiscovery
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import com.letta.mobile.data.session.LocalRuntimeDialer
import com.letta.mobile.data.session.ProcessScope
import com.letta.mobile.runtime.local.AndroidLocalRuntimeDialer
import kotlinx.coroutines.CoroutineScope
import com.letta.mobile.runtime.local.OnDeviceChatCompletionEngine
import com.letta.mobile.runtime.local.OnDeviceModelImporter
import com.letta.mobile.runtime.local.OnDeviceOpenAiBridge
import com.letta.mobile.runtime.local.SafOnDeviceModelImporter
import com.letta.mobile.runtime.clipboard.AndroidClipboardProvider
import com.letta.mobile.runtime.clipboard.ClipboardProvider
import com.letta.mobile.runtime.clipboard.ClipboardTool
import com.letta.mobile.runtime.hardware.AndroidDeviceHardwareControlProvider
import com.letta.mobile.runtime.hardware.DeviceHardwareControlProvider
import com.letta.mobile.runtime.hardware.DeviceHardwareControlTool
import com.letta.mobile.runtime.local.modelcatalog.AssetEmbeddedModelRepository
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
import com.letta.mobile.runtime.RuntimeEventOutbox
import com.letta.mobile.runtime.actions.AndroidMobileActionCapabilityProvider
import com.letta.mobile.runtime.actions.InMemoryMobileActionAuditSink
import com.letta.mobile.runtime.actions.MobileActionAuditSink
import com.letta.mobile.runtime.actions.MobileActionCapabilityProvider
import com.letta.mobile.runtime.actions.MobileExternalToolHandler
import com.letta.mobile.runtime.screen.AndroidScreenCaptureProvider
import com.letta.mobile.runtime.screen.ScreenCaptureProvider
import com.letta.mobile.runtime.screen.ScreenCaptureTool
import com.letta.mobile.runtime.sensors.AndroidDeviceSensorSampler
import com.letta.mobile.runtime.sensors.AndroidDeviceSensorSnapshotProvider
import com.letta.mobile.runtime.sensors.DeviceSensorReadTool
import com.letta.mobile.runtime.sensors.DeviceSensorSampler
import com.letta.mobile.runtime.sensors.DeviceSensorSnapshotProvider
import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RuntimeModule {
    @Provides
    @Singleton
    fun provideRuntimeEventOutbox(database: LettaDatabase): RuntimeEventOutbox =
        RoomRuntimeEventOutbox(database)

    @Provides
    @Singleton
    fun provideDeviceSensorSnapshotProvider(
        @ApplicationContext context: Context,
    ): DeviceSensorSnapshotProvider = AndroidDeviceSensorSnapshotProvider(context)

    @Provides
    @Singleton
    fun provideDeviceHardwareControlProvider(
        @ApplicationContext context: Context,
    ): DeviceHardwareControlProvider = AndroidDeviceHardwareControlProvider(context)

    @Provides
    @Singleton
    fun provideClipboardProvider(
        @ApplicationContext context: Context,
    ): ClipboardProvider = AndroidClipboardProvider(context)

    @Provides
    @Singleton
    fun provideDeviceSensorSampler(sampler: AndroidDeviceSensorSampler): DeviceSensorSampler = sampler

    @Provides
    @Singleton
    fun provideScreenCaptureProvider(application: Application): ScreenCaptureProvider =
        AndroidScreenCaptureProvider(application)

    @Provides
    @Singleton
    fun provideDeviceSensorReadTool(
        provider: DeviceSensorSnapshotProvider,
        sampler: DeviceSensorSampler,
    ): DeviceSensorReadTool = DeviceSensorReadTool(provider, sampler)

    @Provides
    @Singleton
    fun provideDeviceHardwareControlTool(provider: DeviceHardwareControlProvider): DeviceHardwareControlTool =
        DeviceHardwareControlTool(provider)

    @Provides
    @Singleton
    fun provideEmbeddedNode(bridge: NativeLettaCodeNodeBridge): EmbeddedNode = bridge

    @Provides
    @Singleton
    fun provideLocalAppServerLauncher(launcher: LibnodeAppServerLauncher): LocalAppServerLauncher = launcher

    /**
     * letta-mobile-by4vm.110.5: the on-device App Server host. Lazy: building it starts nothing;
     * the first `acquire()` (the first LOCAL_RUNTIME dial, by4vm.110.6) launches node.
     */
    @Provides
    @Singleton
    fun provideLocalAppServerHost(
        launcher: LocalAppServerLauncher,
        launchSource: AndroidLocalAppServerLaunchSource,
        @ProcessScope scope: CoroutineScope,
    ): LocalAppServerHost = androidLocalAppServerHost(
        launcher = launcher,
        launchSource = launchSource,
        discovery = AppServerDiscovery(HttpClient(OkHttp)),
        scope = scope,
    )

    /** letta-mobile-by4vm.110.6: how a LOCAL_RUNTIME session's channel dials the host above. */
    @Provides
    @Singleton
    fun provideLocalRuntimeDialer(dialer: AndroidLocalRuntimeDialer): LocalRuntimeDialer = dialer

    @Provides
    @Singleton
    fun provideOnDeviceOpenAiBridge(bridge: LocalOpenAiOnDeviceBridge): OnDeviceOpenAiBridge = bridge

    @Provides
    @Singleton
    fun provideAndroidNetworkBridge(bridge: LocalAndroidNetworkBridge): AndroidNetworkBridge = bridge

    @Provides
    @Singleton
    fun provideMobileActionAuditSink(): MobileActionAuditSink = InMemoryMobileActionAuditSink()

    @Provides
    @Singleton
    fun provideNotificationStore(): com.letta.mobile.runtime.notifications.NotificationStore =
        com.letta.mobile.runtime.notifications.InMemoryNotificationStore()

    @Provides
    @IntoSet
    @Singleton
    fun provideNotificationPollTool(
        @ApplicationContext context: Context,
        store: com.letta.mobile.runtime.notifications.NotificationStore,
    ): MobileExternalToolHandler = com.letta.mobile.runtime.notifications.NotificationPollTool(
        store = store,
        isAccessGranted = {
            com.letta.mobile.runtime.notifications.LettaNotificationListenerService
                .isNotificationAccessGranted(context)
        },
    )

    @Provides
    @IntoSet
    @Singleton
    fun provideAndroidMobileActionCapabilityProvider(
        @ApplicationContext context: Context,
    ): MobileActionCapabilityProvider = AndroidMobileActionCapabilityProvider(context)

    @Provides
    @IntoSet
    @Singleton
    fun provideScreenCaptureTool(provider: ScreenCaptureProvider): MobileExternalToolHandler =
        ScreenCaptureTool(provider)

    @Provides
    @IntoSet
    @Singleton
    fun provideClipboardTool(tool: ClipboardTool): MobileExternalToolHandler = tool

    @Provides
    @ElementsIntoSet
    @Singleton
    fun provideMobileExternalToolHandlers(): Set<MobileExternalToolHandler> = emptySet()

    @Provides
    @Singleton
    fun provideOnDeviceChatCompletionEngine(
        engine: LiteRtLmOnDeviceChatCompletionEngine,
    ): OnDeviceChatCompletionEngine = engine

    @Provides
    @Singleton
    fun provideOnDeviceModelImporter(importer: SafOnDeviceModelImporter): OnDeviceModelImporter = importer

    @Provides
    @Singleton
    fun provideEmbeddedLettaCodeRuntimeStatusProvider(
        provider: BuildConfigEmbeddedLettaCodeRuntimeStatusProvider,
    ): EmbeddedLettaCodeRuntimeStatusProvider = provider

    @Provides
    @Singleton
    fun provideEmbeddedModelRepository(repository: AssetEmbeddedModelRepository): EmbeddedModelRepository = repository
}
