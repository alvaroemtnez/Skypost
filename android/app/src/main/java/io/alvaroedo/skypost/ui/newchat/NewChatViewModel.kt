package io.alvaroedo.skypost.ui.newchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.Contact
import io.alvaroedo.skypost.model.Group
import io.alvaroedo.skypost.model.Recent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class NewChatViewModel(private val repo: Repository) : ViewModel() {
    val contacts: StateFlow<List<Contact>> = repo.contacts
    val groups: StateFlow<List<Group>> = repo.groups

    /** Bridged chats need an active Pharos. */
    val hasPharos: StateFlow<Boolean> = repo.pharos.map { list -> list.any { it.active } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repo.pharos.value.any { it.active })

    val recents: StateFlow<List<Recent>> = repo.recents

    fun chatWith(key: String): String = repo.chatWith(key)

    fun openRecent(r: Recent): String? = repo.openBridged(r.kind, r.address, r.name)

    fun forgetRecent(r: Recent) = repo.forgetRecent(r)

    fun removeContact(key: String) = repo.removeContact(key)

    fun leaveGroup(id: String) = repo.leaveGroup(id)

    fun setContactBlocked(key: String, on: Boolean) = repo.setContactBlocked(key, on)

    fun setGroupBlocked(id: String, on: Boolean) = repo.setGroupBlocked(id, on)

    fun setRecentBlocked(r: Recent, on: Boolean) = repo.setRecentBlocked(r, on)

    /** Keys of everything blocked (a recent's key is its chat id). */
    val blockedKeys: StateFlow<Set<String>> = repo.blocked.map { l -> l.map { it.key }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun chatInGroup(id: String): String = repo.chatInGroup(id)

    fun createGroup(name: String): String? = name.trim().takeIf { it.isNotEmpty() }?.let(repo::createGroup)

    /**
     * A phone number (any format) or, for Signal, a username. Returns the
     * address to open, or null if the input is not usable.
     */
    fun address(kind: ChatKind, input: String): String? {
        val t = input.trim()
        if (kind == ChatKind.SIGNAL && t.any { it.isLetter() }) {
            return t.takeIf { USERNAME.matches(it) }
        }
        return repo.normalizePhone(t).takeIf { it.length in 8..15 }
    }

    /** Opens the chat through the active Pharos; null without one or with a bad address. */
    fun openBridged(kind: ChatKind, input: String, name: String?): String? =
        address(kind, input)?.let { repo.openBridged(kind, it, name?.takeIf(String::isNotBlank)) }

    private companion object {
        /** Signal usernames: letters, digits, `_`, then `.` and at least two digits. */
        val USERNAME = Regex("^[A-Za-z_][A-Za-z0-9_]{2,31}\\.[0-9]{2,9}$")
    }
}
