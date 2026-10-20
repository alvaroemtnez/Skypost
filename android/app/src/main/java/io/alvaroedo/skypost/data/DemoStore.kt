package io.alvaroedo.skypost.data

import io.alvaroedo.skypost.model.AdminEntry
import io.alvaroedo.skypost.model.Blocked
import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatMessage
import io.alvaroedo.skypost.model.Contact
import io.alvaroedo.skypost.model.Group
import io.alvaroedo.skypost.model.Identity
import io.alvaroedo.skypost.model.Pharos
import io.alvaroedo.skypost.model.Recent
import io.alvaroedo.skypost.model.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Everything [FakeRepository] holds, so the demo survives an app restart. */
@Serializable
data class DemoSnapshot(
    val identity: Identity?,
    val chats: List<Chat>,
    val contacts: List<Contact>,
    val groups: List<Group>,
    val pharos: List<Pharos>,
    val settings: Settings,
    val messages: Map<String, List<ChatMessage>>,
    val recents: List<Recent> = emptyList(),
    val blocked: List<Blocked> = emptyList(),
    val adminLog: List<AdminEntry> = emptyList(),
)

interface DemoStore {
    fun load(): DemoSnapshot?

    fun save(snapshot: DemoSnapshot)
}

/**
 * JSON file in app-private storage (no cloud backup, see decision 0013).
 * Holds demo data only, no key material; phase 2 replaces it with the
 * Keystore-encrypted keyring and a message database.
 */
class FileDemoStore(private val file: File) : DemoStore {
    private val json = Json { ignoreUnknownKeys = true }

    /** Null if absent or unreadable (e.g. after a format change): start over. */
    override fun load(): DemoSnapshot? =
        runCatching { json.decodeFromString<DemoSnapshot>(file.readText()) }.getOrNull()

    override fun save(snapshot: DemoSnapshot) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(DemoSnapshot.serializer(), snapshot))
        tmp.renameTo(file)
    }
}
