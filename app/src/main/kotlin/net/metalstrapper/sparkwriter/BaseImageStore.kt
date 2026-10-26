package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.VerifiedImage
import java.io.File
import java.security.MessageDigest

private const val BASE_IMAGE_ROOT = "sparkwriter/images"

class BaseImageStore(
    filesDir: File,
) {
    private val imagesRoot = File(filesDir, BASE_IMAGE_ROOT)

    fun directImageFile(manifest: SparkManifest, url: String): File {
        return directImageFile(manifest.metadata.id, url)
    }

    fun directImageFile(manifestId: String, url: String): File {
        return imageFile(manifestId, url.toImageFileName())
    }

    fun artifactImageFile(manifest: SparkManifest, artifact: String?): File? {
        val normalizedArtifact = artifact?.normalizeImagePath()?.ifBlank { null } ?: return null
        return imageFile(manifest, normalizedArtifact)
    }

    fun cachedVerifiedImage(manifest: SparkManifest): VerifiedImage? {
        val acquisition = runCatching { manifest.imageAcquisitionPlan() }.getOrNull() ?: return null
        return when (acquisition) {
            is ImageAcquisitionPlan.Direct -> {
                val file = directImageFile(manifest, acquisition.url)
                verifiedImage(manifest, file, acquisition.url)
            }
            is ImageAcquisitionPlan.Torrent -> {
                val file = artifactImageFile(manifest, acquisition.artifact) ?: return null
                verifiedImage(manifest, file, acquisition.torrentUrl)
            }
        }
    }

    fun verifiedImage(
        manifest: SparkManifest,
        file: File,
        provenance: String,
        mediaType: String = "application/octet-stream",
    ): VerifiedImage? {
        val expectedSha256 = manifest.source?.sha256?.ifBlank { null } ?: return null
        if (!file.isFile || file.length() <= 0) return null
        val actualSha256 = sha256(file)
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) return null
        val fingerprint = file.baseImageFingerprint()
        return VerifiedImage(
            uri = file.toURI().toString(),
            sha256 = actualSha256,
            sizeBytes = file.length(),
            mediaType = mediaType,
            provenance = provenance,
            displayName = file.name.ifBlank { manifest.source?.name ?: manifest.metadata.name },
            first64KSha256 = fingerprint.first64KSha256,
            pvdSha256 = fingerprint.pvdSha256,
            isoVolumeId = fingerprint.isoVolumeId,
            isoApplicationId = fingerprint.isoApplicationId,
        )
    }

    private fun imageFile(manifest: SparkManifest, relativePath: String): File {
        return imageFile(manifest.metadata.id, relativePath)
    }

    private fun imageFile(manifestId: String, relativePath: String): File {
        val saveDir = File(imagesRoot, manifestId.safePathSegment())
        val target = File(saveDir, relativePath)
        val canonicalSaveDir = saveDir.canonicalFile
        val canonicalTarget = target.canonicalFile
        if (canonicalTarget == canonicalSaveDir || !canonicalTarget.path.startsWith(canonicalSaveDir.path + File.separator)) {
            throw IllegalArgumentException("Base image path escapes app storage")
        }
        return canonicalTarget
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun String.toImageFileName(): String {
    val rawName = substringAfterLast('/').substringBefore('?').ifBlank { "sparkwriter-image.bin" }
    return rawName.map { char ->
        if (char.isLetterOrDigit() || char in listOf('.', '-', '_')) char else '_'
    }.joinToString("").ifBlank { "sparkwriter-image.bin" }
}

private fun String.safePathSegment(): String {
    return map { char ->
        if (char.isLetterOrDigit() || char in listOf('.', '-', '_')) char else '_'
    }.joinToString("").ifBlank { "sparkwriter-image" }
}

private fun String.normalizeImagePath(): String {
    return replace('\\', '/').trim().trim('/')
}
