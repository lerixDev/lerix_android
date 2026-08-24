package com.lerix.sdk

import android.util.Base64
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Initializes a secondary, *named* Firebase app using Atelerix's own
 * shared Firebase project — the same one the Flutter plugin's Android
 * side uses — instead of the app's default `FirebaseApp`. This is what
 * lets push notifications work with zero Firebase setup on the
 * developer's part: no `google-services.json`, no Google Services
 * Gradle plugin.
 *
 * Firebase officially supports multiple named app instances per process;
 * this one is scoped entirely to Lerix and never touches/requires the
 * app's own (possibly nonexistent) default Firebase configuration.
 */
internal object LerixFirebase {
    private const val APP_NAME = "LERIX_FCM_APP"
    private const val PROJECT_ID = "atelerix-44685"
    private const val APPLICATION_ID = "1:152690376774:android:9d72d1299efc71ac39ca47"
    private const val API_KEY_BASE64 = "QUl6YVN5RGZTZ20xU0Q3bHcwd0FuRmJwT0RQX3ZoSjZlbjNObHA0"
    // Must match the project number embedded in APPLICATION_ID
    // ("1:152690376774:android:..."), not some other Sender ID — a
    // mismatch here is exactly what produces FCM's "SenderId mismatch"
    // delivery error, since the token gets minted against one project
    // number while APPLICATION_ID identifies a different one.
    private const val GCM_SENDER_ID = "152690376774"

    private var app: FirebaseApp? = null

    private fun app(): FirebaseApp {
        app?.let { return it }
        synchronized(this) {
            app?.let { return it }

            val existing = runCatching { FirebaseApp.getInstance(APP_NAME) }.getOrNull()
            if (existing != null) {
                app = existing
                return existing
            }

            val apiKey = String(Base64.decode(API_KEY_BASE64, Base64.DEFAULT))
            val options = FirebaseOptions.Builder()
                .setGcmSenderId(GCM_SENDER_ID)
                .setProjectId(PROJECT_ID)
                .setApplicationId(APPLICATION_ID)
                .setApiKey(apiKey)
                .build()

            val created = FirebaseApp.initializeApp(LerixKeys.appContext, options, APP_NAME)
            app = created
            return created
        }
    }

    fun messaging(): FirebaseMessaging = app().get(FirebaseMessaging::class.java)
}
