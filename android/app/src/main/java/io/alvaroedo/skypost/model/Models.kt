package io.alvaroedo.skypost.model

import io.alvaroedo.skypost.core.Card

/** The five kinds of conversation, in filter-chip order. */
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
enum class Auth { SIGNED, ENCRYPTED }

/** Sent messages: waiting for the ACK, ACKed by the contact or Pharos, or given up. */
enum class Delivery { PENDING, ACKED, FAILED }

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
)

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
)

data class Contact(
    val card: Card,
    val verified: Boolean,
    val safetyNumber: String,
)

data class Group(
    val id: String,
    val name: String,
    /** The invitation URI (secret). */
    val uri: String,
    val members: List<String>,
)

enum class Registration { REGISTERED, PENDING, REFUSED, NOT_REGISTERED }

data class Pharos(
    val card: Card,
    val verified: Boolean,
    val active: Boolean,
    val lastHeardMillis: Long?,
    val snrDb: Int?,
    val registration: Registration,
    val bridges: Set<ChatKind>,
    val safetyNumber: String,
)

data class Discovered(
    val card: Card,
    val snrDb: Int,
    val trusted: Boolean,
    val bridges: Set<ChatKind>,
)

data class Identity(
    val card: Card,
    val uri: String,
    val safetyNumber: String,
)

data class Settings(
    /** Automatic Pharos ping, minutes; 0 = off (default: less RF footprint). */
    val pingIntervalMin: Int = 0,
)

/** Something scanned, pasted or opened from a `skypost:` / `meshcore://` link. */
sealed interface Scanned {
    data class Person(val card: Card, val safetyNumber: String) : Scanned

    data class PharosCard(val card: Card, val safetyNumber: String) : Scanned

    data class GroupInvite(val name: String, val uri: String) : Scanned

    data class MeshCore(val name: String, val key: String, val channel: Boolean) : Scanned
}
