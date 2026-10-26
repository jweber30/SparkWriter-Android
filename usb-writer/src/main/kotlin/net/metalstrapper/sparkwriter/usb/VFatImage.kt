package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.AuxiliaryDataFile
import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import kotlin.math.ceil

private const val SECTOR_SIZE = 512
private const val RESERVED_SECTORS = 32
private const val FAT_COUNT = 2
private const val END_OF_CHAIN = 0x0fff_ffff

internal object VFatImage {
    fun create(plan: AuxiliaryDataPlan): ByteArray {
        return sparse(plan).toByteArray()
    }

    fun sparse(plan: AuxiliaryDataPlan): SparseVFatImage {
        if (!plan.filesystem.equals("vfat", ignoreCase = true)) {
            throw WriteRefusedException("Unsupported auxiliary filesystem: ${plan.filesystem}")
        }
        val geometry = plan.geometry ?: throw WriteRefusedException("Auxiliary delivery is missing resolved partition geometry")
        if (geometry.byteLength > Int.MAX_VALUE) {
            throw WriteRefusedException("Auxiliary partition is too large for VFAT creation")
        }
        if (geometry.byteLength % SECTOR_SIZE != 0L) {
            throw WriteRefusedException("Auxiliary partition length must be 512-byte aligned")
        }
        val layout = FatLayout.forBytes(geometry.byteLength.toInt())
        val root = DirectoryNode("", parent = null)
        plan.files.forEach { root.add(it) }
        val allocator = ClusterAllocator(layout)
        allocator.reserveRoot(root)
        root.allocateChildren(allocator)
        return SparseVFatImage(
            size = geometry.byteLength.toInt(),
            ranges = buildList {
                addAll(bootSectorRanges(layout, plan.label))
                addAll(fatRanges(layout, allocator.fat))
                addAll(root.ranges(layout, plan.label))
            },
        )
    }

    fun populate(existing: ByteArray, plan: AuxiliaryDataPlan): ByteArray {
        if (!plan.filesystem.equals("vfat", ignoreCase = true)) {
            throw WriteRefusedException("Unsupported auxiliary filesystem: ${plan.filesystem}")
        }
        if (existing.size % SECTOR_SIZE != 0) {
            throw WriteRefusedException("Auxiliary VFAT image must be 512-byte aligned")
        }
        val image = existing.copyOf()
        val layout = FatLayout.fromExisting(image)
        val root = DirectoryNode("", parent = null)
        root.firstCluster = layout.rootCluster
        plan.files.forEach { root.add(it) }
        val allocator = ClusterAllocator.fromExisting(layout, image)
        root.allocateChildren(allocator)
        writeFats(image, layout, allocator.fat)
        root.writeDirectory(image, layout, plan.label)
        return image
    }

    fun populateOrCreate(existing: ByteArray, plan: AuxiliaryDataPlan): ByteArray {
        return try {
            populate(existing, plan)
        } catch (error: WriteRefusedException) {
            if (existing.all { it == 0.toByte() }) {
                create(plan)
            } else {
                throw error
            }
        }
    }
}

internal class SparseVFatImage(
    val size: Int,
    ranges: List<SparseRange>,
) {
    private val ranges = ranges.sortedBy { it.offset }

    fun chunk(offset: Int, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw WriteRefusedException("Auxiliary VFAT chunk is outside the partition")
        }
        val chunk = ByteArray(length)
        ranges.forEach { range ->
            val start = maxOf(offset, range.offset)
            val end = minOf(offset + length, range.offset + range.bytes.size)
            if (start < end) {
                range.bytes.copyInto(
                    destination = chunk,
                    destinationOffset = start - offset,
                    startIndex = start - range.offset,
                    endIndex = end - range.offset,
                )
            }
        }
        return chunk
    }

    fun toByteArray(): ByteArray {
        val image = ByteArray(size)
        ranges.forEach { range -> range.bytes.copyInto(image, range.offset) }
        return image
    }
}

internal data class SparseRange(
    val offset: Int,
    val bytes: ByteArray,
)

private data class FatLayout(
    val totalSectors: Int,
    val sectorsPerCluster: Int,
    val fatSectors: Int,
    val reservedSectors: Int = RESERVED_SECTORS,
    val fatCount: Int = FAT_COUNT,
    val rootCluster: Int = 2,
) {
    val clusterBytes: Int = sectorsPerCluster * SECTOR_SIZE
    val dataStartSector: Int = reservedSectors + fatCount * fatSectors

    fun clusterOffset(cluster: Int): Int {
        return (dataStartSector + (cluster - 2) * sectorsPerCluster) * SECTOR_SIZE
    }

    companion object {
        fun forBytes(byteCount: Int): FatLayout {
            val totalSectors = byteCount / SECTOR_SIZE
            var fatSectors = 1
            while (true) {
                val clusters = totalSectors - RESERVED_SECTORS - FAT_COUNT * fatSectors
                if (clusters <= 0) throw WriteRefusedException("Auxiliary partition is too small for VFAT")
                val requiredFatSectors = ceil((clusters + 2) * 4.0 / SECTOR_SIZE).toInt()
                if (requiredFatSectors <= fatSectors) {
                    return FatLayout(totalSectors, sectorsPerCluster = 1, fatSectors = fatSectors)
                }
                fatSectors = requiredFatSectors
            }
        }

        fun fromExisting(image: ByteArray): FatLayout {
            if (image.size < SECTOR_SIZE || image[510] != 0x55.toByte() || image[511] != 0xAA.toByte()) {
                throw WriteRefusedException("Auxiliary partition is not a valid VFAT filesystem")
            }
            val bytesPerSector = image.getLe16(11)
            if (bytesPerSector != SECTOR_SIZE) {
                throw WriteRefusedException("Auxiliary VFAT sector size must be 512 bytes")
            }
            val sectorsPerCluster = image[13].toInt() and 0xff
            val reservedSectors = image.getLe16(14)
            val fatCount = image[16].toInt() and 0xff
            val totalSectors16 = image.getLe16(19)
            val totalSectors = if (totalSectors16 != 0) totalSectors16 else image.getLe32(32)
            val fatSectors16 = image.getLe16(22)
            val fatSectors = if (fatSectors16 != 0) fatSectors16 else image.getLe32(36)
            val rootCluster = image.getLe32(44)
            if (sectorsPerCluster <= 0 || reservedSectors <= 0 || fatCount <= 0 || fatSectors <= 0 || rootCluster < 2) {
                throw WriteRefusedException("Auxiliary partition has invalid VFAT geometry")
            }
            if (totalSectors <= 0 || totalSectors * SECTOR_SIZE > image.size) {
                throw WriteRefusedException("Auxiliary VFAT total sectors exceed partition image")
            }
            val layout = FatLayout(totalSectors, sectorsPerCluster, fatSectors, reservedSectors, fatCount, rootCluster)
            if (layout.clusterOffset(rootCluster) + layout.clusterBytes > image.size) {
                throw WriteRefusedException("Auxiliary VFAT root directory is outside the partition")
            }
            return layout
        }
    }
}

private class ClusterAllocator(private val layout: FatLayout) {
    val fat = IntArray(layout.totalSectors - layout.dataStartSector + 2)
    val clusterBytes: Int get() = layout.clusterBytes
    private var nextCluster = 2

    init {
        fat[0] = 0x0fff_fff8.toInt()
        fat[1] = END_OF_CHAIN
    }

    private constructor(layout: FatLayout, existingFat: IntArray) : this(layout) {
        existingFat.copyInto(fat, endIndex = minOf(existingFat.size, fat.size))
    }

    fun reserveRoot(root: DirectoryNode) {
        root.firstCluster = allocateChain(1).single()
    }

    fun allocateChain(count: Int): List<Int> {
        val clusters = (0 until count).map {
            while (nextCluster < fat.size && !fat[nextCluster].isFreeCluster()) nextCluster++
            if (nextCluster >= fat.size) throw WriteRefusedException("Auxiliary partition is too small for files")
            nextCluster++
        }
        clusters.forEachIndexed { index, cluster ->
            fat[cluster] = clusters.getOrNull(index + 1) ?: END_OF_CHAIN
        }
        return clusters
    }

    companion object {
        fun fromExisting(layout: FatLayout, image: ByteArray): ClusterAllocator {
            val offset = layout.reservedSectors * SECTOR_SIZE
            val entries = (layout.fatSectors * SECTOR_SIZE) / 4
            if (offset + entries * 4 > image.size) {
                throw WriteRefusedException("Auxiliary VFAT table is outside the partition")
            }
            val existingFat = IntArray(entries) { index -> image.getLe32(offset + index * 4) }
            return ClusterAllocator(layout, existingFat).also {
                it.nextCluster = 2
            }
        }
    }
}

private class DirectoryNode(
    val name: String,
    val parent: DirectoryNode?,
) {
    val directories = linkedMapOf<String, DirectoryNode>()
    val files = linkedMapOf<String, FileNode>()
    var firstCluster: Int = 0

    fun add(file: AuxiliaryDataFile) {
        val parts = file.path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) throw WriteRefusedException("Auxiliary file path must name a file")
        var current = this
        parts.dropLast(1).forEach { part ->
            current = current.directories.getOrPut(part) { DirectoryNode(part, current) }
        }
        current.files[parts.last()] = FileNode(parts.last(), file.content.toByteArray(Charsets.UTF_8))
    }

    fun allocateChildren(allocator: ClusterAllocator) {
        directories.values.forEach { directory ->
            directory.firstCluster = allocator.allocateChain(1).single()
            directory.allocateChildren(allocator)
        }
        files.values.forEach { file ->
            val count = ceil(file.bytes.size.toDouble() / allocator.clusterBytes).toInt().coerceAtLeast(1)
            file.clusters = allocator.allocateChain(count)
        }
    }

    fun writeDirectory(image: ByteArray, layout: FatLayout, label: String) {
        ranges(layout, label).forEach { range ->
            range.bytes.copyInto(image, range.offset)
        }
    }

    fun ranges(layout: FatLayout, label: String): List<SparseRange> {
        return buildList {
            add(directoryRange(layout, label))
            directories.values.forEach { addAll(it.ranges(layout, label)) }
            files.values.forEach { file ->
                var copied = 0
                file.clusters.forEach { cluster ->
                    val bytes = ByteArray(layout.clusterBytes)
                    val count = minOf(layout.clusterBytes, file.bytes.size - copied)
                    if (count > 0) file.bytes.copyInto(bytes, 0, copied, copied + count)
                    copied += count
                    add(SparseRange(layout.clusterOffset(cluster), bytes))
                }
            }
        }
    }

    private fun directoryRange(layout: FatLayout, label: String): SparseRange {
        val entries = mutableListOf<ByteArray>()
        if (parent == null) entries += volumeLabelEntry(label)
        if (parent != null) {
            entries += shortEntry(".", "", 0x10, firstCluster, 0)
            entries += shortEntry("..", "", 0x10, parent.firstCluster, 0)
        }
        directories.values.forEach { directory ->
            entries += lfnEntries(directory.name, shortName(directory.name, directory = true))
            entries += shortEntryFor(directory.name, directory = true, firstCluster = directory.firstCluster, size = 0)
        }
        files.values.forEach { file ->
            entries += lfnEntries(file.name, shortName(file.name, directory = false))
            entries += shortEntryFor(file.name, directory = false, firstCluster = file.clusters.first(), size = file.bytes.size)
        }
        val directoryOffset = layout.clusterOffset(firstCluster)
        val requiredBytes = entries.size * 32
        if (requiredBytes > layout.clusterBytes) {
            throw WriteRefusedException("Auxiliary directory ${name.ifBlank { "/" }} has too many entries")
        }
        val bytes = ByteArray(layout.clusterBytes)
        entries.forEachIndexed { index, entry ->
            entry.copyInto(bytes, index * 32)
        }
        return SparseRange(directoryOffset, bytes)
    }
}

private data class FileNode(
    val name: String,
    val bytes: ByteArray,
) {
    var clusters: List<Int> = emptyList()
}

private fun writeBootSectors(image: ByteArray, layout: FatLayout, label: String) {
    bootSectorRanges(layout, label).forEach { range -> range.bytes.copyInto(image, range.offset) }
}

private fun bootSectorRanges(layout: FatLayout, label: String): List<SparseRange> {
    val boot = ByteArray(SECTOR_SIZE)
    boot[0] = 0xEB.toByte()
    boot[1] = 0x58
    boot[2] = 0x90.toByte()
    "MSWIN4.1".toByteArray(Charsets.US_ASCII).copyInto(boot, 3)
    boot.putLe16(11, SECTOR_SIZE)
    boot[13] = layout.sectorsPerCluster.toByte()
    boot.putLe16(14, RESERVED_SECTORS)
    boot[16] = FAT_COUNT.toByte()
    boot.putLe32(32, layout.totalSectors)
    boot[21] = 0xF8.toByte()
    boot.putLe32(36, layout.fatSectors)
    boot.putLe16(40, 0)
    boot.putLe16(42, 0)
    boot.putLe32(44, 2)
    boot.putLe16(48, 1)
    boot.putLe16(50, 6)
    boot[64] = 0x80.toByte()
    boot[66] = 0x29
    boot.putLe32(67, 0x53505752)
    fatLabel(label).copyInto(boot, 71)
    "FAT32   ".toByteArray(Charsets.US_ASCII).copyInto(boot, 82)
    boot[510] = 0x55
    boot[511] = 0xAA.toByte()

    val fsInfo = ByteArray(SECTOR_SIZE)
    fsInfo.putLe32(0, 0x41615252)
    fsInfo.putLe32(484, 0x61417272)
    fsInfo.putLe32(488, 0xffff_ffff.toInt())
    fsInfo.putLe32(492, 0xffff_ffff.toInt())
    fsInfo[510] = 0x55
    fsInfo[511] = 0xAA.toByte()
    return listOf(
        SparseRange(0, boot),
        SparseRange(6 * SECTOR_SIZE, boot),
        SparseRange(SECTOR_SIZE, fsInfo),
        SparseRange(7 * SECTOR_SIZE, fsInfo),
    )
}

private fun writeFats(image: ByteArray, layout: FatLayout, fat: IntArray) {
    fatRanges(layout, fat).forEach { range ->
        range.bytes.copyInto(image, range.offset)
    }
}

private fun fatRanges(layout: FatLayout, fat: IntArray): List<SparseRange> {
    val fatBytes = ByteArray(layout.fatSectors * SECTOR_SIZE)
    fat.forEachIndexed { index, value ->
        val entryOffset = index * 4
        if (entryOffset + 4 <= fatBytes.size) fatBytes.putLe32(entryOffset, value)
    }
    return List(layout.fatCount) { fatIndex ->
        SparseRange(
            offset = (layout.reservedSectors + fatIndex * layout.fatSectors) * SECTOR_SIZE,
            bytes = fatBytes,
        )
    }
}

private fun volumeLabelEntry(label: String): ByteArray {
    val entry = ByteArray(32)
    fatLabel(label).copyInto(entry, 0)
    entry[11] = 0x08
    return entry
}

private fun shortEntryFor(name: String, directory: Boolean, firstCluster: Int, size: Int): ByteArray {
    val short = shortName(name, directory)
    return shortEntry(short.first, short.second, if (directory) 0x10 else 0x20, firstCluster, size)
}

private fun shortEntry(base: String, ext: String, attr: Int, firstCluster: Int, size: Int): ByteArray {
    val entry = ByteArray(32)
    base.padEnd(8).take(8).toByteArray(Charsets.US_ASCII).copyInto(entry, 0)
    ext.padEnd(3).take(3).toByteArray(Charsets.US_ASCII).copyInto(entry, 8)
    entry[11] = attr.toByte()
    entry.putLe16(20, (firstCluster ushr 16) and 0xffff)
    entry.putLe16(26, firstCluster and 0xffff)
    entry.putLe32(28, size)
    return entry
}

private fun lfnEntries(name: String, short: Pair<String, String>): List<ByteArray> {
    if (isSimpleShortName(name, short)) return emptyList()
    val chars = name.toCharArray().toList()
    val chunks = chars.chunked(13)
    val checksum = lfnChecksum(short)
    return chunks.indices.reversed().map { index ->
        val sequence = index + 1
        lfnEntry(
            order = if (index == chunks.lastIndex) sequence or 0x40 else sequence,
            chars = chunks[index],
            checksum = checksum,
        )
    }
}

private fun lfnEntry(order: Int, chars: List<Char>, checksum: Int): ByteArray {
    val entry = ByteArray(32) { 0xff.toByte() }
    entry[0] = order.toByte()
    entry[11] = 0x0f
    entry[12] = 0
    entry[13] = checksum.toByte()
    entry[26] = 0
    entry[27] = 0
    val padded = chars + '\u0000'
    val positions = listOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
    positions.forEachIndexed { index, offset ->
        val value = padded.getOrNull(index)?.code ?: 0xffff
        entry.putLe16(offset, value)
    }
    return entry
}

private fun shortName(name: String, directory: Boolean): Pair<String, String> {
    val clean = name.uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    val ext = if (!directory && name.contains('.')) {
        name.substringAfterLast('.').uppercase().filter { it.isLetterOrDigit() }.take(3)
    } else {
        ""
    }
    val baseSource = if (!directory && name.contains('.')) name.substringBeforeLast('.') else name
    val baseClean = baseSource.uppercase().filter { it.isLetterOrDigit() }.ifBlank { "FILE" }
    val needsTail = !isEightDotThree(name, directory)
    val base = if (needsTail) baseClean.take(6).padEnd(1, 'X') + "~1" else baseClean.take(8)
    return base.take(8) to ext
}

private fun isSimpleShortName(name: String, short: Pair<String, String>): Boolean {
    return name.uppercase() == if (short.second.isBlank()) short.first.trim() else "${short.first.trim()}.${short.second.trim()}"
}

private fun isEightDotThree(name: String, directory: Boolean): Boolean {
    val parts = name.split('.')
    val validChars = Regex("^[A-Za-z0-9]+$")
    return if (directory) {
        parts.size == 1 && name.length in 1..8 && validChars.matches(name)
    } else {
        parts.size <= 2 &&
            parts[0].length in 1..8 &&
            parts.getOrNull(1).orEmpty().length <= 3 &&
            parts.all { it.isNotBlank() && validChars.matches(it) }
    }
}

private fun lfnChecksum(short: Pair<String, String>): Int {
    val bytes = (short.first.padEnd(8) + short.second.padEnd(3)).toByteArray(Charsets.US_ASCII)
    var sum = 0
    bytes.forEach { byte ->
        sum = (((sum and 1) shl 7) + (sum shr 1) + (byte.toInt() and 0xff)) and 0xff
    }
    return sum
}

private fun fatLabel(label: String): ByteArray {
    return label.uppercase()
        .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
        .joinToString("")
        .take(11)
        .padEnd(11)
        .toByteArray(Charsets.US_ASCII)
}

private fun ByteArray.putLe16(offset: Int, value: Int) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
}

private fun ByteArray.putLe32(offset: Int, value: Int) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
    this[offset + 2] = ((value ushr 16) and 0xff).toByte()
    this[offset + 3] = ((value ushr 24) and 0xff).toByte()
}

private fun ByteArray.getLe16(offset: Int): Int {
    return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
}

private fun ByteArray.getLe32(offset: Int): Int {
    return (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        ((this[offset + 3].toInt() and 0xff) shl 24)
}

private fun Int.isFreeCluster(): Boolean = this == 0
