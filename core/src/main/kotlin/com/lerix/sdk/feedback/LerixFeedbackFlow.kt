package com.lerix.sdk.feedback

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.lerix.sdk.analytics.LerixAnalytics
import com.lerix.sdk.core.LerixIds
import com.lerix.sdk.core.LerixLifecycle
import com.lerix.sdk.core.LerixLog
import com.lerix.sdk.core.LerixRuntime
import com.lerix.sdk.ui.LerixUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class LerixFeedbackStep { MESSAGE, LABELS, SCREENSHOTS, EMAIL, REVIEW, SUCCESS }

internal class LerixShot(val file: File, val width: Int, val height: Int, val mime: String)

/** In-memory state of one feedback flow. Survives the activity closing while the user captures another screen. */
internal class LerixFeedbackDraft(
    val options: LerixFeedbackOptions,
    val config: LerixFeedbackConfig,
    val accent: Int,
    val callback: ((LerixFeedbackResult) -> Unit)?,
) {
    val clientId: String = LerixIds.newId()
    val dir: File = File(LerixRuntime.context.cacheDir, "lerix-feedback/$clientId").apply { mkdirs() }
    var message: String = ""
    val labels = LinkedHashSet<String>()
    val shots = mutableListOf<LerixShot>()
    var email: String = options.prefillEmail ?: LerixFeedback.userEmail ?: ""
    var step: LerixFeedbackStep = LerixFeedbackStep.MESSAGE
    var annotateIndex: Int? = null
    var detailsOpen = false
    var capturing = false
    var secureScreen = false
    var submittedClientId: String? = null
    /** Copies the draft into the outbox; the draft dir is deleted only after it. */
    var enqueueJob: kotlinx.coroutines.Job? = null
    private var shotCounter = 0

    val emailMode: LerixFieldMode get() = options.email ?: config.email

    val screenshotsEnabled: Boolean
        get() = (options.screenshot ?: config.screenshot) != LerixFieldMode.HIDDEN && config.maxScreenshots > 0

    val visibleLabels: List<LerixFeedbackLabel>
        get() = config.labels.filter { !it.hidden && (options.labels == null || it.key in options.labels) }

    val steps: List<LerixFeedbackStep>
        get() = buildList {
            add(LerixFeedbackStep.MESSAGE)
            if (visibleLabels.isNotEmpty()) add(LerixFeedbackStep.LABELS)
            if (screenshotsEnabled) add(LerixFeedbackStep.SCREENSHOTS)
            if (emailMode != LerixFieldMode.HIDDEN) add(LerixFeedbackStep.EMAIL)
            add(LerixFeedbackStep.REVIEW)
        }

    fun nextShotFile(mime: String): File {
        val ext = if (mime == "image/jpeg") "jpg" else "png"
        return File(dir, "shot-${shotCounter++}.$ext")
    }
}

/** Orchestrates the feedback UI: capture first, then the activity, the capture overlay and submission. */
internal object LerixFeedbackFlow {
    @Volatile var draft: LerixFeedbackDraft? = null
        private set

    private val main = Handler(Looper.getMainLooper())

    fun isActive(): Boolean = draft != null

    fun start(activity: Activity, options: LerixFeedbackOptions, callback: ((LerixFeedbackResult) -> Unit)?) {
        if (draft != null) {
            LerixLog.debug("feedback flow already open")
            callback?.invoke(LerixFeedbackResult(submitted = false))
            return
        }
        val accent = LerixUi.resolveAccent(activity, options.accentColor)
        // Capture BEFORE any SDK UI is on screen.
        LerixScreenshot.capture(activity) { bitmap ->
            val secure = LerixScreenshot.isSecure(activity)
            LerixRuntime.scope.launch {
                val config = LerixFeedback.loadConfig()
                if (!config.enabled) {
                    LerixLog.debug("feedback is disabled for this project")
                    bitmap?.recycle()
                    main.post { callback?.invoke(LerixFeedbackResult(submitted = false)) }
                    return@launch
                }
                val newDraft = LerixFeedbackDraft(options, config, accent, callback)
                newDraft.secureScreen = secure
                if (bitmap != null && newDraft.screenshotsEnabled) {
                    saveShot(newDraft, bitmap)
                } else {
                    bitmap?.recycle()
                }
                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) {
                        val host = LerixLifecycle.currentActivity()
                        if (host == null) {
                            newDraft.dir.deleteRecursively()
                            callback?.invoke(LerixFeedbackResult(submitted = false))
                            return@withContext
                        }
                        draft = newDraft
                        openActivity(host)
                    } else {
                        draft = newDraft
                        openActivity(activity)
                    }
                }
            }
        }
    }

    fun openActivity(from: Activity) {
        from.startActivity(Intent(from, LerixFeedbackActivity::class.java))
        @Suppress("DEPRECATION")
        from.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** Writes the bitmap to the draft dir (call off the main thread). */
    fun saveShot(draft: LerixFeedbackDraft, bitmap: Bitmap): LerixShot {
        val (bytes, mime) = LerixScreenshot.encode(bitmap)
        val file = draft.nextShotFile(mime)
        file.writeBytes(bytes)
        val shot = LerixShot(file, bitmap.width, bitmap.height, mime)
        bitmap.recycle()
        synchronized(draft.shots) {
            if (draft.shots.size < draft.config.maxScreenshots) draft.shots += shot else file.delete()
        }
        return shot
    }

    fun replaceShot(draft: LerixFeedbackDraft, index: Int, bitmap: Bitmap) {
        val old = draft.shots.getOrNull(index) ?: return
        val (bytes, mime) = LerixScreenshot.encode(bitmap)
        val file = draft.nextShotFile(mime)
        file.writeBytes(bytes)
        draft.shots[index] = LerixShot(file, bitmap.width, bitmap.height, mime)
        old.file.delete()
    }

    fun decode(file: File, maxSide: Int = 0): Bitmap? = try {
        if (maxSide <= 0) {
            BitmapFactory.decodeFile(file.absolutePath)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (t: Throwable) {
        null
    }

    /** "Add screenshot": close the flow UI and let the user navigate the app. */
    fun beginCapture(activity: Activity) {
        val d = draft ?: return
        d.capturing = true
        activity.finish()
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        LerixCaptureOverlay.show(
            onCapture = { host -> captureFrom(host) },
            onCancel = { host -> resume(host) },
        )
    }

    private fun captureFrom(host: Activity) {
        val d = draft ?: return
        LerixScreenshot.capture(host) { bitmap ->
            if (bitmap == null) {
                d.secureScreen = LerixScreenshot.isSecure(host)
                resume(host)
                return@capture
            }
            LerixRuntime.scope.launch {
                saveShot(d, bitmap)
                withContext(Dispatchers.Main) {
                    d.annotateIndex = d.shots.size - 1
                    resume(host)
                }
            }
        }
    }

    private fun resume(host: Activity) {
        val d = draft ?: return
        d.capturing = false
        d.step = LerixFeedbackStep.SCREENSHOTS
        openActivity(host)
    }

    /** Builds the request body, persists it to the outbox and starts sending. */
    fun submit(draft: LerixFeedbackDraft) {
        val body = LerixFeedback.buildBody(
            clientId = draft.clientId,
            message = draft.message,
            labels = draft.labels.toList(),
            email = draft.email.takeIf { draft.emailMode != LerixFieldMode.HIDDEN },
            custom = draft.options.custom,
        )
        val shots = draft.shots.toList()
        draft.submittedClientId = draft.clientId
        draft.enqueueJob = LerixRuntime.scope.launch {
            try {
                val files = shots.mapNotNull { shot ->
                    if (!shot.file.exists()) return@mapNotNull null
                    LerixPendingFile(shot.file.readBytes(), shot.mime, shot.width, shot.height)
                }
                LerixFeedbackOutbox.enqueue(draft.clientId, body, files)
            } catch (e: Exception) {
                LerixLog.debug("feedback enqueue failed: $e")
            }
        }
        LerixRuntime.scope.launch {
            draft.enqueueJob?.join()
            LerixFeedbackOutbox.process()
        }
        LerixAnalytics.currentSessionId() // keep the session fresh
    }

    /** Ends the flow (submitted or cancelled) and reports the result once. */
    fun finish(submitted: Boolean) {
        val d = draft ?: return
        draft = null
        LerixCaptureOverlay.hide()
        val clientId = d.submittedClientId
        val result = if (submitted && clientId != null) {
            LerixFeedbackResult(true, LerixFeedbackOutbox.serverId(clientId), clientId)
        } else {
            LerixFeedbackResult(false)
        }
        LerixRuntime.scope.launch {
            d.enqueueJob?.join()
            d.dir.deleteRecursively()
        }
        main.post { d.callback?.invoke(result) }
    }
}
