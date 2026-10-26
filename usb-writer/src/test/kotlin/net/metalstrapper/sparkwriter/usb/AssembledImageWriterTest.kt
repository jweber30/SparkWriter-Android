package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.AuxiliaryDataFile
import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import net.metalstrapper.sparkwriter.core.PartitionGeometry
import net.metalstrapper.sparkwriter.core.VerifiedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class AssembledImageWriterTest {
    @Test
    fun writesPreallocatedAuxiliaryPartitionIntoCopiedImage() {
        val source = ByteArray(256 * 1024)
        val output = File.createTempFile("assembled-image-writer", ".img")
        val auxiliaryOffset = 64L * 1024L
        val auxiliaryLength = 64L * 1024L
        try {
            AssembledImageWriter.assemble(
                source = ByteArrayInputStream(source),
                outputFile = output,
                image = VerifiedImage(
                    uri = "file:///source.iso",
                    sha256 = "a".repeat(64),
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
                    sourceSha256 = "a".repeat(64),
                    geometry = PartitionGeometry(
                        baseImageSha256 = "a".repeat(64),
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
}
