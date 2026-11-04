package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.storage.IsoRetention
import java.io.File

private const val DOWNLOADED_IMAGES_ROOT = "sparkwriter/images"

internal data class StoredImage(
    val file: File,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val source: Source,
) {
    enum class Source { Imported, Downloaded }
}

/** Every ISO the app keeps in private storage, newest first. */
internal fun listStoredImages(filesDir: File): List<StoredImage> {
    val imported = stagedImportsDir(filesDir).listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".iso") }
        .map { StoredImage(it, it.length(), it.lastModified(), StoredImage.Source.Imported) }
    val downloaded = File(filesDir, DOWNLOADED_IMAGES_ROOT).walkTopDown()
        .filter { it.isFile }
        .map { StoredImage(it, it.length(), it.lastModified(), StoredImage.Source.Downloaded) }
        .toList()
    return (imported + downloaded).sortedByDescending { it.modifiedMillis }
}

/** Deletes [image] only if it is a file this app stores, never an arbitrary path. */
internal fun deleteStoredImage(filesDir: File, image: StoredImage): Boolean {
    val canonical = image.file.canonicalFile
    val roots = listOf(stagedImportsDir(filesDir), File(filesDir, DOWNLOADED_IMAGES_ROOT))
    if (roots.none { canonical.path.startsWith(it.canonicalPath + File.separator) }) return false
    return canonical.delete()
}

/**
 * Deletes stored images the [retention] policy no longer wants. [protect] (the image about to be
 * written) is never deleted. [DeleteAfterWrite] keeps nothing but the protected image.
 */
internal fun applyRetention(filesDir: File, retention: IsoRetention, maxImages: Int, protect: File?) {
    if (retention == IsoRetention.KeepUntilDeleted) return
    val protectedPath = protect?.canonicalPath
    val candidates = listStoredImages(filesDir).filter { it.file.canonicalPath != protectedPath }
    val keep = when (retention) {
        IsoRetention.KeepRecent -> (maxImages - if (protectedPath != null) 1 else 0).coerceAtLeast(0)
        else -> 0
    }
    candidates.drop(keep).forEach { deleteStoredImage(filesDir, it) }
}
