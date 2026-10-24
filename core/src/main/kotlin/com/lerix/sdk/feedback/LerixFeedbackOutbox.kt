package com.lerix.sdk.feedback

import com.lerix.sdk.LerixMultipart
import com.lerix.sdk.LerixRoute
import com.lerix.sdk.core.LerixBackoff
import com.lerix.sdk.core.LerixFeature
import com.lerix.sdk.core.LerixFileQueue
import com.lerix.sdk.core.LerixLog
import com.lerix.sdk.core.LerixRetryDecision
import com.lerix.sdk.core.LerixRuntime
import com.lerix.sdk.core.LerixSender
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A screenshot waiting to be uploaded with a feedback item. */
internal class LerixPendingFile(val bytes: ByteArray, val mime: String, val width: Int?, val height: Int?)

/**
 * Feedback outbox. Attachments are uploaded first and checkpointed (the item
 * stores each returned id and the local file is deleted), then
 * `POST /plugin/feedback` is sent with the item's `clientId`, so a retry after
 * a lost response is deduplicated by the server. Items have no age cap.
 */
internal object LerixFeedbackOutbox {
    private val queue: LerixFileQueue by lazy { LerixFileQueue(LerixRuntime.outboxDir("feedback")) }
    private val filesRoot: File by lazy { LerixRuntime.outboxDir("feedback-files") }
    private val mutex = Mutex()
    private val backoff = LerixBackoff()
    private val serverIds = ConcurrentHashMap<String, String>()
    @Volatile private var retryJob: Job? = null

    fun start() {
        LerixRuntime.scope.launch {
            cleanOrphans()
            delay(5_000)
            process()
        }
    }

    /** Persists the feedback (files first, then the item). */
    fun enqueue(clientId: String, body: JSONObject, files: List<LerixPendingFile>) {
        val dir = File(filesRoot, clientId).apply { mkdirs() }
        val fileEntries = JSONArray()
        files.forEachIndexed { index, f ->
            val ext = when (f.mime) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                else -> "png"
            }
            val name = "$index.$ext"
            File(dir, name).writeBytes(f.bytes)
            fileEntries.put(
                JSONObject().apply {
                    put("name", name)
                    put("mime", f.mime)
                    f.width?.let { put("width", it) }
                    f.height?.let { put("height", it) }
                },
            )
        }
        val item = JSONObject().apply {
            put("clientId", clientId)
            put("body", body)
            put("files", fileEntries)
            put("attachmentIds", JSONArray())
            put("attachmentsStripped", false)
        }
        queue.put(clientId, item.toString())
    }

    fun serverId(clientId: String): String? = serverIds[clientId]

    fun isPending(clientId: String): Boolean = queue.contains(clientId)

    suspend fun process(force: Boolean = false) {
        mutex.withLock {
            for (entry in queue.list()) {
                if (LerixRuntime.hints.isDisabled(LerixFeature.FEEDBACK)) return
                if (!force && !backoff.canAttempt()) {
                    scheduleRetry()
                    return
                }
                val item = queue.read(entry)?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (item == null) {
                    queue.delete(entry.id)
                    continue
                }
                if (!sendItem(entry.id, item)) {
                    backoff.onFailure()
                    scheduleRetry()
                    return
                }
                backoff.onSuccess()
            }
        }
    }

    /** Returns false when the item must be retried later. */
    private suspend fun sendItem(clientId: String, item: JSONObject): Boolean {
        val userId = LerixRuntime.ensureUser() ?: return false
        val dir = File(filesRoot, clientId)

        // 1. Attachments, checkpointed one by one.
        val files = item.optJSONArray("files") ?: JSONArray()
        while (files.length() > 0) {
            val f = files.getJSONObject(0)
            val file = File(dir, f.optString("name"))
            if (!file.exists()) {
                files.remove(0)
                checkpoint(clientId, item)
                continue
            }
            val multipart = LerixMultipart()
            if (f.has("width")) multipart.field("width", f.optInt("width").toString())
            if (f.has("height")) multipart.field("height", f.optInt("height").toString())
            multipart.file("file", file.name, f.optString("mime", "image/png"), file.readBytes())
            val result = LerixSender.send(
                LerixFeature.FEEDBACK,
                LerixRoute.FEEDBACK_ATTACHMENT,
                userId,
                multipart.build(),
                multipart.contentType,
            )
            when (result.decision) {
                LerixRetryDecision.SUCCESS -> {
                    val id = result.response?.json?.optString("id")?.takeIf { it.isNotBlank() }
                    if (id != null) item.getJSONArray("attachmentIds").put(id)
                    files.remove(0)
                    file.delete()
                    checkpoint(clientId, item)
                }
                LerixRetryDecision.DROP -> {
                    // This screenshot will never be accepted; send the feedback without it.
                    LerixLog.debug("screenshot rejected (${result.response?.status}), sending feedback without it")
                    files.remove(0)
                    file.delete()
                    checkpoint(clientId, item)
                }
                LerixRetryDecision.RETRY, LerixRetryDecision.REREGISTER -> return false
            }
        }

        // 2. The feedback itself.
        while (true) {
            val body = JSONObject(item.getJSONObject("body").toString())
            body.put("attachmentIds", item.getJSONArray("attachmentIds"))
            val result = LerixSender.send(
                LerixFeature.FEEDBACK,
                LerixRoute.FEEDBACK,
                userId,
                body.toString().toByteArray(Charsets.UTF_8),
            )
            when (result.decision) {
                LerixRetryDecision.SUCCESS -> {
                    result.response?.json?.optString("id")?.takeIf { it.isNotBlank() }?.let { serverIds[clientId] = it }
                    remove(clientId)
                    return true
                }
                LerixRetryDecision.DROP -> {
                    val ids = item.getJSONArray("attachmentIds")
                    if (ids.length() > 0 && !item.optBoolean("attachmentsStripped")) {
                        // Attachments unlinked for > 24 h are swept by the server, which then
                        // rejects their ids. Keep the text rather than losing the whole item.
                        LerixLog.debug("feedback rejected with attachments, retrying without them")
                        item.put("attachmentIds", JSONArray())
                        item.put("attachmentsStripped", true)
                        checkpoint(clientId, item)
                        continue
                    }
                    LerixLog.debug("feedback dropped: ${result.response?.status} ${result.response?.text?.take(300)}")
                    remove(clientId)
                    return true
                }
                LerixRetryDecision.RETRY, LerixRetryDecision.REREGISTER -> return false
            }
        }
    }

    private fun checkpoint(clientId: String, item: JSONObject) = queue.put(clientId, item.toString())

    private fun remove(clientId: String) {
        queue.delete(clientId)
        File(filesRoot, clientId).deleteRecursively()
    }

    private fun cleanOrphans() {
        try {
            val live = queue.list().map { it.id }.toSet()
            filesRoot.listFiles()?.forEach { if (it.name !in live) it.deleteRecursively() }
        } catch (e: Exception) {
            LerixLog.debug("feedback cleanup failed: $e")
        }
    }

    private fun scheduleRetry() {
        val job = retryJob
        if (job != null && job.isActive) return
        retryJob = LerixRuntime.scope.launch {
            delay(backoff.delayMs().coerceAtLeast(2_000))
            retryJob = null
            process()
        }
    }
}
