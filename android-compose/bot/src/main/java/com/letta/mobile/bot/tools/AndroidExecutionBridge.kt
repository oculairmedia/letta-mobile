package com.letta.mobile.bot.tools

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface AndroidExecutionBridge {
    fun launchMainApp(): JsonObject
    fun launchApp(packageName: String): JsonObject
    fun writeClipboard(text: String): JsonObject
    fun readClipboard(): JsonObject
    fun notificationStatus(): JsonObject
    fun listLaunchableApps(limit: Int = 20): JsonObject
}

@Singleton
class DefaultAndroidExecutionBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) : AndroidExecutionBridge {
    private val packageManager: PackageManager = context.packageManager

    override fun launchMainApp(): JsonObject {
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
            ?: return buildJsonObject {
                put("status", "unavailable")
                put("action", "launch_main_app")
                put("reason", "Host app is not launchable from package manager")
            }
        context.startActivity(intent)
        return buildJsonObject {
            put("status", "success")
            put("action", "launch_main_app")
        }
    }

    override fun launchApp(packageName: String): JsonObject {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return buildJsonObject {
                put("status", "unavailable")
                put("package_name", packageName)
                put("reason", "App is not installed or launchable")
            }

        context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        return buildJsonObject {
            put("status", "success")
            put("package_name", packageName)
            put("action", "launch_app")
        }
    }

    override fun writeClipboard(text: String): JsonObject {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Letta Bot", text))
        return buildJsonObject {
            put("status", "success")
            put("chars_written", text.length)
        }
    }

    override fun readClipboard(): JsonObject {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(context)?.toString()
        return buildJsonObject {
            put("status", if (text != null) "success" else "unavailable")
            put("text", text ?: "")
        }
    }

    override fun notificationStatus(): JsonObject {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return buildJsonObject {
            put("status", "success")
            put("notifications_enabled", manager.areNotificationsEnabled())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                put("active_notification_count", manager.activeNotifications.size)
            }
        }
    }

    override fun listLaunchableApps(limit: Int): JsonObject {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = packageManager.queryIntentActivities(intent, 0)
            .sortedBy { it.loadLabel(packageManager).toString() }
            .take(limit)

        return buildJsonObject {
            put("status", "success")
            put("count", apps.size)
            put("apps", kotlinx.serialization.json.JsonArray(apps.map { info ->
                buildJsonObject {
                    put("label", info.loadLabel(packageManager).toString())
                    put("package_name", info.activityInfo.packageName)
                }
            }))
        }
    }
}
