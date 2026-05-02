package com.letta.mobile.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Runtime probes against the installed, merged APK manifest and device feature
 * set.
 *
 * The system-access registry should use these probes in addition to
 * [SystemAccessBuild] so tools stay invisible when a permission/service is not
 * present in the active flavor or the device cannot support it.
 */
object ManifestCapabilityProbe {
    fun hasDeclaredPermission(
        context: Context,
        permission: String,
    ): Boolean {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }

        return packageInfo.requestedPermissions?.contains(permission) == true
    }

    fun hasDeclaredService(
        context: Context,
        serviceClass: Class<*>,
    ): Boolean {
        val component = ComponentName(context, serviceClass)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getServiceInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
            }
        }.isSuccess
    }

    fun hasSystemFeature(
        context: Context,
        featureName: String,
    ): Boolean = context.packageManager.hasSystemFeature(featureName)
}
