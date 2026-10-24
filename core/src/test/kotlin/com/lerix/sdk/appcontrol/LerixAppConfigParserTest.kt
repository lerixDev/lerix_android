package com.lerix.sdk.appcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LerixAppConfigParserTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val full = """
        {
          "v": 1,
          "etag": "\"a1b2c3\"",
          "serverTime": "2026-10-23T10:00:00Z",
          "ttlSeconds": 60,
          "update": {
            "state": "recommended", "reason": "outdated", "ruleId": "r-1",
            "latestVersion": "3.4.0", "minVersion": "3.0.0",
            "storeUrl": "https://play.google.com/store/apps/details?id=com.example",
            "title": "Update available", "message": "New stuff", "releaseNotes": "- fixes",
            "remindAfterHours": 24, "allowSkip": true, "requiredAt": "2026-11-01T00:00:00Z"
          },
          "maintenance": {
            "id": "m-1", "title": "Back soon", "message": "Maintenance", "imageUrl": null,
            "startsAt": "2026-10-23T10:00:00Z", "endsAt": "2026-10-23T14:00:00Z", "showEndsAt": false
          },
          "sdk": { "disabled": ["analytics", "push"], "until": "2026-10-24T00:00:00Z" },
          "banner": { "future": true },
          "flags": [1, 2, 3]
        }
    """.trimIndent()

    @Test
    fun parsesTheContractShapeAndIgnoresUnknownFields() {
        val c = LerixAppConfigParser.parse(full)!!
        assertEquals("\"a1b2c3\"", c.etag)
        assertEquals(ms("2026-10-23T10:00:00Z"), c.serverTime)
        assertEquals(60, c.ttlSeconds)

        val u = c.update!!
        assertEquals(LerixUpdateState.RECOMMENDED, u.state)
        assertEquals("outdated", u.reason)
        assertEquals("r-1", u.ruleId)
        assertEquals("3.4.0", u.latestVersion)
        assertEquals("3.0.0", u.minVersion)
        assertEquals("https://play.google.com/store/apps/details?id=com.example", u.storeUrl)
        assertEquals("- fixes", u.releaseNotes)
        assertEquals(24, u.remindAfterHours)
        assertFalse("recommended is never skippable", u.allowSkip)
        assertEquals(ms("2026-11-01T00:00:00Z"), u.requiredAt)

        val m = c.maintenance!!
        assertEquals("m-1", m.id)
        assertNull(m.imageUrl)
        assertEquals(ms("2026-10-23T10:00:00Z"), m.startsAt)
        assertEquals(ms("2026-10-23T14:00:00Z"), m.endsAt)
        assertFalse(m.showEndsAt)

        val sdk = c.sdk!!
        assertEquals(setOf("analytics", "push"), sdk.disabled)
        assertEquals(ms("2026-10-24T00:00:00Z"), sdk.until)
    }

    @Test
    fun nullBlocksAndMissingPartsAreAbsent() {
        val c = LerixAppConfigParser.parse("""{"v":1,"update":null,"maintenance":null,"sdk":null}""")!!
        assertNull(c.update)
        assertNull(c.maintenance)
        assertNull(c.sdk)
        assertNull(c.etag)
        assertNull(c.serverTime)
        assertEquals(3600, c.ttlSeconds)
    }

    @Test
    fun unknownUpdateStateDropsOnlyTheUpdate() {
        val c = LerixAppConfigParser.parse(
            """{"update":{"state":"forced","title":"x"},
                "maintenance":{"id":"m","startsAt":"2026-10-23T10:00:00Z","endsAt":"2026-10-23T11:00:00Z"}}""",
        )!!
        assertNull(c.update)
        assertNotNull(c.maintenance)
    }

    @Test
    fun wrongTypesFallBackPerField() {
        val u = LerixAppConfigParser.parse(
            """{"update":{"state":"available","reason":"weird","title":5,"message":["m"],
                "remindAfterHours":"72","allowSkip":"true","requiredAt":"tomorrow",
                "storeUrl":"javascript:alert(1)","latestVersion":340}}""",
        )!!.update!!
        assertEquals(LerixUpdateState.AVAILABLE, u.state)
        assertNull(u.reason)
        assertNull(u.title)
        assertNull(u.message)
        assertEquals(LerixAppConfigParser.DEFAULT_REMIND_HOURS, u.remindAfterHours)
        assertFalse(u.allowSkip)
        assertNull(u.requiredAt)
        assertNull("only http(s)/market links are opened", u.storeUrl)
        assertNull(u.latestVersion)
    }

    @Test
    fun availableUpdateHonoursAllowSkip() {
        val u = LerixAppConfigParser.parse("""{"update":{"state":"available","allowSkip":true}}""")!!.update!!
        assertTrue(u.allowSkip)
    }

    @Test
    fun nonObjectBlocksAreAbsent() {
        val c = LerixAppConfigParser.parse("""{"update":"required","maintenance":[1],"sdk":true}""")!!
        assertNull(c.update)
        assertNull(c.maintenance)
        assertNull(c.sdk)
    }

    @Test
    fun maintenanceWithoutAValidEndIsAbsentFailOpen() {
        assertNull(LerixAppConfigParser.parse("""{"maintenance":{"id":"m","startsAt":"2026-10-23T10:00:00Z"}}""")!!.maintenance)
        assertNull(LerixAppConfigParser.parse("""{"maintenance":{"id":"m","endsAt":"soon"}}""")!!.maintenance)
        assertNull(
            "ends before it starts",
            LerixAppConfigParser.parse(
                """{"maintenance":{"startsAt":"2026-10-23T12:00:00Z","endsAt":"2026-10-23T11:00:00Z"}}""",
            )!!.maintenance,
        )
    }

    @Test
    fun maintenanceWithoutStartIsAlreadyActive() {
        val m = LerixAppConfigParser.parse("""{"maintenance":{"endsAt":"2026-10-23T14:00:00Z"}}""")!!.maintenance!!
        assertEquals(Long.MIN_VALUE, m.startsAt)
        assertEquals("", m.id)
        assertTrue(m.showEndsAt)
    }

    @Test
    fun sdkBlockFiltersUnknownModulesAndNeedsUntil() {
        val c = LerixAppConfigParser.parse("""{"sdk":{"disabled":["replay","errors",3],"until":"2026-10-24T00:00:00Z"}}""")!!
        assertEquals(setOf("errors"), c.sdk!!.disabled)
        assertNull(LerixAppConfigParser.parse("""{"sdk":{"disabled":["replay"],"until":"2026-10-24T00:00:00Z"}}""")!!.sdk)
        assertNull(LerixAppConfigParser.parse("""{"sdk":{"disabled":["all"]}}""")!!.sdk)
        assertNull(LerixAppConfigParser.parse("""{"sdk":{"disabled":"all","until":"2026-10-24T00:00:00Z"}}""")!!.sdk)
    }

    @Test
    fun ttlIsClampedAndDefaulted() {
        assertEquals(3600, LerixAppConfigParser.parse("""{"ttlSeconds":"60"}""")!!.ttlSeconds)
        assertEquals(10, LerixAppConfigParser.parse("""{"ttlSeconds":5}""")!!.ttlSeconds)
        assertEquals(86_400, LerixAppConfigParser.parse("""{"ttlSeconds":9999999}""")!!.ttlSeconds)
        assertEquals(60, LerixAppConfigParser.parse("""{"ttlSeconds":60.0}""")!!.ttlSeconds)
    }

    @Test
    fun unreadableBodiesAreNull() {
        assertNull(LerixAppConfigParser.parse(null))
        assertNull(LerixAppConfigParser.parse(""))
        assertNull(LerixAppConfigParser.parse("<html>502</html>"))
        assertNull(LerixAppConfigParser.parse("[1,2]"))
    }

    @Test
    fun isoTimestamps() {
        assertEquals(ms("2026-10-23T10:00:00Z"), LerixIsoTime.parse("2026-10-23T10:00:00Z"))
        assertEquals(ms("2026-10-23T10:00:00.123Z"), LerixIsoTime.parse("2026-10-23T10:00:00.123Z"))
        assertEquals(ms("2026-10-23T10:00:00.120Z"), LerixIsoTime.parse("2026-10-23T10:00:00.12Z"))
        assertEquals(ms("2026-10-23T10:00:00.123Z"), LerixIsoTime.parse("2026-10-23T10:00:00.123456Z"))
        assertEquals(ms("2026-10-23T06:00:00Z"), LerixIsoTime.parse("2026-10-23T10:00:00+04:00"))
        assertEquals(ms("2026-10-23T15:30:00Z"), LerixIsoTime.parse("2026-10-23T10:00:00-0530"))
        assertEquals("no zone = UTC", ms("2026-10-23T10:00:00Z"), LerixIsoTime.parse("2026-10-23T10:00:00"))
        assertEquals(ms("2024-02-29T00:00:00Z"), LerixIsoTime.parse("2024-02-29T00:00:00Z"))
        assertEquals(ms("1969-12-31T23:59:59Z"), LerixIsoTime.parse("1969-12-31T23:59:59Z"))
        assertNull(LerixIsoTime.parse("2026-13-01T00:00:00Z"))
        assertNull(LerixIsoTime.parse("2026-10-23T25:00:00Z"))
        assertNull(LerixIsoTime.parse("1729677600000"))
        assertNull(LerixIsoTime.parse(null))
    }
}
