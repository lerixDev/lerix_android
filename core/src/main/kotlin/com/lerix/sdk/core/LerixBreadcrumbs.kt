package com.lerix.sdk.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One breadcrumb (contract §3.3). [category] is navigation, log, http, ui or custom. */
data class LerixBreadcrumb(
    val ts: Long,
    val category: String,
    val message: String,
    val data: Map<String, Any?>? = null,
)

/** Ring buffer of the last 50 breadcrumbs, newest last. Thread safe. */
internal class LerixBreadcrumbBuffer(private val capacity: Int = 50) {
    private val items = ArrayDeque<LerixBreadcrumb>()

    fun add(message: String, category: String?, data: Map<String, Any?>?, ts: Long = System.currentTimeMillis()) {
        val trimmed = message.trim().take(1024)
        if (trimmed.isEmpty()) return
        val cat = category?.takeIf { it in CATEGORIES } ?: "custom"
        synchronized(items) {
            items.addLast(LerixBreadcrumb(ts, cat, trimmed, data))
            while (items.size > capacity) items.removeFirst()
        }
    }

    fun snapshot(): List<LerixBreadcrumb> = synchronized(items) { items.toList() }

    fun toJson(): JSONArray {
        val array = JSONArray()
        snapshot().forEach { crumb ->
            array.put(
                JSONObject().apply {
                    put("ts", LerixTime.iso(crumb.ts))
                    put("category", crumb.category)
                    put("message", crumb.message)
                    put("data", LerixJson.objectOf(crumb.data ?: emptyMap()))
                },
            )
        }
        return array
    }

    companion object {
        val CATEGORIES = setOf("navigation", "log", "http", "ui", "custom")
    }
}

internal object LerixTime {
    fun iso(ms: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(ms))
    }
}

internal object LerixJson {
    /** Converts a map into a JSONObject, keeping only JSON-serialisable values. */
    fun objectOf(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, wrap(v)) }
        return obj
    }

    fun wrap(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is String, is Boolean, is Int, is Long -> value
        is Double -> if (value.isFinite()) value else JSONObject.NULL
        is Float -> if (value.isFinite()) value.toDouble() else JSONObject.NULL
        is Number -> value.toDouble().takeIf { it.isFinite() } ?: JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> if (k != null) put(k.toString(), wrap(v)) } }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(wrap(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(wrap(it)) } }
        is JSONObject, is JSONArray -> value
        else -> value.toString()
    }
}
