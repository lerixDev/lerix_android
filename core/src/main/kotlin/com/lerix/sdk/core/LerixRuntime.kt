package com.lerix.sdk.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.DisplayMetrics
import com.lerix.sdk.LerixDeviceInfo
import com.lerix.sdk.LerixInit
import com.lerix.sdk.LerixKeys
import com.lerix.sdk.appcontrol.LerixAppControl
import com.lerix.sdk.models.LerixApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** Shared state for feedback, surveys and analytics. Holds the application context only. */
@SuppressLint("StaticFieldLeak")
internal object LerixRuntime {
    const val SDK_NAME = "lerix-android"
    const val SDK_VERSION = "1.5.0"

    lateinit var context: Context
        private set
    lateinit var store: LerixStore
        private set
    lateinit var hints: LerixServerHints
        private set

    val breadcrumbs = LerixBreadcrumbBuffer()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lerix-io").apply { isDaemon = true }
    }

    /** Serial background dispatcher: outbox file writes and sends run here, in order. */
    val serial = executor.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var environment: String = "prod"
        private set

    @Volatile var initialized = false
        private set

    /**
     * True when an older SDK version already ran on this install (its encrypted
     * prefs, which hold the registered user id, existed before this init), so the
     * missing install marker is written without a `$first_open`.
     */
    @Volatile var legacyInstall = false

    private val userMutex = Mutex()
    private var cachedApp: LerixApp? = null

    fun init(context: Context, environment: String?) {
        if (initialized) {
            LerixEnvironment.normalize(environment)?.let { this.environment = it }
            return
        }
        this.context = context.applicationContext
        val dir = File(this.context.noBackupFilesDir, "lerix")
        store = LerixStore(File(dir, "state.json"), executor)
        hints = LerixServerHints(store, killSwitch = { feature -> LerixAppControl.isModuleDisabled(feature.key) })
        if (environment != null && LerixEnvironment.normalize(environment) == null) {
            LerixLog.debug("environment \"$environment\" is invalid (^[a-z][a-z0-9_-]{1,31}$), auto-detecting")
        }
        this.environment = LerixEnvironment.normalize(environment) ?: LerixEnvironment.detect(this.context)
        initialized = true
    }

    fun outboxDir(name: String): File = File(context.noBackupFilesDir, "lerix/outbox/$name")

    /** Stable per-install id (survey bucketing). Not backed up, so a restored device gets its own. */
    fun installId(): String {
        store.getString("install_id")?.let { return it }
        val id = LerixIds.newId()
        store.putString("install_id", id)
        return id
    }

    /** `Lerix.initialize`'s own ping/registration; awaited so the outbox never registers a second user in parallel. */
    @Volatile var initJob: Job? = null

    /** The registered `app-user` id, registering first if needed. Null when that fails (offline). */
    suspend fun ensureUser(): String? {
        LerixInit.existingUserId()?.let { return it }
        initJob?.let { job -> withTimeoutOrNull(30_000) { job.join() } }
        LerixInit.existingUserId()?.let { return it }
        return userMutex.withLock {
            LerixInit.existingUserId() ?: try {
                LerixInit.registerUser()
            } catch (e: Exception) {
                LerixLog.debug("registration failed: $e")
                null
            }
        }
    }

    /** Handles a 409 / USER_PROJECT_NOT_EXIST for [failedUserId]: register a new install user once. */
    suspend fun reregister(failedUserId: String) {
        userMutex.withLock {
            if (LerixInit.existingUserId() != failedUserId) return
            try {
                LerixInit.replaceStaleUser()
            } catch (e: Exception) {
                LerixLog.debug("re-registration failed: $e")
            }
        }
    }

    fun app(): LerixApp = cachedApp ?: LerixDeviceInfo.collectApp().also { cachedApp = it }

    fun locale(): Locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()

    /** `context` of `POST /plugin/events` (contract §3.1). */
    fun analyticsContext(): JSONObject {
        val app = app()
        val locale = locale()
        return JSONObject().apply {
            put("platform", "android")
            put("os", "Android")
            put("osVersion", Build.VERSION.RELEASE ?: "unknown")
            put("appVersion", app.version ?: "0.0.0")
            put("build", app.buildNo ?: "0")
            put("locale", locale.toLanguageTag())
            put("environment", environment)
            put("sdk", SDK_NAME)
            put("sdkVersion", SDK_VERSION)
            put("deviceModel", Build.MODEL ?: "unknown")
            locale.country.takeIf { it.length == 2 }?.let { put("country", it) }
        }
    }

    /** `device` of `POST /plugin/feedback` (contract §1.3). */
    fun feedbackDevice(): JSONObject {
        val metrics: DisplayMetrics = Resources.getSystem().displayMetrics
        return JSONObject().apply {
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("osName", "Android")
            put("osVersion", Build.VERSION.RELEASE ?: "unknown")
            put("locale", locale().toLanguageTag())
            put("timeZone", TimeZone.getDefault().id)
            put(
                "screen",
                JSONObject().apply {
                    put("width", metrics.widthPixels)
                    put("height", metrics.heightPixels)
                    put("pixelRatio", metrics.density.toDouble())
                },
            )
            put("isPhysical", !LerixEnvironment.isEmulator())
        }
    }

    /** `app` of `POST /plugin/feedback`. */
    fun feedbackApp(): JSONObject {
        val app = app()
        return JSONObject().apply {
            put("name", app.name ?: "unknown")
            put("bundleId", app.packageName ?: context.packageName)
            put("version", app.version ?: "0.0.0")
            put("build", app.buildNo ?: "0")
        }
    }

    fun appUserHeaders(userId: String): Map<String, String> = mapOf("app-user" to userId)

    fun debug(): Boolean = LerixKeys.debug
}
