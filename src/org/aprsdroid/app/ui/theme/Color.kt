package org.aprsdroid.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// The palette grows out of the app icon: deep navy, steel blue, and the
// amber of a radio's display. Navy carries the surfaces, amber marks what
// you can act on or where you are, green means "transmitting / mine".

// Brand roots (also in res/values/colors.xml)
val BrandNavy = Color(0xFF1C2F51)
val BrandNavyDark = Color(0xFF0D182D)
val BrandSteel = Color(0xFF2C4B62)
val BrandAmber = Color(0xFFCEB619)

// Night
val NightBackground = Color(0xFF0D182D)
val NightSurface = Color(0xFF14233F)
val NightRaised = Color(0xFF1C2F51)
val NightLine = Color(0xFF2C4B62)
val NightText = Color(0xFFE6ECF5)
val NightMuted = Color(0xFF9CB3C8)
val NightAmber = Color(0xFFEBCF3A)

// Day (sunlight): cool chart-paper white, navy ink
val DayBackground = Color(0xFFF2F5F9)
val DaySurface = Color(0xFFFFFFFF)
val DayRaised = Color(0xFFE4EAF2)
val DayLine = Color(0xFFC9D4E2)
val DayText = Color(0xFF0D182D)
val DayMuted = Color(0xFF51647D)
val DayAmber = Color(0xFF7A6900)

/** Semantic colours that Material's scheme has no slot for. */
@Immutable
data class AprsColors(
    /** Our own packets, "on air", own station. */
    val tx: Color,
    /** Packets heard from others. */
    val rx: Color,
    val info: Color,
    val error: Color,
    val digi: Color,
    val igate: Color,
    /** Callsigns and other "radio display" text. */
    val callsign: Color,
    val line: Color,
    val muted: Color,
    /** Background tint for our own station row. */
    val ownRow: Color,
)

val NightAprsColors = AprsColors(
    tx = Color(0xFF7FD68A),
    rx = Color(0xFF9DB4D6),
    info = Color(0xFFCFC58A),
    error = Color(0xFFFF9A8F),
    digi = Color(0xFFF2A65A),
    igate = Color(0xFFE3D61C),
    callsign = NightAmber,
    line = NightLine,
    muted = NightMuted,
    ownRow = Color(0xFF173A33),
)

val DayAprsColors = AprsColors(
    tx = Color(0xFF1F7A33),
    rx = Color(0xFF2C4B62),
    info = Color(0xFF6B6222),
    error = Color(0xFFB3261E),
    digi = Color(0xFFA85A00),
    igate = Color(0xFF7A6900),
    callsign = BrandNavy,
    line = DayLine,
    muted = DayMuted,
    ownRow = Color(0xFFE3F3E6),
)

val LocalAprsColors = staticCompositionLocalOf { NightAprsColors }
