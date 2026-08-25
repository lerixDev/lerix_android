package com.lerix.sdk.models

import org.json.JSONObject

enum class BugType(val raw: String) {
    RUNTIME_ERROR("runtime_error"),
    LOGIC_BUG("logic_bug"),
    UI_BUG("ui_bug"),
    NETWORK_ERROR("network_error"),
    PERFORMANCE("performance"),
    COMPATIBILITY("compatibility"),
    VALIDATION_ERROR("validation_error"),
    SECURITY("security"),
    CRASH("crash"),
    UNKNOWN("unknown"),
}

enum class BugSeverity(val raw: String) {
    CRITICAL("critical"),
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    UNKNOWN("unknown"),
}

data class LerixDevice(
    val deviceName: String?,
    val arc: String?,
    val osName: String?,
    val osVersion: String?,
    val timeZone: String?,
    val countryCode: String?,
) {
    fun toJson(): String = JSONObject().apply {
        put("deviceName", deviceName ?: JSONObject.NULL)
        put("arc", arc ?: JSONObject.NULL)
        put("osName", osName ?: JSONObject.NULL)
        put("osVersion", osVersion ?: JSONObject.NULL)
        put("timeZone", timeZone ?: JSONObject.NULL)
        put("countryCode", countryCode ?: JSONObject.NULL)
    }.toString()
}

data class LerixApp(
    val name: String?,
    val packageName: String?,
    val version: String?,
    val buildNo: String?,
) {
    fun toJson(): String = JSONObject().apply {
        put("name", name ?: JSONObject.NULL)
        put("package", packageName ?: JSONObject.NULL)
        put("version", version ?: JSONObject.NULL)
        put("buildNo", buildNo ?: JSONObject.NULL)
    }.toString()
}

data class LerixPingConfig(
    val id: String?,
    val appId: String?,
    val platform: String?,
    val os: String?,
    val projectSlug: String?,
    val status: String?,
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id ?: JSONObject.NULL)
        put("appID", appId ?: JSONObject.NULL)
        put("platform", platform ?: JSONObject.NULL)
        put("os", os ?: JSONObject.NULL)
        put("projectSlug", projectSlug ?: JSONObject.NULL)
        put("status", status ?: JSONObject.NULL)
    }.toString()

    companion object {
        fun fromJson(json: JSONObject): LerixPingConfig = LerixPingConfig(
            id = json.optStringOrNull("id"),
            appId = json.optStringOrNull("appID"),
            platform = json.optStringOrNull("platform"),
            os = json.optStringOrNull("os"),
            projectSlug = json.optStringOrNull("projectSlug"),
            status = json.optStringOrNull("status"),
        )

        fun fromJsonString(raw: String): LerixPingConfig = fromJson(JSONObject(raw))
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
