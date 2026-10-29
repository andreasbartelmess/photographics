package de.photographics.core.psd

import de.photographics.core.tr

/** Roh erhaltener "Additional Layer Information"-Block (Signatur 8BIM/8B64, 4-Zeichen-Schlüssel). */
class PsdTaggedBlock(val signature: String, val key: String, val data: ByteArray)

/** Roh erhaltene Bildressource aus dem Image-Resources-Abschnitt. */
class PsdImageResource(val id: Int, val name: String, val data: ByteArray)

class PsdFormatException(msg: String) : Exception(msg)

object PsdColorMode {
    const val BITMAP = 0
    const val GRAYSCALE = 1
    const val INDEXED = 2
    const val RGB = 3
    const val CMYK = 4
    const val MULTICHANNEL = 7
    const val DUOTONE = 8
    const val LAB = 9

    fun name(mode: Int) = when (mode) {
        BITMAP -> "Bitmap"; GRAYSCALE -> "Graustufen"; INDEXED -> tr("Indizierte Farben"); RGB -> "RGB"
        CMYK -> "CMYK"; MULTICHANNEL -> "Mehrkanal"; DUOTONE -> "Duplex"; LAB -> "Lab"; else -> tr("Unbekannt ({0})", mode)
    }
}
