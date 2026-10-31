package net.metalstrapper.sparkwriter

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.metalstrapper.sparkwriter.core.VerifiedImage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

private const val ISO_MEDIA_TYPE = "application/x-iso9660-image"
private const val STAGED_IMPORTS_ROOT = "sparkwriter/imports"
private const val STAGED_SUFFIX = ".iso"
private const val PARTIAL_SUFFIX = ".partial"
private const val STAGING_BUFFER_BYTES = 1024 * 1024
private const val STAGING_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L

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
    // Images outside app storage are copied in while they are hashed: a document provider can
    // serve different bytes on every open, so later phases read only the private staged copy.
    val stagingDir = stagedImportsDir(context.filesDir)
    val stage = !uri.isInside(context.filesDir)
    if (stage) requireStagingSpace(stagingDir, expectedBytes)
    onProgress(LocalImageImportProgress(phase = context.getString(R.string.opening_iso), totalBytes = expectedBytes))
    val read = context.openImageInputStream(uri).use { input ->
        if (input == null) error("Image URI could not be opened")
        onProgress(LocalImageImportProgress(phase = context.getString(R.string.reading_iso), totalBytes = expectedBytes))
        readAndStageImage(input, stagingDir.takeIf { stage }) { bytesRead ->
            ensureActive()
            onProgress(
                LocalImageImportProgress(
                    phase = context.getString(R.string.reading_iso),
                    bytesRead = bytesRead,
                    totalBytes = expectedBytes,
                ),
            )
        }
    }
    val bytesRead = read.sizeBytes

    onProgress(LocalImageImportProgress(phase = context.getString(R.string.fingerprinting_iso), bytesRead = bytesRead, totalBytes = expectedBytes))
    val fingerprint = read.prefix.toBaseImageFingerprint(bytesRead)
    onProgress(LocalImageImportProgress(phase = context.getString(R.string.verifying_iso_hash), bytesRead = bytesRead, totalBytes = expectedBytes))
    read.stagedFile?.let { staged ->
        // Keep only the newest staged import, and never delete one an active write may reopen.
        if (activeUsbWriteWorkInfo(WorkManager.getInstance(context)) == null) {
            pruneStagedImports(stagingDir, keep = staged)
        }
    }
    VerifiedImage(
        uri = read.stagedFile?.let { Uri.fromFile(it).toString() } ?: uri.toString(),
        sha256 = read.sha256,
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

internal data class ReadImage(
    val sha256: String,
    val sizeBytes: Long,
    val prefix: ByteArray,
    /** Content-addressed private copy, or null when the source already lives in app storage. */
    val stagedFile: File?,
)

internal fun stagedImportsDir(filesDir: File): File = File(filesDir, STAGED_IMPORTS_ROOT)

/**
 * Reads [input] once, hashing it and (when [stagingDir] is set) copying it to
 * `<sha256>.iso` there. The copy is written to a `.partial` file and renamed only after the
 * digest is final, so a crashed or cancelled import never leaves a file that looks complete.
 */
internal fun readAndStageImage(
    input: InputStream,
    stagingDir: File?,
    onProgress: (Long) -> Unit = {},
): ReadImage {
    val digest = MessageDigest.getInstance("SHA-256")
    val prefix = ByteArray(BASE_IMAGE_PREFIX_BYTES)
    var prefixBytes = 0
    var bytesRead = 0L
    var nextProgressAt = 0L
    val partial = stagingDir?.let {
        it.mkdirs()
        File.createTempFile("import-", PARTIAL_SUFFIX, it)
    }
    try {
        partial?.let(::FileOutputStream).use { output ->
            val buffer = ByteArray(STAGING_BUFFER_BYTES)
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
                output?.write(buffer, 0, read)
                bytesRead += read
                if (bytesRead >= nextProgressAt) {
                    onProgress(bytesRead)
                    nextProgressAt = bytesRead + 16L * 1024L * 1024L
                }
            }
            output?.fd?.sync()
        }
        if (bytesRead <= 0) error("Imported image is empty")
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val staged = partial?.let { file ->
            val target = File(stagingDir, "$sha256$STAGED_SUFFIX")
            if (target.isFile && target.length() == bytesRead) {
                file.delete()
            } else if (!file.renameTo(target)) {
                error("Could not store the imported image in app storage")
            }
            target
        }
        return ReadImage(sha256, bytesRead, prefix.copyOf(prefixBytes), staged)
    } finally {
        partial?.takeIf { it.exists() }?.delete()
    }
}

/** Deletes every staged import and leftover partial copy except [keep]. */
internal fun pruneStagedImports(stagingDir: File, keep: File) {
    stagingDir.listFiles()
        ?.filter { it.isFile && it.canonicalPath != keep.canonicalPath }
        ?.filter { it.name.endsWith(STAGED_SUFFIX) || it.name.endsWith(PARTIAL_SUFFIX) }
        ?.forEach { it.delete() }
}

private fun requireStagingSpace(stagingDir: File, expectedBytes: Long?) {
    expectedBytes ?: return
    stagingDir.mkdirs()
    val needed = expectedBytes + STAGING_SPACE_MARGIN_BYTES
    if (stagingDir.usableSpace < needed) {
        error(
            "Not enough free storage to import this image: needs ${needed.toHumanBytes()}, " +
                "${stagingDir.usableSpace.toHumanBytes()} available",
        )
    }
}

private fun Uri.isInside(directory: File): Boolean {
    if (scheme != "file") return false
    val path = path ?: return false
    val root = directory.canonicalPath + File.separator
    return File(path).canonicalPath.startsWith(root)
}

private fun Context.openImageInputStream(uri: Uri): InputStream? {
    return if (uri.scheme == "file") {
        uri.path?.let { FileInputStream(File(it)) }
    } else {
        contentResolver.openInputStream(uri)
    }
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
