package io.alvaroedo.skypost.ui

import io.alvaroedo.skypost.MainDispatcherRule
import io.alvaroedo.skypost.core.FakeKeryxCore
import io.alvaroedo.skypost.data.FakeRepository
import io.alvaroedo.skypost.model.Auth
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.ui.chat.ChatViewModel
import io.alvaroedo.skypost.ui.chats.ChatListViewModel
import io.alvaroedo.skypost.ui.newchat.NewChatViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun TestScope.repo() =
        FakeRepository(FakeKeryxCore(), backgroundScope, demo = true, ackDelayMs = 10).also { it.createIdentity("ana", null) }

    @Test
    fun `chat sends signed plaintext until emergency encryption is switched on`() = runTest {
        val r = repo()
        val id = r.chats.value.first { it.kind == ChatKind.DIRECT }.id
        val vm = ChatViewModel(r, id)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }
        vm.setDraft("   ")
        vm.send()
        val before = r.messages(id).first().size
        assertEquals("blank text is not sent", before, r.messages(id).first().size)
        vm.setDraft("Hemos llegado")
        assertTrue(vm.ui.value.frames > 0)
        vm.send()
        assertEquals("", vm.ui.value.draft)
        vm.setEncrypted(true)
        vm.setDraft("Estamos bien")
        vm.send()
        assertEquals(listOf(Auth.SIGNED, Auth.ENCRYPTED), r.messages(id).first().takeLast(2).map { it.auth })
    }

    @Test
    fun `chat list filters by kind`() = runTest {
        val r = repo()
        val vm = ChatListViewModel(r)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }
        assertEquals(5, vm.ui.value.chats.size)
        vm.setFilter(ChatKind.WHATSAPP)
        assertEquals(listOf(ChatKind.WHATSAPP), vm.ui.value.chats.map { it.kind })
        assertEquals("casa", vm.ui.value.activePharos?.card?.alias)
    }

    @Test
    fun `phone numbers and signal usernames`() = runTest {
        val vm = NewChatViewModel(repo())
        assertEquals("34600123456", vm.address(ChatKind.WHATSAPP, "+34 600 12 34 56"))
        assertNull(vm.address(ChatKind.WHATSAPP, "112"))
        assertEquals("mama.01", vm.address(ChatKind.SIGNAL, "mama.01"))
        assertNull(vm.address(ChatKind.WHATSAPP, "mama.01"))
    }

    @Test
    fun `scanned contact is confirmed before it is added`() = runTest {
        val r = repo()
        val app = AppViewModel(r)
        assertFalse(app.offer("hola"))
        assertTrue(app.offer("skypost:id/ZZZZ?n=carla&c=EA5CCC"))
        assertTrue(r.contacts.value.none { it.card.alias == "carla" })
        val chat = app.confirmImport()
        assertEquals("direct:ZZZZ", chat)
        assertNull(app.pendingImport.value)
        assertTrue(r.contacts.value.any { it.card.alias == "carla" })
    }
}
