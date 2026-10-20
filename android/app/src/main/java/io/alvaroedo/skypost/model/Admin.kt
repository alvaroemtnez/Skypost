package io.alvaroedo.skypost.model

import kotlinx.serialization.Serializable

/**
 * Remote management of a Pharos (decision 0021). These mirror
 * `keryx::admin::{Command, Reply}`; the Rust core encodes them on air (one
 * opcode byte and compact arguments) and the app shows replies in its own
 * language.
 */
enum class PharosSwitch { OPEN, REQUESTS, LOGIN, EMERGENCY, STEALTH }

/** Network of a user or contact in admin replies. */
enum class Who { RADIO, SIGNAL, WHATSAPP, MESHCORE }

/**
 * Who a command is about. Never by alias: anyone can claim one. Radio users
 * and MeshCore contacts go by their 8-byte id (16 hex digits; for a radio
 * user the start of its safety code); [name] is only for showing.
 */
@Serializable
sealed interface AdminTarget {
    @Serializable
    data class User(val id: String, val name: String) : AdminTarget

    /** Signal phone or username, WhatsApp phone (digits, no `+`). */
    @Serializable
    data class Contact(val kind: ChatKind, val address: String) : AdminTarget

    @Serializable
    data class MeshCore(val id: String, val name: String) : AdminTarget
}

@Serializable
sealed interface AdminCommand {
    @Serializable
    data object Status : AdminCommand

    @Serializable
    data object Requests : AdminCommand

    @Serializable
    data class Accept(val num: Int) : AdminCommand

    @Serializable
    data class Refuse(val num: Int) : AdminCommand

    @Serializable
    data object Clients : AdminCommand

    @Serializable
    data object Admins : AdminCommand

    @Serializable
    data class Block(val target: AdminTarget, val on: Boolean) : AdminCommand

    /** List who is blocked. */
    @Serializable
    data object Blocked : AdminCommand

    /** Take a user or contact off the Pharos without blocking: they may ask again. */
    @Serializable
    data class Remove(val target: AdminTarget) : AdminCommand

    /** Signal or WhatsApp only: MeshCore contacts join by request. */
    @Serializable
    data class Allow(val target: AdminTarget.Contact) : AdminCommand

    @Serializable
    data class AddAdmin(val target: AdminTarget) : AdminCommand

    @Serializable
    data class RemoveAdmin(val target: AdminTarget) : AdminCommand

    @Serializable
    data class Set(val switch: PharosSwitch, val on: Boolean) : AdminCommand

    /** The password itself is never kept. */
    @Serializable
    data object Login : AdminCommand
}

enum class AdminOutcome { OK, NOT_ADMIN, UNKNOWN, WRONG_PASSWORD, PASSWORD_IN_CLEAR, LAST_ADMIN, LOGIN_OFF, LOCKED, REFUSED }

/** [id]: 16 hex digits, for radio users and MeshCore contacts. */
@Serializable
data class RequestEntry(val num: Int, val who: Who, val name: String, val id: String? = null)

@Serializable
data class ClientEntry(val status: Registration, val heardMin: Int?, val alias: String, val id: String)

/** Someone in a list (admins, blocked) or a notice: the target to act on them, and a name. */
@Serializable
data class Party(val target: AdminTarget, val name: String) {
    val who: Who get() = when (target) {
        is AdminTarget.User -> Who.RADIO
        is AdminTarget.MeshCore -> Who.MESHCORE
        is AdminTarget.Contact -> if (target.kind == ChatKind.WHATSAPP) Who.WHATSAPP else Who.SIGNAL
    }

    val id: String? get() = when (target) {
        is AdminTarget.User -> target.id
        is AdminTarget.MeshCore -> target.id
        is AdminTarget.Contact -> null
    }
}

@Serializable
sealed interface AdminReply {
    @Serializable
    data class Done(val outcome: AdminOutcome, val subject: String? = null) : AdminReply

    @Serializable
    data class Status(
        val switches: kotlin.collections.Set<PharosSwitch>,
        val registered: Int,
        val requests: Int,
        val listening: Int,
        val queued: Int,
        val contacts: Int,
    ) : AdminReply

    @Serializable
    data class Requests(val list: List<RequestEntry>) : AdminReply

    @Serializable
    data class Clients(val list: List<ClientEntry>) : AdminReply

    @Serializable
    data class Admins(val list: List<Party>) : AdminReply

    @Serializable
    data class Blocked(val list: List<Party>) : AdminReply

    /** Unsolicited: new access requests (no personal data on air). */
    @Serializable
    data class NewRequests(val networks: kotlin.collections.Set<Who>, val pending: Int) : AdminReply

    /** Unsolicited: another admin changed the switches. */
    @Serializable
    data class SwitchesChanged(val switches: kotlin.collections.Set<PharosSwitch>) : AdminReply

    /** Unsolicited: someone became admin or stopped being one. */
    @Serializable
    data class AdminChanged(val added: Boolean, val party: Party) : AdminReply
}

/**
 * The admin log of one Pharos: a command we sent and its reply, or a notice
 * the Pharos sent on its own (`command` null).
 */
@Serializable
data class AdminEntry(
    val id: Long,
    val pharosKey: String,
    val command: AdminCommand?,
    val atMillis: Long,
    val reply: AdminReply? = null,
)
