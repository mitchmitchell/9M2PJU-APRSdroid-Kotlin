package org.aprsdroid.app

/**
 * Explains, in words, how a packet reached us, from its header:
 *
 *     KF5JUQ-9>APRS,WIDE1-1,W5DCR-3*,WIDE2-1,qAR,W5DCR-3:...
 *
 * Pure Kotlin so it can be unit-tested.
 */
object AprsPath {

    data class Route(
        /** Digipeaters that actually repeated the packet over radio, in order. */
        val digipeaters: List<String>,
        /** The station that passed it to the internet, if any. */
        val igate: String?,
        /** True if it never went over radio (sent straight to the internet). */
        val internetOnly: Boolean,
    ) {
        /** One or two plain sentences. */
        fun describe(): String {
            if (internetOnly) {
                return if (igate != null) "Sent over the internet, through $igate." else "Sent over the internet."
            }
            val radio = when (digipeaters.size) {
                0 -> "Heard directly over radio"
                1 -> "Relayed over radio by ${digipeaters[0]}"
                else -> "Relayed over radio by " + digipeaters.dropLast(1).joinToString(", ") + " and " + digipeaters.last()
            }
            return if (igate != null) "$radio, then passed to the internet by $igate." else "$radio."
        }
    }

    private val ALIAS = Regex("^(WIDE|RELAY|TRACE|NCA|SSn|TEMP|ECHO|GATE|HOP|ARISS|RFONLY|NOGATE)\\d*(-\\d+)?$", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun parse(rawPacket: String?): Route? {
        if (rawPacket == null) return null
        val header = rawPacket.substringBefore(':', "")
        if (!header.contains('>')) return null
        val parts = header.substringAfter('>').split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val hops = parts.drop(1) // first element is the destination (software id)

        val qIndex = hops.indexOfFirst { it.length == 3 && it[0] == 'q' && it[1] == 'A' }
        val rf = if (qIndex >= 0) hops.subList(0, qIndex) else hops
        val igate = if (qIndex >= 0) hops.getOrNull(qIndex + 1) else null
        val internetOnly = rf.any { it.trimEnd('*').equals("TCPIP", true) || it.trimEnd('*').equals("TCPXX", true) }

        // Everything up to the last "*" was used. Generic aliases (WIDE1-1) are not stations.
        val lastUsed = rf.indexOfLast { it.endsWith("*") }
        val digis = if (lastUsed >= 0) {
            rf.subList(0, lastUsed + 1).map { it.trimEnd('*') }.filter { !ALIAS.matches(it) }
        } else emptyList()
        return Route(digis, igate, internetOnly)
    }
}
