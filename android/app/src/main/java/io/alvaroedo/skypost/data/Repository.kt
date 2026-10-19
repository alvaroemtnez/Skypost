package io.alvaroedo.skypost.data

import io.alvaroedo.skypost.model.Blocked
import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.ChatMessage
import io.alvaroedo.skypost.model.Contact
import io.alvaroedo.skypost.model.DeletedChats
import io.alvaroedo.skypost.model.Discovered
import io.alvaroedo.skypost.model.GeoFix
import io.alvaroedo.skypost.model.Group
import io.alvaroedo.skypost.model.Identity
import io.alvaroedo.skypost.model.Pharos
import io.alvaroedo.skypost.model.Recent
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

    /** JS8 frames a stock phrase takes in this chat. */
    fun framesForPhrase(chatId: String): Int

    fun send(chatId: String, text: String)

    /** Send stock phrase [id] (1 byte on air; each phone shows it in its language). */
    fun sendPhrase(chatId: String, id: Int)

    /** "Estoy bien", with the position if there is one. */
    fun sendStatusOk(chatId: String, fix: GeoFix?)

    fun sendPosition(chatId: String, fix: GeoFix)

    fun setEncrypted(chatId: String, on: Boolean)

    fun markRead(chatId: String)

    /** Send a failed message again (same content, new attempt). */
    fun retry(chatId: String, messageId: Long)

    /** Removes messages from this phone only; returns them for undo. */
    fun deleteMessages(chatId: String, ids: Set<Long>): List<ChatMessage>

    fun restoreMessages(chatId: String, messages: List<ChatMessage>)

    fun setArchived(ids: Set<String>, on: Boolean)

    fun setMarkedUnread(ids: Set<String>, on: Boolean)

    /** False when pinning would exceed [io.alvaroedo.skypost.model.Chat.MAX_PINNED]. */
    fun setPinned(id: String, on: Boolean): Boolean

    /** Removes chats and their messages (contacts and groups stay). */
    fun deleteChats(ids: Set<String>): DeletedChats

    fun restoreChats(deleted: DeletedChats)

    /** Contacts, groups, bridged contacts and Pharos that are blocked. */
    val blocked: StateFlow<List<Blocked>>

    /** Block or unblock the contact, group or bridged contact behind a chat. */
    fun setChatBlocked(chatId: String, on: Boolean)

    fun setPharosBlocked(key: String, on: Boolean)

    fun unblock(key: String)

    /** Signal/WhatsApp/MeshCore contacts used before. */
    val recents: StateFlow<List<Recent>>

    fun forgetRecent(recent: Recent)

    /** Forget a contact (its chat, if any, stays). */
    fun removeContact(key: String)

    /** Leave a group and forget its key (its chat, if any, stays). */
    fun leaveGroup(id: String)

    fun setContactBlocked(key: String, on: Boolean)

    fun setGroupBlocked(id: String, on: Boolean)

    fun setRecentBlocked(recent: Recent, on: Boolean)

    /**
     * "Are you listening?": pings the contact (direct) or the Pharos (bridged)
     * without bothering anyone. The SNR it heard us at, or null if silent.
     */
    suspend fun pingChat(chatId: String): Int?

    /** Where an SOS goes now: the chosen chats, or every group if none were chosen. */
    fun sosTargets(): List<Chat>

    /** SOS in signed plaintext to [sosTargets], with the position if there is one. Returns how many destinations. */
    fun sos(fix: GeoFix?): Int

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
