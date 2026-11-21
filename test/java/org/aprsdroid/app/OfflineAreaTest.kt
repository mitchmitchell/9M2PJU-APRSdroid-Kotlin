package org.aprsdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAreaTest {
    @Test
    fun countsTilesForABox() {
        // The whole world at zoom 0 and 1.
        assertEquals(1L, OfflineArea.tileCount(-85.0, -180.0, 85.0, 179.99, 0, 0))
        assertEquals(5L, OfflineArea.tileCount(-85.0, -180.0, 85.0, 179.99, 0, 1))
        // A county-sized box around Dallas saved from zoom 9 stays well under the limit.
        val county = OfflineArea.tileCount(32.5, -97.5, 33.3, -96.5, 9, 14)
        assertTrue(county in 100..OfflineArea.TILE_LIMIT)
        // Texas from zoom 5 does not.
        assertTrue(OfflineArea.tileCount(25.8, -106.6, 36.5, -93.5, 5, 14) > OfflineArea.TILE_LIMIT)
    }

    @Test
    fun startsFromTheZoomOnScreen() {
        assertEquals(9, OfflineArea.minZoom(9.7))
        assertEquals(14, OfflineArea.minZoom(17.2))
        assertEquals(0, OfflineArea.minZoom(-1.0))
    }

    @Test
    fun stylesAreFoundByTag() {
        assertEquals(MapLibreStyle.QUIET, MapLibreStyle.byTag("ml_quiet"))
        assertEquals(MapLibreStyle.OSM, MapLibreStyle.byTag("unknown"))
        assertTrue(MapLibreStyle.OSM.json!!.contains("tile.openstreetmap.org"))
        assertTrue(!MapLibreStyle.OSM.canSave && MapLibreStyle.STREETS.canSave)
    }
}
