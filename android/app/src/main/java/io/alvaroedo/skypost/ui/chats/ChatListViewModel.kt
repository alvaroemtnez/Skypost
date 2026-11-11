package io.alvaroedo.skypost.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.DeletedChats
import io.alvaroedo.skypost.model.Pharos
import io.alvaroedo.skypost.radio.RadioModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class ChatListUi(
    val chats: List<Chat> = emptyList(),
    /** null = all kinds. */
    val filter: ChatKind? = null,
    /** Chats per kind; chips for kinds with none are hidden. */
    val counts: Map<ChatKind, Int> = emptyMap(),
    val activePharos: Pharos? = null,
    /** Names of the chats an SOS would go to now. */
    val sosTargets: List<String> = emptyList(),
    val archivedCount: Int = 0,
    /** Some archived chat has unread messages. */
    val archivedUnread: Boolean = false,
    /** Selected chats (multi-select mode when not empty). */
    val selected: Set<String> = emptySet(),
    /** The search typed in the top bar; blank = no search. */
    val query: String = "",
    /** The radio's short name for the top bar ("QMX+"), null if none chosen. */
    val radioName: String? = null,
    /** The radio is a custom one (shown as "Custom" when it has no name). */
    val radioCustom: Boolean = false,
)

/** A chat matches a search by its name, its detail (callsign, number) or its last message. */
internal fun Chat.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return title.contains(q, ignoreCase = true) ||
        detail?.contains(q, ignoreCase = true) == true ||
        last?.text?.contains(q, ignoreCase = true) == true
}

/** Pinned first (in the order they were pinned), then the most recent. */
internal fun List<Chat>.ordered(): List<Chat> =
    sortedWith(
        compareBy<Chat> { it.pinnedAt == null }
            .thenBy { it.pinnedAt ?: 0L }
            // 0L, not 0: a boxed Int against a Long crashes the comparator (empty chats).
            .thenByDescending { it.last?.atMillis ?: 0L },
    )

/** The chat list, or with [archived] the archived chats. */
class ChatListViewModel(private val repo: Repository, private val archived: Boolean = false) : ViewModel() {
    private val filter = MutableStateFlow<ChatKind?>(null)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val query = MutableStateFlow("")
    private val view = combine(filter, query, ::Pair)

    val ui: StateFlow<ChatListUi> = combine(repo.chats, repo.pharos, repo.settings, view, selected) { all, pharos, settings, (f, q), sel ->
        val here = all.filter { it.archived == archived }
        val shown = (if (f == null) here else here.filter { it.kind == f }).filter { it.matches(q) }
        val arch = all.filter { it.archived }
        ChatListUi(
            chats = shown.ordered(),
            filter = f,
            counts = here.groupingBy { it.kind }.eachCount(),
            activePharos = pharos.firstOrNull { it.active },
            sosTargets = repo.sosTargets().map { it.title },
            archivedCount = arch.size,
            archivedUnread = arch.any { it.unread > 0 || it.markedUnread },
            selected = sel.filterTo(mutableSetOf()) { id -> here.any { it.id == id } },
            query = q,
            radioName = settings.radioShortName,
            radioCustom = settings.radio == RadioModel.CUSTOM,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUi())

    fun setFilter(kind: ChatKind?) {
        filter.value = kind
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun toggleSelected(id: String) = selected.update { if (id in it) it - id else it + id }

    fun clearSelection() {
        selected.value = emptySet()
    }

    fun setArchived(ids: Set<String>, on: Boolean) {
        repo.setArchived(ids, on)
        clearSelection()
    }

    fun setMarkedUnread(ids: Set<String>, on: Boolean) {
        repo.setMarkedUnread(ids, on)
        clearSelection()
    }

    /** False if three chats are already pinned. */
    fun setPinned(id: String, on: Boolean): Boolean = repo.setPinned(id, on).also { if (it) clearSelection() }

    fun delete(ids: Set<String>): DeletedChats = repo.deleteChats(ids).also { clearSelection() }

    fun restore(deleted: DeletedChats) = repo.restoreChats(deleted)

    fun setBlocked(id: String, on: Boolean) = repo.setChatBlocked(id, on)
}
