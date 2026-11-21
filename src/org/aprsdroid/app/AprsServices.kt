package org.aprsdroid.app

/**
 * The APRS message services the app knows how to talk to.
 *
 * An APRS "service" is an automated station: you send it a short message in
 * its command language and it answers. Each one has its own syntax, which is
 * why they are little used. Here every command is an [Action] with labelled
 * fields, and the app writes the command text.
 *
 * Every action produces exactly one message of at most [MAX_LENGTH]
 * characters. Pure Kotlin so it can be unit-tested.
 */
object AprsServices {

    /** The longest text an APRS message can carry. */
    const val MAX_LENGTH = 67

    class Field(
        val key: String,
        val label: String,
        val hint: String = "",
        val required: Boolean = true,
        /** Upper-case as typed (callsigns, group names). */
        val upper: Boolean = false,
        /** Keep only digits (phone numbers). */
        val digitsOnly: Boolean = false,
    ) {
        /**
         * True for details worth remembering between uses (a phone number, an
         * address, a group), false for the message text itself.
         */
        val remembered: Boolean get() = key != "msg" && key != "n"
    }

    class Action(
        val label: String,
        /** What happens, for the form: one plain sentence. */
        val explain: String,
        val fields: List<Field> = emptyList(),
        private val template: (Map<String, String>) -> String,
    ) {
        /** The message text to send. Values are trimmed; blanks collapse. */
        fun build(values: Map<String, String>): String {
            val clean = HashMap<String, String>()
            for (f in fields) {
                var v = values[f.key].orEmpty().trim()
                if (f.upper) v = v.uppercase()
                if (f.digitsOnly) v = v.filter { it.isDigit() }
                clean[f.key] = v
            }
            return template(clean).trim().replace(Regex(" {2,}"), " ")
        }

        /** Why the form cannot be sent yet, or null if it can. */
        fun problem(values: Map<String, String>): String? {
            for (f in fields) {
                var v = values[f.key].orEmpty().trim()
                if (f.digitsOnly) v = v.filter { it.isDigit() }
                if (f.required && v.isEmpty()) return "Fill in ${f.label.lowercase()}."
            }
            val length = build(values).length
            return if (length > MAX_LENGTH) "Too long by ${length - MAX_LENGTH} characters. APRS messages hold $MAX_LENGTH." else null
        }
    }

    class Service(
        val callsign: String,
        val title: String,
        /** One line for the card. */
        val summary: String,
        /** Anything the user must know before using it. */
        val note: String? = null,
        val actions: List<Action>,
    )

    private fun text(vararg parts: String) = parts.joinToString(" ")

    val ALL: List<Service> = listOf(
        Service(
            callsign = "WLNK-1",
            title = "Winlink email",
            summary = "Send and read email through your Winlink account.",
            note = "Needs a Winlink account. The first time in a session WLNK-1 asks you to log in: it replies with " +
                "three numbers, and you answer with the characters at those positions in your Winlink password, " +
                "padded to six characters with anything else.",
            actions = listOf(
                Action(
                    "Send a short email", "Sends a one-line email to an address or a Winlink callsign.",
                    listOf(
                        Field("to", "To", "name@example.com or a callsign"),
                        Field("msg", "Message", "Keep it short"),
                    ),
                ) { text("SMS", it.getValue("to"), it.getValue("msg")) },
                Action("Check my mail", "Lists up to five waiting messages, numbered.") { "L" },
                Action(
                    "Read a message", "Reads one of the messages from the list.",
                    listOf(Field("n", "Message number", "1 to 5", digitsOnly = true)),
                ) { "R" + it.getValue("n") },
                Action(
                    "Reply to a message", "Starts a reply to one of the listed messages; send your text next, then /EX.",
                    listOf(Field("n", "Message number", "1 to 5", digitsOnly = true)),
                ) { "Y" + it.getValue("n") },
                Action("Nearest Winlink gateway", "Tells you the closest active Winlink packet station.") { "G" },
                Action("About APRSLink", "Asks WLNK-1 for information about itself.") { "I" },
            ),
        ),
        Service(
            callsign = "WXBOT",
            title = "Weather forecast",
            summary = "National Weather Service forecast for where you are.",
            note = "Uses the position you last beaconed, so send your position first. United States only.",
            actions = listOf(
                Action("Forecast for today", "Replies with today's forecast for your position.") { "today" },
                Action("Tonight", "Replies with tonight's forecast.") { "tonight" },
                Action("Tomorrow", "Replies with tomorrow's forecast.") { "tomorrow" },
                Action("Current conditions", "Replies with conditions right now.") { "current" },
                Action("Full forecast", "Replies with a longer forecast, over several messages.") { "full" },
                Action(
                    "Somewhere else", "Forecast for a ZIP code or a place you name.",
                    listOf(Field("place", "ZIP code or place", "75028")),
                ) { it.getValue("place") },
                Action("Help", "Asks WXBOT what it understands.") { "help" },
            ),
        ),
        Service(
            callsign = "ANSRVR",
            title = "Groups",
            summary = "Message everyone in an interest group at once: nets, SKYWARN, ARES, clubs.",
            note = "Sending to a group also joins you to it for a while, so you get the replies.",
            actions = listOf(
                Action(
                    "Message a group", "Sends your text to every member of the group.",
                    listOf(
                        Field("group", "Group", "SKYWARN", upper = true),
                        Field("msg", "Message"),
                    ),
                ) { text("CQ", it.getValue("group"), it.getValue("msg")) },
                Action(
                    "Join a group", "Start receiving the group's messages.",
                    listOf(Field("group", "Group", "SKYWARN", upper = true)),
                ) { text("J", it.getValue("group")) },
                Action(
                    "Leave a group", "Stop receiving the group's messages.",
                    listOf(Field("group", "Group", "SKYWARN", upper = true)),
                ) { text("U", it.getValue("group")) },
                Action("List active groups", "Replies with the groups in use and their member counts.") { "?" },
            ),
        ),
        Service(
            callsign = "REPEAT",
            title = "Nearest repeaters",
            summary = "Find repeaters near where you are.",
            note = "Uses the position you last beaconed.",
            actions = listOf(
                Action("Find nearest repeaters", "Replies with the closest repeaters and their frequencies.") { "nearest" },
                Action("Help", "Asks REPEAT what it understands.") { "help" },
            ),
        ),
        Service(
            callsign = "WHOIS",
            title = "Who is this callsign",
            summary = "Look up the licence holder of a callsign.",
            actions = listOf(
                Action(
                    "Look up a callsign", "Replies with the name and location on the licence.",
                    listOf(Field("call", "Callsign", "W1AW", upper = true)),
                ) { it.getValue("call") },
            ),
        ),
        Service(
            callsign = "FIND",
            title = "Find a station",
            summary = "Where and when a station was last heard on APRS.",
            actions = listOf(
                Action(
                    "Find a station", "Replies with its last position, speed and distance from you.",
                    listOf(Field("call", "Callsign", "W1AW-9", upper = true)),
                ) { it.getValue("call") },
            ),
        ),
        Service(
            callsign = "QRX",
            title = "Missed-message mailbox",
            summary = "Holds messages sent to you while you were off the air.",
            note = "Register once; after that QRX keeps messages you miss.",
            actions = listOf(
                Action("Register", "Signs your callsign up for the mailbox.") { "REG" },
                Action("Check my inbox", "Lists messages waiting for you.") { "INBOX" },
                Action("Read my messages", "Sends you the waiting messages.") { "READ" },
                Action("Help", "Asks QRX what it understands.") { "HELP" },
            ),
        ),
        Service(
            callsign = "SMS",
            title = "Text a phone",
            summary = "Send a text message to a mobile phone number.",
            note = "US and Canada: ten digits, no leading 1. The person you text may have to opt in before " +
                "messages reach them.",
            actions = listOf(
                Action(
                    "Send a text", "Sends your message to the phone number as an SMS.",
                    listOf(
                        Field("number", "Phone number", "9725550100", digitsOnly = true),
                        Field("msg", "Message"),
                    ),
                ) { text("@" + it.getValue("number"), it.getValue("msg")) },
            ),
        ),
    )

    /** The service that answers on [callsign], if the app knows it. */
    @JvmStatic
    fun forCallsign(callsign: String): Service? = ALL.firstOrNull { it.callsign.equals(callsign.trim(), ignoreCase = true) }
}
