package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.PartitionGeometry
import java.util.UUID
import java.util.zip.CRC32
import kotlin.math.max

private const val GPT_SECTOR_SIZE = 512
private const val GPT_SIGNATURE = "EFI PART"
private const val GPT_HEADER_LBA = 1L
private const val GPT_MIN_ENTRY_SIZE = 128
private const val GPT_ALIGNMENT_SECTORS = 2048L
private const val PROTECTIVE_MBR_PARTITION_OFFSET = 446
private const val MICROSOFT_BASIC_DATA_GUID = "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7"

internal object GptPartitionAppender {
    fun appendFat32Partition(
        label: String,
        sizeBytes: Long,
        sourceSha256: String,
        deviceBytes: Long,
        readBytes: (offset: Long, length: Int) -> ByteArray,
        writeBytes: (offset: Long, data: ByteArray) -> Unit,
        partitionGuid: UUID = UUID.randomUUID(),
    ): PartitionGeometry {
        if (deviceBytes % GPT_SECTOR_SIZE != 0L) throw WriteRefusedException("GPT device size must be 512-byte aligned")
        if (sizeBytes <= 0 || sizeBytes % GPT_SECTOR_SIZE != 0L) {
            throw WriteRefusedException("Dynamic auxiliary partition size must be 512-byte aligned")
        }
        val deviceSectors = deviceBytes / GPT_SECTOR_SIZE
        val deviceLastLba = deviceSectors - 1
        val header = GptHeader.read(readBytes(GPT_SECTOR_SIZE.toLong(), GPT_SECTOR_SIZE))
        if (header.currentLba != GPT_HEADER_LBA) throw WriteRefusedException("Unsupported primary GPT header location")
        if (header.entrySize < GPT_MIN_ENTRY_SIZE) throw WriteRefusedException("GPT partition entry size is unsupported")

        val entryBytes = header.entryCount.checkedIntMultiply(header.entrySize, "GPT partition table is too large")
        val entrySectors = roundUpDiv(entryBytes.toLong(), GPT_SECTOR_SIZE.toLong())
        val entryTransferBytes = (entrySectors * GPT_SECTOR_SIZE).toIntOrNull("GPT partition table is too large")
        val entryTable = readBytes(header.entriesLba * GPT_SECTOR_SIZE, entryTransferBytes)
        val entries = entryTable.copyOfRange(0, entryBytes)
        if (crc32(entries) != header.entriesCrc) throw WriteRefusedException("GPT partition table CRC mismatch")

        val parsed = parseEntries(entries, header.entryCount, header.entrySize, label)
        val freeIndex = parsed.freeIndex ?: throw WriteRefusedException("GPT partition table has no free entry for auxiliary data")
        if (parsed.labelExists) throw WriteRefusedException("Auxiliary GPT partition $label already exists")

        val backupEntriesLba = deviceLastLba - entrySectors
        val lastUsableLba = backupEntriesLba - 1
        val startLba = alignUp(parsed.highestUsedLastLba + 1, GPT_ALIGNMENT_SECTORS)
        val sectorsNeeded = sizeBytes / GPT_SECTOR_SIZE
        val lastLba = startLba + sectorsNeeded - 1
        if (lastLba > lastUsableLba) throw WriteRefusedException("USB device has no room for auxiliary partition")

        val updatedEntries = entries.copyOf()
        partitionEntry(
            typeGuid = UUID.fromString(MICROSOFT_BASIC_DATA_GUID),
            uniqueGuid = partitionGuid,
            firstLba = startLba,
            lastLba = lastLba,
            label = label,
        ).copyInto(updatedEntries, freeIndex * header.entrySize)
        val updatedEntriesCrc = crc32(updatedEntries)
        val updatedEntryTable = entryTable.copyOf()
        updatedEntries.copyInto(updatedEntryTable)
        val primary = header.copy(
            backupLba = deviceLastLba,
            lastUsableLba = lastUsableLba,
            entriesCrc = updatedEntriesCrc,
        ).toBytes()
        val backup = header.copy(
            currentLba = deviceLastLba,
            backupLba = GPT_HEADER_LBA,
            lastUsableLba = lastUsableLba,
            entriesLba = backupEntriesLba,
            entriesCrc = updatedEntriesCrc,
        ).toBytes()
        val protectiveMbr = protectiveMbr(readBytes(0, GPT_SECTOR_SIZE), deviceSectors)

        writeBytes(0, protectiveMbr)
        writeBytes(header.entriesLba * GPT_SECTOR_SIZE, updatedEntryTable)
        writeBytes(GPT_SECTOR_SIZE.toLong(), primary)
        writeBytes(backupEntriesLba * GPT_SECTOR_SIZE, updatedEntryTable)
        writeBytes(deviceLastLba * GPT_SECTOR_SIZE, backup)

        return PartitionGeometry(
            baseImageSha256 = sourceSha256,
            partitionTable = "gpt",
            partitionNumber = freeIndex + 1,
            startLba = startLba,
            sectorSize = GPT_SECTOR_SIZE,
            byteOffset = startLba * GPT_SECTOR_SIZE,
            byteLength = sizeBytes,
        )
    }
}

private data class ParsedEntries(
    val freeIndex: Int?,
    val labelExists: Boolean,
    val highestUsedLastLba: Long,
)

private data class GptHeader(
    val raw: ByteArray,
    val headerSize: Int,
    val currentLba: Long,
    val backupLba: Long,
    val firstUsableLba: Long,
    val lastUsableLba: Long,
    val diskGuid: ByteArray,
    val entriesLba: Long,
    val entryCount: Int,
    val entrySize: Int,
    val entriesCrc: Long,
) {
    fun toBytes(): ByteArray {
        val bytes = ByteArray(GPT_SECTOR_SIZE)
        raw.copyInto(bytes, endIndex = headerSize)
        bytes.putLe64(24, currentLba)
        bytes.putLe64(32, backupLba)
        bytes.putLe64(40, firstUsableLba)
        bytes.putLe64(48, lastUsableLba)
        diskGuid.copyInto(bytes, 56)
        bytes.putLe64(72, entriesLba)
        bytes.putLe32(80, entryCount.toLong())
        bytes.putLe32(84, entrySize.toLong())
        bytes.putLe32(88, entriesCrc)
        bytes.putLe32(16, 0)
        bytes.putLe32(16, crc32(bytes.copyOfRange(0, headerSize)).toLong())
        return bytes
    }

    companion object {
        fun read(bytes: ByteArray): GptHeader {
            if (bytes.decodeAscii(0, 8) != GPT_SIGNATURE) {
                throw WriteRefusedException("GPT header was not found after base image write")
            }
            val headerSize = bytes.readUInt32LE(12).toInt()
            if (headerSize !in 92..GPT_SECTOR_SIZE) throw WriteRefusedException("GPT header size is unsupported")
            val expectedCrc = bytes.readUInt32LE(16)
            val copy = bytes.copyOf()
            copy.putLe32(16, 0)
            if (crc32(copy.copyOfRange(0, headerSize)).toLong() != expectedCrc) {
                throw WriteRefusedException("GPT header CRC mismatch")
            }
            val entryCount = bytes.readUInt32LE(80).toIntOrNull("GPT partition count is too large")
            val entrySize = bytes.readUInt32LE(84).toIntOrNull("GPT partition entry size is too large")
            return GptHeader(
                raw = bytes.copyOf(),
                headerSize = headerSize,
                currentLba = bytes.readUInt64LE(24),
                backupLba = bytes.readUInt64LE(32),
                firstUsableLba = bytes.readUInt64LE(40),
                lastUsableLba = bytes.readUInt64LE(48),
                diskGuid = bytes.copyOfRange(56, 72),
                entriesLba = bytes.readUInt64LE(72),
                entryCount = entryCount,
                entrySize = entrySize,
                entriesCrc = bytes.readUInt32LE(88),
            )
        }
    }
}

private fun parseEntries(entries: ByteArray, entryCount: Int, entrySize: Int, label: String): ParsedEntries {
    var freeIndex: Int? = null
    var labelExists = false
    var highest = 0L
    repeat(entryCount) { index ->
        val offset = index * entrySize
        val entry = entries.copyOfRange(offset, offset + entrySize)
        if (entry.copyOfRange(0, 16).all { it == 0.toByte() }) {
            if (freeIndex == null) freeIndex = index
        } else {
            highest = max(highest, entry.readUInt64LE(40))
            if (entry.decodeUtf16Le(56, 72).equals(label, ignoreCase = true)) labelExists = true
        }
    }
    return ParsedEntries(freeIndex, labelExists, highest)
}

private fun partitionEntry(typeGuid: UUID, uniqueGuid: UUID, firstLba: Long, lastLba: Long, label: String): ByteArray {
    val entry = ByteArray(GPT_MIN_ENTRY_SIZE)
    typeGuid.toLittleEndianBytes().copyInto(entry, 0)
    uniqueGuid.toLittleEndianBytes().copyInto(entry, 16)
    entry.putLe64(32, firstLba)
    entry.putLe64(40, lastLba)
    val labelBytes = label.toByteArray(Charsets.UTF_16LE).copyOfRange(0, minOf(label.length * 2, 72))
    labelBytes.copyInto(entry, 56)
    return entry
}

private fun protectiveMbr(existing: ByteArray, deviceSectors: Long): ByteArray {
    val mbr = existing.copyOf(GPT_SECTOR_SIZE)
    mbr.putLe32(PROTECTIVE_MBR_PARTITION_OFFSET + 8, 1)
    mbr.putLe32(PROTECTIVE_MBR_PARTITION_OFFSET + 12, minOf(deviceSectors - 1, 0xffff_ffffL))
    mbr[510] = 0x55
    mbr[511] = 0xaa.toByte()
    return mbr
}

private fun UUID.toLittleEndianBytes(): ByteArray {
    val text = toString().replace("-", "")
    val raw = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return byteArrayOf(
        raw[3], raw[2], raw[1], raw[0],
        raw[5], raw[4],
        raw[7], raw[6],
        raw[8], raw[9], raw[10], raw[11], raw[12], raw[13], raw[14], raw[15],
    )
}

private fun ByteArray.decodeAscii(offset: Int, length: Int): String {
    return copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
}

private fun ByteArray.decodeUtf16Le(offset: Int, length: Int): String {
    return copyOfRange(offset, offset + length).toString(Charsets.UTF_16LE).trimEnd('\u0000')
}

private fun ByteArray.readUInt32LE(offset: Int): Long {
    return (this[offset].toLong() and 0xff) or
        ((this[offset + 1].toLong() and 0xff) shl 8) or
        ((this[offset + 2].toLong() and 0xff) shl 16) or
        ((this[offset + 3].toLong() and 0xff) shl 24)
}

private fun ByteArray.readUInt64LE(offset: Int): Long {
    var value = 0L
    for (index in 7 downTo 0) {
        value = (value shl 8) or (this[offset + index].toLong() and 0xff)
    }
    if (value < 0) throw WriteRefusedException("GPT value is too large")
    return value
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

private fun Long.toIntOrNull(message: String): Int {
    if (this > Int.MAX_VALUE) throw WriteRefusedException(message)
    return toInt()
}

private fun Int.checkedIntMultiply(other: Int, message: String): Int {
    val value = toLong() * other.toLong()
    if (value > Int.MAX_VALUE) throw WriteRefusedException(message)
    return value.toInt()
}

private fun roundUpDiv(value: Long, divisor: Long): Long = (value + divisor - 1) / divisor

private fun alignUp(value: Long, alignment: Long): Long = roundUpDiv(value, alignment) * alignment

private fun crc32(bytes: ByteArray): Long {
    val crc = CRC32()
    crc.update(bytes)
    return crc.value
}
