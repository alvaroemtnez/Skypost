package io.alvaroedo.skypost.ui.pharos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.model.Discovered
import io.alvaroedo.skypost.model.Pharos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PharosUi(
    val list: List<Pharos> = emptyList(),
    val discovering: Boolean = false,
    /** Answers to the last discovery, null before the first one. */
    val found: List<Discovered>? = null,
    val pinging: Boolean = false,
    /** Result of the last ping: true answered, false silent, null none yet. */
    val lastPing: Boolean? = null,
)

class PharosViewModel(private val repo: Repository) : ViewModel() {
    private val local = MutableStateFlow(PharosUi())

    val ui: StateFlow<PharosUi> = combine(repo.pharos, local) { list, l ->
        val trusted = list.map { it.card.key }.toSet()
        // Blocked Pharos only show under Settings > Blocked.
        val blocked = list.filter { it.blocked }.map { it.card.key }.toSet()
        l.copy(
            list = list.filterNot { it.blocked },
            found = l.found?.filterNot { it.card.key in blocked }?.map { it.copy(trusted = it.card.key in trusted) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PharosUi())

    fun discover() {
        if (local.value.discovering) return
        local.update { it.copy(discovering = true) }
        viewModelScope.launch {
            val found = repo.discover()
            local.update { it.copy(discovering = false, found = found) }
        }
    }

    fun ping() {
        if (local.value.pinging) return
        local.update { it.copy(pinging = true) }
        viewModelScope.launch {
            val ok = repo.ping()
            local.update { it.copy(pinging = false, lastPing = ok) }
        }
    }

    fun trust(d: Discovered, code: String?) = repo.trust(d, code?.trim()?.takeIf(String::isNotEmpty))

    fun register(key: String, code: String) = repo.register(key, code.trim())

    /** Register without a code: an access request for the admins. */
    fun requestAccess(key: String) = repo.register(key, null)

    fun setActive(key: String) = repo.setActivePharos(key)

    fun setVerified(key: String, on: Boolean) = repo.setPharosVerified(key, on)

    fun remove(key: String) = repo.removePharos(key)

    fun block(key: String) = repo.setPharosBlocked(key, true)
}
