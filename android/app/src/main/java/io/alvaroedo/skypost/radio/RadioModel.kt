package io.alvaroedo.skypost.radio

import kotlinx.serialization.Serializable

/** How the app talks to the radio's CAT port (phase 2, `RadioLink`). */
@Serializable
enum class CatProtocol {
    /** Audio only: frequency set by hand, PTT by VOX. */
    NONE,

    /** Kenwood TS-480 commands (QRP Labs QDX/QMX emulate it). */
    KENWOOD,

    /** Icom CI-V (the Xiegu G90 speaks it). */
    CIV,
}

/**
 * The radios Skypost knows, with the short name the top bar shows ("QMX+"),
 * and [CUSTOM] for anything else, set up by hand ([CustomRadio]).
 * Brand names, so not translated ([CUSTOM]'s names are never shown: the UI
 * uses its own string). Append only: the name is saved.
 */
@Serializable
enum class RadioModel(val shortName: String, val fullName: String, val cat: CatProtocol, val baud: Int) {
    QDX("QDX", "QRP Labs QDX", CatProtocol.KENWOOD, 9600),
    QMX("QMX", "QRP Labs QMX", CatProtocol.KENWOOD, 9600),
    QMX_PLUS("QMX+", "QRP Labs QMX+", CatProtocol.KENWOOD, 9600),
    G90("G90", "Xiegu G90", CatProtocol.CIV, 19200),
    CUSTOM("Custom", "Custom", CatProtocol.NONE, 9600),
}

/** A radio not in [RadioModel], described by hand. */
@Serializable
data class CustomRadio(
    /** What the top bar shows; up to [MAX_NAME] characters. */
    val name: String = "",
    val cat: CatProtocol = CatProtocol.NONE,
    val baud: Int = 9600,
) {
    companion object {
        const val MAX_NAME = 8
        val BAUDS = listOf(4800, 9600, 19200, 38400, 57600, 115200)
    }
}
