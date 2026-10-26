package net.metalstrapper.sparkwriter

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.metalstrapper.sparkwriter.core.VerifiedImage
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

private const val ISO_MEDIA_TYPE = "application/x-iso9660-image"

data class LocalImageImportProgress(
    val phase: String,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
)

suspend fun importLocalImage(
    context: Context,
    uri: Uri,
    onProgress: (LocalImageImportProgress) -> Unit = {},
): VerifiedImage = withContext(Dispatchers.IO) {
    onProgress(LocalImageImportProgress(phase = context.getString(R.string.inspecting_iso)))
    val displayName = context.displayNameFor(uri)
    val expectedBytes = context.sizeFor(uri)
    onProgress(LocalImageImportProgress(phase = context.getString(R.string.opening_iso), totalBytes = expectedBytes))
    val digest = MessageDigest.getInstance("SHA-256")
    val prefix = ByteArray(BASE_IMAGE_PREFIX_BYTES)
    var prefixBytes = 0
    var bytesRead = 0L
    var nextProgressAt = 0L
    context.openImageInputStream(uri).use { input ->
        if (input == null) error("Image URI could not be opened")
        onProgress(LocalImageImportProgress(phase = context.getString(R.string.reading_iso), totalBytes = expectedBytes))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            val prefixRemaining = BASE_IMAGE_PREFIX_BYTES - prefixBytes
            if (prefixRemaining > 0) {
                val copyBytes = minOf(prefixRemaining, read)
                buffer.copyInto(prefix, destinationOffset = prefixBytes, endIndex = copyBytes)
                prefixBytes += copyBytes
            }
            digest.update(buffer, 0, read)
            bytesRead += read
            if (bytesRead >= nextProgressAt) {
                onProgress(
                    LocalImageImportProgress(
                        phase = context.getString(R.string.reading_iso),
                        bytesRead = bytesRead,
                        totalBytes = expectedBytes,
                    ),
                )
                nextProgressAt = bytesRead + 16L * 1024L * 1024L
            }
        }
    }

    if (bytesRead <= 0) {
        error("Imported image is empty")
    }

    onProgress(LocalImageImportProgress(phase = context.getString(R.string.fingerprinting_iso), bytesRead = bytesRead, totalBytes = expectedBytes))
    val fingerprint = prefix.copyOf(prefixBytes).toBaseImageFingerprint(bytesRead)
    onProgress(LocalImageImportProgress(phase = context.getString(R.string.verifying_iso_hash), bytesRead = bytesRead, totalBytes = expectedBytes))
    VerifiedImage(
        uri = uri.toString(),
        sha256 = digest.digest().joinToString("") { "%02x".format(it) },
        sizeBytes = bytesRead,
        mediaType = ISO_MEDIA_TYPE,
        provenance = uri.toString(),
        displayName = displayName,
        first64KSha256 = fingerprint.first64KSha256,
        pvdSha256 = fingerprint.pvdSha256,
        isoVolumeId = fingerprint.isoVolumeId,
        isoApplicationId = fingerprint.isoApplicationId,
    )
}

suspend fun removeLocalImageSource(context: Context, image: VerifiedImage): Boolean = withContext(Dispatchers.IO) {
    val uri = Uri.parse(image.provenance.ifBlank { image.uri })
    when (uri.scheme) {
        "file" -> uri.path?.let { File(it).delete() } ?: false
        "content" -> context.deleteDocumentUri(uri)
        else -> false
    }
}

private fun Context.openImageInputStream(uri: Uri): InputStream? {
    return if (uri.scheme == "file") {
        uri.path?.let { FileInputStream(File(it)) }
    } else {
        contentResolver.openInputStream(uri)
    }
}

private fun Context.deleteDocumentUri(uri: Uri): Boolean {
    runCatching {
        if (DocumentsContract.isDocumentUri(this, uri) && DocumentsContract.deleteDocument(contentResolver, uri)) {
            return true
        }
    }
    return runCatching { contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
}

private fun Context.displayNameFor(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor ->
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                val name = cursor.getString(index)?.ifBlank { null }
                if (name != null) return name
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "sparkwriter-image.iso"
}

private fun Context.sizeFor(uri: Uri): Long? {
    if (uri.scheme == "file") {
        return uri.path?.let { File(it).length().takeIf { size -> size > 0 } }
    }
    contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null).use { cursor ->
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) {
                return cursor.getLong(index).takeIf { it > 0 }
            }
        }
    }
    return null
}
