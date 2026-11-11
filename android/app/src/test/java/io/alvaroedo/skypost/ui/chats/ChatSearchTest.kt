package io.alvaroedo.skypost.ui.chats

import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchTest {
    private val refugio = Chat(id = "m:1", kind = ChatKind.MESHCORE, title = "Refugio Góriz", detail = "a1b2c3")
    private val mama = Chat(id = "d:1", kind = ChatKind.DIRECT, title = "Mamá", detail = "EA5XXX")

    @Test
    fun searchIgnoresAccentsAndCase() {
        assertTrue(refugio.matches("goriz"))
        assertTrue(refugio.matches("GÓRIZ"))
        assertTrue(mama.matches("mama"))
        assertTrue(mama.matches(" ea5 "))
        assertFalse(mama.matches("papa"))
    }

    @Test
    fun keysDropMarksOnly() {
        assertEquals("nino alvar canco", searchKey("Niño Àlvar Cançó"))
        assertEquals("", searchKey(""))
    }
}
