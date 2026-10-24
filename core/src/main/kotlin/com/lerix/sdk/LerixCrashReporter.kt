package com.lerix.sdk

import com.lerix.sdk.appcontrol.LerixAppControl
import com.lerix.sdk.models.BugSeverity
import com.lerix.sdk.models.BugType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

internal object LerixCrashReporter {
    private const val PENDING_CRASH_FILE = "lerix_pending_crash.json"
    private var installed = false

    private fun pendingCrashFile(): File = File(LerixKeys.appContext.cacheDir, PENDING_CRASH_FILE)

    fun install() {
        if (installed) return
        installed = true

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                persistCrash(throwable)
            } catch (e: Exception) {
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun persistCrash(throwable: Throwable) {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        val stackLines = writer.toString().lines().filter { it.isNotBlank() }

        val payload = JSONObject().apply {
            put("issue", "${throwable.javaClass.name}: ${throwable.message ?: "no message"}")
            put("stack", JSONArray(stackLines))
            runCatching { com.lerix.sdk.analytics.LerixAnalytics.currentSessionId() }.getOrNull()
                ?.let { put("sessionId", it) }
        }
        pendingCrashFile().writeText(payload.toString())
        // Flush pending state (session timestamps) before the process dies.
        runCatching { if (com.lerix.sdk.core.LerixRuntime.initialized) com.lerix.sdk.core.LerixRuntime.store.writeNow() }
    }

    suspend fun reportPendingCrashIfAny() {
        val file = pendingCrashFile()
        if (!file.exists()) return
        // Kept on disk while the kill switch is on; sent once it lifts (contract §2.5).
        if (LerixAppControl.isModuleDisabled("errors")) return

        val payload = runCatching { JSONObject(file.readText()) }.getOrNull()
        file.delete()
        if (payload == null) return

        val issue = payload.optString("issue").ifEmpty { return }
        val stackArray = payload.optJSONArray("stack")
        val stack = stackArray?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()

        val sessionId = payload.optString("sessionId").takeIf { it.isNotEmpty() }
        ErrorsHandler.throwError(issue, stack, BugType.CRASH, BugSeverity.CRITICAL, sessionId = sessionId)
    }
}
