package org.aprsdroid.app.export

import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The ATAK "APRS" iconset, identical in UID, group and file names to the
 * one the aprscot gateway on the TAK Server uses (GrokAPRS
 * `build_iconset.py` + `symbol-icons.patch`). Because they match, a
 * station drawn from either feed gets the same icon, and every APRS
 * station lands in the one "APRS" overlay in ATAK — ATAK names the
 * overlay after the group segment of the icon path.
 */
object AprsIconset {

    const val DEFAULT_UID = "9c9f2b11-0d60-4f6c-a001-aprsiconset-v3"
    const val GROUP = "APRS"
    const val SYMBOLS_PER_TABLE = 96

    /** Index of an APRS symbol code within its table (0..95; out of range -> 0). */
    @JvmStatic
    fun index(symbolCode: Char): Int {
        val idx = symbolCode.code - 33
        return if (idx < 0 || idx > 95) 0 else idx
    }

    /** "table0" for the primary table ('/'), "table1" for alternate and overlay tables. */
    @JvmStatic
    fun table(symbolTable: Char): String = if (symbolTable == '/') "table0" else "table1"

    @JvmStatic
    fun fileName(table: String, index: Int): String = "%s_symbol_%02d.png".format(table, index)

    /**
     * `usericon` iconsetpath for a two-character APRS symbol (table + code).
     * Missing or short symbols fall back to "/>" (car), as aprscot does.
     */
    @JvmStatic
    fun iconPath(symbol: String?, uid: String = DEFAULT_UID): String {
        val st = symbol?.getOrNull(0) ?: '/'
        val sc = symbol?.getOrNull(1) ?: '>'
        return "$uid/$GROUP/${fileName(table(st), index(sc))}"
    }

    @JvmStatic
    fun iconsetXml(uid: String = DEFAULT_UID): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<iconset name=\"APRS\" uid=\"$uid\" version=\"2\" " +
            "defaultFriendly=\"\" defaultHostile=\"\" defaultNeutral=\"\" " +
            "defaultUnknown=\"\" selectedGroup=\"$GROUP\"/>\n"

    /**
     * Write the iconset as an ATAK data package (mission package zip that
     * imports itself on receipt).
     *
     * @param png returns the PNG bytes for (table 0|1, index 0..95).
     */
    @JvmStatic
    fun writeZip(out: OutputStream, uid: String = DEFAULT_UID, png: (table: Int, index: Int) -> ByteArray) {
        val entries = ArrayList<Pair<String, Pair<Int, Int>>>()
        for (t in 0..1) for (i in 0 until SYMBOLS_PER_TABLE) {
            entries += "$GROUP/${fileName("table$t", i)}" to (t to i)
        }
        val manifest = StringBuilder()
            .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<MissionPackageManifest version=\"2\">\n")
            .append("  <Configuration>\n")
            .append("    <Parameter name=\"uid\" value=\"${UUID.randomUUID()}\"/>\n")
            .append("    <Parameter name=\"name\" value=\"APRS Icons\"/>\n")
            .append("    <Parameter name=\"onReceiveImport\" value=\"true\"/>\n")
            .append("    <Parameter name=\"onReceiveDelete\" value=\"false\"/>\n")
            .append("  </Configuration>\n")
            .append("  <Contents>\n")
            .append("    <Content ignore=\"false\" zipEntry=\"iconset.xml\"/>\n")
        for ((name, _) in entries) manifest.append("    <Content ignore=\"false\" zipEntry=\"$name\"/>\n")
        manifest.append("  </Contents>\n</MissionPackageManifest>\n")

        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("iconset.xml"))
            z.write(iconsetXml(uid).toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry("MANIFEST/manifest.xml"))
            z.write(manifest.toString().toByteArray(Charsets.UTF_8))
            z.closeEntry()
            for ((name, ti) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(png(ti.first, ti.second))
                z.closeEntry()
            }
        }
    }
}
