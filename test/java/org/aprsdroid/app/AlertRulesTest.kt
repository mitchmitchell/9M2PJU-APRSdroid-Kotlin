package org.aprsdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRulesTest {
    private val min = 60_000L
    private val home = Pair(33.0, -97.0)
    private fun kinds(a: List<StationAlert>) = a.map { it.kind }

    @Test
    fun watchPatterns() {
        assertEquals(listOf("W5ABC", "N5XYZ-9", "KD5*"), AlertRules.parseWatch(" w5abc, n5xyz-9  kd5* "))
        assertTrue(AlertRules.matches("W5ABC", "W5ABC-9"))
        assertTrue(AlertRules.matches("W5ABC", "w5abc"))
        assertFalse(AlertRules.matches("W5ABC", "W5ABCD"))
        assertTrue(AlertRules.matches("N5XYZ-9", "N5XYZ-9"))
        assertFalse(AlertRules.matches("N5XYZ-9", "N5XYZ-7"))
        assertTrue(AlertRules.matches("KD5*", "KD5UMO-1"))
    }

    @Test
    fun heardOnceThenAgainOnlyAfterQuiet() {
        val r = AlertRules()
        val cfg = AlertConfig(watch = listOf("W5ABC"))
        assertEquals(listOf(StationAlert.Kind.HEARD), kinds(r.onPosition(cfg, "W5ABC-9", 33.1, -97.0, 0, null, null)))
        assertTrue(r.onPosition(cfg, "W5ABC-9", 33.1, -97.0, 5 * min, null, null).isEmpty())
        assertEquals(listOf(StationAlert.Kind.HEARD), kinds(r.onPosition(cfg, "W5ABC-9", 33.1, -97.0, 40 * min, null, null)))
        // Not watched: silence.
        assertTrue(r.onPosition(cfg, "N0CALL", 33.1, -97.0, 0, null, null).isEmpty())
    }

    @Test
    fun movingIsAnnouncedOnceAWhile() {
        val r = AlertRules()
        val cfg = AlertConfig(watch = listOf("W5ABC-9"), heard = false)
        assertTrue(r.onPosition(cfg, "W5ABC-9", 33.1000, -97.0, 0, null, null).isEmpty())
        // GPS wobble of a few metres is not movement.
        assertTrue(r.onPosition(cfg, "W5ABC-9", 33.1001, -97.0, min, null, null).isEmpty())
        assertEquals(listOf(StationAlert.Kind.MOVING), kinds(r.onPosition(cfg, "W5ABC-9", 33.1100, -97.0, 2 * min, null, null)))
        // Still driving: no repeat for 15 minutes.
        assertTrue(r.onPosition(cfg, "W5ABC-9", 33.1300, -97.0, 4 * min, null, null).isEmpty())
        assertEquals(listOf(StationAlert.Kind.MOVING), kinds(r.onPosition(cfg, "W5ABC-9", 33.3000, -97.0, 20 * min, null, null)))
    }

    @Test
    fun enteringTheArea() {
        val r = AlertRules()
        val cfg = AlertConfig(watch = listOf("W5ABC"), heard = false, moved = false, areaMetres = 5000.0)
        // About 11 km north: outside.
        assertTrue(r.onPosition(cfg, "W5ABC-9", 33.10, -97.0, 0, home.first, home.second).isEmpty())
        val a = r.onPosition(cfg, "W5ABC-9", 33.02, -97.0, min, home.first, home.second)
        assertEquals(listOf(StationAlert.Kind.ENTERED_AREA), kinds(a))
        assertTrue(a[0].distanceMetres!! in 2000.0..2500.0)
        // Staying inside, or wobbling on the edge, does not repeat.
        assertTrue(r.onPosition(cfg, "W5ABC-9", 33.03, -97.0, 2 * min, home.first, home.second).isEmpty())
        assertTrue(r.onPosition(cfg, "W5ABC-9", 33.047, -97.0, 3 * min, home.first, home.second).isEmpty())
        assertTrue(r.onPosition(cfg, "W5ABC-9", 33.04, -97.0, 4 * min, home.first, home.second).isEmpty())
        // Leaves properly, comes back.
        assertTrue(r.onPosition(cfg, "W5ABC-9", 33.20, -97.0, 5 * min, home.first, home.second).isEmpty())
        assertEquals(listOf(StationAlert.Kind.ENTERED_AREA), kinds(r.onPosition(cfg, "W5ABC-9", 33.01, -97.0, 6 * min, home.first, home.second)))
    }

    @Test
    fun strangersOnlyWhenAskedAndOnlyOnTheMove() {
        val r = AlertRules()
        val watchedOnly = AlertConfig(areaMetres = 5000.0)
        assertTrue(r.onPosition(watchedOnly, "N0CAR-9", 33.10, -97.0, 0, home.first, home.second, 40).isEmpty())
        assertTrue(r.onPosition(watchedOnly, "N0CAR-9", 33.02, -97.0, min, home.first, home.second, 40).isEmpty())

        val r2 = AlertRules()
        val anyone = AlertConfig(areaMetres = 5000.0, areaWatchedOnly = false)
        // A house already inside the area when first heard is not news.
        assertTrue(r2.onPosition(anyone, "N0HOME", 33.01, -97.0, 0, home.first, home.second).isEmpty())
        assertTrue(r2.onPosition(anyone, "N0CAR-9", 33.10, -97.0, 0, home.first, home.second, 40).isEmpty())
        assertEquals(listOf(StationAlert.Kind.ENTERED_AREA), kinds(r2.onPosition(anyone, "N0CAR-9", 33.02, -97.0, min, home.first, home.second, 40)))
    }

    @Test
    fun readsWeatherAlerts() {
        val json = """{"features":[
          {"properties":{"id":"urn:1","event":"Wind Advisory","severity":"Minor","status":"Actual","headline":"Wind Advisory until 6 PM","areaDesc":"Denton"}},
          {"properties":{"id":"urn:2","event":"Tornado Warning","severity":"Extreme","status":"Actual","headline":"Tornado Warning until 11:45 PM","areaDesc":"Denton; Tarrant","instruction":"TAKE COVER NOW"}},
          {"properties":{"id":"urn:3","event":"Severe Thunderstorm Warning","severity":"Severe","status":"Actual","headline":null,"areaDesc":"Tarrant","instruction":null}},
          {"properties":{"id":"urn:4","event":"Tornado Warning","severity":"Extreme","status":"Test"}}
        ]}"""
        val a = NwsAlerts.parse(json)
        assertEquals(listOf("urn:2", "urn:3"), a.map { it.id })
        assertEquals("TAKE COVER NOW", a[0].instruction)
        assertEquals("Severe Thunderstorm Warning", a[1].headline)
        assertEquals("", a[1].instruction)
        assertEquals(3, NwsAlerts.parse(json, "minor").size)
        assertTrue(NwsAlerts.parse("not json").isEmpty())
        assertEquals("https://api.weather.gov/alerts/active?point=33.0110,-97.0527", NwsAlerts.url(33.01104, -97.05271))
    }
}
