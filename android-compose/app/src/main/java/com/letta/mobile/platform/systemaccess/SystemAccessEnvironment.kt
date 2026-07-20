package com.letta.mobile.platform.systemaccess

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.letta.mobile.platform.ManifestCapabilityProbe
import com.letta.mobile.platform.SystemAccessBuild
import com.letta.mobile.platform.SystemAccessFlavor
import com.letta.mobile.platform.root.RootShellAvailability
import com.letta.mobile.platform.root.RootShellBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Runtime inputs used to resolve capability status. Kept small so registry rules are unit-testable. */
interface SystemAccessEnvironment {
    val flavor: SystemAccessFlavor
    val sdkInt: Int
    val localShellBuildEnabled: Boolean
    val shizukuBuildEnabled: Boolean
    val rootToolsBuildEnabled: Boolean

    fun hasDeclaredPermission(permission: String): Boolean
    fun isPermissionGranted(permission: String): Boolean
    fun hasDeclaredService(serviceClassName: String): Boolean
    fun hasSystemFeature(featureName: String): Boolean
    fun hasPersistedUriGrant(): Boolean
    fun canDrawOverlays(): Boolean
    fun isNotificationListenerEnabled(serviceClassName: String): Boolean
    fun isAccessibilityServiceEnabled(serviceClassName: String): Boolean
    fun rootShellAvailability(): RootShellAvailability
}

@Singleton
class AndroidSystemAccessEnvironment @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val rootShellBridge: RootShellBridge,
) : SystemAccessEnvironment {
    override val flavor: SystemAccessFlavor
        get() = SystemAccessBuild.flavor
    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT
    override val localShellBuildEnabled: Boolean
        get() = SystemAccessBuild.localShellEnabled
    override val shizukuBuildEnabled: Boolean
        get() = SystemAccessBuild.shizukuEnabled
    override val rootToolsBuildEnabled: Boolean
        get() = SystemAccessBuild.rootToolsEnabled

    override fun hasDeclaredPermission(permission: String): Boolean =
        ManifestCapabilityProbe.hasDeclaredPermission(context, permission)

    override fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun hasDeclaredService(serviceClassName: String): Boolean =
        ManifestCapabilityProbe.hasDeclaredService(context, serviceClassName)

    override fun hasSystemFeature(featureName: String): Boolean =
        ManifestCapabilityProbe.hasSystemFeature(context, featureName)

    override fun hasPersistedUriGrant(): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.isReadPermission || it.isWritePermission }

    override fun canDrawOverlays(): Boolean =
        Settings.canDrawOverlays(context)

    override fun isNotificationListenerEnabled(serviceClassName: String): Boolean =
        enabledComponentSettingContains(ENABLED_NOTIFICATION_LISTENERS, serviceClassName)

    override fun isAccessibilityServiceEnabled(serviceClassName: String): Boolean =
        enabledComponentSettingContains(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, serviceClassName)

    override fun rootShellAvailability(): RootShellAvailability = rootShellBridge.peekAvailability()

    private fun enabledComponentSettingContains(
        settingName: String,
        serviceClassName: String,
    ): Boolean {
        val flattenedComponents = Settings.Secure.getString(context.contentResolver, settingName).orEmpty()
        if (flattenedComponents.isBlank()) return false

        val expectedClassNames = setOf(
            serviceClassName,
            ".${serviceClassName.substringAfterLast('.')}",
        )
        return flattenedComponents.split(':').any { flattened ->
            val component = android.content.ComponentName.unflattenFromString(flattened) ?: return@any false
            component.packageName == context.packageName && component.className in expectedClassNames
        }
    }
}

private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"

internal object AndroidPermissionNames {
    const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    const val READ_EXTERNAL_STORAGE = Manifest.permission.READ_EXTERNAL_STORAGE
    const val MANAGE_EXTERNAL_STORAGE = Manifest.permission.MANAGE_EXTERNAL_STORAGE
    const val READ_CONTACTS = Manifest.permission.READ_CONTACTS
    const val WRITE_CONTACTS = Manifest.permission.WRITE_CONTACTS
    const val SYSTEM_ALERT_WINDOW = Manifest.permission.SYSTEM_ALERT_WINDOW
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val BIND_ACCESSIBILITY_SERVICE = Manifest.permission.BIND_ACCESSIBILITY_SERVICE

    const val SHIZUKU_API_V23 = "moe.shizuku.manager.permission.API_V23"
}

internal object AndroidSettingsActions {
    const val ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"
    const val ACTION_OPEN_DOCUMENT_TREE = "android.intent.action.OPEN_DOCUMENT_TREE"
    const val ACTION_MANAGE_OVERLAY_PERMISSION = Settings.ACTION_MANAGE_OVERLAY_PERMISSION
    const val ACTION_NOTIFICATION_LISTENER_SETTINGS = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
    const val ACTION_ACCESSIBILITY_SETTINGS = Settings.ACTION_ACCESSIBILITY_SETTINGS
    const val ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION

    fun appDetailsUri(packageName: String): Uri = "package:$packageName".toUri()
}
