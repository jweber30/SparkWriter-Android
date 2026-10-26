package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.PartitionGeometry
import org.junit.Test

class GptGeometryValidatorTest {
    @Test
    fun acceptsDeclaredPartitionGeometry() {
        val disk = syntheticGptDisk(startLba = 2048, lastLba = 4095)
        GptGeometryValidator.validate(geometry(startLba = 2048, byteLength = 1024L * 1024L)) { offset, length ->
            disk.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
    }

    @Test(expected = WriteRefusedException::class)
    fun rejectsStartLbaMismatch() {
        val disk = syntheticGptDisk(startLba = 4096, lastLba = 8191)
        GptGeometryValidator.validate(geometry(startLba = 2048, byteLength = 1024L * 1024L)) { offset, length ->
            disk.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
    }

    private fun geometry(startLba: Long, byteLength: Long): PartitionGeometry = PartitionGeometry(
        baseImageSha256 = "c".repeat(64),
        partitionTable = "gpt",
        partitionNumber = 1,
        startLba = startLba,
        sectorSize = 512,
        byteOffset = startLba * 512L,
        byteLength = byteLength,
    )

    private fun syntheticGptDisk(startLba: Long, lastLba: Long): ByteArray {
        val disk = ByteArray(4096)
        "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(disk, 512)
        disk.putLe64(512 + 72, 2)
        disk.putLe32(512 + 80, 1)
        disk.putLe32(512 + 84, 128)
        val entryOffset = 1024
        disk[entryOffset] = 1
        disk.putLe64(entryOffset + 32, startLba)
        disk.putLe64(entryOffset + 40, lastLba)
        return disk
    }
}

private fun ByteArray.putLe32(offset: Int, value: Long) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
    this[offset + 2] = ((value ushr 16) and 0xff).toByte()
    this[offset + 3] = ((value ushr 24) and 0xff).toByte()
}

private fun ByteArray.putLe64(offset: Int, value: Long) {
    repeat(8) { index ->
        this[offset + index] = ((value ushr (index * 8)) and 0xff).toByte()
    }
}
