package net.metalstrapper.sparkwriter

import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.VerifiedImage
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

const val BASE_IMAGE_PREFIX_BYTES: Int = 64 * 1024
private const val ISO_PVD_OFFSET = 0x8000
private const val ISO_VOLUME_DESCRIPTOR_SIZE = 2048
private const val ISO_VOLUME_ID_OFFSET = 40
private const val ISO_VOLUME_ID_LENGTH = 32
private const val ISO_APPLICATION_ID_OFFSET = 574
private const val ISO_APPLICATION_ID_LENGTH = 128

data class BaseImageFingerprint(
    val sizeBytes: Long,
    val first64KSha256: String,
    val pvdSha256: String? = null,
    val isoVolumeId: String? = null,
    val isoApplicationId: String? = null,
)

data class BaseImageManifestMatches(
    val exactHashMatches: List<SparkManifest>,
    val prefixMatches: List<SparkManifest>,
    val sizeOnlyMatches: List<SparkManifest>,
    val pvdMatches: List<SparkManifest>,
) {
    val hasAnyMatch: Boolean
        get() = exactHashMatches.isNotEmpty() ||
            prefixMatches.isNotEmpty() ||
            sizeOnlyMatches.isNotEmpty() ||
            pvdMatches.isNotEmpty()
}

fun File.baseImageFingerprint(): BaseImageFingerprint {
    inputStream().use { input ->
        return input.baseImageFingerprint(length())
    }
}

fun InputStream.baseImageFingerprint(sizeBytes: Long): BaseImageFingerprint {
    require(sizeBytes >= 0) { "sizeBytes must be non-negative" }
    val prefix = readPrefix(BASE_IMAGE_PREFIX_BYTES)
    if (sizeBytes > 0 && prefix.isEmpty()) error("Image is empty")
    return BaseImageFingerprint(
        sizeBytes = sizeBytes,
        first64KSha256 = prefix.sha256(),
        pvdSha256 = prefix.isoPrimaryVolumeDescriptor()?.sha256(),
        isoVolumeId = prefix.isoPrimaryVolumeDescriptor()?.isoString(ISO_VOLUME_ID_OFFSET, ISO_VOLUME_ID_LENGTH),
        isoApplicationId = prefix.isoPrimaryVolumeDescriptor()?.isoString(ISO_APPLICATION_ID_OFFSET, ISO_APPLICATION_ID_LENGTH),
    )
}

fun VerifiedImage.fingerprint(): BaseImageFingerprint? {
    val first64K = first64KSha256 ?: return null
    return BaseImageFingerprint(
        sizeBytes = sizeBytes,
        first64KSha256 = first64K,
        pvdSha256 = pvdSha256,
        isoVolumeId = isoVolumeId,
        isoApplicationId = isoApplicationId,
    )
}

fun matchBaseImageFingerprint(
    fingerprint: BaseImageFingerprint,
    manifests: List<SparkManifest>,
    fullSha256: String? = null,
): BaseImageManifestMatches {
    val exactCandidates = manifests.filter { it.source?.sha256?.isSha256Hex() == true }
    val exactHashMatches = fullSha256?.let { sha ->
        exactCandidates.filter { it.source?.sha256.equals(sha, ignoreCase = true) }
    }.orEmpty()

    val sizeMatches = manifests.filter { manifest ->
        manifest.source?.sizeBytes == fingerprint.sizeBytes
    }
    val prefixMatches = sizeMatches.filter { manifest ->
        manifest.source?.first64KSha256?.equals(fingerprint.first64KSha256, ignoreCase = true) == true
    }
    val sizeOnlyMatches = sizeMatches.filter { it !in prefixMatches }
    val pvdMatches = if (prefixMatches.isEmpty()) {
        manifests.filter { manifest ->
            val expected = manifest.source?.pvdSha256
            expected != null && expected.equals(fingerprint.pvdSha256, ignoreCase = true)
        }
    } else {
        emptyList()
    }

    return BaseImageManifestMatches(
        exactHashMatches = exactHashMatches,
        prefixMatches = prefixMatches,
        sizeOnlyMatches = sizeOnlyMatches,
        pvdMatches = pvdMatches,
    )
}

internal fun ByteArray.toBaseImageFingerprint(sizeBytes: Long = size.toLong()): BaseImageFingerprint {
    val prefix = if (size <= BASE_IMAGE_PREFIX_BYTES) this else copyOfRange(0, BASE_IMAGE_PREFIX_BYTES)
    val pvd = prefix.isoPrimaryVolumeDescriptor()
    return BaseImageFingerprint(
        sizeBytes = sizeBytes,
        first64KSha256 = prefix.sha256(),
        pvdSha256 = pvd?.sha256(),
        isoVolumeId = pvd?.isoString(ISO_VOLUME_ID_OFFSET, ISO_VOLUME_ID_LENGTH),
        isoApplicationId = pvd?.isoString(ISO_APPLICATION_ID_OFFSET, ISO_APPLICATION_ID_LENGTH),
    )
}

private fun InputStream.readPrefix(maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var total = 0
    while (total < maxBytes) {
        val read = read(buffer, total, maxBytes - total)
        if (read < 0) break
        total += read
    }
    return buffer.copyOf(total)
}

private fun ByteArray.isoPrimaryVolumeDescriptor(): ByteArray? {
    if (size < ISO_PVD_OFFSET + ISO_VOLUME_DESCRIPTOR_SIZE) return null
    val descriptor = copyOfRange(ISO_PVD_OFFSET, ISO_PVD_OFFSET + ISO_VOLUME_DESCRIPTOR_SIZE)
    val isPrimaryVolumeDescriptor = descriptor[0].toInt() == 1
    val hasCd001 = descriptor.copyOfRange(1, 6).toString(Charsets.US_ASCII) == "CD001"
    return if (isPrimaryVolumeDescriptor && hasCd001) descriptor else null
}

private fun ByteArray.isoString(offset: Int, length: Int): String? {
    if (offset < 0 || offset + length > size) return null
    return copyOfRange(offset, offset + length)
        .toString(Charsets.US_ASCII)
        .trim()
        .ifBlank { null }
}

private fun String.isSha256Hex(): Boolean = matches(Regex("^[A-Fa-f0-9]{64}$"))
