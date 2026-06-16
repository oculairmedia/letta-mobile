package com.letta.mobile.runtime.mobileactions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class AndroidProviderReadTool @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    constructor(
        context: Context,
        permissionChecker: (String) -> Int,
    ) : this(context) {
        this.permissionChecker = permissionChecker
    }

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private var permissionChecker: (String) -> Int = { permission ->
        ContextCompat.checkSelfPermission(context, permission)
    }

    fun handleJson(command: String, input: JsonObject): String = json.encodeToString(handle(command, input))

    fun handle(command: String, input: JsonObject): ProviderReadResult {
        return when (command) {
            "contacts.read" -> readContacts(input)
            "calendar.read" -> readCalendar(input)
            else -> ProviderReadResult(
                command = command,
                status = "error",
                error = "Unknown provider read command: $command"
            )
        }
    }

    private fun readContacts(input: JsonObject): ProviderReadResult {
        val permission = Manifest.permission.READ_CONTACTS
        val permissionStatus = checkPermission(permission)
        if (permissionStatus != "available") {
            return ProviderReadResult(
                command = "contacts.read",
                status = permissionStatus,
                permission = permission
            )
        }

        val queryFilter = input.string("query")
        val limit = input.int("limit") ?: 50
        val effectiveLimit = limit.coerceIn(1, 50)

        return try {
            val contacts = mutableListOf<ContactEntry>()
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME
            )
            val selection = if (!queryFilter.isNullOrBlank()) {
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
            } else null
            val selectionArgs = if (!queryFilter.isNullOrBlank()) {
                arrayOf("%$queryFilter%")
            } else null

            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC LIMIT $effectiveLimit"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)

                while (cursor.moveToNext() && contacts.size < effectiveLimit) {
                    val contactId = if (idIndex >= 0) cursor.getString(idIndex) else null
                    val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null

                    if (contactId != null && displayName != null) {
                        val phones = mutableListOf<String>()
                        val emails = mutableListOf<String>()

                        // Query phones
                        context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(contactId),
                            null
                        )?.use { phoneCursor ->
                            val phoneIndex = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            while (phoneCursor.moveToNext()) {
                                val phone = if (phoneIndex >= 0) phoneCursor.getString(phoneIndex) else null
                                if (!phone.isNullOrBlank()) phones.add(phone)
                            }
                        }

                        // Query emails
                        context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                            arrayOf(contactId),
                            null
                        )?.use { emailCursor ->
                            val emailIndex = emailCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                            while (emailCursor.moveToNext()) {
                                val email = if (emailIndex >= 0) emailCursor.getString(emailIndex) else null
                                if (!email.isNullOrBlank()) emails.add(email)
                            }
                        }

                        contacts.add(
                            ContactEntry(
                                displayName = displayName,
                                phones = phones.take(3), // Limit to 3 per contact
                                emails = emails.take(3)  // Limit to 3 per contact
                            )
                        )
                    }
                }
            }

            ProviderReadResult(
                command = "contacts.read",
                status = "available",
                count = contacts.size,
                contacts = contacts.ifEmpty { null }
            )
        } catch (e: SecurityException) {
            ProviderReadResult(
                command = "contacts.read",
                status = "blocked_by_android_policy",
                error = "SecurityException: ${e.message}"
            )
        } catch (e: Exception) {
            ProviderReadResult(
                command = "contacts.read",
                status = "error",
                error = "Failed to read contacts: ${e.message}"
            )
        }
    }

    private fun readCalendar(input: JsonObject): ProviderReadResult {
        val permission = Manifest.permission.READ_CALENDAR
        val permissionStatus = checkPermission(permission)
        if (permissionStatus != "available") {
            return ProviderReadResult(
                command = "calendar.read",
                status = permissionStatus,
                permission = permission
            )
        }

        val startMillis = input.long("startTimeMillis") ?: System.currentTimeMillis()
        val endMillis = input.long("endTimeMillis") ?: (startMillis + 7 * 24 * 60 * 60 * 1000L) // Default 7 days
        val limit = input.int("limit") ?: 50
        val effectiveLimit = limit.coerceIn(1, 50)

        return try {
            val events = mutableListOf<CalendarEvent>()
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION
            )
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())

            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC LIMIT $effectiveLimit"
            )?.use { cursor ->
                val titleIndex = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val startIndex = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIndex = cursor.getColumnIndex(CalendarContract.Events.DTEND)
                val locationIndex = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)

                while (cursor.moveToNext() && events.size < effectiveLimit) {
                    val title = if (titleIndex >= 0) cursor.getString(titleIndex) else null
                    val dtstart = if (startIndex >= 0) cursor.getLong(startIndex) else null
                    val dtend = if (endIndex >= 0) cursor.getLong(endIndex) else null
                    val location = if (locationIndex >= 0) cursor.getString(locationIndex) else null

                    events.add(
                        CalendarEvent(
                            title = title ?: "(No title)",
                            dtstart = dtstart,
                            dtend = dtend,
                            location = location
                        )
                    )
                }
            }

            ProviderReadResult(
                command = "calendar.read",
                status = "available",
                count = events.size,
                events = events.ifEmpty { null }
            )
        } catch (e: SecurityException) {
            ProviderReadResult(
                command = "calendar.read",
                status = "blocked_by_android_policy",
                error = "SecurityException: ${e.message}"
            )
        } catch (e: Exception) {
            ProviderReadResult(
                command = "calendar.read",
                status = "error",
                error = "Failed to read calendar: ${e.message}"
            )
        }
    }

    private fun checkPermission(permission: String): String {
        return when (permissionChecker(permission)) {
            PackageManager.PERMISSION_GRANTED -> "available"
            PackageManager.PERMISSION_DENIED -> "permission_required"
            else -> "not_granted"
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
}

@Serializable
data class ProviderReadResult(
    val command: String,
    val status: String,
    val permission: String? = null,
    val count: Int? = null,
    val contacts: List<ContactEntry>? = null,
    val events: List<CalendarEvent>? = null,
    val error: String? = null,
)

@Serializable
data class ContactEntry(
    val displayName: String,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
)

@Serializable
data class CalendarEvent(
    val title: String,
    val dtstart: Long? = null,
    val dtend: Long? = null,
    val location: String? = null,
)
