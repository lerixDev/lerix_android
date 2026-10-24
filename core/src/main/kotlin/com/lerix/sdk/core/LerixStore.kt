package com.lerix.sdk.core

import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

/**
 * Small persisted key/value store backed by one JSON file (write-tmp-then-rename).
 *
 * Lives in `noBackupFilesDir`, so install markers, counters and session state
 * are never restored onto another device by Auto Backup. Reads come from
 * memory; writes are coalesced onto [executor]. Pure JVM so it can be unit
 * tested.
 */
internal class LerixStore(private val file: File, private val executor: Executor) {
    private val lock = Any()
    private val values: JSONObject = load()
    private var writeScheduled = false

    private fun load(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    fun getString(key: String): String? = synchronized(lock) {
        if (values.has(key) && !values.isNull(key)) values.optString(key) else null
    }

    fun getLong(key: String): Long? = synchronized(lock) {
        if (values.has(key) && !values.isNull(key)) values.optLong(key) else null
    }

    fun getBoolean(key: String): Boolean? = synchronized(lock) {
        if (values.has(key) && !values.isNull(key)) values.optBoolean(key) else null
    }

    fun putString(key: String, value: String?) = set(key, value)
    fun putLong(key: String, value: Long?) = set(key, value)
    fun putBoolean(key: String, value: Boolean?) = set(key, value)
    fun remove(key: String) = set(key, null)

    /** Removes every key starting with [prefix]. */
    fun removePrefix(prefix: String) {
        synchronized(lock) {
            val keys = values.keys().asSequence().filter { it.startsWith(prefix) }.toList()
            keys.forEach { values.remove(it) }
        }
        scheduleWrite()
    }

    private fun set(key: String, value: Any?) {
        synchronized(lock) {
            if (value == null) values.remove(key) else values.put(key, value)
        }
        scheduleWrite()
    }

    private fun scheduleWrite() {
        synchronized(lock) {
            if (writeScheduled) return
            writeScheduled = true
        }
        executor.execute { writeNow() }
    }

    /** Writes the current values synchronously. */
    fun writeNow() {
        val text = synchronized(lock) {
            writeScheduled = false
            values.toString()
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            LerixLog.debug("state write failed: $e")
        }
    }
}
