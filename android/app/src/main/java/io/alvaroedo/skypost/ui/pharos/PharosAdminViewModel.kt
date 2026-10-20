package io.alvaroedo.skypost.ui.pharos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.model.AdminCommand
import io.alvaroedo.skypost.model.AdminEntry
import io.alvaroedo.skypost.model.AdminOutcome
import io.alvaroedo.skypost.model.AdminReply
import io.alvaroedo.skypost.model.AdminTarget
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.Pharos
import io.alvaroedo.skypost.model.PharosSwitch
import io.alvaroedo.skypost.model.RequestEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PharosAdminUi(
    val pharos: Pharos? = null,
    /** Emergency encryption for everything: the password may only go out then. */
    val encryptAll: Boolean = false,
    /** Newest first. */
    val log: List<AdminEntry> = emptyList(),
    /** The switches as last heard (status, notices, our own changes); null before any. */
    val switches: Set<PharosSwitch>? = null,
    /** Pending requests from the last list, minus those decided since; null before any. */
    val requests: List<RequestEntry>? = null,
    /** A request list was asked for and has not arrived. */
    val requestsLoading: Boolean = false,
)

/**
 * Remote management of one Pharos (decision 0021). Commands go on air as
 * compact `Admin` messages; replies come back structured and the screen shows
 * them in the app's language.
 */
class PharosAdminViewModel(private val repo: Repository, private val key: String) : ViewModel() {
    val ui: StateFlow<PharosAdminUi> = combine(repo.pharos, repo.settings, repo.adminLog(key)) { list, settings, log ->
        PharosAdminUi(
            pharos = list.firstOrNull { it.card.key == key },
            encryptAll = settings.encryptAll,
            log = log.asReversed(),
            switches = switchesFrom(log),
            requests = requestsFrom(log),
            requestsLoading = log.lastOrNull { it.command == AdminCommand.Requests }?.reply == null &&
                log.any { it.command == AdminCommand.Requests },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PharosAdminUi())

    fun send(command: AdminCommand) = repo.sendAdmin(key, command)

    /** It goes out in plain and puts a name or number on air: ask first. */
    fun needsWarning(command: AdminCommand): Boolean = !ui.value.encryptAll && repo.revealsPersonalData(command)

    /** False when it was not sent (encryption off). */
    fun login(password: String): Boolean = repo.adminLogin(key, password.trim())

    /**
     * What was typed, as a target: an alias for a field user, a phone or a
     * Signal username, or the first 8 hex digits of a MeshCore key. Null if
     * it is not usable.
     */
    fun target(kind: ChatKind?, input: String): AdminTarget? {
        val t = input.trim()
        return when (kind) {
            null -> t.removePrefix("@").takeIf(repo::validAlias)?.let { AdminTarget.FieldUser(it) }
            ChatKind.MESHCORE -> t.lowercase().takeIf { HEX.matches(it) }?.let { AdminTarget.MeshCore(it.take(8)) }
            ChatKind.SIGNAL if USERNAME.matches(t) -> AdminTarget.Contact(ChatKind.SIGNAL, t)
            ChatKind.SIGNAL, ChatKind.WHATSAPP ->
                repo.normalizePhone(t).takeIf { it.length in 8..15 }?.let { AdminTarget.Contact(kind, it) }
            else -> null
        }
    }

    companion object {
        /** A MeshCore key, or its first 8 hex digits at least. */
        private val HEX = Regex("^[0-9a-f]{8,64}$")

        /** Signal usernames: letters, digits, `_`, then `.` and at least two digits. */
        private val USERNAME = Regex("^[A-Za-z_][A-Za-z0-9_]{2,31}\\.[0-9]{2,9}$")

        /** Oldest first: status and notices set them, our own changes apply unless refused. */
        fun switchesFrom(log: List<AdminEntry>): Set<PharosSwitch>? {
            var s: Set<PharosSwitch>? = null
            for (e in log) {
                when (val r = e.reply) {
                    is AdminReply.Status -> s = r.switches
                    is AdminReply.SwitchesChanged -> s = r.switches
                    else -> {
                        val c = e.command as? AdminCommand.Set ?: continue
                        if (r != null && (r as? AdminReply.Done)?.outcome != AdminOutcome.OK) continue
                        val base = s ?: continue
                        s = if (c.on) base + c.switch else base - c.switch
                    }
                }
            }
            return s
        }

        fun requestsFrom(log: List<AdminEntry>): List<RequestEntry>? {
            val i = log.indexOfLast { it.reply is AdminReply.Requests }
            if (i < 0) return null
            val decided = log.drop(i + 1).mapNotNull {
                when (val c = it.command) {
                    is AdminCommand.Accept -> c.num
                    is AdminCommand.Refuse -> c.num
                    else -> null
                }
            }.toSet()
            return (log[i].reply as AdminReply.Requests).list.filterNot { it.num in decided }
        }
    }
}
