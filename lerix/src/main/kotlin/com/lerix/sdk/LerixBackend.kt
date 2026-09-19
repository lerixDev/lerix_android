package com.lerix.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

internal data class LerixRoute(val method: String, val path: String) {
    companion object {
        val PING = LerixRoute("GET", "plugin/init/ping")
        val REGISTER_USER = LerixRoute("POST", "plugin/init/register-user")
        val DELETE_USER = LerixRoute("DELETE", "plugin/init/user")
        val SEND_BUG = LerixRoute("POST", "plugin/bugs/create")
        val REGISTER_TOKEN = LerixRoute("POST", "plugin/notifications/register-token")
        val SENDER_ID = LerixRoute("GET", "plugin/notifications/sender-id")
        val SUBSCRIBE_TOPIC = LerixRoute("POST", "plugin/notifications/subscribe-topic")
        val UNSUBSCRIBE_TOPIC = LerixRoute("POST", "plugin/notifications/unsubscribe-topic")
    }
}

internal class LerixApiException(val code: String, message: String) : Exception(message)

internal object LerixBackend {
    suspend fun get(
        route: LerixRoute,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject? = request(route, headers, body = null)

    suspend fun post(
        route: LerixRoute,
        data: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): JSONObject? = request(route, headers, body = JSONObject(data))

    suspend fun delete(
        route: LerixRoute,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject? = request(route, headers, body = null)

    private suspend fun request(
        route: LerixRoute,
        headers: Map<String, String>,
        body: JSONObject?,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val url = URL("${LerixKeys.url}/${LerixKeys.projectId}/${route.path}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = route.method
            setRequestProperty("content-type", "application/json")
            setRequestProperty("accept", "application/json")
            setRequestProperty("lerix-key", LerixKeys.apiKey)
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            connectTimeout = 15_000
            readTimeout = 15_000
            if (body != null) {
                doOutput = true
            }
        }

        if (LerixKeys.debug) {
            println("[Lerix] → ${route.method} $url")
        }

        try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { readStream(it) } ?: ""

            if (LerixKeys.debug) {
                println("[Lerix] ← $status $url")
            }

            val json = if (text.isBlank()) null else JSONObject(text)

            if (json != null && json.has("error") && !json.isNull("error")) {
                throw LerixApiException(json.optString("error"), json.optString("message", "Request failed"))
            }

            if (status !in 200..299) {
                throw LerixApiException(status.toString(), "Request failed")
            }

            json
        } finally {
            connection.disconnect()
        }
    }

    private fun readStream(stream: java.io.InputStream): String =
        BufferedReader(InputStreamReader(stream)).use { it.readText() }
}
