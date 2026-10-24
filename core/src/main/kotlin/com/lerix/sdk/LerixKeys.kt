package com.lerix.sdk

import android.content.Context
import com.lerix.sdk.models.LerixErrorEvent
import com.lerix.sdk.models.LerixPingConfig

internal object LerixKeys {
    lateinit var appContext: Context
        private set

    var url: String = "https://api.lerix.dev/v1"
    var apiKey: String = ""
    var projectId: String = ""
    var debug: Boolean = false

    var ignoreErrors: List<String> = emptyList()
    var ignoreErrorPatterns: List<Regex> = emptyList()
    var beforeSend: ((LerixErrorEvent) -> LerixErrorEvent?)? = null

    var projectConfig: LerixPingConfig? = null
    var projectUser: String? = null

    fun bind(context: Context) {
        appContext = context.applicationContext
    }
}
