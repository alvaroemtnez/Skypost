package io.alvaroedo.skypost.radio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RadioState { DISCONNECTED, CONNECTING, READY, TRANSMITTING }

/**
 * The radio over USB OTG. Phase 2 implementation notes:
 *
 * - Permission: `UsbManager.requestPermission`, or the USB_DEVICE_ATTACHED
 *   intent filter in the manifest (lets the user tick "always open").
 * - CAT/PTT: usb-serial-for-android (QDX: CDC-ACM; G90 + XGGComms: check the
 *   serial chip with `lsusb`). Do not use WebUSB: Android binds CDC-ACM to its
 *   own driver, so a web app cannot claim the QDX's CAT interface.
 * - Audio: `AudioManager.getDevices()` → the `TYPE_USB_DEVICE` input/output;
 *   `AudioRecord`/`AudioTrack.setPreferredDevice(...)`, input preset
 *   `UNPROCESSED` (no AGC/noise suppression on modem audio).
 * - Samples go to the Rust core (JS8 engine) through the FFI boundary.
 * - Run it all in a foreground service of type `connectedDevice`.
 */
interface RadioLink {
    val state: StateFlow<RadioState>
}

class FakeRadioLink : RadioLink {
    override val state: StateFlow<RadioState> = MutableStateFlow(RadioState.DISCONNECTED)
}
