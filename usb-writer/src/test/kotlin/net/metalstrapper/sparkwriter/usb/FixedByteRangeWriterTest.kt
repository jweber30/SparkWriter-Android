package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.FixedByteRangePatch
import net.metalstrapper.sparkwriter.core.FixedByteRangePlan
import net.metalstrapper.sparkwriter.core.PaddingSpec
import net.metalstrapper.sparkwriter.core.PlaceholderSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class FixedByteRangeWriterTest {
    @Test
    fun verifiesZeroPlaceholderAndPadsTomlWithComments() {
        val patch = patch(
            mediaType = "application/toml",
            content = "answer = true",
            padding = PaddingSpec("toml-comment"),
            placeholder = PlaceholderSpec(encoding = "zeroes"),
        )

        FixedByteRangeWriter.verifyPlaceholder(patch, ByteArray(32))
        val replacement = FixedByteRangeWriter.replacementBytes(patch)

        assertEquals(32, replacement.size)
        assertTrue(replacement.toString(Charsets.UTF_8).startsWith("answer = true\n#"))
    }

    @Test
    fun verifiesPlaceholderHashWhenDeclared() {
        val existing = "placeholder".toByteArray()
        val patch = patch(
            byteLength = existing.size.toLong(),
            placeholder = PlaceholderSpec(sha256 = existing.sha256()),
        )

        FixedByteRangeWriter.verifyPlaceholder(patch, existing)
    }

    @Test
    fun verifiesMetalStrapperPatchSlotByManifestPatchId() {
        val existing = "# METALSTRAPPER_PATCH_SLOT answer_toml v1\n".toByteArray()
        val patch = patch(
            id = "answer_toml",
            byteLength = existing.size.toLong(),
            placeholder = PlaceholderSpec(encoding = "metalstrapper-patch-slot"),
            mediaType = "application/toml",
            padding = PaddingSpec("toml-comment"),
            content = "[global]\nfqdn = \"pve1.example\"\n",
        )

        FixedByteRangeWriter.verifyPlaceholder(patch, existing)
        val replacement = FixedByteRangeWriter.replacementBytes(patch).toString(Charsets.UTF_8)

        assertTrue(replacement.startsWith("[global]\nfqdn = \"pve1.example\"\n"))
        assertTrue(!replacement.contains("METALSTRAPPER_PATCH_SLOT"))
    }

    @Test(expected = WriteRefusedException::class)
    fun rejectsMetalStrapperPatchSlotForDifferentManifestPatch() {
        FixedByteRangeWriter.verifyPlaceholder(
            patch(
                id = "firstboot_script",
                byteLength = 48,
                placeholder = PlaceholderSpec(encoding = "metalstrapper-patch-slot"),
            ),
            "# METALSTRAPPER_PATCH_SLOT answer_toml v1\n".toByteArray(),
        )
    }

    @Test(expected = WriteRefusedException::class)
    fun rejectsTextPatchWithoutExplicitPadding() {
        FixedByteRangeWriter.replacementBytes(
            patch(mediaType = "text/x-shellscript", content = "#!/bin/sh\n"),
        )
    }

    @Test(expected = WriteRefusedException::class)
    fun rejectsOversizedArtifacts() {
        FixedByteRangeWriter.replacementBytes(patch(byteLength = 4, content = "too large"))
    }

    @Test(expected = WriteRefusedException::class)
    fun rejectsOverlappingPatchPlan() {
        FixedByteRangeWriter.validatePlan(
            FixedByteRangePlan(
                baseImageSha256 = "c".repeat(64),
                patches = listOf(
                    patch(id = "one", byteOffset = 10, byteLength = 10),
                    patch(id = "two", byteOffset = 19, byteLength = 10),
                ),
            ),
            imageSha256 = "c".repeat(64),
            imageSizeBytes = 100,
            deviceSizeBytes = 100,
        )
    }

    private fun patch(
        id: String = "answer",
        mediaType: String? = null,
        byteOffset: Long = 0,
        byteLength: Long = 32,
        placeholder: PlaceholderSpec? = null,
        padding: PaddingSpec? = null,
        content: String = "ok",
    ): FixedByteRangePatch = FixedByteRangePatch(
        id = id,
        mediaType = mediaType,
        byteOffset = byteOffset,
        byteLength = byteLength,
        placeholder = placeholder,
        padding = padding,
        content = content,
        contentSha256 = content.toByteArray().sha256(),
    )
}

private fun ByteArray.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}
