package com.lerix.example

import android.app.Application
import com.lerix.sdk.Lerix

class LerixExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Lerix.initialize(
            context = this,
            apiKey = "6141d7ec-6860-414e-bd5f-96a056698be5",
            projectId = "u2ip-111-2esw",
            debugMode = true,
        )
    }
}
