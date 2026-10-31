package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.ByteExtent
import net.metalstrapper.sparkwriter.core.FinalImageDigest
import net.metalstrapper.sparkwriter.core.VerifiedImage
import java.io.InputStream
import java.security.MessageDigest

/**
 * Every device write made after the base image, in the order it was made, so the finished image
 * can be reconstructed as base + overlay. Writes are stored sparsely: runs of all-zero
 * [SPARSE_GRANULE_BYTES] granules keep only their extent, so a mostly empty FAT partition costs
 * little memory.
 */
class WriteOverlay {
    private val writes = mutableListOf<OverlayWrite>()

    fun record(offset: Long, data: ByteArray) = record(offset, data, deviceFiller = false)

    /**
     * Records a read-modify-write: [patch] is what was intended at [patchOffset]; [alignedBuffer]
     * (at [alignedOffset]) is the whole blocks written, including surrounding bytes read back from
     * the device. Those surrounding bytes are only trusted outside the base image, where there is
     * no source to compare with; inside it, the base image stays the expectation, so the patch
     * cannot launder corruption that was already on the device.
     */
    fun recordReadModifyWrite(alignedOffset: Long, alignedBuffer: ByteArray, patchOffset: Long, patch: ByteArray) {
        record(alignedOffset, alignedBuffer, deviceFiller = true)
        record(patchOffset, patch, deviceFiller = false)
    }

    private fun record(offset: Long, data: ByteArray, deviceFiller: Boolean) {
        if (data.isEmpty()) return
        val runs = mutableListOf<OverlayRun>()
        var runStart = -1
        var position = 0
        while (position < data.size) {
            val end = minOf(position + SPARSE_GRANULE_BYTES, data.size)
            val zero = data.isZero(position, end)
            if (!zero && runStart < 0) runStart = position
            if (zero && runStart >= 0) {
                runs += OverlayRun(offset + runStart, data.copyOfRange(runStart, position))
                runStart = -1
            }
            position = end
        }
        if (runStart >= 0) runs += OverlayRun(offset + runStart, data.copyOfRange(runStart, data.size))
        writes += OverlayWrite(offset, data.size.toLong(), runs, deviceFiller)
    }

    /** Merged, sorted extents covered by recorded writes. */
    fun extents(): List<ByteExtent> = mergeExtents(writes.map { ByteExtent(it.offset, it.length) })

    /**
     * Applies recorded writes, oldest first, onto [buffer], which holds expected bytes from
     * [windowOffset]. Device filler from read-modify-writes is applied only at or beyond [baseEnd].
     */
    fun applyTo(windowOffset: Long, buffer: ByteArray, length: Int, baseEnd: Long = 0) {
        val windowEnd = windowOffset + length
        writes.forEach { write ->
            val start = maxOf(windowOffset, write.offset, if (write.deviceFiller) baseEnd else Long.MIN_VALUE)
            val end = minOf(windowEnd, write.offset + write.length)
            if (start >= end) return@forEach
            buffer.fill(0, (start - windowOffset).toInt(), (end - windowOffset).toInt())
            write.runs.forEach { run ->
                val runStart = maxOf(start, run.offset)
                val runEnd = minOf(end, run.offset + run.bytes.size)
                if (runStart < runEnd) {
                    run.bytes.copyInto(
                        destination = buffer,
                        destinationOffset = (runStart - windowOffset).toInt(),
                        startIndex = (runStart - run.offset).toInt(),
                        endIndex = (runEnd - run.offset).toInt(),
                    )
                }
            }
        }
    }

    private class OverlayWrite(
        val offset: Long,
        val length: Long,
        val runs: List<OverlayRun>,
        val deviceFiller: Boolean,
    )

    private class OverlayRun(val offset: Long, val bytes: ByteArray)

    private companion object {
        const val SPARSE_GRANULE_BYTES = 512
    }
}

object FinalImageVerifier {
    /**
     * Reads back everything that was written to the device in one ordered pass and compares it
     * with the expected finished image: [source] (block-padded with zeros, as the base write
     * does) with [overlay] applied. The source is hashed against the approved digest along the
     * way, so the comparison cannot pass against content that changed after verification.
     */
    suspend fun verify(
        source: InputStream,
        image: VerifiedImage,
        blockSize: Int,
        overlay: WriteOverlay,
        transferBytes: Int,
        readDevice: (offset: Long, buffer: ByteArray, length: Int) -> Unit,
        onProgress: suspend (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): FinalImageDigest {
        if (blockSize <= 0 || transferBytes < blockSize || transferBytes % blockSize != 0) {
            throw WriteRefusedException("Final verification transfer size must be a multiple of the block size")
        }
        val baseEnd = roundUp(image.sizeBytes, blockSize.toLong())
        val extents = mergeExtents(listOf(ByteExtent(0, baseEnd)) + overlay.extents())
        extents.forEach { extent ->
            if (extent.offset % blockSize != 0L || extent.length % blockSize != 0L) {
                throw WriteRefusedException("Final verification extent is not block aligned: $extent")
            }
        }
        val totalBytes = extents.sumOf { it.length }
        val approved = approvedImageStream(source)
        val digest = MessageDigest.getInstance("SHA-256")
        val expected = ByteArray(transferBytes)
        val actual = ByteArray(transferBytes)
        var sourcePosition = 0L
        var verified = 0L

        extents.forEach { extent ->
            var position = extent.offset
            val extentEnd = extent.offset + extent.length
            while (position < extentEnd) {
                val length = minOf(transferBytes.toLong(), extentEnd - position).toInt()
                expected.fill(0, 0, length)
                if (position < image.sizeBytes) {
                    // The base image is the first extent's prefix, so source reads stay sequential.
                    check(position == sourcePosition) { "Final verification read the source out of order" }
                    val sourceBytes = minOf(length.toLong(), image.sizeBytes - position).toInt()
                    val read = approved.readFullyOrToEnd(expected, sourceBytes)
                    if (read != sourceBytes) throw WriteRefusedException("Image ended before expected size")
                    sourcePosition += read
                }
                overlay.applyTo(position, expected, length, baseEnd)
                readDevice(position, actual, length)
                for (index in 0 until length) {
                    if (expected[index] != actual[index]) {
                        throw WriteRefusedException("Final USB verification failed at byte ${position + index}")
                    }
                }
                digest.update(actual, 0, length)
                position += length
                verified += length
                onProgress(verified, totalBytes)
            }
        }
        requireApprovedDigest(approved, image)
        return FinalImageDigest(
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            totalBytes = totalBytes,
            extents = extents,
        )
    }
}

internal fun mergeExtents(extents: List<ByteExtent>): List<ByteExtent> {
    val sorted = extents.filter { it.length > 0 }.sortedBy { it.offset }
    val merged = mutableListOf<ByteExtent>()
    sorted.forEach { extent ->
        val last = merged.lastOrNull()
        if (last != null && extent.offset <= last.offset + last.length) {
            val end = maxOf(last.offset + last.length, extent.offset + extent.length)
            merged[merged.lastIndex] = ByteExtent(last.offset, end - last.offset)
        } else {
            merged += extent
        }
    }
    return merged
}

private fun ByteArray.isZero(start: Int, end: Int): Boolean {
    for (index in start until end) if (this[index] != 0.toByte()) return false
    return true
}

private fun InputStream.readFullyOrToEnd(buffer: ByteArray, targetBytes: Int): Int {
    var total = 0
    while (total < targetBytes) {
        val read = read(buffer, total, targetBytes - total)
        if (read < 0) break
        total += read
    }
    return total
}

private fun roundUp(value: Long, alignment: Long): Long = ((value + alignment - 1) / alignment) * alignment
