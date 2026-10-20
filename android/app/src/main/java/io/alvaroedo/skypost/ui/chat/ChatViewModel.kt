package io.alvaroedo.skypost.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.alvaroedo.skypost.core.Card
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.ChatMessage
import io.alvaroedo.skypost.model.GeoFix
import io.alvaroedo.skypost.location.Locator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PingResult {
    data object Waiting : PingResult

    data class Heard(val snrDb: Int) : PingResult

    data object Silent : PingResult
}

data class ChatUi(
    val chat: Chat? = null,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    /** JS8 frames the draft would take (0 when blank). */
    val frames: Int = 0,
    /** Pharos the chat goes through (bridged chats). */
    val via: String? = null,
    /** Bridged chat without a usable Pharos: sending will fail. */
    val noPharos: Boolean = false,
    /** Direct chats: the contact's card, safety number and whether it was verified. */
    val contact: Card? = null,
    val safetyNumber: String? = null,
    val verified: Boolean = false,
    /** Emergency encryption is on for every chat (Settings); the per-chat switch is moot. */
    val encryptAll: Boolean = false,
) {
    /** Messages go out encrypted. */
    val encrypted: Boolean get() = encryptAll || chat?.encrypted == true
}

/** One-off results to show in a snackbar. */
enum class ChatEvent { SENT_WITHOUT_POSITION, NO_POSITION }

class ChatViewModel(
    private val repo: Repository,
    private val chatId: String,
    private val locator: Locator = Locator { null },
) : ViewModel() {
    private val _locating = MutableStateFlow(false)

    /** Waiting for a GNSS fix before sending. */
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private fun withFix(send: (GeoFix?) -> Unit) {
        if (_locating.value) return
        _locating.value = true
        viewModelScope.launch {
            try {
                send(locator.fix(timeoutMs = 30_000))
            } finally {
                _locating.value = false
            }
        }
    }

    private val draft = MutableStateFlow("")

    /** Stock phrases in the app's language (index = id), set by the screen. */
    private var localPhrases: List<String> = emptyList()

    private val _ping = MutableStateFlow<PingResult?>(null)

    /** Last "are you listening?" result, for a snackbar. */
    val ping: StateFlow<PingResult?> = _ping.asStateFlow()

    fun setLocalPhrases(phrases: List<String>) {
        localPhrases = phrases
        draft.value = draft.value // re-evaluate the estimate
    }

    /** Typed text that is exactly a stock phrase goes as its 1-byte id. */
    private fun phraseId(text: String): Int? =
        localPhrases.indexOfFirst { it.equals(text.trim(), ignoreCase = true) }.takeIf { it >= 0 }

    val ui: StateFlow<ChatUi> = combine(
        repo.chats,
        repo.messages(chatId),
        draft,
        repo.pharos,
        repo.contacts.combine(repo.settings) { c, s -> c to s },
    ) { chats, msgs, d, pharos, (contacts, settings) ->
        val chat = chats.firstOrNull { it.id == chatId }
        val via = pharos.firstOrNull { it.card.key == chat?.ref }
        val contact = contacts.firstOrNull { it.card.key == chat?.ref && chat.kind == ChatKind.DIRECT }
        ChatUi(
            chat = chat,
            messages = msgs,
            draft = d,
            frames = when {
                d.isBlank() -> 0
                phraseId(d) != null -> repo.framesForPhrase(chatId)
                else -> repo.framesFor(chatId, d)
            },
            via = via?.card?.alias,
            noPharos = chat?.kind?.bridged == true && via == null,
            encryptAll = settings.encryptAll,
            contact = contact?.card,
            safetyNumber = contact?.safetyNumber,
            verified = contact?.verified == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUi())

    init {
        repo.markRead(chatId)
    }

    fun setDraft(text: String) {
        draft.value = text.take(MAX_CHARS)
    }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty()) return
        phraseId(text)?.let { repo.sendPhrase(chatId, it) } ?: repo.send(chatId, text)
        draft.value = ""
    }

    fun sendPhrase(id: Int) = repo.sendPhrase(chatId, id)

    fun setBlocked(on: Boolean) = repo.setChatBlocked(chatId, on)

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())

    /** Selected messages (bulk copy or delete). */
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    fun toggleSelected(id: Long) = _selected.update { if (id in it) it - id else it + id }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun retry(id: Long) = repo.retry(chatId, id)

    fun delete(ids: Set<Long>): List<ChatMessage> = repo.deleteMessages(chatId, ids).also { clearSelection() }

    fun restore(messages: List<ChatMessage>) = repo.restoreMessages(chatId, messages)

    fun ping() {
        _ping.value = PingResult.Waiting
        viewModelScope.launch {
            _ping.value = repo.pingChat(chatId)?.let { PingResult.Heard(it) } ?: PingResult.Silent
        }
    }

    fun pingShown() {
        if (_ping.value != PingResult.Waiting) _ping.value = null
    }

    /** "Estoy bien" goes even without a fix (it says so). */
    fun sendStatusOk() = withFix { fix ->
        repo.sendStatusOk(chatId, fix)
        if (fix == null) _events.tryEmit(ChatEvent.SENT_WITHOUT_POSITION)
    }

    fun sendPosition() = withFix { fix ->
        if (fix != null) repo.sendPosition(chatId, fix) else _events.tryEmit(ChatEvent.NO_POSITION)
    }

    fun setEncrypted(on: Boolean) = repo.setEncrypted(chatId, on)

    fun setVerified(on: Boolean) {
        ui.value.contact?.let { repo.setContactVerified(it.key, on) }
    }

    fun markRead() = repo.markRead(chatId)

    companion object {
        /** Long texts cost minutes of airtime; Pharos also caps bridged texts at 200. */
        const val MAX_CHARS = 200
    }
}
