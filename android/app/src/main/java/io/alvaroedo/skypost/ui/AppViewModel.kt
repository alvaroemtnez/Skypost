package io.alvaroedo.skypost.ui

import androidx.lifecycle.ViewModel
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.location.Locator
import io.alvaroedo.skypost.model.Identity
import io.alvaroedo.skypost.model.Scanned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A code waiting for the user to confirm, and whether it was scanned in person. */
data class PendingImport(val scanned: Scanned, val inPerson: Boolean)

/** How an SOS went out: to how many chats, and whether it carried a position. */
data class SosResult(val sentTo: Int, val withPosition: Boolean)

/**
 * App-wide state: whether there is an identity yet, and a contact, Pharos,
 * group or MeshCore code waiting for the user to confirm (from a deep link, a
 * scan or the clipboard).
 */
class AppViewModel(private val repo: Repository, private val locator: Locator) : ViewModel() {
    val identity: StateFlow<Identity?> = repo.identity

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    /**
     * Returns false if the text is not a code the app understands. [inPerson]:
     * it came from the in-app scanner (verified), not a paste or a link.
     */
    fun offer(text: String?, inPerson: Boolean = false): Boolean {
        val s = text?.let(repo::parse) ?: return false
        _pendingImport.value = PendingImport(s, inPerson)
        return true
    }

    /** Adds what was offered; returns the chat to open, if any. */
    fun confirmImport(): String? {
        val p = _pendingImport.value ?: return null
        _pendingImport.value = null
        return repo.import(p.scanned, p.inPerson)
    }

    fun dismissImport() {
        _pendingImport.value = null
    }

    /** Gets a position (20 s at most: an SOS must not wait long), then sends the SOS. */
    suspend fun sos(): SosResult {
        val fix = locator.fix(timeoutMs = 20_000)
        return SosResult(repo.sos(fix), fix != null)
    }
}
