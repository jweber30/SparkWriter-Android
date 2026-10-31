package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.AuxiliaryDataFile
import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import net.metalstrapper.sparkwriter.core.PartitionGeometry
import net.metalstrapper.sparkwriter.core.VerifiedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class AssembledImageWriterTest {
    @Test
    fun writesPreallocatedAuxiliaryPartitionIntoCopiedImage() {
        val source = ByteArray(256 * 1024)
        val sourceSha256 = source.sha256Hex()
        val output = File.createTempFile("assembled-image-writer", ".img")
        val auxiliaryOffset = 64L * 1024L
        val auxiliaryLength = 64L * 1024L
        try {
            AssembledImageWriter.assemble(
                source = ByteArrayInputStream(source),
                outputFile = output,
                image = VerifiedImage(
                    uri = "file:///source.iso",
                    sha256 = sourceSha256,
                    sizeBytes = source.size.toLong(),
                    mediaType = "application/octet-stream",
                    provenance = "test",
                    displayName = "source.iso",
                ),
                auxiliaryData = AuxiliaryDataPlan(
                    label = "CIDATA",
                    filesystem = "vfat",
                    placement = "preallocated_in_base_image",
                    sizeBytes = auxiliaryLength,
                    sourceSha256 = sourceSha256,
                    geometry = PartitionGeometry(
                        baseImageSha256 = sourceSha256,
                        partitionTable = "gpt",
                        partitionNumber = 2,
                        startLba = auxiliaryOffset / 512L,
                        sectorSize = 512,
                        byteOffset = auxiliaryOffset,
                        byteLength = auxiliaryLength,
                    ),
                    files = listOf(AuxiliaryDataFile(path = "/answer.toml", content = "answer = true")),
                ),
            )

            val bytes = output.readBytes()
            assertEquals(source.size, bytes.size)
            assertEquals(0x55.toByte(), bytes[(auxiliaryOffset + 510).toInt()])
            assertEquals(0xaa.toByte(), bytes[(auxiliaryOffset + 511).toInt()])
            assertTrue(bytes.copyOfRange(auxiliaryOffset.toInt(), (auxiliaryOffset + auxiliaryLength).toInt()).any { it != 0.toByte() })
        } finally {
            output.delete()
        }
    }

    @Test
    fun refusesSourceWhoseBytesDifferFromTheApprovedDigest() {
        val approved = ByteArray(64 * 1024)
        val changed = approved.copyOf().also { it[4096] = 1 }
        val output = File.createTempFile("assembled-image-writer", ".img")
        try {
            val error = assertThrows(WriteRefusedException::class.java) {
                AssembledImageWriter.assemble(
                    source = ByteArrayInputStream(changed),
                    outputFile = output,
                    image = VerifiedImage(
                        uri = "file:///source.iso",
                        sha256 = approved.sha256Hex(),
                        sizeBytes = approved.size.toLong(),
                        mediaType = "application/octet-stream",
                        provenance = "test",
                        displayName = "source.iso",
                    ),
                )
            }
            assertTrue(error.message!!.startsWith("Image changed after it was verified"))
        } finally {
            output.delete()
        }
    }
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
