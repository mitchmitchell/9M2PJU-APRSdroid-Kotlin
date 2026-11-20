package org.aprsdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AprsWeatherTest {

    @Test
    fun decodesAFullReportFromTheAir() {
        // KE5WTH-14, as received: wind prefix still in the comment
        val w = AprsWeather.parse("161/005g012t074r000p000P000b10173h61L311eMB64")!!
        assertEquals(161, w.windDirDeg)
        assertEquals(5, w.windMph)
        assertEquals(12, w.gustMph)
        assertEquals(74, w.tempF)
        assertEquals(0, w.rainHour)
        assertEquals(0, w.rain24h)
        assertEquals(0, w.rainToday)
        assertEquals(10173, w.pressureTenthsHpa)
        assertEquals(61, w.humidityPct)
        assertEquals(311, w.luminosity)
    }

    @Test
    fun usesWindAlreadySplitOffByThePacketParser() {
        val w = AprsWeather.parse("g012t074h61b10173", course = 200, speed = 7)!!
        assertEquals(200, w.windDirDeg)
        assertEquals(7, w.windMph)
        assertEquals(74, w.tempF)
    }

    @Test
    fun handlesMissingSensorsNegativeTemperatureAndSaturatedHumidity() {
        val w = AprsWeather.parse(".../...g...t-05r...p...P...h00b09987")!!
        assertNull(w.windDirDeg)
        assertNull(w.windMph)
        assertNull(w.gustMph)
        assertEquals(-5, w.tempF)
        assertNull(w.rainHour)
        assertEquals(100, w.humidityPct)
        assertEquals(9987, w.pressureTenthsHpa)
    }

    @Test
    fun positionlessFormatAndBrightSun() {
        val w = AprsWeather.parse("c220s004g005t077r000p000P000h50b09900l234")!!
        assertEquals(220, w.windDirDeg)
        assertEquals(4, w.windMph)
        assertEquals(1234, w.luminosity)
    }

    @Test
    fun ordinaryCommentsAreNotWeather() {
        assertNull(AprsWeather.parse("Mitch mobile https://example.org"))
        assertNull(AprsWeather.parse("PHG5130/W3,TXn Dallas digipeater"))
        assertNull(AprsWeather.parse("146.520MHz p100 radio"))
        assertNull(AprsWeather.parse(""))
        assertNull(AprsWeather.parse(null))
    }

    @Test
    fun textAfterIsTheStationSoftware() {
        assertEquals("eMB64", AprsWeather.textAfter("161/005g012t074r000p000P000b10173h61L311eMB64"))
        assertEquals("", AprsWeather.textAfter("g012t074h61b10173"))
    }

    @Test
    fun formatsInBothUnitSystems() {
        val w = AprsWeather.parse("180/010g015t068r012p034P020h55b10132")
        assertNotNull(w)
        assertEquals("68 °F  •  S 10 mph  •  55 %  •  rain 0.12 in/h", WeatherFormat.summary(w!!, metric = false))
        assertEquals("20 °C  •  S 16 km/h  •  55 %  •  rain 3.0 mm/h", WeatherFormat.summary(w, metric = true))
        assertEquals("1013.2 hPa", WeatherFormat.pressure(10132, metric = true))
        assertEquals("29.92 inHg", WeatherFormat.pressure(10132, metric = false))
        assertEquals("Calm", WeatherFormat.wind(AprsWeather(windMph = 0, windDirDeg = null), false))
        assertEquals("N", WeatherFormat.compass(350))
    }
}
