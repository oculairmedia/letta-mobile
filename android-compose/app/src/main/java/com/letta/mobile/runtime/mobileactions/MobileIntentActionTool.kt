package com.letta.mobile.runtime.mobileactions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class MobileIntentActionTool @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    fun handle(input: JsonObject): MobileIntentActionResponse {
        val toolName = input.string("tool")?.trim().orEmpty()
        val dryRun = input.boolean("dryRun") ?: false
        return when (toolName) {
            OPEN_WIFI_SETTINGS -> handleIntent(toolName, dryRun, userActionRequired = true) { openWifiSettingsIntent() }
            SHOW_LOCATION_ON_MAP -> handleIntent(toolName, dryRun, userActionRequired = true) {
                val location = input.required("location")
                showLocationOnMapIntent(location)
            }
            COMPOSE_EMAIL -> handleIntent(toolName, dryRun, userActionRequired = true) {
                composeEmailIntent(
                    to = input.required("to"),
                    subject = input.string("subject").orEmpty(),
                    body = input.string("body").orEmpty(),
                )
            }
            INSERT_CONTACT -> handleIntent(toolName, dryRun, userActionRequired = true) {
                insertContactIntent(
                    firstName = input.string("firstName").orEmpty(),
                    lastName = input.string("lastName").orEmpty(),
                    phoneNumber = input.string("phoneNumber").orEmpty(),
                    email = input.string("email").orEmpty(),
                )
            }
            INSERT_CALENDAR_EVENT -> handleIntent(toolName, dryRun, userActionRequired = true) {
                insertCalendarEventIntent(
                    datetime = input.required("datetime"),
                    title = input.required("title"),
                )
            }
            else -> MobileIntentActionResponse(
                tool = toolName.ifBlank { "unknown" },
                status = "error",
                userActionRequired = true,
                dryRun = dryRun,
                error = "Unsupported mobile action '$toolName'.",
            )
        }
    }

    fun handleJson(input: JsonObject): String = json.encodeToString(handle(input))

    private fun handleIntent(
        toolName: String,
        dryRun: Boolean,
        userActionRequired: Boolean,
        buildIntent: () -> Intent,
    ): MobileIntentActionResponse = try {
        val intent = buildIntent().withNewTaskFlag()
        val resolved = intent.resolveActivity(context.packageManager) != null
        val mapping = intent.toMapping()
        when {
            !resolved -> MobileIntentActionResponse(
                tool = toolName,
                status = "no_handler",
                userActionRequired = userActionRequired,
                dryRun = dryRun,
                resolved = false,
                launched = false,
                intent = mapping,
                message = "No Android app can handle this user-mediated intent action.",
            )
            dryRun -> MobileIntentActionResponse(
                tool = toolName,
                status = "resolved",
                userActionRequired = userActionRequired,
                dryRun = true,
                resolved = true,
                launched = false,
                intent = mapping,
            )
            else -> {
                context.startActivity(intent)
                MobileIntentActionResponse(
                    tool = toolName,
                    status = "opened_ui_awaiting_user_confirmation",
                    userActionRequired = userActionRequired,
                    dryRun = false,
                    resolved = true,
                    launched = true,
                    intent = mapping,
                    message = openedUiMessage(toolName),
                )
            }
        }
    } catch (error: MissingInputException) {
        MobileIntentActionResponse(
            tool = toolName,
            status = "error",
            userActionRequired = userActionRequired,
            dryRun = dryRun,
            error = error.message,
        )
    } catch (error: ActivityNotFoundException) {
        MobileIntentActionResponse(
            tool = toolName,
            status = "not_available",
            userActionRequired = userActionRequired,
            dryRun = dryRun,
            resolved = false,
            launched = false,
            error = error.message ?: "No activity could handle the intent.",
        )
    } catch (error: SecurityException) {
        MobileIntentActionResponse(
            tool = toolName,
            status = "blocked_by_android_policy",
            userActionRequired = userActionRequired,
            dryRun = dryRun,
            resolved = true,
            launched = false,
            error = error.message ?: "Android policy blocked this user-mediated intent action.",
        )
    } catch (error: Exception) {
        MobileIntentActionResponse(
            tool = toolName,
            status = "error",
            userActionRequired = userActionRequired,
            dryRun = dryRun,
            error = error.message ?: error::class.java.simpleName,
        )
    }

    companion object {
        const val OPEN_WIFI_SETTINGS = "open_wifi_settings"
        const val SHOW_LOCATION_ON_MAP = "show_location_on_map"
        const val COMPOSE_EMAIL = "compose_email"
        const val INSERT_CONTACT = "insert_contact"
        const val INSERT_CALENDAR_EVENT = "insert_calendar_event"
    }
}

fun openWifiSettingsIntent(): Intent = Intent(Settings.ACTION_WIFI_SETTINGS)

fun showLocationOnMapIntent(location: String): Intent = Intent(
    Intent.ACTION_VIEW,
    Uri.parse("geo:0,0?q=${Uri.encode(location.trim())}"),
)

fun composeEmailIntent(to: String, subject: String, body: String): Intent = Intent(Intent.ACTION_SENDTO).apply {
    data = Uri.parse("mailto:")
    putExtra(Intent.EXTRA_EMAIL, arrayOf(to.trim()))
    putExtra(Intent.EXTRA_SUBJECT, subject)
    putExtra(Intent.EXTRA_TEXT, body)
}

fun insertContactIntent(firstName: String, lastName: String, phoneNumber: String, email: String): Intent =
    Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        val name = listOf(firstName.trim(), lastName.trim()).filter { it.isNotBlank() }.joinToString(" ")
        if (name.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NAME, name)
        if (phoneNumber.isNotBlank()) putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber.trim())
        if (email.isNotBlank()) putExtra(ContactsContract.Intents.Insert.EMAIL, email.trim())
    }

fun insertCalendarEventIntent(datetime: String, title: String): Intent = Intent(Intent.ACTION_INSERT).apply {
    data = CalendarContract.Events.CONTENT_URI
    putExtra(CalendarContract.Events.TITLE, title.trim())
    parseEventStartMillis(datetime)?.let { startMillis ->
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
    }
}

private fun parseEventStartMillis(datetime: String): Long? {
    val trimmed = datetime.trim()
    if (trimmed.isBlank()) return null
    return runCatching { Instant.parse(trimmed).toEpochMilli() }
        .recoverCatching {
            LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
        .recoverCatching {
            throw DateTimeParseException("Unsupported datetime", trimmed, 0)
        }
        .getOrNull()
}

private fun Intent.withNewTaskFlag(): Intent = apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

private fun openedUiMessage(toolName: String): String = when (toolName) {
    MobileIntentActionTool.OPEN_WIFI_SETTINGS -> "Opened Android Wi-Fi settings. The user must confirm any settings changes in Android UI."
    MobileIntentActionTool.SHOW_LOCATION_ON_MAP -> "Opened Android maps UI for this location. The user remains in control of navigation or follow-up actions."
    MobileIntentActionTool.COMPOSE_EMAIL -> "Opened Android email composer. The user must review and tap send; the app did not send the email."
    MobileIntentActionTool.INSERT_CONTACT -> "Opened Android contact insert UI. The user must confirm save; the app did not save the contact."
    MobileIntentActionTool.INSERT_CALENDAR_EVENT -> "Opened Android calendar event editor. The user must confirm save; the app did not save the event."
    else -> "Opened Android UI for this user-mediated action. The user must confirm before anything is completed."
}

private fun Intent.toMapping(): IntentMapping = IntentMapping(
    action = action,
    data = data?.toString(),
    type = type,
    flags = flags,
    extras = extras?.keySet()?.sorted()?.associateWith { key ->
        val value = extras?.get(key)
        when (value) {
            is Array<*> -> value.filterIsInstance<String>().joinToString(",")
            else -> value?.toString().orEmpty()
        }
    }.orEmpty(),
)

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.required(key: String): String = string(key)?.trim()?.takeIf { it.isNotBlank() }
    ?: throw MissingInputException("$key is required.")

private class MissingInputException(message: String) : IllegalArgumentException(message)

@Serializable
data class MobileIntentActionResponse(
    val tool: String,
    val status: String,
    val userActionRequired: Boolean,
    val dryRun: Boolean,
    val resolved: Boolean? = null,
    val launched: Boolean? = null,
    val intent: IntentMapping? = null,
    val message: String? = null,
    val error: String? = null,
)

@Serializable
data class IntentMapping(
    val action: String? = null,
    val data: String? = null,
    val type: String? = null,
    val flags: Int = 0,
    val extras: Map<String, String> = emptyMap(),
)
