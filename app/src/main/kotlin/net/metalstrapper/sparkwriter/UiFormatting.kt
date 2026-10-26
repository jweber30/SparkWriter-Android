package net.metalstrapper.sparkwriter

import android.content.ClipData
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

internal suspend fun Clipboard.copyText(context: Context, text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText(context.getString(R.string.clipboard_label), text)))
}

internal fun Long.toHumanBytes(): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${this} ${units[unitIndex]}"
    } else {
        "%.1f %s".format(value, units[unitIndex])
    }
}

internal fun LocalImageImportProgress.progressFraction(): Float? {
    val total = totalBytes ?: return null
    if (total <= 0 || bytesRead <= 0) return null
    return (bytesRead.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

internal fun LocalImageImportProgress.visibleImportStatus(): String {
    val total = totalBytes
    val byteText = when {
        total != null && bytesRead > 0 -> "${bytesRead.toHumanBytes()} / ${total.toHumanBytes()}"
        bytesRead > 0 -> "${bytesRead.toHumanBytes()} read"
        total != null -> total.toHumanBytes()
        else -> null
    }
    return listOfNotNull(phase, byteText).joinToString(" - ")
}

internal fun String?.removeUsbSchemePrefix(): String {
    if (this == null) return ""
    return if (startsWith("usb://")) removePrefix("usb://") else this
}
