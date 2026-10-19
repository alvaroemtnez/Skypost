package io.alvaroedo.skypost.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import io.alvaroedo.skypost.model.GeoFix
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Where the phone is, for "Estoy bien", positions and SOS. */
fun interface Locator {
    /** A fresh fix within [timeoutMs], else a recent one; null without permission or signal. */
    suspend fun fix(timeoutMs: Long): GeoFix?
}

/**
 * Android's own LocationManager: GPS first (works off-grid), then whatever
 * other provider the phone has. No Google Play services and no network
 * lookups: the fused provider is used only if the platform itself has it.
 */
class AndroidLocator(private val context: Context) : Locator {
    private val lm = context.getSystemService(LocationManager::class.java)

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked in granted()
    override suspend fun fix(timeoutMs: Long): GeoFix? {
        if (!granted()) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, "fused" /* LocationManager.FUSED_PROVIDER, API 31 */, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        // The best provider gets the whole time budget.
        providers.firstOrNull()?.let { p -> withTimeoutOrNull(timeoutMs) { current(p) } }?.let { return it.toFix() }
        // No fresh fix in time: accept one up to 15 minutes old.
        val cutoff = System.currentTimeMillis() - 15 * 60_000
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { it.time >= cutoff }
            .maxByOrNull { it.time }
            ?.toFix()
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(provider: String): Location? = suspendCancellableCoroutine { cont ->
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        LocationManagerCompat.getCurrentLocation(lm, provider, cancel, ContextCompat.getMainExecutor(context)) { loc ->
            if (cont.isActive) cont.resume(loc)
        }
    }
}

private fun Location.toFix() = GeoFix(
    lat = latitude,
    lon = longitude,
    accuracyM = if (hasAccuracy()) accuracy else null,
    atMillis = time,
)
