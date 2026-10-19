package io.alvaroedo.skypost.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshCoreQrTest {
    private val key = "ab".repeat(32)

    @Test
    fun `contact codes only`() {
        val c = MeshCoreQr.parse("meshcore://contact/add?name=Refugio%20G%C3%B3riz&public_key=$key&type=1")!!
        assertEquals("Refugio Góriz" to key, c.name to c.key)
        assertNull("channels are never bridged", MeshCoreQr.parse("meshcore://channel/add?name=Pirineo&secret=${"cd".repeat(16)}"))
    }

    @Test
    fun `rejects other codes and short keys`() {
        assertNull(MeshCoreQr.parse("skypost:id/ABC?n=ana"))
        assertNull(MeshCoreQr.parse("meshcore://contact/add?name=x&public_key=abcd"))
        assertNull(MeshCoreQr.parse("meshcore://contact/add?public_key=$key"))
    }
}
