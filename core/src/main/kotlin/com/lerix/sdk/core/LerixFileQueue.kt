package com.lerix.sdk.core

import java.io.File

/**
 * Durable outbox: one JSON file per item, written to a `.tmp` file and renamed
 * into place so a crash mid-write never leaves a half item behind.
 *
 * File names are `<createdAt, 13 digits>-<id>.json`, so a directory listing
 * sorts oldest first. [put] with an existing id overwrites that item in place
 * (keeping its position), which is how feedback checkpoints uploaded
 * attachments and survey responses merge their updates. Pure JVM.
 */
internal class LerixFileQueue(val dir: File) {
    data class Entry(val id: String, val createdAt: Long, val file: File) {
        val size: Long get() = file.length()
    }

    private val lock = Any()

    init {
        dir.mkdirs()
        // Leftovers from a write interrupted by a crash.
        dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    fun put(id: String, content: String, createdAt: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            dir.mkdirs()
            val target = find(id) ?: File(dir, fileName(createdAt, id))
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }
    }

    fun read(id: String): String? = synchronized(lock) {
        find(id)?.let { runCatching { it.readText() }.getOrNull() }
    }

    fun read(entry: Entry): String? = synchronized(lock) {
        runCatching { entry.file.readText() }.getOrNull()
    }

    fun delete(id: String) {
        synchronized(lock) { find(id)?.delete() }
    }

    fun contains(id: String): Boolean = synchronized(lock) { find(id) != null }

    /** Items, oldest first. */
    fun list(): List<Entry> = synchronized(lock) {
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { parse(it) }
            .sortedWith(compareBy<Entry> { it.createdAt }.thenBy { it.file.name })
    }

    fun count(): Int = list().size

    fun clear() {
        synchronized(lock) { dir.listFiles()?.forEach { it.delete() } }
    }

    /**
     * Enforces the local cap: drops items older than [maxAgeMs], then the
     * oldest items until the total size is at most [maxBytes]. Returns the
     * number of items dropped.
     */
    fun trim(maxBytes: Long, maxAgeMs: Long, now: Long): Int {
        synchronized(lock) {
            var dropped = 0
            val entries = list().toMutableList()
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.createdAt > maxAgeMs) {
                    entry.file.delete()
                    iterator.remove()
                    dropped++
                }
            }
            var total = entries.sumOf { it.size }
            for (entry in entries) {
                if (total <= maxBytes) break
                total -= entry.size
                entry.file.delete()
                dropped++
            }
            return dropped
        }
    }

    private fun find(id: String): File? =
        dir.listFiles()?.firstOrNull { it.name.endsWith("-$id.json") }

    private fun parse(file: File): Entry? {
        val name = file.name.removeSuffix(".json")
        val dash = name.indexOf('-')
        if (dash <= 0) return null
        val createdAt = name.substring(0, dash).toLongOrNull() ?: return null
        return Entry(name.substring(dash + 1), createdAt, file)
    }

    companion object {
        fun fileName(createdAt: Long, id: String): String =
            "${createdAt.coerceAtLeast(0).toString().padStart(13, '0')}-$id.json"
    }
}
