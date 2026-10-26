package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import net.metalstrapper.sparkwriter.core.BootPatchPlan
import net.metalstrapper.sparkwriter.core.FixedByteRangePlan
import net.metalstrapper.sparkwriter.core.PartitionGeometry
import net.metalstrapper.sparkwriter.core.VerifiedImage
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

object AssembledImageWriter {
    private const val BUFFER_SIZE = 1024 * 1024
    private const val SECTOR_SIZE = 512L
    private const val DYNAMIC_APPEND_SLACK_BYTES = 4096L * SECTOR_SIZE

    fun assemble(
        source: InputStream,
        outputFile: File,
        image: VerifiedImage,
        auxiliaryData: AuxiliaryDataPlan? = null,
        fixedByteRanges: FixedByteRangePlan? = null,
        bootPatch: BootPatchPlan? = null,
        onProgress: (AssembledImageProgress) -> Unit = {},
    ) {
        if (image.sizeBytes <= 0) throw WriteRefusedException("Image size is invalid")
        outputFile.parentFile?.mkdirs()
        RandomAccessFile(outputFile, "rw").use { output ->
            output.setLength(0)
            copyBaseImage(source, output, image.sizeBytes) { written ->
                onProgress(AssembledImageProgress("Copying base image", written, image.sizeBytes))
            }
            output.setLength(image.sizeBytes)

            bootPatch?.let { plan ->
                val patches = IsoBootPatchWriter.buildPatches(plan) { offset, length -> output.readRange(offset, length) }
                if (patches.isEmpty()) throw WriteRefusedException("Boot patch plan produced no patches to apply")
                patches.forEachIndexed { index, patch ->
                    if (patch.offset + patch.bytes.size > output.length()) {
                        throw WriteRefusedException("Boot config patch exceeds assembled image size")
                    }
                    output.writeRange(patch.offset, patch.bytes)
                    onProgress(
                        AssembledImageProgress(
                            "Applying boot config patch: ${patch.description}",
                            index + 1L,
                            patches.size.toLong(),
                        ),
                    )
                }
            }

            auxiliaryData?.let { auxiliary ->
                writeAuxiliaryData(output, image, auxiliary) { progress ->
                    onProgress(progress)
                }
            }

            fixedByteRanges?.let { plan ->
                FixedByteRangeWriter.validatePlan(
                    plan = plan,
                    imageSha256 = image.sha256,
                    imageSizeBytes = output.length(),
                    deviceSizeBytes = output.length(),
                )
                plan.patches.forEachIndexed { index, patch ->
                    val length = patch.byteLength.toIntOrNull()
                        ?: throw WriteRefusedException("Fixed byte-range patch ${patch.id} is too large for Android memory")
                    val existing = output.readRange(patch.byteOffset, length)
                    FixedByteRangeWriter.verifyPlaceholder(patch, existing)
                    output.writeRange(patch.byteOffset, FixedByteRangeWriter.replacementBytes(patch))
                    onProgress(
                        AssembledImageProgress(
                            "Applying fixed byte-range patch: ${patch.id}",
                            index + 1L,
                            plan.patches.size.toLong(),
                        ),
                    )
                }
            }
        }
    }

    private fun copyBaseImage(
        source: InputStream,
        output: RandomAccessFile,
        expectedBytes: Long,
        onCopied: (Long) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (copied < expectedBytes) {
            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), expectedBytes - copied).toInt())
            if (read < 0) throw WriteRefusedException("Image ended before expected size")
            output.write(buffer, 0, read)
            copied += read
            onCopied(copied)
        }
    }

    private fun writeAuxiliaryData(
        output: RandomAccessFile,
        image: VerifiedImage,
        auxiliary: AuxiliaryDataPlan,
        onProgress: (AssembledImageProgress) -> Unit,
    ) {
        when (auxiliary.placement) {
            "preallocated_in_base_image" -> {
                val geometry = auxiliary.geometry
                    ?: throw WriteRefusedException("Auxiliary delivery is missing resolved partition geometry")
                if (geometry.byteOffset + geometry.byteLength > output.length()) {
                    throw WriteRefusedException("Auxiliary geometry exceeds assembled image size")
                }
                val existing = output.readRange(geometry.byteOffset, geometry.byteLength.toIntOrNull())
                val imageBytes = VFatImage.populateOrCreate(existing, auxiliary.copy(geometry = geometry))
                output.writeRange(geometry.byteOffset, imageBytes)
                onProgress(AssembledImageProgress("Applying auxiliary partition", 1, 1))
            }
            "append_after_image" -> {
                val targetLength = appendedImageLength(image.sizeBytes, auxiliary.sizeBytes)
                output.setLength(targetLength)
                val geometry = GptPartitionAppender.appendFat32Partition(
                    label = auxiliary.label,
                    sizeBytes = auxiliary.sizeBytes,
                    sourceSha256 = auxiliary.sourceSha256 ?: image.sha256,
                    deviceBytes = targetLength,
                    readBytes = { offset, length -> output.readRange(offset, length) },
                    writeBytes = { offset, data -> output.writeRange(offset, data) },
                )
                val sparse = VFatImage.sparse(auxiliary.copy(geometry = geometry))
                writeSparseAuxiliary(output, geometry, sparse) { written ->
                    onProgress(AssembledImageProgress("Applying auxiliary partition", written, sparse.size.toLong()))
                }
            }
            else -> throw WriteRefusedException("Unsupported auxiliary partition placement: ${auxiliary.placement}")
        }
    }

    private fun writeSparseAuxiliary(
        output: RandomAccessFile,
        geometry: PartitionGeometry,
        sparse: SparseVFatImage,
        onWritten: (Long) -> Unit,
    ) {
        var written = 0L
        while (written < sparse.size) {
            val length = minOf(BUFFER_SIZE, sparse.size - written.toInt())
            output.writeRange(geometry.byteOffset + written, sparse.chunk(written.toInt(), length))
            written += length
            onWritten(written)
        }
    }

    private fun appendedImageLength(imageBytes: Long, auxiliaryBytes: Long): Long {
        val base = imageBytes.roundUp(SECTOR_SIZE)
        return (base + auxiliaryBytes + DYNAMIC_APPEND_SLACK_BYTES).roundUp(SECTOR_SIZE)
    }

    private fun RandomAccessFile.readRange(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > length()) {
            throw WriteRefusedException("Read range is outside the assembled image")
        }
        seek(offset)
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes
    }

    private fun RandomAccessFile.writeRange(offset: Long, bytes: ByteArray) {
        if (offset < 0 || offset + bytes.size > length()) {
            throw WriteRefusedException("Write range is outside the assembled image")
        }
        seek(offset)
        write(bytes)
    }

    private fun Long.toIntOrNull(): Int {
        if (this !in 0..Int.MAX_VALUE.toLong()) {
            throw WriteRefusedException("Byte range is too large for Android memory")
        }
        return toInt()
    }

    private fun Long.roundUp(alignment: Long): Long = ((this + alignment - 1) / alignment) * alignment
}

data class AssembledImageProgress(
    val message: String,
    val completed: Long,
    val total: Long,
) {
    val fraction: Float?
        get() = total.takeIf { it > 0 }?.let { (completed.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}
