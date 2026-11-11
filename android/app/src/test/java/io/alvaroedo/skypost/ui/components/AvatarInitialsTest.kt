package io.alvaroedo.skypost.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarInitialsTest {
    @Test
    fun twoWordsGiveTwoLetters() {
        assertEquals("ÁE", AvatarInitials.of("Álvaro Edo"))
        assertEquals("CP", AvatarInitials.of("casa_pirineo"))
        assertEquals("EM", AvatarInitials.of("EA5XXX-mama"))
    }

    @Test
    fun aHumpCountsAsASecondWord() {
        assertEquals("BB", AvatarInitials.of("BlueBase"))
        assertEquals("M", AvatarInitials.of("mama"))
        assertEquals("E", AvatarInitials.of("EA5XXX"))
    }

    @Test
    fun noLettersGiveAQuestionMark() {
        assertEquals("?", AvatarInitials.of(""))
        assertEquals("?", AvatarInitials.of("+-+"))
    }

    @Test
    fun phoneNumbersUseTheirFirstDigit() {
        assertEquals("3", AvatarInitials.of("+34600111222"))
    }

    @Test
    fun oneKeepsASingleLetter() {
        assertEquals("Á", AvatarInitials.one("Álvaro Edo"))
    }

    @Test
    fun barsFollowTheLanes() {
        assertEquals(0, pharosBars(null))
        assertEquals(4, pharosBars(-10))
        assertEquals(3, pharosBars(-11))
        assertEquals(2, pharosBars(-24))
        assertEquals(1, pharosBars(-25))
    }
}
