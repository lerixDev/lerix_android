package com.lerix.sdk.core

import java.util.UUID
import kotlin.random.Random

internal object LerixLog {
    @Volatile var enabled: Boolean = false

    fun debug(message: String) {
        if (enabled) println("[Lerix] $message")
    }
}

internal object LerixIds {
    private val CLIENT_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

    fun newId(): String = UUID.randomUUID().toString()

    fun isValidClientId(id: String): Boolean = CLIENT_ID.matches(id)
}

/** What the outbox does with an item after a send attempt (contract §0). */
internal enum class LerixRetryDecision {
    /** 2xx: delete the item. */
    SUCCESS,

    /** 400 / 413 / 422: delete it, it will never succeed. */
    DROP,

    /** 401 / 403 / 404 / 429 / 5xx / network: keep it and back off. */
    RETRY,

    /** 409 ("user not registered"): re-run registration, then retry. */
    REREGISTER,
}

internal object LerixRetryPolicy {
    const val MIN_BACKOFF_MS = 2_000L
    const val MAX_BACKOFF_MS = 5 * 60_000L

    /** Backend codes meaning "this app-user isn't valid for the project": re-register, never drop. */
    val REREGISTER_CODES = setOf("USER_PROJECT_NOT_EXIST", "PROJECT_USER_MISMATCH")

    /**
     * [status] is null for a network failure. [errorCode] is the body's `error`
     * code; `USER_PROJECT_NOT_EXIST` (400) and `PROJECT_USER_MISMATCH` (403) are
     * handled like 409. Unlisted statuses keep the item: losing data is worse
     * than retrying.
     */
    fun decide(status: Int?, errorCode: String? = null): LerixRetryDecision = when {
        status == null -> LerixRetryDecision.RETRY
        status !in 200..299 && errorCode in REREGISTER_CODES -> LerixRetryDecision.REREGISTER
        status in 200..299 -> LerixRetryDecision.SUCCESS
        status == 400 || status == 413 || status == 422 -> LerixRetryDecision.DROP
        status == 409 -> LerixRetryDecision.REREGISTER
        else -> LerixRetryDecision.RETRY // 401 never deletes; 403/404/429/5xx too.
    }

    /** Backoff before retry number [failures] (1-based): 2 s doubling to 5 min, jittered ±25 %. */
    fun backoffMs(failures: Int, random: Random = Random.Default): Long {
        val exponent = (failures - 1).coerceIn(0, 20)
        val base = (MIN_BACKOFF_MS shl exponent).coerceAtMost(MAX_BACKOFF_MS)
        val jitter = 0.75 + random.nextDouble() * 0.5
        return (base * jitter).toLong().coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
    }
}

/** Per-queue backoff state (in memory; a fresh process starts eager). */
internal class LerixBackoff(private val now: () -> Long = System::currentTimeMillis) {
    @Volatile var failures: Int = 0
        private set
    @Volatile var nextAttemptAt: Long = 0
        private set

    fun canAttempt(): Boolean = now() >= nextAttemptAt

    fun onSuccess() {
        failures = 0
        nextAttemptAt = 0
    }

    fun onFailure() {
        failures += 1
        nextAttemptAt = now() + LerixRetryPolicy.backoffMs(failures)
    }

    fun delayMs(): Long = (nextAttemptAt - now()).coerceAtLeast(0)
}
