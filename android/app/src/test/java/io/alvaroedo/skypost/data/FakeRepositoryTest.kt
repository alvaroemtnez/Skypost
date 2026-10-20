package io.alvaroedo.skypost.data

import io.alvaroedo.skypost.core.FakeKeryxCore
import io.alvaroedo.skypost.model.Auth
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.Delivery
import io.alvaroedo.skypost.model.PharosAccess
import io.alvaroedo.skypost.model.Registration
import io.alvaroedo.skypost.model.Scanned
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeRepositoryTest {
    /** Simulated radio: its own scheduler, so ACKs arrive only when we advance it. */
    private val air = TestScope()

    private fun repo(demo: Boolean = false) =
        FakeRepository(FakeKeryxCore(), air, demo = demo, ackDelayMs = 10, now = { 1_000_000L })
            .also { it.createIdentity("ana", null) }

    @Test
    fun `messages are signed by default and acked later`() = runTest {
        val r = repo(demo = true)
        val chat = r.chats.value.first { it.kind == ChatKind.DIRECT }.id
        r.send(chat, "hola")
        val sent = r.messages(chat).first().last()
        assertEquals(Auth.SIGNED to Delivery.PENDING, sent.auth to sent.delivery)
        air.advanceUntilIdle()
        assertEquals(Delivery.ACKED, r.messages(chat).first().last().delivery)
    }

    @Test
    fun `bridged chats need an active pharos`() = runTest {
        val r = repo()
        assertNull(r.openBridged(ChatKind.SIGNAL, "34600123456", null))
        val p = r.parse("skypost:id/H7KDQ2AZ?n=casa&c=EA5YYY&k=pharos")
        assertTrue(p is Scanned.PharosCard)
        r.import(p!!, inPerson = true)
        assertNotNull(r.openBridged(ChatKind.SIGNAL, "34600123456", null))
    }

    @Test
    fun `sos goes to every group by default, unencrypted even under encrypt-all`() = runTest {
        val r = repo(demo = true)
        val familia = r.chats.value.first { it.kind == ChatKind.GROUP }.id
        r.setEncryptAll(true)
        assertEquals(1, r.sos(null))
        val sos = r.messages(familia).first().last()
        assertTrue(sos.sos)
        assertEquals(Auth.SIGNED, sos.auth)
    }

    @Test
    fun `sos contacts can be chosen and encrypt-all overrides chats`() = runTest {
        val r = repo(demo = true)
        val mama = r.chats.value.first { it.kind == ChatKind.SIGNAL }
        r.setSosChats(setOf(mama.id))
        assertEquals(listOf(mama.id), r.sosTargets().map { it.id })
        val direct = r.chats.value.first { it.kind == ChatKind.DIRECT }.id
        r.setEncryptAll(true)
        r.send(direct, "hola")
        assertEquals(Auth.ENCRYPTED, r.messages(direct).first().last().auth)
    }

    @Test
    fun `a pharos that needs a code waits for it`() = runTest {
        val r = repo()
        val found = r.discover().first { it.access == PharosAccess.CODE }
        r.trust(found, null)
        assertEquals(Registration.NEEDS_CODE, r.pharos.value.single().registration)
        r.register(found.card.key, "caballo pila grapa")
        assertEquals(Registration.REGISTERED, r.pharos.value.single().registration)
    }

    @Test
    fun `state survives a restart through the store`() = runTest {
        val file = java.io.File.createTempFile("demo", ".json").apply { delete() }
        val store = FileDemoStore(file)
        val io = StandardTestDispatcher(air.testScheduler)
        val first = FakeRepository(FakeKeryxCore(), air, demo = true, ackDelayMs = 10, store = store, io = io)
        first.createIdentity("ana", "EA5AAA")
        first.setEncryptAll(true)
        val pharos = first.pharos.value.first().card.key
        first.sendAdmin(pharos, "status")
        air.advanceUntilIdle()
        val again = FakeRepository(FakeKeryxCore(), air, demo = true, store = store)
        assertEquals("ana", again.identity.value?.card?.alias)
        // Ids stay unique across restarts (they key the lists in the UI).
        again.sendAdmin(pharos, "clients")
        assertEquals(2, again.adminLog(pharos).first().map { it.id }.distinct().size)
        assertEquals(first.chats.value.map { it.id }.toSet(), again.chats.value.map { it.id }.toSet())
        assertTrue(again.settings.value.encryptAll)
        file.delete()
    }

    @Test
    fun `test QR payloads parse`() = runTest {
        val r = repo()
        val dir = listOf("test-qr", "../test-qr").map { java.io.File(it) }.first { it.isDirectory }
        val payloads = Regex("```\n(.+?)\n```").findAll(java.io.File(dir, "README.md").readText()).map { it.groupValues[1] }.toList()
        val parsed = payloads.map { r.parse(it) }
        assertTrue(parsed[0] is Scanned.Person)
        assertTrue(parsed[1] is Scanned.PharosCard)
        assertEquals("Montaña", (parsed[2] as Scanned.GroupInvite).name)
        assertTrue(parsed[3] is Scanned.MeshCore)
        assertNull(parsed[4])
    }

    @Test
    fun `pin at most three, archive, delete and undo`() = runTest {
        val r = repo(demo = true)
        val ids = r.chats.value.map { it.id }
        assertTrue(ids.take(3).all { r.setPinned(it, true) })
        assertEquals(false, r.setPinned(ids[3], true))
        r.setArchived(setOf(ids[0]), true)
        val archived = r.chats.value.first { it.id == ids[0] }
        assertTrue(archived.archived && archived.pinnedAt == null)
        val gone = r.deleteChats(setOf(ids[1]))
        assertTrue(r.chats.value.none { it.id == ids[1] })
        r.restoreChats(gone)
        assertTrue(r.chats.value.any { it.id == ids[1] } && r.messages(ids[1]).first().isNotEmpty())
    }

    @Test
    fun `blocked chats send nothing and do not answer pings`() = runTest {
        val r = repo(demo = true)
        val direct = r.chats.value.first { it.kind == ChatKind.DIRECT }
        assertEquals(-17, r.pingChat(direct.id))
        r.setChatBlocked(direct.id, true)
        val before = r.messages(direct.id).first().size
        r.send(direct.id, "hola")
        assertEquals(before, r.messages(direct.id).first().size)
        assertNull(r.pingChat(direct.id))
        assertTrue(r.contacts.value.single().blocked)
        r.unblock(r.blocked.value.single().key)
        assertEquals(false, r.chats.value.first { it.id == direct.id }.blocked)
    }

    @Test
    fun `phrases travel as ids and recents remember bridged contacts`() = runTest {
        val r = repo(demo = true)
        val familia = r.chats.value.first { it.kind == ChatKind.GROUP }.id
        r.sendPhrase(familia, 15)
        assertEquals(15, r.messages(familia).first().last().phrase)
        assertTrue(r.recents.value.any { it.kind == ChatKind.SIGNAL && it.address == "34611111111" })
        r.forgetRecent(r.recents.value.first())
        assertTrue("forgetting a recent keeps its chat", r.chats.value.any { it.kind == ChatKind.SIGNAL })
    }

    @Test
    fun `failed messages can be retried and deleted ones restored`() = runTest {
        val r = repo(demo = true)
        val mama = r.chats.value.first { it.kind == ChatKind.SIGNAL }.id
        r.setPharosBlocked(r.pharos.value.first().card.key, true)
        r.send(mama, "¿Hola?")
        air.advanceUntilIdle()
        val failed = r.messages(mama).first().last()
        assertEquals(Delivery.FAILED, failed.delivery)
        r.unblock(r.blocked.value.single().key)
        r.setActivePharos(r.pharos.value.first().card.key)
        r.retry(mama, failed.id)
        air.advanceUntilIdle()
        assertEquals(Delivery.ACKED, r.messages(mama).first().last().delivery)
        val gone = r.deleteMessages(mama, setOf(failed.id))
        assertTrue(r.messages(mama).first().none { it.id == failed.id })
        assertTrue(r.chats.value.first { it.id == mama }.last?.id != failed.id)
        r.restoreMessages(mama, gone)
        assertEquals(failed.id, r.messages(mama).first().last().id)
    }
}
