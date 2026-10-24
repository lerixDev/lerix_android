@file:OptIn(LerixModuleApi::class)

package com.lerix.sdk.notifications

import android.util.Base64
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.lerix.sdk.module.LerixModuleApi
import com.lerix.sdk.module.LerixModuleHost

internal object LerixFirebase {
    private const val APP_NAME = "LERIX_FCM_APP"
    private const val PLACEHOLDER_PROJECT_ID = "lerix-b7899"
    private const val PLACEHOLDER_APP_ID = "1:536339477577:android:7850e82b1f244536fabb98"
    private const val PLACEHOLDER_API_KEY_BASE64 = "QUl6YVN5RDFuWHVXM1pKQi1VN3FNSkllcDZ4bi00VDFxdTZIZ04w"

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

        val created = FirebaseApp.initializeApp(LerixModuleHost.appContext, options, APP_NAME)
        app = created
        return created
    }

    private suspend fun fetchSenderId(): String {
        val response = LerixModuleHost.get("plugin/notifications/sender-id")
        return response?.optString("senderId")?.takeIf { it.isNotEmpty() }
            ?: throw LerixModuleHost.backendError(
                "No Android Firebase service account configured for this project — upload one from the dashboard (Notifications → Settings)",
            )
    }

    suspend fun messaging(): FirebaseMessaging = app().get(FirebaseMessaging::class.java)
}
