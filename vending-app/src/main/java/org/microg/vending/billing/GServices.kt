package org.microg.vending.billing

import android.content.ContentResolver
import android.net.Uri
import org.microg.gms.common.Constants

// TODO: Move
object GServices {
    private val CONTENT_URI: Uri = Uri.parse("content://${Constants.VENDOR_GROUP_ID}.android.gsf.gservices")

    fun getString(resolver: ContentResolver, key: String, defaultValue: String?): String? {
        var result = defaultValue
        val cursor = resolver.query(CONTENT_URI, null, null, arrayOf(key), null)
        cursor?.use {
            if (cursor.moveToNext()) {
                result = cursor.getString(1)
            }
        }
        return result
    }
}