package org.aprsdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapClusterTest {

    private data class P(val name: String, val lat: Double, val lon: Double)

    private fun cluster(items: List<P>, zoom: Double, pinned: Set<String> = emptySet()) =
        MapCluster.cluster(items, zoom, 64.0, { it.lat }, { it.lon }, { it.name in pinned })

    @Test
    fun mercatorRoundTrips() {
        for (zoom in listOf(3.0, 10.0, 17.5)) {
            val x = MapCluster.worldX(-97.07, zoom)
            val y = MapCluster.worldY(33.03, zoom)
            assertEquals(-97.07, MapCluster.lonOf(x, zoom), 1e-9)
            assertEquals(33.03, MapCluster.latOf(y, zoom), 1e-9)
        }
    }

    @Test
    fun nearbyStationsMergeWhenZoomedOutAndSplitWhenZoomedIn() {
        val dallas = P("A", 32.78, -96.80)
        val fortWorth = P("B", 32.75, -97.33)
        val far = cluster(listOf(dallas, fortWorth), 4.0)
        assertEquals(1, far.size)
        assertTrue(far[0].isCluster)
        assertEquals(2, far[0].items.size)

        val near = cluster(listOf(dallas, fortWorth), 12.0)
        assertEquals(2, near.size)
        assertFalse(near.any { it.isCluster })
    }

    @Test
    fun singleStationKeepsItsTruePosition() {
        val g = cluster(listOf(P("A", 33.03, -97.07)), 6.0).single()
        assertEquals(33.03, g.lat, 0.0)
        assertEquals(-97.07, g.lon, 0.0)
    }

    @Test
    fun clusterSitsInsideItsCellNearItsMembers() {
        val a = P("A", 33.030, -97.070)
        val b = P("B", 33.031, -97.071)
        val g = cluster(listOf(a, b), 10.0).single()
        // 64 px at zoom 10 is about 0.09 degrees of longitude.
        assertEquals(33.03, g.lat, 0.1)
        assertEquals(-97.07, g.lon, 0.1)
    }

    @Test
    fun pinnedStationIsNeverAbsorbed() {
        val items = listOf(P("ME", 33.0, -97.0), P("X", 33.0, -97.0), P("Y", 33.0, -97.0))
        val groups = cluster(items, 5.0, pinned = setOf("ME"))
        assertEquals(2, groups.size)
        assertTrue(groups.any { !it.isCluster && it.items.single().name == "ME" })
        assertTrue(groups.any { it.isCluster && it.items.size == 2 })
    }

    @Test
    fun everyStationAppearsExactlyOnce() {
        val items = (0 until 500).map { P("S$it", 32.0 + (it % 25) * 0.04, -98.0 + (it / 25) * 0.07) }
        for (zoom in listOf(2.0, 7.0, 9.0, 13.0)) {
            val names = cluster(items, zoom).flatMap { g -> g.items.map { it.name } }
            assertEquals(500, names.size)
            assertEquals(500, names.toSet().size)
        }
    }

    @Test
    fun negativeCellsDoNotCollide() {
        // West/east and north/south of the origin of the cell grid must stay separate.
        val items = listOf(P("NW", 80.0, -170.0), P("SE", -80.0, 170.0))
        assertEquals(2, cluster(items, 3.0).size)
    }

    @Test
    fun mobileMeansMovingOrAVehicleSymbol() {
        assertTrue(MapFilters.isMobile("/>", null))      // car
        assertTrue(MapFilters.isMobile("/-", 12))        // house, but moving
        assertFalse(MapFilters.isMobile("/-", 0))        // house
        assertFalse(MapFilters.isMobile("/_", null))     // weather station
        assertFalse(MapFilters.isMobile("/#", null))     // digipeater
        assertTrue(MapFilters.isMobile("\\k", null))     // alternate-table truck
    }

    @Test
    fun filterKeepsNamedStations() {
        fun sta(call: String, symbol: String) =
            MapStation(call, null, symbol, LatLon(0.0, 0.0), null, null, null, null, emptyList())
        val all = listOf(sta("HOME", "/-"), sta("CAR", "/>"), sta("ME", "/-"))
        val counts = MapFilters.counts(all)
        assertEquals(3, counts.all)
        assertEquals(1, counts[StationKind.MOBILE])
        assertEquals(2, counts[StationKind.FIXED])
        assertEquals(0, counts[StationKind.WEATHER])
        assertEquals(listOf("CAR", "ME"), MapFilters.apply(all, setOf(StationKind.MOBILE), setOf("ME")).map { it.call })
        assertEquals(listOf("HOME", "ME"), MapFilters.apply(all, setOf(StationKind.FIXED), emptySet()).map { it.call })
        assertEquals(3, MapFilters.apply(all, emptySet(), emptySet()).size)
    }

    @Test
    fun everyStationIsExactlyOneKind() {
        assertEquals(StationKind.WEATHER, MapFilters.kindOf("/_", 5))      // wind speed is not movement
        assertEquals(StationKind.DIGIPEATER, MapFilters.kindOf("/#", null))
        assertEquals(StationKind.DIGIPEATER, MapFilters.kindOf("S#", null)) // overlay digipeater
        assertEquals(StationKind.IGATE, MapFilters.kindOf("I&", null))
        assertEquals(StationKind.AIR, MapFilters.kindOf("/O", 20))          // balloon
        assertEquals(StationKind.AIR, MapFilters.kindOf("/'", null))        // small aircraft
        assertEquals(StationKind.MOBILE, MapFilters.kindOf("/>", null))
        assertEquals(StationKind.MOBILE, MapFilters.kindOf("/-", 12))       // a moving "house"
        assertEquals(StationKind.FIXED, MapFilters.kindOf("/-", 0))
        assertEquals(StationKind.FIXED, MapFilters.kindOf("", null))
    }

    @Test
    fun chipsFilterFirstThenSwitchOnAndOff() {
        val all = emptySet<StationKind>()
        val onlyWeather = MapFilters.toggle(all, StationKind.WEATHER)
        assertEquals(setOf(StationKind.WEATHER), onlyWeather)                 // tap = show only this
        val two = MapFilters.toggle(onlyWeather, StationKind.DIGIPEATER)
        assertEquals(setOf(StationKind.WEATHER, StationKind.DIGIPEATER), two) // tap another = add it
        assertEquals(setOf(StationKind.DIGIPEATER), MapFilters.toggle(two, StationKind.WEATHER)) // tap again = off
        assertEquals(all, MapFilters.toggle(onlyWeather, StationKind.WEATHER)) // last one off = All
        var s = all
        for (k in StationKind.values()) s = MapFilters.toggle(s, k)
        assertEquals(all, s)                                                   // every one on = All
    }
}
