package to.eyed.inferno.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Copies [text] to the system clipboard. On API 33+ the system shows its own "Copied" overlay,
 * so callers must not toast on top of it.
 */
fun copyToClipboard(context: Context, text: String, label: String = "Inferno") {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}
