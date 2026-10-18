package io.alvaroedo.skypost.ui.family

import io.alvaroedo.skypost.core.EncodedFrame
import io.alvaroedo.skypost.core.KeryxCore
import io.alvaroedo.skypost.core.OutMessage
import io.alvaroedo.skypost.model.DeliveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyViewModelTest {
    /** Records what the UI asked the core to seal. */
    private class RecordingCore : KeryxCore {
        val calls = mutableListOf<Pair<OutMessage, Boolean>>()

        override fun encode(msg: OutMessage, encrypted: Boolean): EncodedFrame {
            calls += msg to encrypted
            return EncodedFrame(wire = ByteArray(0), counter = calls.size, js8Frames = 1)
        }
    }

    @Test
    fun `signed plaintext is the default`() {
        val core = RecordingCore()
        FamilyViewModel(core = core).sendOk()
        assertEquals(listOf(false), core.calls.map { it.second })
    }

    @Test
    fun `emergency encryption applies only after it is switched on`() {
        val core = RecordingCore()
        val vm = FamilyViewModel(core = core)
        vm.sendOk()
        vm.setEmergencyEncryption(true)
        vm.sendHelp()
        assertEquals(listOf(false, true), core.calls.map { it.second })
        assertTrue(core.calls[1].first is OutMessage.Sos)
    }

    @Test
    fun `blank text is not sent`() {
        val core = RecordingCore()
        val vm = FamilyViewModel(core = core)
        vm.sendText("   ")
        assertTrue(core.calls.isEmpty())
        assertTrue(vm.ui.value.outgoing.isEmpty())
    }

    @Test
    fun `newest message is listed first, queued`() {
        val vm = FamilyViewModel(core = RecordingCore())
        vm.sendOk()
        vm.sendText(" hola ")
        assertEquals(listOf("hola", "Estoy bien"), vm.ui.value.outgoing.map { it.label })
        assertTrue(vm.ui.value.outgoing.all { it.state == DeliveryState.QUEUED })
    }
}
