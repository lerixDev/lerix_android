package com.lerix.sdk

import com.lerix.sdk.models.LerixErrorEvent

/**
 * Client-side error filtering, applied to every report right before it is
 * sent: the ignore lists first, then `beforeSend`. Pure (no Android types) so
 * it can be unit tested on the JVM.
 */
internal object LerixErrorFilter {
    /** True when [message] matches any [ignoreErrors] substring (case-insensitive) or [ignoreErrorPatterns] regex. */
    fun isIgnored(
        message: String,
        ignoreErrors: List<String>,
        ignoreErrorPatterns: List<Regex>,
    ): Boolean {
        if (ignoreErrors.any { it.isNotBlank() && message.contains(it, ignoreCase = true) }) return true
        return ignoreErrorPatterns.any { it.containsMatchIn(message) }
    }

    /**
     * Returns the event to send, or null to drop it. If [beforeSend] throws,
     * the original event is sent and the exception goes to [onBeforeSendError]
     * only; it never reaches the host app and is never reported itself.
     */
    fun apply(
        event: LerixErrorEvent,
        ignoreErrors: List<String>,
        ignoreErrorPatterns: List<Regex>,
        beforeSend: ((LerixErrorEvent) -> LerixErrorEvent?)?,
        onBeforeSendError: (Throwable) -> Unit = {},
    ): LerixErrorEvent? {
        if (isIgnored(event.message, ignoreErrors, ignoreErrorPatterns)) return null
        if (beforeSend == null) return event
        return try {
            beforeSend(event)
        } catch (t: Throwable) {
            onBeforeSendError(t)
            event
        }
    }
}
