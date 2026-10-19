package io.alvaroedo.skypost.data

import io.alvaroedo.skypost.core.FakeKeryxCore
import io.alvaroedo.skypost.model.Auth
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.Delivery
import io.alvaroedo.skypost.model.Scanned
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
        r.import(p!!)
        assertNotNull(r.openBridged(ChatKind.SIGNAL, "34600123456", null))
    }

    @Test
    fun `sos goes to the pharos and every group, unencrypted`() = runTest {
        val r = repo(demo = true)
        val familia = r.chats.value.first { it.kind == ChatKind.GROUP }.id
        r.setEncrypted(familia, true)
        assertEquals(2, r.sos())
        val sos = r.messages(familia).first().last()
        assertTrue(sos.sos)
        assertEquals(Auth.SIGNED, sos.auth)
    }
}
