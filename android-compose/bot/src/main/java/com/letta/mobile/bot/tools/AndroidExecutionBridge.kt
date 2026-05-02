@file:Suppress("SwallowedException", "TooManyFunctions")

package com.letta.mobile.bot.tools

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val ACTION_OPEN_WIFI_SETTINGS = "android_open_wifi_settings"
private const val ACTION_SHOW_LOCATION_ON_MAP = "android_show_location_on_map"
private const val ACTION_SEND_EMAIL_DRAFT = "android_send_email_draft"
private const val ACTION_SEND_SMS_DRAFT = "android_send_sms_draft"
private const val ACTION_CREATE_CALENDAR_EVENT_DRAFT = "android_create_calendar_event_draft"
private const val ACTION_CREATE_CONTACT_DRAFT = "android_create_contact_draft"
private const val ACTION_SET_FLASHLIGHT = "android_set_flashlight"
private const val FLASHLIGHT_CAPABILITY_ID = "android.flashlight"

interface AndroidExecutionBridge {
    fun launchMainApp(): JsonObject
    fun launchApp(packageName: String): JsonObject
    fun writeClipboard(text: String): JsonObject
    fun readClipboard(): JsonObject
    fun notificationStatus(): JsonObject
    fun listLaunchableApps(limit: Int = 20): JsonObject
    fun openWifiSettings(): JsonObject
    fun showLocationOnMap(location: String): JsonObject
    fun sendEmailDraft(to: String, subject: String, body: String): JsonObject
    fun sendSmsDraft(phoneNumber: String, body: String): JsonObject
    fun createCalendarEventDraft(title: String, datetime: String): JsonObject
    fun createContactDraft(firstName: String, lastName: String, phoneNumber: String?, email: String?): JsonObject
    fun setFlashlight(enabled: Boolean): JsonObject
}

@Singleton
class DefaultAndroidExecutionBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
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
            put("apps", JsonArray(apps.map { info ->
                buildJsonObject {
                    put("label", info.loadLabel(packageManager).toString())
                    put("package_name", info.activityInfo.packageName)
                }
            }))
        }
    }

    override fun openWifiSettings(): JsonObject {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
        return startUserMediatedIntent(ACTION_OPEN_WIFI_SETTINGS, intent) {
            put("settings_action", Settings.ACTION_WIFI_SETTINGS)
        }
    }

    override fun showLocationOnMap(location: String): JsonObject {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("geo:0,0?q=${Uri.encode(location)}")
        }
        return startUserMediatedIntent(ACTION_SHOW_LOCATION_ON_MAP, intent) {
            put("location", location)
        }
    }

    override fun sendEmailDraft(to: String, subject: String, body: String): JsonObject {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${Uri.encode(to)}")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        return startUserMediatedIntent(ACTION_SEND_EMAIL_DRAFT, intent) {
            put("to", to)
            put("subject", subject)
            put("body_chars", body.length)
            put("sends_without_user", false)
        }
    }

    override fun sendSmsDraft(phoneNumber: String, body: String): JsonObject {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(phoneNumber)}")
            putExtra("sms_body", body)
        }
        return startUserMediatedIntent(ACTION_SEND_SMS_DRAFT, intent) {
            put("phone_number", phoneNumber)
            put("body_chars", body.length)
            put("sends_without_user", false)
        }
    }

    override fun createCalendarEventDraft(title: String, datetime: String): JsonObject {
        val startMillis = parseDatetimeMillis(datetime)
            ?: return unavailableResult(
                action = ACTION_CREATE_CALENDAR_EVENT_DRAFT,
                reason = "datetime must be ISO-8601, for example 2026-05-02T14:30:00",
            )

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 60 * 60 * 1000)
        }
        return startUserMediatedIntent(ACTION_CREATE_CALENDAR_EVENT_DRAFT, intent) {
            put("title", title)
            put("datetime", datetime)
            put("begin_time_millis", startMillis)
        }
    }

    override fun createContactDraft(
        firstName: String,
        lastName: String,
        phoneNumber: String?,
        email: String?,
    ): JsonObject {
        val displayName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, displayName)
            phoneNumber?.let {
                putExtra(ContactsContract.Intents.Insert.PHONE, it)
                putExtra(
                    ContactsContract.Intents.Insert.PHONE_TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                )
            }
            email?.let {
                putExtra(ContactsContract.Intents.Insert.EMAIL, it)
                putExtra(
                    ContactsContract.Intents.Insert.EMAIL_TYPE,
                    ContactsContract.CommonDataKinds.Email.TYPE_HOME,
                )
            }
        }
        return startUserMediatedIntent(ACTION_CREATE_CONTACT_DRAFT, intent) {
            put("first_name", firstName)
            put("last_name", lastName)
            put("has_phone_number", phoneNumber != null)
            put("has_email", email != null)
        }
    }

    override fun setFlashlight(enabled: Boolean): JsonObject {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            return unavailableResult(
                action = ACTION_SET_FLASHLIGHT,
                reason = "Device does not report a camera flash feature",
                capabilityId = FLASHLIGHT_CAPABILITY_ID,
            )
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findTorchCameraId(cameraManager)
            ?: return unavailableResult(
                action = ACTION_SET_FLASHLIGHT,
                reason = "No camera with a torch/flash is available",
                capabilityId = FLASHLIGHT_CAPABILITY_ID,
            )

        return try {
            cameraManager.setTorchMode(cameraId, enabled)
            buildJsonObject {
                put("status", "success")
                put("action", ACTION_SET_FLASHLIGHT)
                put("enabled", enabled)
                put("capability_id", FLASHLIGHT_CAPABILITY_ID)
                put("approval", buildJsonObject {
                    put("required", false)
                    put("decision", "allowed_device_feature")
                })
                put("audit", buildJsonObject {
                    put("tool_id", ACTION_SET_FLASHLIGHT)
                    put("operation", "set_torch_mode")
                    put("direct_device_state_change", true)
                    put("redacted", false)
                })
            }
        } catch (e: CameraAccessException) {
            errorResult(ACTION_SET_FLASHLIGHT, e.message ?: "Camera access failed", FLASHLIGHT_CAPABILITY_ID)
        } catch (e: SecurityException) {
            unavailableResult(ACTION_SET_FLASHLIGHT, e.message ?: "Camera access is not permitted", FLASHLIGHT_CAPABILITY_ID)
        } catch (e: IllegalArgumentException) {
            errorResult(ACTION_SET_FLASHLIGHT, e.message ?: "Torch camera id is invalid", FLASHLIGHT_CAPABILITY_ID)
        }
    }

    private fun findTorchCameraId(cameraManager: CameraManager): String? = try {
        cameraManager.cameraIdList.firstOrNull { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: CameraAccessException) {
        null
    }

    private fun startUserMediatedIntent(
        action: String,
        intent: Intent,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): JsonObject {
        val reason = tryStartActivity(intent)
        if (reason != null) {
            return unavailableResult(action, reason)
        }

        return buildJsonObject {
            put("status", "success")
            put("action", action)
            fields()
            put("approval", buildJsonObject {
                put("required", false)
                put("decision", "user_mediated")
            })
        }
    }

    private fun tryStartActivity(intent: Intent): String? = try {
        context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        null
    } catch (e: ActivityNotFoundException) {
        e.message ?: "No Android activity is available to handle this action"
    } catch (e: SecurityException) {
        e.message ?: "Android blocked this action because required access is unavailable"
    }

    private fun parseDatetimeMillis(datetime: String): Long? {
        runCatching { return Instant.parse(datetime).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(datetime).toInstant().toEpochMilli() }
        return runCatching {
            LocalDateTime.parse(datetime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun unavailableResult(action: String, reason: String, capabilityId: String? = null): JsonObject = buildJsonObject {
        put("status", "unavailable")
        put("action", action)
        capabilityId?.let { put("capability_id", it) }
        put("reason", reason)
    }

    private fun errorResult(action: String, reason: String, capabilityId: String? = null): JsonObject = buildJsonObject {
        put("status", "error")
        put("action", action)
        capabilityId?.let { put("capability_id", it) }
        put("error", reason)
    }
}
