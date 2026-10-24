package com.lerix.sdk.appcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LerixAppControlLogicTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun cache(body: String, fetchedAt: Long = 0L, offset: Long = 0L): LerixAppControlCache =
        LerixAppControlCache(body, LerixAppConfigParser.parse(body)!!, null, fetchedAt, offset, fetchedAt)

    private fun update(state: String, extra: String = "") =
        """{"update":{"state":"$state","latestVersion":"3.4.0","ruleId":"r1","remindAfterHours":72,"allowSkip":true$extra}}"""

    private val none = LerixAppControlPrefs()

    // ---- §2.2 offline table ----

    @Test
    fun requiredUpdateKeepsBlockingFromAnOldCache() {
        val c = cache(update("required"), fetchedAt = 0L)
        val s = LerixAppControlLogic.evaluate(c, now = 90 * day, prefs = none).state
        assertEquals(LerixUpdateState.REQUIRED, s.update!!.state)
        assertTrue(s.isBlocking)
    }

    @Test
    fun requiredIgnoresLaterAndSkip() {
        val c = cache(update("required"))
        val prefs = LerixAppControlPrefs(laterKey = "3.4.0", laterUntil = Long.MAX_VALUE, skipKey = "3.4.0")
        assertNotNull(LerixAppControlLogic.evaluate(c, now = 1L, prefs = prefs).state.update)
    }

    @Test
    fun softUpdateIsShownFromCacheThenDroppedAfterSevenDays() {
        val fetchedAt = 1_000L
        val c = cache(update("available"), fetchedAt = fetchedAt)
        val fresh = LerixAppControlLogic.evaluate(c, now = fetchedAt + 6 * day, prefs = none)
        assertNotNull(fresh.state.update)
        assertEquals(fetchedAt + 7 * day, fresh.nextChangeAt)
        assertNull(LerixAppControlLogic.evaluate(c, now = fetchedAt + 7 * day, prefs = none).state.update)
        assertNull(
            LerixAppControlLogic.evaluate(cache(update("recommended"), fetchedAt), now = fetchedAt + 8 * day, prefs = none).state.update,
        )
    }

    @Test
    fun maintenanceLiftsAtEndsAtOfflineUsingTheClockOffset() {
        // Device clock 1 h behind the server: offset = serverTime - deviceNow = +1 h.
        val body = """{"maintenance":{"id":"m1","startsAt":"2026-10-23T10:00:00Z","endsAt":"2026-10-23T14:00:00Z"}}"""
        val c = cache(body, offset = hour)
        val endsAt = ms("2026-10-23T14:00:00Z")

        val active = LerixAppControlLogic.evaluate(c, now = ms("2026-10-23T12:30:00Z"), prefs = none)
        assertEquals("m1", active.state.maintenance!!.id)
        assertTrue(active.state.isBlocking)
        assertEquals("next change = endsAt on the device clock", endsAt - hour, active.nextChangeAt)

        // Device 12:59:59 = server 13:59:59: still on. Device 13:00 = server 14:00: lifted, with no network.
        assertNotNull(LerixAppControlLogic.evaluate(c, now = endsAt - hour - 1, prefs = none).state.maintenance)
        val lifted = LerixAppControlLogic.evaluate(c, now = endsAt - hour, prefs = none)
        assertNull(lifted.state.maintenance)
        assertFalse(lifted.state.isBlocking)
        assertNull(lifted.nextChangeAt)
    }

    @Test
    fun scheduledMaintenanceStartsAtStartsAt() {
        val body = """{"maintenance":{"id":"m2","startsAt":"2026-10-23T10:00:00Z","endsAt":"2026-10-23T14:00:00Z"}}"""
        val offset = -30 * 60_000L // device clock 30 min ahead
        val c = cache(body, offset = offset)
        val startsAt = ms("2026-10-23T10:00:00Z")
        val before = LerixAppControlLogic.evaluate(c, now = startsAt - 2 * hour, prefs = none)
        assertNull(before.state.maintenance)
        assertEquals(startsAt - offset, before.nextChangeAt)
        assertNotNull(LerixAppControlLogic.evaluate(c, now = startsAt - offset, prefs = none).state.maintenance)
        assertNull(LerixAppControlLogic.evaluate(c, now = startsAt - offset - 1, prefs = none).state.maintenance)
    }

    @Test
    fun sdkDisabledAppliesUntilUntil() {
        val c = cache("""{"sdk":{"disabled":["all"],"until":"2026-10-24T00:00:00Z"}}""", offset = hour)
        val until = ms("2026-10-24T00:00:00Z")
        val on = LerixAppControlLogic.evaluate(c, now = until - hour - 1, prefs = none)
        assertEquals(setOf("all"), on.state.sdk!!.disabled)
        assertEquals(until - hour, on.nextChangeAt)
        assertNull(LerixAppControlLogic.evaluate(c, now = until - hour, prefs = none).state.sdk)
    }

    // ---- remind / skip ----

    @Test
    fun laterHidesUntilTheReminderForTheSameVersion() {
        val c = cache(update("recommended"), fetchedAt = 0L)
        val prefs = LerixAppControlPrefs(laterKey = "3.4.0", laterUntil = 72 * hour)
        val hidden = LerixAppControlLogic.evaluate(c, now = 10 * hour, prefs = prefs)
        assertNull(hidden.state.update)
        assertEquals(72 * hour, hidden.nextChangeAt)
        assertNotNull(LerixAppControlLogic.evaluate(c, now = 72 * hour, prefs = prefs).state.update)
        // A newer latestVersion is shown again right away.
        val newer = cache(update("recommended").replace("3.4.0", "3.5.0"), fetchedAt = 0L)
        assertNotNull(LerixAppControlLogic.evaluate(newer, now = 10 * hour, prefs = prefs).state.update)
    }

    @Test
    fun skipHidesAvailableUntilLatestVersionChanges() {
        val prefs = LerixAppControlPrefs(skipKey = "3.4.0")
        assertNull(LerixAppControlLogic.evaluate(cache(update("available")), now = 1L, prefs = prefs).state.update)
        val newer = cache(update("available").replace("3.4.0", "3.5.0"))
        assertNotNull(LerixAppControlLogic.evaluate(newer, now = 1L, prefs = prefs).state.update)
        // Recommended (and anything without allowSkip) is never skippable.
        assertNotNull(LerixAppControlLogic.evaluate(cache(update("recommended")), now = 1L, prefs = prefs).state.update)
        val noSkip = cache(update("available").replace("\"allowSkip\":true", "\"allowSkip\":false"))
        assertNotNull(LerixAppControlLogic.evaluate(noSkip, now = 1L, prefs = prefs).state.update)
    }

    @Test
    fun promptKeyFallsBackToRuleId() {
        val u = LerixAppConfigParser.parse("""{"update":{"state":"available","ruleId":"r9"}}""")!!.update!!
        assertEquals("r9", LerixAppControlLogic.promptKey(u))
    }

    @Test
    fun recommendedEscalatesToRequiredAtItsDeadline() {
        val c = cache(update("recommended", ""","requiredAt":"2026-11-01T00:00:00Z""""), fetchedAt = ms("2026-10-30T00:00:00Z"))
        val deadline = ms("2026-11-01T00:00:00Z")
        val before = LerixAppControlLogic.evaluate(c, now = deadline - 1, prefs = LerixAppControlPrefs(laterKey = "3.4.0", laterUntil = Long.MAX_VALUE))
        assertNull("still snoozed before the deadline", before.state.update)
        val after = LerixAppControlLogic.evaluate(c, now = deadline, prefs = LerixAppControlPrefs(laterKey = "3.4.0", laterUntil = Long.MAX_VALUE))
        assertEquals(LerixUpdateState.REQUIRED, after.state.update!!.state)
        assertEquals("deadline_passed", after.state.update!!.reason)
        assertFalse(after.state.update!!.allowSkip)
        val pending = LerixAppControlLogic.evaluate(c, now = deadline - day, prefs = none)
        assertEquals(deadline, pending.nextChangeAt)
    }

    // ---- precedence ----

    @Test
    fun maintenanceBeatsRequiredBeatsSoft() {
        val m = """"maintenance":{"id":"m","startsAt":"2020-01-01T00:00:00Z","endsAt":"2100-01-01T00:00:00Z"}"""
        val t = ms("2026-10-23T12:00:00Z")
        val both = cache("""{"update":{"state":"required"},$m}""")
        val s = LerixAppControlLogic.evaluate(both, now = t, prefs = none).state
        assertNotNull(s.update)
        assertEquals(LerixAppControlScreen.MAINTENANCE, LerixAppControlLogic.screenFor(s))
        val softAndMaintenance = LerixAppControlLogic.evaluate(cache("""{"update":{"state":"available"},$m}"""), now = t, prefs = none).state
        assertEquals(LerixAppControlScreen.MAINTENANCE, LerixAppControlLogic.screenFor(softAndMaintenance))
        val required = LerixAppControlLogic.evaluate(cache(update("required")), now = t, prefs = none).state
        assertEquals(LerixAppControlScreen.REQUIRED_UPDATE, LerixAppControlLogic.screenFor(required))
        val soft = LerixAppControlLogic.evaluate(cache(update("recommended"), fetchedAt = t), now = t, prefs = none).state
        assertEquals(LerixAppControlScreen.SOFT_UPDATE, LerixAppControlLogic.screenFor(soft))
        assertFalse(soft.isBlocking)
        assertEquals(LerixAppControlScreen.NONE, LerixAppControlLogic.screenFor(LerixAppControlState.EMPTY))
    }

    @Test
    fun disabledAppControlKeepsOnlyTheKillSwitch() {
        val body = """{"update":{"state":"required"},
            "maintenance":{"id":"m","startsAt":"2020-01-01T00:00:00Z","endsAt":"2100-01-01T00:00:00Z"},
            "sdk":{"disabled":["errors"],"until":"2100-01-01T00:00:00Z"}}"""
        val s = LerixAppControlLogic.evaluate(cache(body), now = ms("2026-10-23T12:00:00Z"), prefs = none, enabled = false).state
        assertNull(s.update)
        assertNull(s.maintenance)
        assertEquals(setOf("errors"), s.sdk!!.disabled)
    }

    @Test
    fun noCacheIsEmpty() {
        val e = LerixAppControlLogic.evaluate(null, now = 1L, prefs = none)
        assertEquals(LerixAppControlState.EMPTY, e.state)
        assertNull(e.nextChangeAt)
    }

    // ---- E6 gating ----

    private val allPaths = listOf(
        "plugin/init/ping", "plugin/init/register-user", "plugin/init/user", "plugin/init/identify", "plugin/init/logout",
        "plugin/bugs/create", "plugin/events", "plugin/feedback", "plugin/feedback/config", "plugin/feedback/attachments",
        "plugin/surveys/config", "plugin/surveys/responses", "plugin/notifications/register-token",
        "plugin/notifications/sender-id", "plugin/notifications/subscribe-topic", "plugin/traces",
    )

    @Test
    fun allBlocksEveryPathExceptAppConfig() {
        val sdk = LerixSdkControl(setOf("all"), until = 1_000L)
        allPaths.forEach { assertTrue(it, LerixAppControlLogic.isPathBlocked(sdk, 999L, it)) }
        assertFalse(LerixAppControlLogic.isPathBlocked(sdk, 999L, "plugin/app-config"))
        assertFalse(LerixAppControlLogic.isPathBlocked(sdk, 999L, "/plugin/app-config?platform=android"))
        assertTrue(LerixAppControlLogic.isModuleBlocked(sdk, 999L, LerixAppControlLogic.MODULE_CORE))
        // Lifts at until.
        allPaths.forEach { assertFalse(it, LerixAppControlLogic.isPathBlocked(sdk, 1_000L, it)) }
    }

    @Test
    fun moduleSwitchBlocksOnlyThatModule() {
        val sdk = LerixSdkControl(setOf("analytics", "push"), until = Long.MAX_VALUE)
        assertTrue(LerixAppControlLogic.isPathBlocked(sdk, 0L, "plugin/events"))
        assertTrue(LerixAppControlLogic.isPathBlocked(sdk, 0L, "plugin/notifications/register-token"))
        assertTrue(LerixAppControlLogic.isPathBlocked(sdk, 0L, "plugin/notifications/sender-id"))
        assertFalse(LerixAppControlLogic.isPathBlocked(sdk, 0L, "plugin/init/ping"))
        assertFalse(LerixAppControlLogic.isPathBlocked(sdk, 0L, "plugin/bugs/create"))
        assertFalse(LerixAppControlLogic.isPathBlocked(sdk, 0L, "plugin/feedback"))
        assertFalse(LerixAppControlLogic.isModuleBlocked(sdk, 0L, LerixAppControlLogic.MODULE_CORE))
        assertFalse(LerixAppControlLogic.isPathBlocked(null, 0L, "plugin/events"))
    }

    @Test
    fun moduleMapping() {
        assertNull(LerixAppControlLogic.moduleFor("plugin/app-config"))
        assertEquals("errors", LerixAppControlLogic.moduleFor("plugin/bugs/create"))
        assertEquals("analytics", LerixAppControlLogic.moduleFor("plugin/events"))
        assertEquals("tracing", LerixAppControlLogic.moduleFor("plugin/traces"))
        assertEquals("feedback", LerixAppControlLogic.moduleFor("plugin/feedback/attachments"))
        assertEquals("surveys", LerixAppControlLogic.moduleFor("plugin/surveys/responses"))
        assertEquals("push", LerixAppControlLogic.moduleFor("plugin/notifications/register-token"))
        assertEquals(LerixAppControlLogic.MODULE_CORE, LerixAppControlLogic.moduleFor("plugin/init/register-user"))
    }

    // ---- fetch results (§2.1) ----

    @Test
    fun notModifiedKeepsTheCacheAndRefreshesLastFetch() {
        val previous = LerixAppControlCache(
            body = update("required"),
            config = LerixAppConfigParser.parse(update("required"))!!,
            etag = "\"e1\"",
            fetchedAt = 1_000L,
            clockOffsetMs = 42L,
            lastFetchAt = 1_000L,
        )
        val next = LerixAppControlLogic.applyResponse(previous, 304, "", sentAt = 5_000L, receivedAt = 5_100L)!!
        assertEquals(previous.body, next.body)
        assertEquals("\"e1\"", next.etag)
        assertEquals(42L, next.clockOffsetMs)
        assertEquals(5_100L, next.lastFetchAt)
        assertEquals(5_100L, next.fetchedAt)
        assertNull(LerixAppControlLogic.applyResponse(null, 304, "", 0L, 1L))
    }

    @Test
    fun okReplacesTheCacheAndMeasuresTheClockOffset() {
        val body = """{"etag":"\"e2\"","serverTime":"2026-10-23T10:00:10Z","ttlSeconds":60,"update":null}"""
        val sentAt = ms("2026-10-23T10:00:00Z")
        val next = LerixAppControlLogic.applyResponse(null, 200, body, sentAt = sentAt, receivedAt = sentAt + 200)!!
        assertEquals("\"e2\"", next.etag)
        assertEquals(10_000L - 100L, next.clockOffsetMs)
        assertEquals(sentAt + 200, next.fetchedAt)
        assertEquals(60, next.config.ttlSeconds)
        assertNull(next.config.update)

        val noServerTime = LerixAppControlLogic.applyResponse(next, 200, """{"etag":"\"e3\""}""", 0L, 10L)!!
        assertEquals("keeps the last known offset", next.clockOffsetMs, noServerTime.clockOffsetMs)
    }

    @Test
    fun failuresKeepTheCache() {
        val previous = cache(update("required"), fetchedAt = 1L)
        assertSame(previous, LerixAppControlLogic.applyResponse(previous, null, null, 0L, 1L))
        assertSame(previous, LerixAppControlLogic.applyResponse(previous, 500, "{}", 0L, 1L))
        assertSame(previous, LerixAppControlLogic.applyResponse(previous, 403, """{"code":"SDK_DISABLED"}""", 0L, 1L))
        assertSame(previous, LerixAppControlLogic.applyResponse(previous, 200, "<html>", 0L, 1L))
    }

    // ---- cache vs the running app (aligned with lerix_ios) ----

    private val killAndRequired = """{"etag":"\"e5\"","update":{"state":"required"},
        "maintenance":{"id":"m","startsAt":"2020-01-01T00:00:00Z","endsAt":"2100-01-01T00:00:00Z"},
        "sdk":{"disabled":["all"],"until":"2100-01-01T00:00:00Z"}}"""

    @Test
    fun okRecordsTheVersionsItWasFetchedFor() {
        val c = LerixAppControlLogic.applyResponse(null, 200, killAndRequired, 0L, 1L, "3.3.0", "330", "1.3.0")!!
        assertEquals("3.3.0", c.appVersion)
        assertEquals("330", c.build)
        assertEquals("1.3.0", c.sdkVersion)
        val confirmed = LerixAppControlLogic.applyResponse(c, 304, "", 5L, 6L, "x", "y", "z")!!
        assertEquals("304 keeps the versions of the body it confirms", "3.3.0", confirmed.appVersion)
    }

    @Test
    fun sameAppKeepsTheCache() {
        val c = LerixAppControlLogic.applyResponse(null, 200, killAndRequired, 0L, 1L, "3.3.0", "330", "1.3.0")!!
        assertSame(c, LerixAppControlLogic.forRunningApp(c, "3.3.0", "330", "1.3.0"))
    }

    @Test
    fun updatedAppDropsTheCachedUpdateAndEtag() {
        val c = LerixAppControlLogic.applyResponse(null, 200, killAndRequired, 0L, 1L, "3.3.0", "330", "1.3.0")!!
        for ((version, build) in listOf("3.4.0" to "340", "3.3.0" to "331")) {
            val r = LerixAppControlLogic.forRunningApp(c, version, build, "1.3.0")!!
            assertNull(r.config.update)
            assertNull("no If-None-Match after a version change", r.etag)
            assertNotNull("maintenance is not version-bound", r.config.maintenance)
            assertNotNull(r.config.sdk)
            val s = LerixAppControlLogic.evaluate(r, now = ms("2026-10-23T12:00:00Z"), prefs = none).state
            assertNull(s.update)
        }
    }

    @Test
    fun newSdkVersionDropsTheCachedKillSwitch() {
        val c = LerixAppControlLogic.applyResponse(null, 200, killAndRequired, 0L, 1L, "3.3.0", "330", "1.3.0")!!
        val r = LerixAppControlLogic.forRunningApp(c, "3.3.0", "330", "1.4.0")!!
        assertNull(r.config.sdk)
        assertNull(r.etag)
        assertNotNull(r.config.update)
        assertNull(LerixAppControlLogic.forRunningApp(null, "1", "1", "1"))
    }

    @Test
    fun cacheRoundTrips() {
        val body = """{"etag":"\"e4\"","update":{"state":"required"}}"""
        val c = LerixAppControlCache(body, LerixAppConfigParser.parse(body)!!, "\"e4\"", 10L, -5L, 11L, "3.3.0", "330", "1.3.0")
        val back = LerixAppControlCache.fromJson(c.toJson())!!
        assertEquals(body, back.body)
        assertEquals("\"e4\"", back.etag)
        assertEquals(10L, back.fetchedAt)
        assertEquals(-5L, back.clockOffsetMs)
        assertEquals(11L, back.lastFetchAt)
        assertEquals("3.3.0", back.appVersion)
        assertEquals("330", back.build)
        assertEquals("1.3.0", back.sdkVersion)
        assertEquals(LerixUpdateState.REQUIRED, back.config.update!!.state)
        assertNull(LerixAppControlCache.fromJson("not json"))
        assertNull(LerixAppControlCache.fromJson(null))
    }

    @Test
    fun aDifferentActivityIsAlwaysCoveredAgain() {
        // The same activity's resume and a state change together: start the screen once.
        assertTrue(LerixAppControlLogic.isDuplicateLaunch(sameActivity = true, sinceLastLaunchMs = 200, guardMs = 1_000))
        assertFalse(LerixAppControlLogic.isDuplicateLaunch(sameActivity = true, sinceLastLaunchMs = 1_000, guardMs = 1_000))
        // A splash that starts MainActivity right after the screen came up: MainActivity is covered too.
        assertFalse(LerixAppControlLogic.isDuplicateLaunch(sameActivity = false, sinceLastLaunchMs = 200, guardMs = 1_000))
        // A clock that went backwards never drops a launch.
        assertFalse(LerixAppControlLogic.isDuplicateLaunch(sameActivity = true, sinceLastLaunchMs = -5, guardMs = 1_000))
    }
}
