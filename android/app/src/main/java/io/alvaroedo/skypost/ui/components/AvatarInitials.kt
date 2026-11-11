package io.alvaroedo.skypost.ui.components

import java.util.Locale

/**
 * Up to two letters for an avatar circle, the way Signal (and MeshConnect)
 * do it. Words are runs of letters and digits: "Álvaro Edo" → "ÁE",
 * "casa_pirineo" → "CP"; a single word with a hump takes it too ("BlueBase"
 * → "BB"). Otherwise one letter; "?" when there is no letter or digit.
 */
object AvatarInitials {
    private val words = Regex("[^\\p{L}\\p{N}]+")

    fun of(label: String): String {
        val parts = label.split(words).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "?"
        if (parts.size >= 2) return (first(parts[0]) + first(parts[1])).uppercase(Locale.ROOT)
        val word = parts[0]
        val hump = (1 until word.length).firstOrNull { word[it].isUpperCase() && word[it - 1].isLowerCase() }
        return (first(word) + (hump?.let { word[it].toString() } ?: "")).uppercase(Locale.ROOT)
    }

    /** One letter, for small circles. */
    fun one(label: String): String = of(label).let { if (it.length > 1 && !it[0].isSurrogate()) it.take(1) else it }

    /** The first character, whole even if it is outside the BMP. */
    private fun first(word: String): String = word.substring(0, word.offsetByCodePoints(0, 1))
}
