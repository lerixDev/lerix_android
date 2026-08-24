package com.lerix.sdk

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Minimal encrypted key/value storage — the Android counterpart to the iOS
 * SDK's Keychain wrapper and the Flutter SDK's `flutter_secure_storage`.
 */
internal object LerixSecureStorage {
    private const val FILE_NAME = "com.lerix.sdk.secure_prefs"
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val created = EncryptedSharedPreferences.create(
                context.applicationContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            prefs = created
            return created
        }
    }

    fun read(context: Context, key: String): String? = prefs(context).getString(key, null)

    fun write(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun delete(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }
}
