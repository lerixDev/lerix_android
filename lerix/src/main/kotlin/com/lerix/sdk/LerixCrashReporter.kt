package com.lerix.sdk

import com.lerix.sdk.models.BugSeverity
import com.lerix.sdk.models.BugType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Automatic crash capture: install once, and any uncaught exception on any
 * thread gets reported on the *next* launch without the app having to call
 * anything itself.
 *
 * A crash handler can't reliably make a network call — the process is
 * about to die — so this only ever does a synchronous file write at crash
 * time, then reports it normally once the app restarts.
 */
internal object LerixCrashReporter {
    private const val PENDING_CRASH_FILE = "lerix_pending_crash.json"
    private var installed = false

    private fun pendingCrashFile(): File = File(LerixKeys.appContext.cacheDir, PENDING_CRASH_FILE)

    /** Call once during `Lerix.initialize()`. Safe to call more than once — later calls are no-ops. */
    fun install() {
        if (installed) return
        installed = true

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                persistCrash(throwable)
            } catch (e: Exception) {
                // Never let the crash reporter itself block the real crash.
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
        }
        pendingCrashFile().writeText(payload.toString())
    }

    /** Call once at startup (after registration) to report and clear any crash captured during a previous run. */
    suspend fun reportPendingCrashIfAny() {
        val file = pendingCrashFile()
        if (!file.exists()) return

        val payload = runCatching { JSONObject(file.readText()) }.getOrNull()
        file.delete()
        if (payload == null) return

        val issue = payload.optString("issue").ifEmpty { return }
        val stackArray = payload.optJSONArray("stack")
        val stack = stackArray?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()

        ErrorsHandler.throwError(issue, stack, BugType.CRASH, BugSeverity.CRITICAL)
    }
}
