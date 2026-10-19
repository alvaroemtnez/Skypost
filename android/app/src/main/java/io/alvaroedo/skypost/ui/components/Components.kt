package io.alvaroedo.skypost.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.Auth
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.Delivery
import io.alvaroedo.skypost.model.Pharos
import io.alvaroedo.skypost.model.Scanned
import io.alvaroedo.skypost.ui.theme.extraColors
import androidx.compose.material3.SnackbarHostState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One snackbar host for the whole app, so any screen can confirm an action. */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

@DrawableRes
fun ChatKind.icon(): Int = when (this) {
    ChatKind.DIRECT -> R.drawable.ic_person
    ChatKind.GROUP -> R.drawable.ic_group
    ChatKind.SIGNAL -> R.drawable.ic_sms
    ChatKind.WHATSAPP -> R.drawable.ic_call
    ChatKind.MESHCORE -> R.drawable.ic_hub
}

@StringRes
fun ChatKind.label(): Int = when (this) {
    ChatKind.DIRECT -> R.string.kind_direct
    ChatKind.GROUP -> R.string.kind_group
    ChatKind.SIGNAL -> R.string.kind_signal
    ChatKind.WHATSAPP -> R.string.kind_whatsapp
    ChatKind.MESHCORE -> R.string.kind_meshcore
}

/** Initials in a tonal circle, with a small badge for the kind of chat. */
@Composable
fun Avatar(name: String, kind: ChatKind, size: Dp = 48.dp) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        ChatKind.DIRECT, ChatKind.GROUP -> colors.primaryContainer to colors.onPrimaryContainer
        ChatKind.SIGNAL, ChatKind.WHATSAPP -> colors.secondaryContainer to colors.onSecondaryContainer
        ChatKind.MESHCORE -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    Box(Modifier.size(size)) {
        Box(
            Modifier.size(size).background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().take(1).uppercase(Locale.ROOT).ifEmpty { "?" },
                style = MaterialTheme.typography.titleMedium,
                color = fg,
            )
        }
        Surface(
            shape = CircleShape,
            color = colors.surface,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(20.dp),
        ) {
            Icon(
                painterResource(kind.icon()),
                contentDescription = stringResource(kind.label()),
                tint = colors.onSurfaceVariant,
                modifier = Modifier.padding(2.dp),
            )
        }
    }
}

/** Shield = signed plaintext, padlock = encrypted. Both mean "verified sender". */
@Composable
fun AuthIcon(auth: Auth, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val (icon, desc) = when (auth) {
        Auth.SIGNED -> R.drawable.ic_verified_user to R.string.auth_signed
        Auth.ENCRYPTED -> R.drawable.ic_lock to R.string.auth_encrypted
    }
    Icon(painterResource(icon), stringResource(desc), modifier.size(14.dp), tint = tint)
}

@Composable
fun DeliveryIcon(d: Delivery, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val (icon, desc) = when (d) {
        Delivery.PENDING -> R.drawable.ic_schedule to R.string.delivery_pending
        Delivery.ACKED -> R.drawable.ic_check to R.string.delivery_acked
        Delivery.FAILED -> R.drawable.ic_error to R.string.delivery_failed
    }
    val t = if (d == Delivery.FAILED) MaterialTheme.colorScheme.error else tint
    Icon(painterResource(icon), stringResource(desc), modifier.size(14.dp), tint = t)
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
/** "14:05" today, "3 oct" before (in the app's language). */
fun shortTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(millis).atZone(zone)
    val day = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    return if (t.toLocalDate() == LocalDate.now(zone)) timeFmt.format(t) else day.format(t)
}

/** "Oído hace 4 min" / "hace 2 h" / "Sin noticias". */
@Composable
fun heardAgo(millis: Long?, now: Long = System.currentTimeMillis()): String {
    if (millis == null) return stringResource(R.string.pharos_never_heard)
    val min = ((now - millis) / 60_000).toInt().coerceAtLeast(0)
    return when {
        min < 1 -> stringResource(R.string.pharos_heard_now)
        min < 120 -> pluralStringResource(R.plurals.pharos_heard_min, min, min)
        else -> pluralStringResource(R.plurals.pharos_heard_h, min / 60, min / 60)
    }
}

/** A Pharos heard longer ago than this counts as silent (grey indicator). */
const val HEARD_RECENTLY_MS = 60 * 60_000L

/**
 * Colour for the SNR the Pharos last reported for us, by the lanes it allows:
 * green ≥ −10 dB (fast lanes), yellow ≥ −18 (Turbo), orange ≥ −24 (Normal),
 * red below (Slow only). Grey when there is no recent report.
 */
@Composable
fun signalColor(snrDb: Int?): Color {
    val x = MaterialTheme.extraColors
    return when {
        snrDb == null -> MaterialTheme.colorScheme.outline
        snrDb >= -10 -> x.signalGood
        snrDb >= -18 -> x.signalFair
        snrDb >= -24 -> x.signalPoor
        else -> x.signalBad
    }
}

/** Top-bar "signal" indicator: grey unless the active Pharos was heard recently, then by SNR. */
@Composable
fun PharosIndicator(active: Pharos?, onClick: () -> Unit, now: Long = System.currentTimeMillis()) {
    val snr = active?.snrDb?.takeIf { active.lastHeardMillis?.let { now - it < HEARD_RECENTLY_MS } == true }
    val tint = signalColor(snr)
    val desc = if (active == null) {
        stringResource(R.string.pharos_none)
    } else {
        stringResource(R.string.pharos_indicator, active.card.alias, heardAgo(active.lastHeardMillis, now))
    }
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = desc }) {
        Icon(painterResource(R.drawable.ic_cell_tower), contentDescription = null, tint = tint)
    }
}

/**
 * A QR code drawn from theme colours, always dark modules on a light ground
 * (scanners expect that): surface/onSurface in light theme, the inverse pair
 * in dark theme.
 */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
        )
    }
    val dark = isSystemInDarkTheme()
    val colors = MaterialTheme.colorScheme
    val ground = if (dark) colors.inverseSurface else colors.surface
    val ink = if (dark) colors.inverseOnSurface else colors.onSurface
    val desc = stringResource(R.string.qr_description)
    Box(
        modifier
            .aspectRatio(1f)
            .background(ground, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .semantics { contentDescription = desc },
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val cell = size.width / matrix.width
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (matrix[x, y]) {
                        drawRect(ink, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
}

/** Monospace safety number, to compare by eye or read out over the phone. */
@Composable
fun SafetyNumber(value: String, modifier: Modifier = Modifier) {
    Text(
        value,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
}

/** Confirm adding a contact, Pharos, group or MeshCore chat that was scanned, pasted or linked. */
@Composable
fun ImportDialog(scanned: Scanned, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, body, extra) = when (scanned) {
        is Scanned.Person -> Triple(
            stringResource(R.string.import_contact_title, scanned.card.alias),
            listOfNotNull(scanned.card.callsign).joinToString(),
            scanned.safetyNumber,
        )
        is Scanned.PharosCard -> Triple(
            stringResource(R.string.import_pharos_title, scanned.card.alias),
            stringResource(R.string.import_pharos_body, scanned.card.callsign.orEmpty()),
            scanned.safetyNumber,
        )
        is Scanned.GroupInvite -> Triple(
            stringResource(R.string.import_group_title, scanned.name),
            stringResource(R.string.import_group_body),
            null,
        )
        is Scanned.MeshCore -> Triple(
            stringResource(R.string.import_meshcore_contact, scanned.name),
            stringResource(R.string.import_meshcore_body),
            null,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (body.isNotEmpty()) Text(body)
                extra?.let {
                    Text(
                        stringResource(R.string.safety_number),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    SafetyNumber(it, Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
