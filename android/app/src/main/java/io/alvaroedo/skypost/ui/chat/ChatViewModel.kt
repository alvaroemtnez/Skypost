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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
            frames = repo.framesFor(chatId, d),
            via = via?.card?.alias,
            noPharos = chat?.kind?.bridged == true && via == null,
            encryptAll = settings.encryptAll,
            safetyNumber = contacts.firstOrNull { it.card.key == chat?.ref && chat.kind == ChatKind.DIRECT }?.safetyNumber,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUi())

    init {
        repo.markRead(chatId)
    }

    fun phrases(): List<String> = repo.phrases()

    fun setDraft(text: String) {
        draft.value = text.take(MAX_CHARS)
    }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty()) return
        repo.send(chatId, text)
        draft.value = ""
    }

    fun sendPhrase(phrase: String) = repo.send(chatId, phrase)

    fun sendStatusOk() = repo.sendStatusOk(chatId)

    fun sendPosition() = repo.sendPosition(chatId)

    fun setEncrypted(on: Boolean) = repo.setEncrypted(chatId, on)

    fun markRead() = repo.markRead(chatId)

    companion object {
        /** Long texts cost minutes of airtime; Pharos also caps bridged texts at 200. */
        const val MAX_CHARS = 200
    }
}
