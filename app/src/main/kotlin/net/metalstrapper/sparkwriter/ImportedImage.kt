package net.metalstrapper.sparkwriter

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.automirrored.filled.List
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.storage.IsoIndexStore
import java.io.InputStream
import java.net.URI
import java.io.File
import java.io.FileInputStream

internal fun SparkManifest.requireDeclaredImageHash(image: VerifiedImage) {
    val expected = source?.sha256?.ifBlank { null } ?: return
    if (allowsDynamicAppendAfterImage()) return
    if (!image.sha256.equals(expected, ignoreCase = true)) {
        error("Selected image hash does not match source.sha256")
    }
}

private fun SparkManifest.allowsDynamicAppendAfterImage(): Boolean {
    return delivery?.mode == "auxiliary_partition" &&
        delivery?.partition?.placement == "append_after_image"
}

internal data class ImportedImageRoute(
    val visibleManifestIds: Set<String>?,
    val selectedManifestId: String?,
)

internal suspend fun IsoIndexStore.routeImportedImage(
    image: VerifiedImage,
    manifests: List<SparkManifest>,
    rawManifest: SparkManifest,
): ImportedImageRoute {
    val matches = matches(image).map { it.manifestId }.distinct()
    if (matches.isNotEmpty()) {
        return ImportedImageRoute(
            visibleManifestIds = matches.toSet(),
            selectedManifestId = matches.singleOrNull(),
        )
    }
    val adapters = manifests
        .filter { it.metadata.id != rawManifest.metadata.id }
        .filter { it.isPatchableImportSparkPlug() }
        .map { it.metadata.id }
    val visible = (adapters + rawManifest.metadata.id).toSet()
    return ImportedImageRoute(
        visibleManifestIds = visible,
        selectedManifestId = if (adapters.isEmpty()) rawManifest.metadata.id else null,
    )
}

internal fun SparkManifest.isPatchableImportSparkPlug(): Boolean {
    return isAndroidRunnable &&
        source?.userSupplied == true &&
        source?.adaptation?.status == "patchable_on_import" &&
        bootPatch != null
}

internal fun openDownloadHandler(context: android.content.Context, manifest: SparkManifest): String {
    val acquisition = runCatching { manifest.imageAcquisitionPlan() }.getOrNull()
        ?: return "Selected manifest does not declare a source image URL"
    val downloadUrl = when (acquisition) {
        is ImageAcquisitionPlan.Direct -> acquisition.url
        is ImageAcquisitionPlan.Torrent -> return "Selected manifest declares a torrent source"
    }
    val uri = Uri.parse(downloadUrl)
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    return try {
        context.startActivity(Intent.createChooser(intent, "Open download with"))
        "Opened download link. Pick the completed ISO when the download finishes."
    } catch (_: ActivityNotFoundException) {
        "No app is installed to open this download link"
    }
}

internal fun openTorrentHandler(context: android.content.Context, manifest: SparkManifest): String {
    val source = manifest.source ?: return "Selected manifest does not declare a torrent source"
    val acquire = source.acquire ?: return "Selected manifest does not declare torrent acquisition"
    val torrentUrl = acquire.url.ifBlank { null } ?: source.url?.ifBlank { null }
        ?: return "Selected manifest does not declare a torrent URL"
    val uri = Uri.parse(torrentUrl)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (uri.scheme == "magnet") {
            data = uri
        } else {
            setDataAndType(uri, "application/x-bittorrent")
        }
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(Intent.createChooser(intent, "Open torrent with"))
        "Opened torrent handler. Import the completed ISO when the download finishes."
    } catch (_: ActivityNotFoundException) {
        "No torrent handler is installed for this source"
    }
}

internal fun SparkManifest.bootPatchSignature(): String? {
    val patch = bootPatch ?: return null
    return Json.encodeToString(patch).toByteArray(Charsets.UTF_8).sha256()
}

internal fun Context.readImageRange(image: VerifiedImage, offset: Long, length: Int): ByteArray {
    if (offset < 0 || length < 0 || offset + length > image.sizeBytes) {
        error("Requested ISO byte range is outside the image")
    }
    // image.uri is the verified copy; provenance may name an external document whose bytes can change.
    openImportedImageInputStream(Uri.parse(image.uri)).use { input ->
        if (input == null) error("Image URI could not be opened")
        var skipped = 0L
        while (skipped < offset) {
            val delta = input.skip(offset - skipped)
            if (delta <= 0) {
                if (input.read() < 0) error("Image ended before requested byte range")
                skipped++
            } else {
                skipped += delta
            }
        }
        val bytes = ByteArray(length)
        var total = 0
        while (total < length) {
            val read = input.read(bytes, total, length - total)
            if (read < 0) error("Image ended before requested byte range")
            total += read
        }
        return bytes
    }
}

internal fun Context.openImportedImageInputStream(uri: Uri): InputStream? {
    return if (uri.scheme == "file") {
        uri.path?.let { FileInputStream(File(it)) }
    } else {
        contentResolver.openInputStream(uri)
    }
}
