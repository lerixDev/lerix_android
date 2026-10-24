package com.lerix.example

import android.app.Application
import com.lerix.sdk.Lerix

class LerixExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Lerix.initialize(
            context = this,
            apiKey = BuildConfig.LERIX_API_KEY,
            projectId = BuildConfig.LERIX_PROJECT_ID,
            url = BuildConfig.LERIX_URL,
            environment = BuildConfig.LERIX_ENVIRONMENT.ifEmpty { null },
            debugMode = true,
        )
    }
}
