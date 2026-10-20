package io.alvaroedo.skypost.model

import io.alvaroedo.skypost.core.Card
import kotlinx.serialization.Serializable

/** The five kinds of conversation, in filter-chip order. */
@Serializable
enum class ChatKind {
    DIRECT,
    GROUP,
    SIGNAL,
    WHATSAPP,
    MESHCORE,
    ;

    /** Goes through a Pharos. */
    val bridged: Boolean get() = this == SIGNAL || this == WHATSAPP || this == MESHCORE
}

/** How a received message was protected on air. Both verify the sender. */
@Serializable
enum class Auth { SIGNED, ENCRYPTED }

/** Sent messages: waiting for the ACK, ACKed by the contact or Pharos, or given up. */
@Serializable
enum class Delivery { PENDING, ACKED, FAILED }

@Serializable
data class ChatMessage(
    val id: Long,
    val chatId: String,
    val outgoing: Boolean,
    val text: String,
    val atMillis: Long,
    val auth: Auth,
    val delivery: Delivery = Delivery.PENDING,
    /** Group chats: who sent it. */
    val sender: String? = null,
    val sos: Boolean = false,
    /** A stock phrase id (`keryx::canned`): shown in the app's language; [text] is the Spanish original. */
    val phrase: Int? = null,
    /** Position attached ("Estoy bien", position, SOS). */
    val position: GeoFix? = null,
)

/** A GNSS fix: 6 bytes on air (~1.2 m resolution); accuracy is shown locally only. */
@Serializable
data class GeoFix(val lat: Double, val lon: Double, val accuracyM: Float? = null, val atMillis: Long = 0)

@Serializable
data class Chat(
    val id: String,
    val kind: ChatKind,
    val title: String,
    /** Callsign, phone number or MeshCore key prefix. */
    val detail: String? = null,
    /** Emergency encryption for this chat (default: signed plaintext). */
    val encrypted: Boolean = false,
    val unread: Int = 0,
    val last: ChatMessage? = null,
    /** Contact key (direct), group URI (group) or the Pharos used (bridged). */
    val ref: String? = null,
    /** Hidden under "Archived chats" and silenced. */
    val archived: Boolean = false,
    /** Pinned at the top (at most [MAX_PINNED]); the time it was pinned orders them. */
    val pinnedAt: Long? = null,
    /** Marked unread by hand: a dot without a number. */
    val markedUnread: Boolean = false,
    /** Blocked: nothing in or out. */
    val blocked: Boolean = false,
) {
    companion object {
        const val MAX_PINNED = 3
    }
}

@Serializable
data class Contact(
    val card: Card,
    val verified: Boolean,
    val safetyNumber: String,
    val blocked: Boolean = false,
)

@Serializable
data class Group(
    val id: String,
    val name: String,
    /** The invitation URI (secret). */
    val uri: String,
    val members: List<String>,
    val blocked: Boolean = false,
)

@Serializable
enum class Registration { REGISTERED, PENDING, REFUSED, NEEDS_CODE, NOT_REGISTERED }

/** Who may use a Pharos, as its announce says. */
enum class PharosAccess { OPEN, CODE, CLOSED }

@Serializable
data class Pharos(
    val card: Card,
    val verified: Boolean,
    val active: Boolean,
    val lastHeardMillis: Long?,
    val snrDb: Int?,
    val registration: Registration,
    val bridges: Set<ChatKind>,
    val safetyNumber: String,
    val blocked: Boolean = false,
    /** We may manage it remotely: listed as admin in its config, or signed in with its admin password. */
    val admin: Boolean = false,
)

/**
 * An admin command sent to a Pharos (plain text to the Pharos itself, decision
 * 0020) and its reply once it arrives.
 */
@Serializable
data class AdminEntry(
    val id: Long,
    val pharosKey: String,
    /** What went on air, e.g. "aceptar @bea". The password is never kept. */
    val command: String,
    val atMillis: Long,
    val reply: String? = null,
)

/** A Signal, WhatsApp or MeshCore contact used before, listed in the new-chat screens. */
@Serializable
data class Recent(val kind: ChatKind, val address: String, val name: String?) {
    val chatId: String get() = "${kind.name.lowercase()}:$address"
    val shown: String get() = name ?: if (address.all { it.isDigit() }) "+$address" else address
}

/** Something blocked: a contact, group, bridged contact, or a Pharos (`kind` null). */
@Serializable
data class Blocked(val key: String, val title: String, val kind: ChatKind?)

/** Chats removed by [io.alvaroedo.skypost.data.Repository.deleteChats], to undo. */
data class DeletedChats(val chats: List<Chat>, val messages: Map<String, List<ChatMessage>>)

data class Discovered(
    val card: Card,
    val snrDb: Int,
    val trusted: Boolean,
    val bridges: Set<ChatKind>,
    val access: PharosAccess = PharosAccess.OPEN,
)

@Serializable
data class Identity(
    val card: Card,
    val uri: String,
    val safetyNumber: String,
)

@Serializable
data class Settings(
    /** Automatic Pharos ping, minutes; 0 = off (default: less RF footprint). */
    val pingIntervalMin: Int = 0,
    /** Emergency encryption for every chat, overriding the per-chat switch. */
    val encryptAll: Boolean = false,
    /** Chats the SOS goes to; null = automatic (every group). */
    val sosChats: Set<String>? = null,
)

/** Something scanned, pasted or opened from a `skypost:` / `meshcore://` link. */
sealed interface Scanned {
    data class Person(val card: Card, val safetyNumber: String) : Scanned

    data class PharosCard(val card: Card, val safetyNumber: String) : Scanned

    data class GroupInvite(val name: String, val uri: String) : Scanned

    /** A MeshCore contact (channels are never bridged). */
    data class MeshCore(val name: String, val key: String) : Scanned
}
