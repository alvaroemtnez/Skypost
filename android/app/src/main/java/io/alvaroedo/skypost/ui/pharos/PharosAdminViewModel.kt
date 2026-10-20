package io.alvaroedo.skypost.ui.pharos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.model.AdminEntry
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.Pharos
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PharosAdminUi(
    val pharos: Pharos? = null,
    /** Emergency encryption for everything: the only time the password may go on air. */
    val encryptAll: Boolean = false,
    /** Newest first. */
    val log: List<AdminEntry> = emptyList(),
)

/** Who a command is about: a field user, or a Signal/WhatsApp/MeshCore contact. */
sealed interface AdminTarget {
    data class FieldUser(val alias: String) : AdminTarget

    data class Bridged(val kind: ChatKind, val input: String) : AdminTarget
}

/**
 * Remote management of one Pharos (decision 0020): builds the admin command
 * texts (English) and sends them as messages to the Pharos. Replies are
 * English too and are shown as they come.
 */
class PharosAdminViewModel(private val repo: Repository, private val key: String) : ViewModel() {
    val ui: StateFlow<PharosAdminUi> = combine(repo.pharos, repo.settings, repo.adminLog(key)) { list, settings, log ->
        PharosAdminUi(list.firstOrNull { it.card.key == key }, settings.encryptAll, log.asReversed())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PharosAdminUi())

    private fun send(command: String) = repo.sendAdmin(key, command)

    fun help() = send("help")

    fun status() = send("status")

    fun clients() = send("clients")

    fun admins() = send("admins")

    /** False if the alias is not valid. */
    fun decide(alias: String, accept: Boolean): Boolean {
        val a = alias(alias) ?: return false
        send(if (accept) "accept @$a" else "refuse @$a")
        return true
    }

    fun block(target: AdminTarget, on: Boolean): Boolean {
        val t = text(target) ?: return false
        send(if (on) "block $t" else "unblock $t")
        return true
    }

    /** Authorise a bridged contact to write first. */
    fun allow(target: AdminTarget.Bridged): Boolean {
        val t = text(target) ?: return false
        send("allow $t")
        return true
    }

    fun revoke(target: AdminTarget): Boolean {
        val t = text(target) ?: return false
        send("revoke $t")
        return true
    }

    /** False when it was not sent (encryption off). */
    fun login(password: String): Boolean = repo.adminLogin(key, password.trim())

    private fun alias(input: String): String? = input.trim().removePrefix("@").takeIf(repo::validAlias)

    /**
     * The command argument: `@alias`, or the network and address (over the
     * radio the network is always named), or null if the input is not usable.
     */
    fun text(target: AdminTarget): String? = when (target) {
        is AdminTarget.FieldUser -> alias(target.alias)?.let { "@$it" }
        is AdminTarget.Bridged -> {
            val t = target.input.trim()
            when (target.kind) {
                ChatKind.MESHCORE -> t.lowercase().takeIf { HEX_KEY.matches(it) }?.let { "meshcore $it" }
                ChatKind.SIGNAL if USERNAME.matches(t) -> "signal $t"
                ChatKind.SIGNAL, ChatKind.WHATSAPP ->
                    repo.normalizePhone(t).takeIf { it.length in 8..15 }?.let { "${target.kind.name.lowercase()} +$it" }
                else -> null
            }
        }
    }

    private companion object {
        val HEX_KEY = Regex("^[0-9a-f]{64}$")

        /** Signal usernames: letters, digits, `_`, then `.` and at least two digits. */
        val USERNAME = Regex("^[A-Za-z_][A-Za-z0-9_]{2,31}\\.[0-9]{2,9}$")
    }
}
