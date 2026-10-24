package com.lerix.sdk.appcontrol

import org.json.JSONArray
import org.json.JSONObject

/** A parsed `GET /plugin/app-config` body. Every part is optional (defensive parsing, contract §2.2). */
internal class LerixAppConfig(
    val etag: String?,
    val serverTime: Long?,
    val ttlSeconds: Int,
    val update: LerixAppUpdate?,
    val maintenance: LerixMaintenance?,
    val sdk: LerixSdkControl?,
)

/**
 * The persisted cache (contract §2.2): the raw body plus when it was fetched
 * (device clock), the server clock offset, and when the server last confirmed
 * it (a 200 or a 304; drives `ttlSeconds`).
 */
internal class LerixAppControlCache(
    val body: String,
    val config: LerixAppConfig,
    val etag: String?,
    val fetchedAt: Long,
    val clockOffsetMs: Long,
    val lastFetchAt: Long,
    /** The running app's version / build and the SDK version this response was fetched for. */
    val appVersion: String? = null,
    val build: String? = null,
    val sdkVersion: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("body", body)
        etag?.let { put("etag", it) }
        put("fetchedAt", fetchedAt)
        put("clockOffsetMs", clockOffsetMs)
        put("lastFetchAt", lastFetchAt)
        appVersion?.let { put("appVersion", it) }
        build?.let { put("build", it) }
        sdkVersion?.let { put("sdkVersion", it) }
    }.toString()

    fun confirmed(at: Long) =
        LerixAppControlCache(body, config, etag, at, clockOffsetMs, at, appVersion, build, sdkVersion)

    companion object {
        fun fromJson(raw: String?): LerixAppControlCache? = if (raw.isNullOrEmpty()) null else try {
            val o = JSONObject(raw)
            val body = o.optString("body", "")
            val config = LerixAppConfigParser.parse(body)
            if (config == null) {
                null
            } else {
                LerixAppControlCache(
                    body = body,
                    config = config,
                    etag = (o.opt("etag") as? String)?.takeIf { it.isNotEmpty() } ?: config.etag,
                    fetchedAt = o.optLong("fetchedAt", 0L),
                    clockOffsetMs = o.optLong("clockOffsetMs", 0L),
                    lastFetchAt = o.optLong("lastFetchAt", 0L),
                    appVersion = (o.opt("appVersion") as? String)?.takeIf { it.isNotEmpty() },
                    build = (o.opt("build") as? String)?.takeIf { it.isNotEmpty() },
                    sdkVersion = (o.opt("sdkVersion") as? String)?.takeIf { it.isNotEmpty() },
                )
            }
        } catch (t: Throwable) {
            null
        }
    }
}

/** "Later" / "Skip this version", persisted per `latestVersion` (contract §2.3). */
internal data class LerixAppControlPrefs(
    val laterKey: String? = null,
    val laterUntil: Long? = null,
    val skipKey: String? = null,
)

internal class LerixAppControlEvaluation(
    val state: LerixAppControlState,
    /** Device time of the next time-based change (maintenance start/end, `until`, reminder, deadline), if any. */
    val nextChangeAt: Long?,
)

/** Which built-in UI the state calls for (contract §2.3 precedence). */
internal enum class LerixAppControlScreen { NONE, SOFT_UPDATE, REQUIRED_UPDATE, MAINTENANCE }

/** Defensive parser: unknown fields are ignored; a wrong type or unknown enum drops that part only. Never throws. */
internal object LerixAppConfigParser {
    const val DEFAULT_TTL_SECONDS = 3600
    const val MIN_TTL_SECONDS = 10
    const val MAX_TTL_SECONDS = 86_400
    const val DEFAULT_REMIND_HOURS = 72

    val UPDATE_REASONS = setOf("outdated", "below_minimum", "blocked_version", "deadline_passed")
    val SDK_MODULES = setOf("all", "errors", "analytics", "tracing", "feedback", "surveys", "push")

    fun parse(text: String?): LerixAppConfig? {
        if (text.isNullOrBlank()) return null
        val json = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return null
        }
        return parse(json)
    }

    fun parse(json: JSONObject): LerixAppConfig = LerixAppConfig(
        etag = safe { string(json, "etag") },
        serverTime = safe { time(json, "serverTime") },
        ttlSeconds = safe { int(json, "ttlSeconds") }?.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS) ?: DEFAULT_TTL_SECONDS,
        update = safe { update(json.opt("update")) },
        maintenance = safe { maintenance(json.opt("maintenance")) },
        sdk = safe { sdk(json.opt("sdk")) },
    )

    private fun update(value: Any?): LerixAppUpdate? {
        val o = value as? JSONObject ?: return null
        val state = LerixUpdateState.from(o.opt("state") as? String) ?: return null
        return LerixAppUpdate(
            state = state,
            reason = string(o, "reason")?.takeIf { it in UPDATE_REASONS },
            ruleId = string(o, "ruleId"),
            latestVersion = string(o, "latestVersion"),
            minVersion = string(o, "minVersion"),
            storeUrl = string(o, "storeUrl")?.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("market://") },
            title = string(o, "title"),
            message = string(o, "message"),
            releaseNotes = string(o, "releaseNotes"),
            remindAfterHours = int(o, "remindAfterHours")?.takeIf { it > 0 }?.coerceAtMost(24 * 365) ?: DEFAULT_REMIND_HOURS,
            // Only an `available` update can be skipped (recommended/required never).
            allowSkip = state == LerixUpdateState.AVAILABLE && bool(o, "allowSkip") == true,
            requiredAt = time(o, "requiredAt"),
        )
    }

    private fun maintenance(value: Any?): LerixMaintenance? {
        val o = value as? JSONObject ?: return null
        // Without a valid endsAt it could never lift offline: fail open.
        val endsAt = time(o, "endsAt") ?: return null
        val startsAt = time(o, "startsAt") ?: Long.MIN_VALUE
        if (endsAt <= startsAt) return null
        return LerixMaintenance(
            id = string(o, "id") ?: "",
            title = string(o, "title"),
            message = string(o, "message"),
            imageUrl = string(o, "imageUrl"),
            startsAt = startsAt,
            endsAt = endsAt,
            showEndsAt = bool(o, "showEndsAt") ?: true,
        )
    }

    private fun sdk(value: Any?): LerixSdkControl? {
        val o = value as? JSONObject ?: return null
        val until = time(o, "until") ?: return null
        val array = o.opt("disabled") as? JSONArray ?: return null
        val modules = (0 until array.length())
            .mapNotNull { array.opt(it) as? String }
            .filter { it in SDK_MODULES }
            .toSet()
        if (modules.isEmpty()) return null
        return LerixSdkControl(modules, until)
    }

    private fun string(o: JSONObject, key: String): String? = (o.opt(key) as? String)?.takeIf { it.isNotBlank() }

    private fun int(o: JSONObject, key: String): Int? {
        val n = o.opt(key) as? Number ?: return null
        val d = n.toDouble()
        if (d.isNaN() || d.isInfinite()) return null
        return d.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
    }

    private fun bool(o: JSONObject, key: String): Boolean? = o.opt(key) as? Boolean

    private fun time(o: JSONObject, key: String): Long? = LerixIsoTime.parse(o.opt(key) as? String)

    private inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (t: Throwable) {
        null
    }
}

/** ISO-8601 parsing without java.time (minSdk 24, no desugaring). A missing zone is treated as UTC. */
internal object LerixIsoTime {
    private val ISO = Regex(
        """^(\d{4})-(\d{2})-(\d{2})(?:[Tt ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?)?\s*(Z|z|[+-]\d{2}(?::?\d{2})?)?$""",
    )

    fun parse(value: String?): Long? {
        val m = ISO.matchEntire(value?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].ifEmpty { "0" }.toInt()
        val minute = g[5].ifEmpty { "0" }.toInt()
        val second = g[6].ifEmpty { "0" }.toInt()
        val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toInt() ?: 0
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
        val zone = g[8]
        val offsetMinutes = when {
            zone.isEmpty() || zone.equals("z", ignoreCase = true) -> 0
            else -> {
                val sign = if (zone[0] == '-') -1 else 1
                val digits = zone.substring(1).replace(":", "")
                val h = digits.take(2).toInt()
                val mm = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
                if (h > 23 || mm > 59) return null
                sign * (h * 60 + mm)
            }
        }
        val days = daysFromCivil(year, month, day)
        return ((days * 86_400L + hour * 3600L + minute * 60L + second) - offsetMinutes * 60L) * 1000L + millis
    }

    /** Days since 1970-01-01 (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}

/** Time-based evaluation of the cache (contract §2.2 table) and the E6 gate (§2.5). Pure. */
internal object LerixAppControlLogic {
    const val SOFT_MAX_AGE_MS = 7 * 86_400_000L
    const val MODULE_ALL = "all"

    /** A module only `all` blocks: init/ping, register-user, identify, logout, delete-user. */
    const val MODULE_CORE = "core"

    fun promptKey(update: LerixAppUpdate): String = update.latestVersion ?: update.ruleId ?: ""

    fun evaluate(
        cache: LerixAppControlCache?,
        now: Long,
        prefs: LerixAppControlPrefs,
        enabled: Boolean = true,
    ): LerixAppControlEvaluation {
        if (cache == null) return LerixAppControlEvaluation(LerixAppControlState.EMPTY, null)
        val offset = cache.clockOffsetMs
        val serverNow = now + offset
        val next = mutableListOf<Long>()
        fun at(serverInstant: Long) {
            val device = serverInstant - offset
            if (device > now) next += device
        }

        val sdk = cache.config.sdk?.takeIf { serverNow < it.until }
        sdk?.let { at(it.until) }

        var maintenance: LerixMaintenance? = null
        var update: LerixAppUpdate? = null
        if (enabled) {
            cache.config.maintenance?.let { m ->
                when {
                    serverNow < m.startsAt -> at(m.startsAt)
                    serverNow < m.endsAt -> {
                        maintenance = m
                        at(m.endsAt)
                    }
                }
            }
            update = evaluateUpdate(cache, now, serverNow, prefs, ::at) { next += it }
        }
        return LerixAppControlEvaluation(
            LerixAppControlState(update, maintenance, sdk),
            next.minOrNull(),
        )
    }

    private fun evaluateUpdate(
        cache: LerixAppControlCache,
        now: Long,
        serverNow: Long,
        prefs: LerixAppControlPrefs,
        atServer: (Long) -> Unit,
        atDevice: (Long) -> Unit,
    ): LerixAppUpdate? {
        val raw = cache.config.update ?: return null
        // Required: kept from cache however old; only a successful fetch lifts it.
        if (raw.state == LerixUpdateState.REQUIRED) return raw
        // A recommended update whose deadline passed escalates like the server would (§1.4 step 4).
        val requiredAt = raw.requiredAt
        if (requiredAt != null) {
            if (serverNow >= requiredAt) {
                return raw.copy(state = LerixUpdateState.REQUIRED, reason = "deadline_passed", allowSkip = false)
            }
            atServer(requiredAt)
        }
        // Soft prompts are dropped 7 days after the server last confirmed them.
        val expiresAt = cache.fetchedAt + SOFT_MAX_AGE_MS
        if (now >= expiresAt) return null
        atDevice(expiresAt)

        val key = promptKey(raw)
        if (raw.state == LerixUpdateState.AVAILABLE && raw.allowSkip && prefs.skipKey == key) return null
        val laterUntil = prefs.laterUntil
        if (prefs.laterKey == key && laterUntil != null && now < laterUntil) {
            atDevice(laterUntil)
            return null
        }
        return raw
    }

    /**
     * Whether a request to start the blocking screen is a duplicate to drop: the same activity asked again within
     * [guardMs] (its resume and a state change arrive together). Another activity asking means it came up over the
     * screen, e.g. a splash that starts its main activity right away, so it is always covered again.
     */
    fun isDuplicateLaunch(sameActivity: Boolean, sinceLastLaunchMs: Long, guardMs: Long): Boolean =
        sameActivity && sinceLastLaunchMs in 0 until guardMs

    fun screenFor(state: LerixAppControlState): LerixAppControlScreen = when {
        state.maintenance != null -> LerixAppControlScreen.MAINTENANCE
        state.update?.state == LerixUpdateState.REQUIRED -> LerixAppControlScreen.REQUIRED_UPDATE
        state.update != null -> LerixAppControlScreen.SOFT_UPDATE
        else -> LerixAppControlScreen.NONE
    }

    /** Disabled modules at [serverNow] (empty when nothing is switched off). */
    fun disabledModules(sdk: LerixSdkControl?, serverNow: Long): Set<String> =
        if (sdk == null || serverNow >= sdk.until) emptySet() else sdk.disabled

    /**
     * Whether a request to [path] is blocked. `app-config` is never blocked, so
     * the switch can be lifted; everything else is blocked by `all` or its module.
     */
    fun isPathBlocked(sdk: LerixSdkControl?, serverNow: Long, path: String): Boolean {
        val module = moduleFor(path) ?: return false
        return isModuleBlocked(sdk, serverNow, module)
    }

    fun isModuleBlocked(sdk: LerixSdkControl?, serverNow: Long, module: String): Boolean {
        val disabled = disabledModules(sdk, serverNow)
        if (disabled.isEmpty()) return false
        return MODULE_ALL in disabled || module in disabled
    }

    /** The E6 module a backend path belongs to; null = never blocked. */
    fun moduleFor(path: String): String? {
        val p = path.trimStart('/').substringBefore('?')
        return when {
            p.startsWith("plugin/app-config") -> null
            p.startsWith("plugin/bugs") || p.startsWith("plugin/sourcemaps") -> "errors"
            p.startsWith("plugin/events") -> "analytics"
            p.startsWith("plugin/traces") || p.startsWith("plugin/tracing") -> "tracing"
            p.startsWith("plugin/feedback") -> "feedback"
            p.startsWith("plugin/surveys") -> "surveys"
            p.startsWith("plugin/notifications") -> "push"
            else -> MODULE_CORE
        }
    }

    /**
     * A cache fetched for another app version/build has no valid `update`
     * (a cached `required` must not block the already-updated app offline);
     * one fetched for another SDK version has no valid `sdk` block. The etag
     * is dropped too, so the next fetch is a full 200 (no `If-None-Match`).
     */
    fun forRunningApp(cache: LerixAppControlCache?, appVersion: String?, build: String?, sdkVersion: String?): LerixAppControlCache? {
        if (cache == null) return null
        val appChanged = cache.appVersion != appVersion || cache.build != build
        val sdkChanged = cache.sdkVersion != sdkVersion
        val dropUpdate = appChanged && cache.config.update != null
        val dropSdk = sdkChanged && cache.config.sdk != null
        if (!appChanged && !sdkChanged) return cache
        val c = cache.config
        val config = if (dropUpdate || dropSdk) {
            LerixAppConfig(
                etag = c.etag,
                serverTime = c.serverTime,
                ttlSeconds = c.ttlSeconds,
                update = if (dropUpdate) null else c.update,
                maintenance = c.maintenance,
                sdk = if (dropSdk) null else c.sdk,
            )
        } else {
            c
        }
        return LerixAppControlCache(
            cache.body, config, null, cache.fetchedAt, cache.clockOffsetMs, cache.lastFetchAt,
            cache.appVersion, cache.build, cache.sdkVersion,
        )
    }

    /**
     * Applies a fetch result to the cache (contract §2.1): 200 replaces it and
     * records the clock offset, 304 keeps it and refreshes `fetchedAt` /
     * `lastFetch`, anything else (or an unreadable body) keeps it unchanged.
     * [status] null = network failure.
     */
    fun applyResponse(
        previous: LerixAppControlCache?,
        status: Int?,
        body: String?,
        sentAt: Long,
        receivedAt: Long,
        appVersion: String? = null,
        build: String? = null,
        sdkVersion: String? = null,
    ): LerixAppControlCache? = when {
        status == 304 -> previous?.confirmed(receivedAt)
        status != null && status in 200..299 -> {
            val config = LerixAppConfigParser.parse(body)
            if (config == null) {
                previous
            } else {
                // Midpoint of the request: half the round trip is the best estimate of when serverTime was stamped.
                val offset = config.serverTime?.let { it - (sentAt + (receivedAt - sentAt) / 2) }
                    ?: previous?.clockOffsetMs
                    ?: 0L
                LerixAppControlCache(body!!, config, config.etag, receivedAt, offset, receivedAt, appVersion, build, sdkVersion)
            }
        }
        else -> previous
    }
}
