package io.alvaroedo.skypost.ui

import io.alvaroedo.skypost.MainDispatcherRule
import io.alvaroedo.skypost.core.FakeKeryxCore
import io.alvaroedo.skypost.data.FakeRepository
import io.alvaroedo.skypost.location.Locator
import io.alvaroedo.skypost.model.GeoFix
import io.alvaroedo.skypost.ui.chat.ChatEvent
import io.alvaroedo.skypost.model.Auth
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.VerifyResult
import io.alvaroedo.skypost.ui.chat.ChatViewModel
import io.alvaroedo.skypost.ui.chats.ChatListViewModel
import io.alvaroedo.skypost.ui.newchat.NewChatViewModel
import io.alvaroedo.skypost.ui.pharos.AdminTarget
import io.alvaroedo.skypost.ui.pharos.PharosAdminViewModel
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
        val app = AppViewModel(r, Locator { null })
        assertFalse(app.offer("hola"))
        assertTrue(app.offer("skypost:id/ZZZZ?n=carla&c=EA5CCC"))
        assertTrue(r.contacts.value.none { it.card.alias == "carla" })
        val chat = app.confirmImport()
        assertEquals("direct:ZZZZ", chat)
        assertNull(app.pendingImport.value)
        assertTrue(r.contacts.value.any { it.card.alias == "carla" })
    }

    @Test
    fun `a pasted contact is unverified until scanned in person or marked`() = runTest {
        val r = repo()
        val app = AppViewModel(r, Locator { null })
        app.offer("skypost:id/ZZZZ?n=carla")
        val chat = app.confirmImport()!!
        val vm = ChatViewModel(r, chat)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }
        assertEquals(false, vm.ui.value.verified)
        app.offer("skypost:id/ZZZZ?n=carla", inPerson = true)
        app.confirmImport()
        assertEquals(true, vm.ui.value.verified)
        assertTrue(r.uriOf(vm.ui.value.contact!!).startsWith("skypost:id/ZZZZ"))
        val other = r.chats.value.first { it.kind == ChatKind.DIRECT && it.id != chat }.ref!!
        r.setContactVerified(other, false)
        assertFalse(r.contacts.value.first { it.card.key == other }.verified)
        assertEquals(VerifyResult.OTHER_KEY, r.verifyByScan(other, "skypost:id/ZZZZ?n=carla"))
        assertEquals(VerifyResult.NOT_A_CODE, r.verifyByScan(other, "hola"))
        assertFalse(r.contacts.value.first { it.card.key == other }.verified)
        assertEquals(VerifyResult.VERIFIED, r.verifyByScan(other, r.uriOf(r.contacts.value.first { it.card.key == other }.card)))
        assertTrue(r.contacts.value.first { it.card.key == other }.verified)
    }

    @Test
    fun `typed text that is a stock phrase in the app language goes as its id`() = runTest {
        val r = repo()
        val id = r.chats.value.first { it.kind == ChatKind.DIRECT }.id
        val vm = ChatViewModel(r, id)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }
        vm.setLocalPhrases(listOf("I'm OK", "All fine, nothing new"))
        vm.setDraft("all fine, nothing new")
        assertEquals(2, vm.ui.value.frames)
        vm.send()
        assertEquals(1, r.messages(id).first().last().phrase)
    }

    @Test
    fun `archive moves chats out of the main list`() = runTest {
        val r = repo()
        val main = ChatListViewModel(r)
        val archive = ChatListViewModel(r, archived = true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { main.ui.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { archive.ui.collect {} }
        val id = main.ui.value.chats.first().id
        main.setArchived(setOf(id), true)
        assertEquals(4, main.ui.value.chats.size)
        assertEquals(1, main.ui.value.archivedCount)
        assertEquals(listOf(id), archive.ui.value.chats.map { it.id })
    }

    @Test
    fun `chats without messages sort among the others`() = runTest {
        val r = repo()
        val vm = ChatListViewModel(r)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }
        assertTrue(AppViewModel(r, Locator { null }).offer("skypost:id/ZZZZ?n=carla"))
        AppViewModel(r, Locator { null }).apply { offer("skypost:id/ZZZZ?n=carla") }.confirmImport()
        assertTrue(vm.ui.value.chats.any { it.id == "direct:ZZZZ" && it.last == null })
    }

    @Test
    fun `status ok carries the fix, or goes without and says so`() = runTest {
        val r = repo()
        val id = r.chats.value.first { it.kind == ChatKind.DIRECT }.id
        val fix = GeoFix(42.6311, 0.6588, 8f)
        val withFix = ChatViewModel(r, id, Locator { fix })
        withFix.sendStatusOk()
        assertEquals(fix, r.messages(id).first().last().position)
        val blind = ChatViewModel(r, id, Locator { null })
        val events = mutableListOf<ChatEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { blind.events.collect { events += it } }
        blind.sendStatusOk()
        blind.sendPosition()
        assertEquals(listOf(ChatEvent.SENT_WITHOUT_POSITION, ChatEvent.NO_POSITION), events)
        assertEquals(0, r.messages(id).first().last().phrase)
    }

    @Test
    fun `pharos admin builds command texts and signs in only encrypted`() = runTest {
        val r = repo()
        val key = r.pharos.value.first().card.key
        val vm = PharosAdminViewModel(r, key)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }
        assertEquals("@bea", vm.text(AdminTarget.FieldUser("@bea")))
        assertEquals("whatsapp +34600123456", vm.text(AdminTarget.Bridged(ChatKind.WHATSAPP, "+34 600 12 34 56")))
        assertEquals("signal mama.01", vm.text(AdminTarget.Bridged(ChatKind.SIGNAL, "mama.01")))
        assertEquals("meshcore " + "ab".repeat(32), vm.text(AdminTarget.Bridged(ChatKind.MESHCORE, "AB".repeat(32))))
        assertNull(vm.text(AdminTarget.Bridged(ChatKind.MESHCORE, "abc")))
        assertFalse(vm.decide("no spaces allowed", true))
        assertTrue(vm.decide("tio", true))
        assertEquals("accept @tio", vm.ui.value.log.first().command)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertEquals("@tio accepted.", vm.ui.value.log.first().reply)
        assertFalse("never in signed plaintext", vm.login("faro viejo de la costa"))
        r.setEncryptAll(true)
        assertTrue(vm.login("faro viejo de la costa"))
        assertFalse("the password is not kept", vm.ui.value.log.first().command.contains("faro"))
    }

    @Test
    fun `sos waits for the fix and reports it`() = runTest {
        val r = repo()
        val res = AppViewModel(r, Locator { GeoFix(40.0, -3.0) }).sos()
        assertEquals(1, res.sentTo)
        assertTrue(res.withPosition)
        val group = r.chats.value.first { it.kind == ChatKind.GROUP }.id
        assertEquals(40.0, r.messages(group).first().last().position?.lat)
    }
}
