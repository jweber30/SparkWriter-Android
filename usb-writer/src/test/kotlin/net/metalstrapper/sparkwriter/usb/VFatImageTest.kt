package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.AuxiliaryDataFile
import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import net.metalstrapper.sparkwriter.core.PartitionGeometry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VFatImageTest {
    @Test
    fun createsVfatImageWithLabelAndLongFilenames() {
        val image = VFatImage.create(
            AuxiliaryDataPlan(
                label = "PROXMOX-AIS",
                filesystem = "vfat",
                placement = "preallocated_in_base_image",
                sizeBytes = 1024L * 1024L,
                sourceSha256 = "c".repeat(64),
                geometry = geometry(byteLength = 1024L * 1024L),
                files = listOf(
                    AuxiliaryDataFile("/answer.toml", "answer = true\n", "application/toml"),
                    AuxiliaryDataFile("/firstboot.sh", "#!/bin/sh\n", "text/x-shellscript", executable = true),
                ),
            ),
        )

        assertEquals("PROXMOX-AIS", image.decodeAscii(71, 11).trim())
        assertTrue(image.containsLfnName("answer.toml"))
        assertTrue(image.containsLfnName("firstboot.sh"))
        assertTrue(image.containsAscii("answer = true"))
        assertTrue(image.containsAscii("#!/bin/sh"))
    }

    @Test
    fun populatesPreformattedVfatImage() {
        val empty = VFatImage.create(
            AuxiliaryDataPlan(
                label = "PROXMOX-AIS",
                filesystem = "vfat",
                placement = "preallocated_in_base_image",
                sizeBytes = 1024L * 1024L,
                sourceSha256 = "c".repeat(64),
                geometry = geometry(byteLength = 1024L * 1024L),
                files = emptyList(),
            ),
        )

        val populated = VFatImage.populate(
            empty,
            AuxiliaryDataPlan(
                label = "PROXMOX-AIS",
                filesystem = "vfat",
                placement = "preallocated_in_base_image",
                sizeBytes = 1024L * 1024L,
                sourceSha256 = "c".repeat(64),
                geometry = geometry(byteLength = 1024L * 1024L),
                files = listOf(
                    AuxiliaryDataFile("/answer.toml", "answer = true\n", "application/toml"),
                    AuxiliaryDataFile("/firstboot.sh", "#!/bin/sh\n", "text/x-shellscript", executable = true),
                ),
            ),
        )

        assertEquals("PROXMOX-AIS", populated.decodeAscii(71, 11).trim())
        assertTrue(populated.containsLfnName("answer.toml"))
        assertTrue(populated.containsLfnName("firstboot.sh"))
        assertTrue(populated.containsAscii("answer = true"))
        assertTrue(populated.containsAscii("#!/bin/sh"))
    }

    @Test
    fun createsVfatWhenExistingPartitionIsZeroFilled() {
        val plan = AuxiliaryDataPlan(
            label = "CIDATA",
            filesystem = "vfat",
            placement = "append_after_image",
            sizeBytes = 1024L * 1024L,
            sourceSha256 = "c".repeat(64),
            geometry = geometry(byteLength = 1024L * 1024L),
            files = listOf(AuxiliaryDataFile("/user-data", "#cloud-config\n", "text/cloud-config")),
        )

        val image = VFatImage.populateOrCreate(ByteArray(1024 * 1024), plan)

        assertEquals("CIDATA", image.decodeAscii(71, 11).trim())
        assertTrue(image.containsLfnName("user-data"))
    }

    @Test
    fun createsBundledUbuntuCidataSize() {
        val size = 64L * 1024L * 1024L
        val plan = AuxiliaryDataPlan(
            label = "CIDATA",
            filesystem = "vfat",
            placement = "append_after_image",
            sizeBytes = size,
            sourceSha256 = "c".repeat(64),
            geometry = geometry(byteLength = size),
            files = listOf(
                AuxiliaryDataFile("/user-data", "#cloud-config\n", "text/cloud-config"),
                AuxiliaryDataFile("/meta-data", "local-hostname: ubuntu-test\n", "text/plain"),
            ),
        )
        val image = VFatImage.create(plan)
        val sparse = VFatImage.sparse(plan)

        assertEquals(size.toInt(), image.size)
        var offset = 0
        while (offset < sparse.size) {
            val length = minOf(64 * 1024, sparse.size - offset)
            assertArrayEquals(image.copyOfRange(offset, offset + length), sparse.chunk(offset, length))
            offset += length
        }
        assertEquals("CIDATA", image.decodeAscii(71, 11).trim())
        assertTrue(image.containsLfnName("user-data"))
        assertTrue(image.containsLfnName("meta-data"))
    }

    @Test(expected = WriteRefusedException::class)
    fun rejectsNonVfatNonZeroExistingPartition() {
        val existing = ByteArray(1024 * 1024) { 1 }
        VFatImage.populateOrCreate(
            existing,
            AuxiliaryDataPlan(
                label = "CIDATA",
                filesystem = "vfat",
                placement = "append_after_image",
                sizeBytes = 1024L * 1024L,
                sourceSha256 = "c".repeat(64),
                geometry = geometry(byteLength = 1024L * 1024L),
                files = emptyList(),
            ),
        )
    }

    private fun geometry(byteLength: Long): PartitionGeometry = PartitionGeometry(
        baseImageSha256 = "c".repeat(64),
        partitionTable = "gpt",
        partitionNumber = 1,
        startLba = 2048,
        sectorSize = 512,
        byteOffset = 2048L * 512L,
        byteLength = byteLength,
    )
}

private fun ByteArray.containsAscii(value: String): Boolean {
    return toString(Charsets.ISO_8859_1).contains(value)
}

private fun ByteArray.containsLfnName(value: String): Boolean {
    return indices.step(32).any { offset ->
        offset + 32 <= size && this[offset + 11] == 0x0f.toByte() && decodeLfnEntry(offset) == value
    }
}

private fun ByteArray.decodeLfnEntry(offset: Int): String {
    val positions = listOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
    return buildString {
        positions.forEach { position ->
            val low = this@decodeLfnEntry[offset + position].toInt() and 0xff
            val high = this@decodeLfnEntry[offset + position + 1].toInt() and 0xff
            val code = low or (high shl 8)
            if (code != 0 && code != 0xffff) append(code.toChar())
        }
    }
}

private fun ByteArray.decodeAscii(offset: Int, length: Int): String {
    return copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
}
