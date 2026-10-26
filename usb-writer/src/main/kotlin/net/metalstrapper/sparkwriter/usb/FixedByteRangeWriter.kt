package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.FixedByteRangePatch
import net.metalstrapper.sparkwriter.core.FixedByteRangePlan
import java.security.MessageDigest

internal object FixedByteRangeWriter {
    fun validatePlan(plan: FixedByteRangePlan, imageSha256: String, imageSizeBytes: Long, deviceSizeBytes: Long) {
        if (!plan.baseImageSha256.equals(imageSha256, ignoreCase = true)) {
            throw WriteRefusedException("Verified image hash does not match fixed byte-range delivery")
        }
        plan.patches.sortedBy { it.byteOffset }.zipWithNext().forEach { (left, right) ->
            if (left.byteOffset + left.byteLength > right.byteOffset) {
                throw WriteRefusedException("Fixed byte-range patches must not overlap")
            }
        }
        plan.patches.forEach { patch ->
            if (patch.byteOffset < 0 || patch.byteLength <= 0) {
                throw WriteRefusedException("Fixed byte-range patch ${patch.id} has invalid byte range")
            }
            if (patch.byteOffset + patch.byteLength > imageSizeBytes) {
                throw WriteRefusedException("Fixed byte-range patch ${patch.id} exceeds verified image size")
            }
            if (patch.byteOffset + patch.byteLength > deviceSizeBytes) {
                throw WriteRefusedException("Fixed byte-range patch ${patch.id} exceeds USB device size")
            }
        }
    }

    fun verifyPlaceholder(patch: FixedByteRangePatch, existing: ByteArray) {
        val expectedHash = patch.placeholder?.sha256?.ifBlank { null }
        if (expectedHash != null && existing.sha256() != expectedHash) {
            throw WriteRefusedException("Placeholder hash mismatch for fixed byte-range patch ${patch.id}")
        }
        if (expectedHash != null) return

        when (patch.placeholder?.encoding) {
            null -> Unit
            "zeroes" -> {
                if (existing.any { it != 0.toByte() }) {
                    throw WriteRefusedException("Placeholder range is not zero-filled for fixed byte-range patch ${patch.id}")
                }
            }
            "metalstrapper-patch-slot" -> verifyPatchSlotPlaceholder(patch, existing)
            else -> throw WriteRefusedException(
                "Unsupported fixed byte-range placeholder encoding for ${patch.id}: ${patch.placeholder?.encoding}",
            )
        }
    }

    fun replacementBytes(patch: FixedByteRangePatch): ByteArray {
        val content = patch.content.toByteArray(Charsets.UTF_8)
        if (content.size > patch.byteLength) {
            throw WriteRefusedException("Fixed byte-range artifact ${patch.id} is larger than its reserved range")
        }
        val targetSize = patch.byteLength.toIntOrNull()
            ?: throw WriteRefusedException("Fixed byte-range patch ${patch.id} is too large for Android memory")
        val output = ByteArray(targetSize)
        content.copyInto(output)
        val remaining = targetSize - content.size
        if (remaining == 0) return output

        val strategy = patch.padding?.strategy?.ifBlank { null } ?: defaultPaddingStrategy(patch)
        val padding = paddingBytes(strategy, patch, content, remaining)
        padding.copyInto(output, content.size, 0, remaining)
        return output
    }

    private fun defaultPaddingStrategy(patch: FixedByteRangePatch): String {
        if (patch.mediaType.isTextLike()) {
            throw WriteRefusedException("Text fixed byte-range patch ${patch.id} requires an explicit padding strategy")
        }
        return "zeroes"
    }

    private fun paddingBytes(
        strategy: String,
        patch: FixedByteRangePatch,
        content: ByteArray,
        remaining: Int,
    ): ByteArray = when (strategy) {
        "zeroes" -> ByteArray(remaining)
        "space" -> ByteArray(remaining) { ' '.code.toByte() }
        "toml-comment", "shell-comment" -> commentPadding(content, remaining)
        else -> throw WriteRefusedException("Unsupported fixed byte-range padding strategy for ${patch.id}: $strategy")
    }

    private fun commentPadding(content: ByteArray, remaining: Int): ByteArray {
        val padding = ByteArray(remaining)
        var offset = 0
        if (content.isNotEmpty() && content.last() != '\n'.code.toByte() && remaining > 0) {
            padding[offset++] = '\n'.code.toByte()
        }
        while (offset < remaining) {
            padding[offset++] = '#'.code.toByte()
        }
        return padding
    }

    private fun verifyPatchSlotPlaceholder(patch: FixedByteRangePatch, existing: ByteArray) {
        val text = existing.toString(Charsets.UTF_8)
        val match = PATCH_SLOT_PATTERN.find(text)
            ?: throw WriteRefusedException("Patch-slot placeholder missing for fixed byte-range patch ${patch.id}")
        val slotLabel = match.groupValues[1]
        if (slotLabel !in patch.acceptedSlotLabels()) {
            throw WriteRefusedException(
                "Patch-slot label $slotLabel does not match fixed byte-range patch ${patch.id}",
            )
        }
    }

    private fun FixedByteRangePatch.acceptedSlotLabels(): Set<String> {
        return buildSet {
            add(id)
            logicalName?.let { name ->
                add(name)
                add(name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_'))
            }
        }.filter { it.isNotBlank() }.toSet()
    }

    private val PATCH_SLOT_PATTERN = Regex("""METALSTRAPPER_PATCH_SLOT\s+([A-Za-z0-9_.-]+)\s+v[0-9]+""")
}

private fun String?.isTextLike(): Boolean {
    val type = this?.lowercase() ?: return false
    return type.startsWith("text/") ||
        type == "application/toml" ||
        type.endsWith("+json") ||
        type.endsWith("+yaml") ||
        type.contains("shellscript")
}

private fun Long.toIntOrNull(): Int? = if (this in 0..Int.MAX_VALUE.toLong()) toInt() else null

private fun ByteArray.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}
