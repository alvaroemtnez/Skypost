package io.alvaroedo.skypost.ui.family

import androidx.lifecycle.ViewModel
import io.alvaroedo.skypost.core.FakeKeryxCore
import io.alvaroedo.skypost.core.KeryxCore
import io.alvaroedo.skypost.core.OutMessage
import io.alvaroedo.skypost.model.DeliveryState
import io.alvaroedo.skypost.model.IncomingItem
import io.alvaroedo.skypost.model.OutgoingItem
import io.alvaroedo.skypost.radio.FakeRadioLink
import io.alvaroedo.skypost.radio.RadioLink
import io.alvaroedo.skypost.radio.RadioState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FamilyUiState(
    val radio: RadioState = RadioState.DISCONNECTED,
    val band: String = "40 m",
    val timeFix: Boolean = false,
    val outgoing: List<OutgoingItem> = emptyList(),
    val inbox: List<IncomingItem> = emptyList(),
    /** Set from the advanced screen. Off = signed plaintext (legal default). */
    val emergencyEncryption: Boolean = false,
)

/**
 * Family mode: no bands, modes or lanes to choose. The core picks the band
 * from the shared schedule and the lane from the base's SNR reports.
 */
class FamilyViewModel(
    private val core: KeryxCore = FakeKeryxCore(),
    private val radio: RadioLink = FakeRadioLink(),
) : ViewModel() {

    private val _ui = MutableStateFlow(FamilyUiState(radio = radio.state.value))
    val ui: StateFlow<FamilyUiState> = _ui.asStateFlow()

    fun setEmergencyEncryption(on: Boolean) = _ui.update { it.copy(emergencyEncryption = on) }

    // TODO(phase 2): attach the GNSS position when there is a recent fix.
    fun sendOk() = enqueue("Estoy bien", OutMessage.StatusOk(lat = null, lon = null))

    fun sendHelp() = enqueue("Necesito ayuda (SOS)", OutMessage.Sos(lat = null, lon = null))

    fun sendText(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) enqueue(t, OutMessage.Text(t))
    }

    private fun enqueue(label: String, msg: OutMessage) {
        val frame = core.encode(msg, encrypted = _ui.value.emergencyEncryption)
        val item = OutgoingItem(id = frame.counter.toLong(), label = label, state = DeliveryState.QUEUED)
        _ui.update { it.copy(outgoing = listOf(item) + it.outgoing) }
        // TODO(phase 2): hand frame.wire to the radio service; update the item
        // state from ARQ events (SENT, HEARD_BY_BASE, DELIVERED, READ, FAILED).
    }
}
