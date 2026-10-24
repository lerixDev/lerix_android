package com.lerix.sdk.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LerixOutboxStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun listsOldestFirstAndOverwritesInPlace() {
        val q = LerixFileQueue(tmp.newFolder("q"))
        q.put("b", "{\"v\":1}", createdAt = 2_000)
        q.put("a", "{\"v\":1}", createdAt = 1_000)
        q.put("c", "{\"v\":1}", createdAt = 3_000)
        assertEquals(listOf("a", "b", "c"), q.list().map { it.id })
        // Updating keeps the original position (checkpointing).
        q.put("a", "{\"v\":2}", createdAt = 9_000)
        assertEquals(listOf("a", "b", "c"), q.list().map { it.id })
        assertEquals("{\"v\":2}", q.read("a"))
        q.delete("b")
        assertFalse(q.contains("b"))
        assertEquals(2, q.count())
    }

    @Test
    fun noTmpFilesAreLeftBehind() {
        val dir = tmp.newFolder("q2")
        File(dir, "0000000000001-x.json.tmp").writeText("half")
        val q = LerixFileQueue(dir)
        q.put("id-1", "{}")
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertEquals(listOf("id-1"), q.list().map { it.id })
    }

    @Test
    fun trimDropsOldThenOldestOverSize() {
        val q = LerixFileQueue(tmp.newFolder("q3"))
        val now = 10 * 86_400_000L
        q.put("old", "x".repeat(10), createdAt = now - 8 * 86_400_000L)
        q.put("e1", "x".repeat(100), createdAt = now - 3)
        q.put("e2", "x".repeat(100), createdAt = now - 2)
        q.put("e3", "x".repeat(100), createdAt = now - 1)
        val dropped = q.trim(maxBytes = 250, maxAgeMs = 7 * 86_400_000L, now = now)
        assertEquals(2, dropped)
        assertEquals(listOf("e2", "e3"), q.list().map { it.id })
    }

    @Test
    fun storePersistsAcrossInstances() {
        val file = File(tmp.root, "state.json")
        val direct = java.util.concurrent.Executor { it.run() }
        val store = LerixStore(file, direct)
        store.putLong("app_starts", 3)
        store.putString("install_id", "abc")
        store.putBoolean("flag", true)
        store.remove("flag")
        val reloaded = LerixStore(file, direct)
        assertEquals(3L, reloaded.getLong("app_starts"))
        assertEquals("abc", reloaded.getString("install_id"))
        assertNull(reloaded.getBoolean("flag"))
    }

    @Test
    fun launchBookkeeping() {
        val direct = java.util.concurrent.Executor { it.run() }
        val store = LerixStore(File(tmp.root, "launch.json"), direct)
        LerixLaunch.record(store, 1_000, "1.0.0", existingInstall = false)
        assertEquals(1L, LerixLaunch.appStarts(store))
        assertEquals(1_000L, LerixLaunch.firstStartAt(store, 5_000))
        assertEquals(true, store.getBoolean(LerixLaunch.PENDING_FIRST_OPEN))
        assertNull(store.getString(LerixLaunch.PENDING_UPDATE_FROM))

        store.remove(LerixLaunch.PENDING_FIRST_OPEN)
        LerixLaunch.record(store, 2_000, "1.1.0", existingInstall = false)
        assertEquals(2L, LerixLaunch.appStarts(store))
        assertEquals(1_000L, LerixLaunch.firstStartAt(store, 5_000))
        assertNull(store.getBoolean(LerixLaunch.PENDING_FIRST_OPEN))
        assertEquals("1.0.0", store.getString(LerixLaunch.PENDING_UPDATE_FROM))
        assertEquals("1.1.0", store.getString(LerixLaunch.PENDING_UPDATE_TO))
    }

    @Test
    fun upgradeFromOlderSdkIsNotAFirstOpen() {
        val direct = java.util.concurrent.Executor { it.run() }
        val store = LerixStore(File(tmp.root, "legacy.json"), direct)
        LerixLaunch.record(store, 1_000, "2.0.0", existingInstall = true)
        assertNull(store.getBoolean(LerixLaunch.PENDING_FIRST_OPEN))
    }

    @Test
    fun sdkDisabledPausesOnlyThatFeature() {
        var now = 1_000_000L
        val direct = java.util.concurrent.Executor { it.run() }
        val hints = LerixServerHints(LerixStore(File(tmp.root, "hints.json"), direct)) { now }
        val body = JSONObject().put("code", "SDK_DISABLED").put("retryAfterSeconds", 120)
        assertTrue(hints.handle(LerixFeature.ANALYTICS, 403, body))
        assertTrue(hints.isDisabled(LerixFeature.ANALYTICS))
        assertFalse(hints.isDisabled(LerixFeature.FEEDBACK))
        assertFalse(hints.handle(LerixFeature.FEEDBACK, 403, JSONObject().put("code", "FORBIDDEN")))
        // ResponseErrors shape ({error: CODE}) is recognised too.
        assertTrue(hints.handle(LerixFeature.SURVEYS, 403, JSONObject().put("error", "SDK_DISABLED")))
        assertTrue(hints.isDisabled(LerixFeature.SURVEYS))
        now += 121_000
        assertFalse(hints.isDisabled(LerixFeature.ANALYTICS))
    }

    @Test
    fun breadcrumbRingBufferKeepsNewest50() {
        val buffer = LerixBreadcrumbBuffer()
        for (i in 1..60) buffer.add("crumb $i", if (i % 2 == 0) "navigation" else "bogus", null, ts = i.toLong())
        val items = buffer.snapshot()
        assertEquals(50, items.size)
        assertEquals("crumb 11", items.first().message)
        assertEquals("crumb 60", items.last().message)
        assertEquals("custom", items.first().category)
        assertEquals("navigation", items.last().category)
        buffer.add("   ", null, null)
        assertEquals(50, buffer.snapshot().size)
    }
}
