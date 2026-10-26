package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.PartitionGeometry

internal object GptGeometryValidator {
    fun validate(geometry: PartitionGeometry, readBytes: (offset: Long, length: Int) -> ByteArray) {
        if (geometry.partitionTable != "gpt") {
            throw WriteRefusedException("Unsupported auxiliary partition table: ${geometry.partitionTable}")
        }
        if (geometry.sectorSize <= 0) throw WriteRefusedException("Invalid GPT sector size")
        val header = readBytes(geometry.sectorSize.toLong(), geometry.sectorSize)
        if (header.decodeAscii(0, 8) != "EFI PART") {
            throw WriteRefusedException("GPT header was not found after base image write")
        }
        val entryLba = header.readUInt64LE(72)
        val entryCount = header.readUInt32LE(80)
        val entrySize = header.readUInt32LE(84)
        if (geometry.partitionNumber <= 0 || geometry.partitionNumber.toLong() > entryCount) {
            throw WriteRefusedException("Auxiliary partition number is outside the GPT entry array")
        }
        if (entrySize < 128) throw WriteRefusedException("GPT partition entry size is unsupported")

        val entryOffset = entryLba * geometry.sectorSize + (geometry.partitionNumber - 1L) * entrySize
        val entry = readBytes(entryOffset, entrySize.toInt())
        if (entry.all { it == 0.toByte() }) {
            throw WriteRefusedException("Auxiliary GPT partition entry is empty")
        }
        val firstLba = entry.readUInt64LE(32)
        val lastLba = entry.readUInt64LE(40)
        if (firstLba != geometry.startLba) {
            throw WriteRefusedException("Auxiliary GPT partition start does not match declared geometry")
        }
        val partitionBytes = (lastLba - firstLba + 1) * geometry.sectorSize
        if (geometry.byteLength > partitionBytes) {
            throw WriteRefusedException("Auxiliary geometry exceeds declared GPT partition")
        }
    }
}

private fun ByteArray.decodeAscii(offset: Int, length: Int): String {
    return copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
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
