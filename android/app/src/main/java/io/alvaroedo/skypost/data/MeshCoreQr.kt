package io.alvaroedo.skypost.data

import io.alvaroedo.skypost.model.Scanned
import java.net.URLDecoder

/**
 * MeshCore's own QR payloads, so a MeshCore contact can be bridged
 * through a Pharos. This is MeshCore's format, not Keryx: the app only reads
 * the name and key and hands them to the core as a `BridgeOpen` address.
 *
 * Contacts only: channels and groups are never bridged (decision 0019).
 * Format: `meshcore://contact/add?name=…&public_key=<hex>&type=<n>`.
 * UNVERIFIED against the current MeshCore app.
 */
object MeshCoreQr {
    private const val SCHEME = "meshcore://"
    private val HEX = Regex("^[0-9a-fA-F]+$")

    fun parse(text: String): Scanned.MeshCore? {
        val t = text.trim()
        if (!t.startsWith(SCHEME, ignoreCase = true)) return null
        val rest = t.substring(SCHEME.length)
        val path = rest.substringBefore('?').lowercase()
        val params = rest.substringAfter('?', "").split('&').mapNotNull { kv ->
            val (k, v) = kv.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            k to URLDecoder.decode(v, "UTF-8")
        }.toMap()
        val name = params["name"]?.takeIf { it.isNotBlank() } ?: return null
        fun hex(key: String, len: Int) = params[key]?.takeIf { it.length == len && HEX.matches(it) }?.lowercase()
        return if (path == "contact/add") hex("public_key", 64)?.let { Scanned.MeshCore(name, it) } else null
    }
}
