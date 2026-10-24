package com.lerix.sdk.core

import com.lerix.sdk.LerixBackend
import com.lerix.sdk.LerixHttpResponse
import com.lerix.sdk.LerixRoute

internal class LerixSendResult(val decision: LerixRetryDecision, val response: LerixHttpResponse?)

/** One outbox send with the contract's retry decision and server-hint handling. */
internal object LerixSender {
    suspend fun send(
        feature: LerixFeature,
        route: LerixRoute,
        userId: String,
        body: ByteArray?,
        contentType: String = "application/json",
        query: String? = null,
    ): LerixSendResult {
        val response = try {
            LerixBackend.exchange(route, LerixRuntime.appUserHeaders(userId), body, contentType, query)
        } catch (e: Exception) {
            LerixLog.debug("${route.path} failed: $e")
            return LerixSendResult(LerixRetryDecision.RETRY, null)
        }
        if (LerixRuntime.hints.handle(feature, response.status, response.json)) {
            return LerixSendResult(LerixRetryDecision.RETRY, response)
        }
        val decision = LerixRetryPolicy.decide(response.status, response.errorCode)
        if (decision == LerixRetryDecision.REREGISTER) LerixRuntime.reregister(userId)
        if (decision != LerixRetryDecision.SUCCESS) {
            LerixLog.debug("${route.path} → ${response.status} ($decision) ${response.text.take(300)}")
        }
        return LerixSendResult(decision, response)
    }
}
