package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.BlockDeviceInfo
import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.core.WriteIntent
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

class WriteRefusedException(message: String) : IllegalStateException(message)

fun validateWriteIntent(intent: WriteIntent, probed: BlockDeviceInfo) {
    val device = probed.device
    if (!probed.permissionGranted) throw WriteRefusedException("USB permission has not been granted")
    if (intent.deviceId != intent.confirmedDeviceId) {
        throw WriteRefusedException("Device changed after confirmation")
    }
    if (intent.deviceId != device.id) {
        throw WriteRefusedException("Confirmed device no longer matches the connected device")
    }
    if (device.isSystemDevice || !device.removable) {
        throw WriteRefusedException("Target does not look like removable USB storage")
    }
    if (device.sizeBytes < intent.image.sizeBytes) {
        throw WriteRefusedException("USB device is smaller than the verified image")
    }
    if (intent.image.sha256.isBlank() || intent.image.sizeBytes <= 0) {
        throw WriteRefusedException("Image must be checksum-verified before writing")
    }
}

internal fun validateAuxiliarySourceMatch(intent: WriteIntent, auxiliary: AuxiliaryDataPlan) {
    val sourceHash = auxiliary.sourceSha256?.ifBlank { null }
    when (auxiliary.placement) {
        "preallocated_in_base_image" -> {
            if (sourceHash == null) throw WriteRefusedException("Auxiliary delivery requires source.sha256")
            if (!intent.image.sha256.equals(sourceHash, ignoreCase = true)) {
                throw WriteRefusedException("Selected image hash does not match auxiliary source hash")
            }
        }
        "append_after_image" -> {
            Unit
        }
        else -> throw WriteRefusedException("Unsupported auxiliary partition placement: ${auxiliary.placement}")
    }
}

/**
 * Hashes the image bytes a phase actually consumes. Every phase that reads the source image wraps
 * its stream with this and calls [requireApprovedDigest] once it has read [VerifiedImage.sizeBytes],
 * so content that changed after verification is refused rather than trusted.
 */
fun approvedImageStream(source: InputStream): DigestInputStream =
    DigestInputStream(source, MessageDigest.getInstance("SHA-256"))

fun requireApprovedDigest(stream: DigestInputStream, image: VerifiedImage) {
    val actual = stream.messageDigest.digest().joinToString("") { "%02x".format(it) }
    if (!actual.equals(image.sha256, ignoreCase = true)) {
        throw WriteRefusedException(
            "Image changed after it was verified: read sha256 $actual, approved ${image.sha256.lowercase()}",
        )
    }
}
