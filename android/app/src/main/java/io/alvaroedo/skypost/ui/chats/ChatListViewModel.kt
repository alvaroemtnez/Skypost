package io.alvaroedo.skypost.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.Pharos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ChatListUi(
    val chats: List<Chat> = emptyList(),
    /** null = all kinds. */
    val filter: ChatKind? = null,
    /** Chats per kind; chips for kinds with none are hidden. */
    val counts: Map<ChatKind, Int> = emptyMap(),
    val activePharos: Pharos? = null,
    /** Names of the chats an SOS would go to now. */
    val sosTargets: List<String> = emptyList(),
)

class ChatListViewModel(private val repo: Repository) : ViewModel() {
    private val filter = MutableStateFlow<ChatKind?>(null)

    val ui: StateFlow<ChatListUi> = combine(repo.chats, repo.pharos, repo.settings, filter) { chats, pharos, _, f ->
        ChatListUi(
            sosTargets = repo.sosTargets().map { it.title },
            chats = if (f == null) chats else chats.filter { it.kind == f },
            filter = f,
            counts = chats.groupingBy { it.kind }.eachCount(),
            activePharos = pharos.firstOrNull { it.active },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUi())

    fun setFilter(kind: ChatKind?) {
        filter.value = kind
    }
}
