package com.lerix.sdk

import com.lerix.sdk.appcontrol.LerixAppControl
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
        val IDENTIFY = LerixRoute("POST", "plugin/init/identify")
        val LOGOUT = LerixRoute("POST", "plugin/init/logout")
        val SEND_BUG = LerixRoute("POST", "plugin/bugs/create")
        val FEEDBACK_CONFIG = LerixRoute("GET", "plugin/feedback/config")
        val FEEDBACK_ATTACHMENT = LerixRoute("POST", "plugin/feedback/attachments")
        val FEEDBACK = LerixRoute("POST", "plugin/feedback")
        val SURVEYS_CONFIG = LerixRoute("GET", "plugin/surveys/config")
        val SURVEY_RESPONSE = LerixRoute("POST", "plugin/surveys/responses")
        val EVENTS = LerixRoute("POST", "plugin/events")
        val APP_CONFIG = LerixRoute("GET", "plugin/app-config")
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
        // Vendor kill switch (contract §2.5): every path except app-config.
        LerixAppControl.checkNetworkAllowed(route)
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

    /**
     * Raw request for the outbox: returns the status and body instead of
     * throwing on non-2xx, so the caller can apply the contract's retry rules.
     * Throws only on network failure (IOException).
     */
    suspend fun exchange(
        route: LerixRoute,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String = "application/json",
        query: String? = null,
        timeoutMs: Int = 15_000,
    ): LerixHttpResponse = withContext(Dispatchers.IO) {
        LerixAppControl.checkNetworkAllowed(route)
        val suffix = if (query.isNullOrEmpty()) "" else "?$query"
        val url = URL("${LerixKeys.url}/${LerixKeys.projectId}/${route.path}$suffix")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = route.method
            setRequestProperty("accept", "application/json")
            setRequestProperty("lerix-key", LerixKeys.apiKey)
            if (body != null) setRequestProperty("content-type", contentType)
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        if (LerixKeys.debug) println("[Lerix] → ${route.method} $url")
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { readStream(it) } ?: ""
            if (LerixKeys.debug) println("[Lerix] ← $status $url")
            LerixHttpResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun readStream(stream: java.io.InputStream): String =
        BufferedReader(InputStreamReader(stream)).use { it.readText() }
}

internal class LerixHttpResponse(val status: Int, val text: String) {
    val json: JSONObject? by lazy {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) runCatching { JSONObject(trimmed) }.getOrNull() else null
    }

    /** The backend's error code, e.g. `USER_PROJECT_NOT_EXIST`, when the body carries one. */
    val errorCode: String?
        get() = json?.let { j ->
            when {
                j.has("error") && !j.isNull("error") && j.opt("error") is String -> j.optString("error")
                j.has("code") && !j.isNull("code") -> j.optString("code")
                else -> null
            }
        }
}

/** Hand-built multipart/form-data body (no extra dependency). */
internal class LerixMultipart {
    val boundary = "----LerixBoundary" + java.util.UUID.randomUUID().toString().replace("-", "")
    private val out = java.io.ByteArrayOutputStream()

    val contentType: String get() = "multipart/form-data; boundary=$boundary"

    fun field(name: String, value: String): LerixMultipart {
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        write(value)
        write("\r\n")
        return this
    }

    fun file(name: String, fileName: String, mime: String, bytes: ByteArray): LerixMultipart {
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\n")
        write("Content-Type: $mime\r\n\r\n")
        out.write(bytes)
        write("\r\n")
        return this
    }

    fun build(): ByteArray {
        write("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))
}
