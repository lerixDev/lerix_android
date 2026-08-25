package com.lerix.sdk

import android.util.Base64
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

internal object LerixFirebase {
    private const val APP_NAME = "LERIX_FCM_APP"
    private const val PLACEHOLDER_PROJECT_ID = "atelerix-44685"
    private const val PLACEHOLDER_APP_ID = "1:152690376774:android:9d72d1299efc71ac39ca47"
    private const val PLACEHOLDER_API_KEY_BASE64 = "QUl6YVN5RGZTZ20xU0Q3bHcwd0FuRmJwT0RQX3ZoSjZlbjNObHA0"

    private var app: FirebaseApp? = null

    private suspend fun app(): FirebaseApp {
        app?.let { return it }
        val senderId = fetchSenderId()

        val existing = runCatching { FirebaseApp.getInstance(APP_NAME) }.getOrNull()
        if (existing != null) {
            app = existing
            return existing
        }

        val apiKey = String(Base64.decode(PLACEHOLDER_API_KEY_BASE64, Base64.DEFAULT))
        val options = FirebaseOptions.Builder()
            .setGcmSenderId(senderId)
            .setProjectId(PLACEHOLDER_PROJECT_ID)
            .setApplicationId(PLACEHOLDER_APP_ID)
            .setApiKey(apiKey)
            .build()

        val created = FirebaseApp.initializeApp(LerixKeys.appContext, options, APP_NAME)
        app = created
        return created
    }

    private suspend fun fetchSenderId(): String {
        val response = LerixBackend.get(LerixRoute.SENDER_ID)
        return response?.optString("senderId")?.takeIf { it.isNotEmpty() }
            ?: throw LerixBackendException(
                "No Android Firebase service account configured for this project — upload one from the dashboard (Notifications → Settings)",
            )
    }

    suspend fun messaging(): FirebaseMessaging = app().get(FirebaseMessaging::class.java)
}
