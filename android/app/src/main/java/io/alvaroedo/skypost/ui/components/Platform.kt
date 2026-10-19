package io.alvaroedo.skypost.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.result.contract.ActivityResultContract
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Scan a QR code with Google's code scanner: no camera permission, the
 * scanning UI runs in Play services. On phones without them it fails and the
 * user can paste the code instead.
 */
fun scanQr(context: Context, onText: (String) -> Unit, onFailure: () -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options)
        .startScan()
        .addOnSuccessListener { b -> b.rawValue?.let(onText) ?: onFailure() }
        .addOnFailureListener { onFailure() }
}

fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java)
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

fun copyToClipboard(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

data class PickedPhone(val name: String?, val number: String)

/**
 * The system contact picker, filtered to phone numbers. The picked row comes
 * with a one-off read grant, so the app needs no READ_CONTACTS permission and
 * never sees the rest of the address book.
 */
class PickPhone : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit) = Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

fun readPickedPhone(context: Context, uri: Uri): PickedPhone? =
    context.contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use { c ->
        if (!c.moveToFirst()) return null
        PickedPhone(name = c.getString(0), number = c.getString(1) ?: return null)
    }
