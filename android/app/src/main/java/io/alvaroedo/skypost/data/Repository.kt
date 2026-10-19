package io.alvaroedo.skypost.data

import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.ChatMessage
import io.alvaroedo.skypost.model.Contact
import io.alvaroedo.skypost.model.Discovered
import io.alvaroedo.skypost.model.Group
import io.alvaroedo.skypost.model.Identity
import io.alvaroedo.skypost.model.Pharos
import io.alvaroedo.skypost.model.Scanned
import io.alvaroedo.skypost.model.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the UI reads and does. Phase 2 implements it over the Rust core
 * (keyring, encode/decode) and the radio service; [FakeRepository] stands in
 * until then.
 */
interface Repository {
    val identity: StateFlow<Identity?>
    val chats: StateFlow<List<Chat>>
    val contacts: StateFlow<List<Contact>>
    val groups: StateFlow<List<Group>>
    val pharos: StateFlow<List<Pharos>>
    val settings: StateFlow<Settings>

    fun messages(chatId: String): Flow<List<ChatMessage>>

    fun createIdentity(alias: String, callsign: String?)

    fun updateProfile(alias: String, callsign: String?)

    fun validAlias(alias: String): Boolean

    fun validCallsign(callsign: String): Boolean

    /** JS8 frames the text would take in this chat (0 for blank text). */
    fun framesFor(chatId: String, text: String): Int

    fun phrases(): List<String>

    fun send(chatId: String, text: String)

    /** "Estoy bien" with the current position. */
    fun sendStatusOk(chatId: String)

    fun sendPosition(chatId: String)

    fun setEncrypted(chatId: String, on: Boolean)

    fun markRead(chatId: String)

    /** Where an SOS goes now: the chosen chats, or every group if none were chosen. */
    fun sosTargets(): List<Chat>

    /** SOS in signed plaintext to [sosTargets]. Returns how many destinations. */
    fun sos(): Int

    /** null = automatic (every group). */
    fun setSosChats(ids: Set<String>?)

    /** Emergency encryption for every chat. */
    fun setEncryptAll(on: Boolean)

    /** A `skypost:` URI or a MeshCore QR payload, or null if it is neither. */
    fun parse(text: String): Scanned?

    /** Adds a contact, Pharos, group or MeshCore chat. Returns the chat to open, if any. */
    fun import(scanned: Scanned): String?

    fun createGroup(name: String): String

    fun chatWith(contactKey: String): String

    fun chatInGroup(groupId: String): String

    /** Opens (or finds) a Signal/WhatsApp chat through the active Pharos. Null without one. */
    fun openBridged(kind: ChatKind, address: String, name: String?): String?

    fun normalizePhone(input: String): String

    /** Sends a Discover on the robust lane and collects the answers. */
    suspend fun discover(): List<Discovered>

    /** Trust a discovered Pharos and register with it, with its access code if it needs one. */
    fun trust(found: Discovered, code: String?)

    /** Register again with a trusted Pharos, e.g. after it asked for its access code. */
    fun register(key: String, code: String?)

    fun setActivePharos(key: String)

    fun markVerified(key: String)

    fun removePharos(key: String)

    /** Pings the active Pharos; true if it answered. */
    suspend fun ping(): Boolean

    fun setPingInterval(minutes: Int)

    /** Passphrase-encrypted backup of identity, contacts, groups, Pharos list and settings. */
    suspend fun exportBackup(passphrase: String): ByteArray

    /** False on a wrong passphrase or a damaged file. */
    suspend fun restoreBackup(data: ByteArray, passphrase: String): Boolean
}
