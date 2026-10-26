package net.metalstrapper.sparkwriter.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.zip.CRC32

class GptPartitionAppenderTest {
    @Test
    fun appendsCidataPartitionAndMovesBackupGptToDeviceEnd() {
        val disk = syntheticGptDisk(deviceSectors = 200_000, entries = listOf(entry("ISO9660", 64, 10_000)))

        val geometry = append(disk, sizeBytes = 64L * 1024L * 1024L)

        assertEquals(10_240L, geometry.startLba)
        assertEquals(10_240L * 512L, geometry.byteOffset)
        assertEquals(64L * 1024L * 1024L, geometry.byteLength)
        assertEquals(2, geometry.partitionNumber)

        val primary = disk.copyOfRange(512, 1024)
        assertEquals(199_999L, primary.readLe64(32))
        assertEquals(199_997L, primary.readLe64(48))
        val appended = disk.copyOfRange(2 * 512 + 128, 2 * 512 + 256)
        assertEquals("CIDATA", appended.decodeName())
        assertEquals(10_240L, appended.readLe64(32))
        assertEquals(141_311L, appended.readLe64(40))
        assertEquals("EFI PART", disk.copyOfRange(199_999 * 512, 200_000 * 512).toString(Charsets.US_ASCII).take(8))
    }

    @Test
    fun usesFirstFreeGptEntry() {
        val disk = syntheticGptDisk(
            deviceSectors = 100_000,
            entries = listOf(entry("one", 64, 100), null, entry("two", 2048, 4095)),
        )

        val geometry = append(disk, sizeBytes = 1024L * 1024L)

        assertEquals(2, geometry.partitionNumber)
        val appended = disk.copyOfRange(2 * 512 + 128, 2 * 512 + 256)
        assertEquals("CIDATA", appended.decodeName())
    }

    @Test(expected = WriteRefusedException::class)
    fun rejectsExistingCidataPartition() {
        val disk = syntheticGptDisk(deviceSectors = 100_000, entries = listOf(entry("CIDATA", 2048, 4095)))

        append(disk, sizeBytes = 1024L * 1024L)
    }

    @Test(expected = WriteRefusedException::class)
    fun rejectsFullPartitionTable() {
        val disk = syntheticGptDisk(
            deviceSectors = 100_000,
            entryCount = 2,
            entries = listOf(entry("one", 64, 100), entry("two", 2048, 4095)),
        )

        append(disk, sizeBytes = 1024L * 1024L)
    }

    @Test(expected = WriteRefusedException::class)
    fun rejectsInsufficientSpace() {
        val disk = syntheticGptDisk(deviceSectors = 10_000, entries = listOf(entry("ISO9660", 64, 9000)))

        append(disk, sizeBytes = 1024L * 1024L)
    }

    @Test(expected = WriteRefusedException::class)
    fun rejectsBadGptHeaderCrc() {
        val disk = syntheticGptDisk(deviceSectors = 100_000, entries = listOf(entry("ISO9660", 64, 100)))
        disk[512 + 20] = 1

        append(disk, sizeBytes = 1024L * 1024L)
    }

    @Test(expected = WriteRefusedException::class)
    fun rejectsBadPartitionTableCrc() {
        val disk = syntheticGptDisk(deviceSectors = 100_000, entries = listOf(entry("ISO9660", 64, 100)))
        disk[2 * 512] = 2

        append(disk, sizeBytes = 1024L * 1024L)
    }

    private fun append(disk: ByteArray, sizeBytes: Long) = GptPartitionAppender.appendFat32Partition(
        label = "CIDATA",
        sizeBytes = sizeBytes,
        sourceSha256 = "c".repeat(64),
        deviceBytes = disk.size.toLong(),
        readBytes = { offset, length -> disk.copyOfRange(offset.toInt(), offset.toInt() + length) },
        writeBytes = { offset, data -> data.copyInto(disk, offset.toInt()) },
        partitionGuid = UUID.fromString("11111111-2222-3333-4444-555555555555"),
    )
}

private data class TestEntry(val label: String, val firstLba: Long, val lastLba: Long)

private fun entry(label: String, firstLba: Long, lastLba: Long): TestEntry = TestEntry(label, firstLba, lastLba)

private fun syntheticGptDisk(deviceSectors: Int, entryCount: Int = 4, entries: List<TestEntry?>): ByteArray {
    val disk = ByteArray(deviceSectors * 512)
    disk[510] = 0x55
    disk[511] = 0xaa.toByte()
    disk[446 + 4] = 0xee.toByte()
    disk.putLe32(446 + 8, 1)
    disk.putLe32(446 + 12, minOf(deviceSectors.toLong() - 1, 0xffff_ffffL))

    val entryBytes = ByteArray(entryCount * 128)
    entries.forEachIndexed { index, testEntry ->
        if (testEntry != null) {
            val offset = index * 128
            UUID.fromString("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7").toLittleEndianBytes().copyInto(entryBytes, offset)
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeee${index}").toLittleEndianBytes().copyInto(entryBytes, offset + 16)
            entryBytes.putLe64(offset + 32, testEntry.firstLba)
            entryBytes.putLe64(offset + 40, testEntry.lastLba)
            testEntry.label.toByteArray(Charsets.UTF_16LE).copyInto(entryBytes, offset + 56)
        }
    }
    entryBytes.copyInto(disk, 2 * 512)
    val entriesCrc = entryBytes.crc32()
    gptHeader(
        currentLba = 1,
        backupLba = 99,
        firstUsableLba = 64,
        lastUsableLba = 98,
        entriesLba = 2,
        entryCount = entryCount,
        entriesCrc = entriesCrc,
    ).copyInto(disk, 512)
    return disk
}

private fun gptHeader(
    currentLba: Long,
    backupLba: Long,
    firstUsableLba: Long,
    lastUsableLba: Long,
    entriesLba: Long,
    entryCount: Int,
    entriesCrc: Long,
): ByteArray {
    val header = ByteArray(512)
    "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(header)
    header.putLe32(8, 0x00010000)
    header.putLe32(12, 92)
    header.putLe64(24, currentLba)
    header.putLe64(32, backupLba)
    header.putLe64(40, firstUsableLba)
    header.putLe64(48, lastUsableLba)
    UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff").toLittleEndianBytes().copyInto(header, 56)
    header.putLe64(72, entriesLba)
    header.putLe32(80, entryCount.toLong())
    header.putLe32(84, 128)
    header.putLe32(88, entriesCrc)
    header.putLe32(16, header.copyOfRange(0, 92).crc32())
    return header
}

private fun ByteArray.decodeName(): String = copyOfRange(56, 128).toString(Charsets.UTF_16LE).trimEnd('\u0000')

private fun ByteArray.readLe64(offset: Int): Long {
    var value = 0L
    for (index in 7 downTo 0) value = (value shl 8) or (this[offset + index].toLong() and 0xff)
    return value
}

private fun ByteArray.putLe32(offset: Int, value: Long) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
    this[offset + 2] = ((value ushr 16) and 0xff).toByte()
    this[offset + 3] = ((value ushr 24) and 0xff).toByte()
}

private fun ByteArray.putLe64(offset: Int, value: Long) {
    repeat(8) { index -> this[offset + index] = ((value ushr (index * 8)) and 0xff).toByte() }
}

private fun ByteArray.crc32(): Long {
    val crc = CRC32()
    crc.update(this)
    return crc.value
}

private fun UUID.toLittleEndianBytes(): ByteArray {
    val raw = toString().replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return byteArrayOf(
        raw[3], raw[2], raw[1], raw[0],
        raw[5], raw[4],
        raw[7], raw[6],
        raw[8], raw[9], raw[10], raw[11], raw[12], raw[13], raw[14], raw[15],
    )
}
