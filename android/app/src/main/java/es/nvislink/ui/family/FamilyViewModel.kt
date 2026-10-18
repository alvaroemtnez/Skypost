package es.nvislink.ui.family

import androidx.lifecycle.ViewModel
import es.nvislink.core.FakeNvlCore
import es.nvislink.core.NvlCore
import es.nvislink.core.OutMessage
import es.nvislink.model.DeliveryState
import es.nvislink.model.IncomingItem
import es.nvislink.model.OutgoingItem
import es.nvislink.radio.FakeRadioLink
import es.nvislink.radio.RadioLink
import es.nvislink.radio.RadioState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class FamilyUiState(
    val radio: RadioState = RadioState.DISCONNECTED,
    val band: String = "40 m",
    val timeFix: Boolean = false,
    val outgoing: List<OutgoingItem> = emptyList(),
    val inbox: List<IncomingItem> = emptyList(),
)

/**
 * Family mode: no bands, modes or lanes to choose. The core picks the band
 * from the shared schedule and the lane from the base's SNR reports.
 */
class FamilyViewModel(
    private val core: NvlCore = FakeNvlCore(),
    private val radio: RadioLink = FakeRadioLink(),
) : ViewModel() {

    private val _ui = MutableStateFlow(FamilyUiState(radio = radio.state.value))
    val ui: StateFlow<FamilyUiState> = _ui

    /** Set from the advanced screen. Off = signed plaintext (legal default). */
    var emergencyEncryption: Boolean = false

    // TODO(phase 2): attach the GNSS position when there is a recent fix.
    fun sendOk() = enqueue("Estoy bien", OutMessage.StatusOk(lat = null, lon = null))

    fun sendHelp() = enqueue("Necesito ayuda (SOS)", OutMessage.Sos(lat = null, lon = null))

    fun sendText(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) enqueue(t, OutMessage.Text(t))
    }

    private fun enqueue(label: String, msg: OutMessage) {
        val frame = core.encode(msg, encrypted = emergencyEncryption)
        val item = OutgoingItem(id = frame.counter.toLong(), label = label, state = DeliveryState.QUEUED)
        _ui.update { it.copy(outgoing = listOf(item) + it.outgoing) }
        // TODO(phase 2): hand frame.wire to the radio service; update the item
        // state from ARQ events (SENT, HEARD_BY_BASE, DELIVERED, READ, FAILED).
    }
}
