package com.letta.mobile.platform.systemaccess

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.letta.mobile.platform.ManifestCapabilityProbe
import com.letta.mobile.platform.SystemAccessBuild
import com.letta.mobile.platform.SystemAccessFlavor
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
}

@Singleton
class AndroidSystemAccessEnvironment @Inject constructor(
    @param:ApplicationContext private val context: Context,
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
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    override fun isNotificationListenerEnabled(serviceClassName: String): Boolean =
        enabledComponentSettingContains(ENABLED_NOTIFICATION_LISTENERS, serviceClassName)

    override fun isAccessibilityServiceEnabled(serviceClassName: String): Boolean =
        enabledComponentSettingContains(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, serviceClassName)

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
    const val ReadMediaImages = Manifest.permission.READ_MEDIA_IMAGES
    const val ReadMediaVideo = Manifest.permission.READ_MEDIA_VIDEO
    const val ReadMediaAudio = Manifest.permission.READ_MEDIA_AUDIO
    const val ReadExternalStorage = Manifest.permission.READ_EXTERNAL_STORAGE
    const val ManageExternalStorage = Manifest.permission.MANAGE_EXTERNAL_STORAGE
    const val ReadContacts = Manifest.permission.READ_CONTACTS
    const val WriteContacts = Manifest.permission.WRITE_CONTACTS
    const val SystemAlertWindow = Manifest.permission.SYSTEM_ALERT_WINDOW
    const val PostNotifications = Manifest.permission.POST_NOTIFICATIONS
    const val BindAccessibilityService = Manifest.permission.BIND_ACCESSIBILITY_SERVICE

    const val ShizukuApiV23 = "moe.shizuku.manager.permission.API_V23"
}

internal object AndroidSettingsActions {
    const val ActionOpenDocument = "android.intent.action.OPEN_DOCUMENT"
    const val ActionOpenDocumentTree = "android.intent.action.OPEN_DOCUMENT_TREE"
    const val ActionManageOverlayPermission = Settings.ACTION_MANAGE_OVERLAY_PERMISSION
    const val ActionNotificationListenerSettings = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
    const val ActionAccessibilitySettings = Settings.ACTION_ACCESSIBILITY_SETTINGS
    const val ActionManageAppAllFilesAccessPermission = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION

    fun appDetailsUri(packageName: String): Uri = Uri.parse("package:$packageName")
}
