package io.alvaroedo.skypost.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    /** Direct chats: the contact's safety number. */
    val safetyNumber: String? = null,
    /** Emergency encryption is on for every chat (Settings); the per-chat switch is moot. */
    val encryptAll: Boolean = false,
) {
    /** Messages go out encrypted. */
    val encrypted: Boolean get() = encryptAll || chat?.encrypted == true
}

class ChatViewModel(private val repo: Repository, private val chatId: String) : ViewModel() {
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
            safetyNumber = contacts.firstOrNull { it.card.key == chat?.ref && chat.kind == ChatKind.DIRECT }?.safetyNumber,
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

    fun ping() {
        _ping.value = PingResult.Waiting
        viewModelScope.launch {
            _ping.value = repo.pingChat(chatId)?.let { PingResult.Heard(it) } ?: PingResult.Silent
        }
    }

    fun pingShown() {
        if (_ping.value != PingResult.Waiting) _ping.value = null
    }

    fun sendStatusOk() = repo.sendStatusOk(chatId)

    fun sendPosition() = repo.sendPosition(chatId)

    fun setEncrypted(on: Boolean) = repo.setEncrypted(chatId, on)

    fun markRead() = repo.markRead(chatId)

    companion object {
        /** Long texts cost minutes of airtime; Pharos also caps bridged texts at 200. */
        const val MAX_CHARS = 200
    }
}
