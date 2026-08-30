package io.github.m1n1m1.easymatic.data.service

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import io.github.m1n1m1.easymatic.core.service.Contacts

/**
 * [Contacts] over the platform's contacts provider.
 *
 * Resolution goes through the **lookup URI** rather than a stored row id, and that
 * is the whole point of storing a lookup key: it re-resolves through contact merges
 * and re-syncs, where a data-row `_ID` is minted afresh whenever a number is deleted
 * and re-added — i.e. it would break in exactly the case the reference exists to
 * cover.
 *
 * Every failure is null, including the `SecurityException` a missing `READ_CONTACTS`
 * throws, because a caller can do nothing different with any of them: see [Contacts].
 */
class AndroidContacts(context: Context) : Contacts {

    private val resolver = context.applicationContext.contentResolver

    override fun phoneNumber(lookupKey: String): String? {
        if (lookupKey.isBlank()) return null
        return runCatching {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI,
                Uri.encode(lookupKey),
            )
            val dataUri = Uri.withAppendedPath(
                lookupUri,
                ContactsContract.Contacts.Data.CONTENT_DIRECTORY,
            )
            resolver.query(
                dataUri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                // The number the Contacts app itself treats as the default, which is
                // what "call Mum" means when Mum has three of them.
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, " +
                    "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC",
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
