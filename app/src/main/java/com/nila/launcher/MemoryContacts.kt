package com.nila.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

private const val SUGGESTION_LIMIT = 3

/** A contact NILA can link to a person: the stable lookup key and the name to show. */
internal data class ContactChoice(val lookupKey: String, val name: String)

/** Asks which contact a person is, once; shown as a small chat card with [suggestions] as pills. */
internal data class LinkContact(val about: String, val suggestions: List<ContactChoice>) : NilaResponse

private fun canReadContacts(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

/** The contact behind a URI from the system picker. Reading a picked contact needs no READ_CONTACTS. */
internal fun resolvePickedContact(context: Context, uri: Uri): ContactChoice? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val lookup = c.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
            ContactChoice(lookup, c.getString(1).orEmpty().ifBlank { lookup })
        }
    }.getOrNull()

/** Up to three contacts: names that contain [about] first, then starred ones. Empty without READ_CONTACTS. */
internal fun suggestContacts(context: Context, about: String): List<ContactChoice> {
    if (!canReadContacts(context)) return emptyList()
    return runCatching {
        val projection = arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME)
        fun query(selection: String?, args: Array<String>?) =
            context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, projection, selection, args, "${ContactsContract.Contacts.DISPLAY_NAME} ASC")?.use { c ->
                buildList {
                    while (c.moveToNext() && size < SUGGESTION_LIMIT) {
                        val lookup = c.getString(0) ?: continue
                        add(ContactChoice(lookup, c.getString(1).orEmpty().ifBlank { lookup }))
                    }
                }
            }.orEmpty()
        val named = query("${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?", arrayOf("%$about%"))
        val starred = query("${ContactsContract.Contacts.STARRED} = 1", null)
        (named + starred).distinctBy { it.lookupKey }.take(SUGGESTION_LIMIT)
    }.getOrDefault(emptyList())
}

/** A thumbnail URI for the contact behind [lookupKey]; null without READ_CONTACTS or a photo. */
internal fun contactPhotoUri(context: Context, lookupKey: String): String? {
    if (!canReadContacts(context)) return null
    return runCatching {
        val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, Uri.encode(lookupKey))
        context.contentResolver.query(lookupUri, arrayOf(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
