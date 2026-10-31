package net.metalstrapper.sparkwriter.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.metalstrapper.sparkwriter.core.BlockDeviceInfo
import net.metalstrapper.sparkwriter.core.DeviceFingerprint
import net.metalstrapper.sparkwriter.core.DeviceRef
import net.metalstrapper.sparkwriter.core.DeviceType
import net.metalstrapper.sparkwriter.core.PermissionResult
import net.metalstrapper.sparkwriter.core.VerifyProgress
import net.metalstrapper.sparkwriter.core.WriteIntent
import net.metalstrapper.sparkwriter.core.WriteProgress
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.min

private const val USB_PERMISSION_ACTION = "net.metalstrapper.sparkwriter.USB_PERMISSION"
private const val BULK_TIMEOUT_MS = 15_000
private const val SYNCHRONIZE_CACHE_TIMEOUT_MS = 120_000
private const val MAX_BLOCKS_PER_TRANSFER = 128
private const val AUXILIARY_STREAM_CHUNK_BYTES = 64 * 1024
private const val CBW_SIGNATURE = 0x43425355
private const val CSW_SIGNATURE = 0x53425355
private const val CSW_LENGTH = 13
private const val CSW_STATUS_PASSED = 0
private const val CSW_STATUS_FAILED = 1
private const val SENSE_KEY_ILLEGAL_REQUEST = 0x05
private const val MASS_STORAGE_SUBCLASS_SCSI_TRANSPARENT = 0x06
private const val MASS_STORAGE_PROTOCOL_BULK_ONLY = 0x50

class AndroidUsbHostBlockWriter(
    private val context: Context,
    private val usbManager: UsbManager = context.getSystemService(UsbManager::class.java),
) : UsbBlockWriter {
    override suspend fun listDevices(): List<DeviceRef> {
        return usbManager.deviceList.values
            .filter { it.massStorageInterface() != null }
            .map { it.toDeviceRef(sizeBytes = 0L) }
    }

    override suspend fun requestPermission(deviceId: String): PermissionResult {
        val device = findDevice(deviceId) ?: return PermissionResult.Denied
        if (usbManager.hasPermission(device)) return PermissionResult.Granted

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val intent = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            Intent(USB_PERMISSION_ACTION).setPackage(context.packageName),
            flags,
        )
        usbManager.requestPermission(device, intent)
        return PermissionResult.NeedsUserAction(deviceId)
    }

    override suspend fun probe(deviceId: String): BlockDeviceInfo {
        return UsbWriteGate.withProbe {
            probeUnlocked(deviceId)
        }
    }

    /**
     * Claims the device's mass-storage interface once and holds it for every write, verify, and
     * patch phase run by [block], so the kernel's usb-storage driver cannot rebind and remount a
     * half-written image between phases. Before anything is written, the claimed device must
     * match [confirmed], the identity probed when the user confirmed it. Flushes the device
     * write cache before releasing it. Must be called inside [UsbWriteGate.withExclusiveWrite].
     */
    suspend fun <T> withClaimedDevice(deviceId: String, confirmed: DeviceFingerprint?, block: suspend () -> T): T {
        val scope = currentExclusiveWrite()
            ?: throw WriteRefusedException("USB device can only be claimed while holding the USB write gate")
        if (scope.claimed != null) throw WriteRefusedException("A USB device is already claimed for this write")
        if (confirmed == null) throw WriteRefusedException("Write has no confirmed device identity")
        val device = findDevice(deviceId) ?: throw WriteRefusedException("USB device not found")
        val session = withContext(Dispatchers.IO) { UsbMassStorageSession.open(usbManager, device) }
        try {
            val capacity = withContext(Dispatchers.IO) {
                session.testUnitReady()
                session.readCapacity()
            }
            requireSameDevice(confirmed, device.fingerprint(capacity))
        } catch (error: Throwable) {
            session.close()
            throw error
        }
        scope.claimed = ClaimedDevice(deviceId, session)
        try {
            val result = block()
            withContext(Dispatchers.IO) { session.synchronizeCache() }
            return result
        } finally {
            scope.claimed = null
            session.close()
        }
    }

    private suspend fun probeUnlocked(deviceId: String): BlockDeviceInfo {
        val device = findDevice(deviceId) ?: throw WriteRefusedException("USB device not found")
        val hasPermission = usbManager.hasPermission(device)
        if (!hasPermission) {
            return BlockDeviceInfo(
                device = device.toDeviceRef(sizeBytes = 0L),
                blockSize = 512,
                blockCount = 0,
                permissionGranted = false,
            )
        }

        val readInfo = { session: UsbMassStorageSession ->
            session.testUnitReady()
            val capacity = session.readCapacity()
            BlockDeviceInfo(
                device = device.toDeviceRef(sizeBytes = capacity.sizeBytes),
                blockSize = capacity.blockSize,
                blockCount = capacity.blockCount,
                permissionGranted = true,
                fingerprint = device.fingerprint(capacity),
            )
        }
        val claimed = currentExclusiveWrite()?.claimed?.takeIf { it.deviceId == deviceId }
        return claimed?.session?.let(readInfo)
            ?: UsbMassStorageSession.open(usbManager, device).use(readInfo)
    }

    override fun writeImage(intent: WriteIntent): Flow<WriteProgress> = flow {
        val probed = probe(intent.deviceId)
        validateWriteIntent(intent, probed)
        validateRoundedImageFits(intent.image.sizeBytes, probed)

        val source = context.contentResolver.openInputStream(Uri.parse(intent.image.uri))
            ?: throw WriteRefusedException("Image URI could not be opened")
        approvedImageStream(source).use { image ->
            claimedSession(intent.deviceId).let { session ->
                session.testUnitReady()
                val blockSize = probed.blockSize
                val blocksPerTransfer = min(MAX_BLOCKS_PER_TRANSFER, Int.MAX_VALUE / blockSize)
                val buffer = ByteArray(blockSize * blocksPerTransfer)
                var bytesWritten = 0L
                var lba = 0L

                while (bytesWritten < intent.image.sizeBytes) {
                    val remaining = intent.image.sizeBytes - bytesWritten
                    val targetBytes = min(buffer.size.toLong(), remaining).toInt()
                    val read = image.readFullyOrToEnd(buffer, targetBytes)
                    if (read <= 0) throw WriteRefusedException("Image ended before expected size")

                    val transferBytes = roundUpToBlock(read, blockSize)
                    if (transferBytes > read) buffer.fill(0, read, transferBytes)

                    session.writeBlocks(lba, buffer, transferBytes, transferBytes / blockSize)
                    bytesWritten += read
                    lba += transferBytes / blockSize
                    emit(WriteProgress(bytesWritten, intent.image.sizeBytes, "Writing image to USB"))
                }
                // The bytes are already on the device; a mismatch fails the write so the
                // receipt never claims a source that was not the approved one.
                requireApprovedDigest(image, intent.image)
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun writeAuxiliaryData(intent: WriteIntent): Flow<WriteProgress> = flow<WriteProgress> {
        val auxiliary = intent.auxiliaryData ?: return@flow
        val probed = probe(intent.deviceId)
        validateWriteIntent(intent, probed)
        validateAuxiliaryIntent(intent, probed)

        claimedSession(intent.deviceId).let { session ->
            session.testUnitReady()
            emit(WriteProgress(0, 100, "Auxiliary preflight: resolve partition"))
            val geometry = when (auxiliary.placement) {
                "preallocated_in_base_image" -> {
                    val geometry = auxiliary.geometry
                        ?: throw WriteRefusedException("Auxiliary delivery is missing resolved partition geometry")
                    GptGeometryValidator.validate(geometry) { offset, length ->
                        session.readByteRange(offset, length, probed.blockSize)
                    }
                    geometry
                }
                "append_after_image" -> {
                    GptPartitionAppender.appendFat32Partition(
                        label = auxiliary.label,
                        sizeBytes = auxiliary.sizeBytes,
                        sourceSha256 = auxiliary.sourceSha256 ?: "",
                        deviceBytes = probed.device.sizeBytes,
                        readBytes = { offset, length -> session.readByteRange(offset, length, probed.blockSize) },
                        writeBytes = { offset, data -> session.writeByteRange(offset, data, probed.blockSize) },
                    )
                }
                else -> throw WriteRefusedException("Unsupported auxiliary partition placement: ${auxiliary.placement}")
            }

            if (auxiliary.placement == "append_after_image") {
                val image = VFatImage.sparse(auxiliary.copy(geometry = geometry))
                emit(WriteProgress(20, 100, "Auxiliary image: build content"))
                writeSparseVFatImage(
                    image = image,
                    byteOffset = geometry.byteOffset,
                    blockSize = probed.blockSize,
                    session = session,
                ) { percent ->
                    emit(WriteProgress(percent.toLong(), 100, "Auxiliary image: write partition"))
                }
                emit(WriteProgress(100, 100, "Auxiliary installer data verified"))
                return@let
            }

            val image = when (auxiliary.placement) {
                "preallocated_in_base_image" -> {
                    val existing = session.readByteRange(
                        geometry.byteOffset,
                        geometry.byteLength.toInt(),
                        probed.blockSize,
                    )
                    VFatImage.populateOrCreate(existing, auxiliary.copy(geometry = geometry))
                }
                else -> VFatImage.create(auxiliary.copy(geometry = geometry))
            }
            emit(WriteProgress(20, 100, "Auxiliary image: build content"))
            session.writeByteRange(geometry.byteOffset, image, probed.blockSize)
            emit(WriteProgress(80, 100, "Auxiliary image: write partition"))

            val actual = session.readByteRange(geometry.byteOffset, image.size, probed.blockSize)
            if (!image.contentEquals(actual)) {
                throw WriteRefusedException("Auxiliary partition readback verification failed")
            }
            emit(WriteProgress(100, 100, "Auxiliary installer data verified"))
        }
    }.flowOn(Dispatchers.IO)

    override fun writeBootPatch(intent: WriteIntent): Flow<WriteProgress> = flow<WriteProgress> {
        val plan = intent.bootPatch ?: return@flow
        val probed = probe(intent.deviceId)
        validateWriteIntent(intent, probed)
        validateRoundedImageFits(intent.image.sizeBytes, probed)

        claimedSession(intent.deviceId).let { session ->
            session.testUnitReady()
            val readImageBytes: (Long, Int) -> ByteArray = { offset, length ->
                if (offset + length > intent.image.sizeBytes) {
                    throw WriteRefusedException("Boot config candidate exceeds verified image size")
                }
                session.readByteRange(offset, length, probed.blockSize)
            }
            val patches = intent.bootPatchTemplate?.let { template ->
                runCatching { IsoBootPatchWriter.materializeTemplatePatches(template, readImageBytes) }
                    .getOrElse { IsoBootPatchWriter.buildPatches(plan, readImageBytes) }
            } ?: IsoBootPatchWriter.buildPatches(plan, readImageBytes)
            if (patches.isEmpty()) {
                throw WriteRefusedException("Boot patch plan produced no patches to apply")
            }
            val total = patches.sumOf { it.bytes.size }.toLong()
            var written = 0L
            patches.forEachIndexed { index, patch ->
                if (patch.offset + patch.bytes.size > intent.image.sizeBytes) {
                    throw WriteRefusedException("Boot config patch exceeds verified image size")
                }
                emit(WriteProgress(written, total, "Writing boot config patch: ${patch.description} (${index + 1}/${patches.size})"))
                session.writeByteRangeReadModifyWrite(patch.offset, patch.bytes, probed.blockSize)
                val actual = session.readByteRange(patch.offset, patch.bytes.size, probed.blockSize)
                if (!patch.bytes.contentEquals(actual)) {
                    throw WriteRefusedException("Boot config patch readback verification failed for ${patch.description}")
                }
                written += patch.bytes.size
            }
            emit(WriteProgress(total, total, "Boot config patch verified"))
        }
    }.flowOn(Dispatchers.IO)

    override fun writeFixedByteRanges(intent: WriteIntent): Flow<WriteProgress> = flow<WriteProgress> {
        val plan = intent.fixedByteRanges ?: return@flow
        val probed = probe(intent.deviceId)
        validateWriteIntent(intent, probed)
        FixedByteRangeWriter.validatePlan(
            plan = plan,
            imageSha256 = intent.image.sha256,
            imageSizeBytes = intent.image.sizeBytes,
            deviceSizeBytes = probed.device.sizeBytes,
        )

        claimedSession(intent.deviceId).let { session ->
            session.testUnitReady()
            val totalBytes = plan.patches.sumOf { it.byteLength }
            var complete = 0L
            plan.patches.forEach { patch ->
                val length = patch.byteLength.toIntOrNull()
                    ?: throw WriteRefusedException("Fixed byte-range patch ${patch.id} is too large for Android memory")
                emit(WriteProgress(complete, totalBytes, "Verifying fixed-byte-range placeholder: ${patch.id}"))
                val existing = session.readByteRange(patch.byteOffset, length, probed.blockSize)
                FixedByteRangeWriter.verifyPlaceholder(patch, existing)
                val replacement = FixedByteRangeWriter.replacementBytes(patch)
                session.writeByteRangeReadModifyWrite(patch.byteOffset, replacement, probed.blockSize)
                val actual = session.readByteRange(patch.byteOffset, replacement.size, probed.blockSize)
                if (!replacement.contentEquals(actual)) {
                    throw WriteRefusedException("Fixed byte-range readback verification failed for ${patch.id}")
                }
                complete += patch.byteLength
                emit(WriteProgress(complete, totalBytes, "Writing fixed-byte-range patch: ${patch.id}"))
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun verifyImage(intent: WriteIntent): Flow<VerifyProgress> = flow {
        val probed = probe(intent.deviceId)
        validateWriteIntent(intent, probed)
        validateRoundedImageFits(intent.image.sizeBytes, probed)

        val source = context.contentResolver.openInputStream(Uri.parse(intent.image.uri))
            ?: throw WriteRefusedException("Image URI could not be opened")
        approvedImageStream(source).use { image ->
            claimedSession(intent.deviceId).let { session ->
                session.testUnitReady()
                val blockSize = probed.blockSize
                val blocksPerTransfer = min(MAX_BLOCKS_PER_TRANSFER, Int.MAX_VALUE / blockSize)
                val expected = ByteArray(blockSize * blocksPerTransfer)
                val actual = ByteArray(expected.size)
                var bytesRead = 0L
                var lba = 0L

                while (bytesRead < intent.image.sizeBytes) {
                    val remaining = intent.image.sizeBytes - bytesRead
                    val targetBytes = min(expected.size.toLong(), remaining).toInt()
                    val read = image.readFullyOrToEnd(expected, targetBytes)
                    if (read <= 0) throw WriteRefusedException("Image ended before expected size")

                    val transferBytes = roundUpToBlock(read, blockSize)
                    if (transferBytes > read) expected.fill(0, read, transferBytes)
                    session.readBlocks(lba, actual, transferBytes, transferBytes / blockSize)

                    for (index in 0 until transferBytes) {
                        if (expected[index] != actual[index]) {
                            throw WriteRefusedException("USB verification failed at byte ${bytesRead + index}")
                        }
                    }

                    bytesRead += read
                    lba += transferBytes / blockSize
                    emit(VerifyProgress(bytesRead, intent.image.sizeBytes, "Verifying USB readback"))
                }
                // Readback matched the source, but the source itself must still be the approved image.
                requireApprovedDigest(image, intent.image)
                emit(
                    VerifyProgress(
                        bytesRead = intent.image.sizeBytes,
                        totalBytes = intent.image.sizeBytes,
                        message = "USB readback verification complete",
                        completed = true,
                        verified = true,
                    ),
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun claimedSession(deviceId: String): UsbMassStorageSession {
        val claimed = currentExclusiveWrite()?.claimed
            ?: throw WriteRefusedException("USB writes must run inside withClaimedDevice")
        if (claimed.deviceId != deviceId) throw WriteRefusedException("USB write targets a device that is not claimed")
        return claimed.session
    }

    private fun findDevice(deviceId: String): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { it.deviceId.toString() == deviceId }
    }

    private suspend fun writeSparseVFatImage(
        image: SparseVFatImage,
        byteOffset: Long,
        blockSize: Int,
        session: UsbMassStorageSession,
        emitProgress: suspend (Int) -> Unit,
    ) {
        val chunkBytes = (AUXILIARY_STREAM_CHUNK_BYTES / blockSize)
            .coerceAtLeast(1)
            .coerceAtMost(MAX_BLOCKS_PER_TRANSFER) * blockSize
        var written = 0
        while (written < image.size) {
            val transferBytes = min(chunkBytes, image.size - written)
            val expected = image.chunk(written, transferBytes)
            val offset = byteOffset + written
            session.writeByteRange(offset, expected, blockSize)
            val actual = session.readByteRange(offset, transferBytes, blockSize)
            if (!expected.contentEquals(actual)) {
                throw WriteRefusedException("Auxiliary partition readback verification failed")
            }
            written += transferBytes
            emitProgress(20 + ((written.toLong() * 80) / image.size).toInt().coerceIn(0, 80))
        }
    }
}

typealias LibaumsUsbBlockWriter = AndroidUsbHostBlockWriter

object UsbWriteGate {
    private val mutex = Mutex()

    val isWriteActive: Boolean
        get() = mutex.isLocked

    suspend fun <T> withExclusiveWrite(block: suspend () -> T): T = mutex.withLock {
        withContext(ExclusiveWriteScope()) { block() }
    }

    suspend fun <T> withProbe(block: suspend () -> T): T {
        // The write holding the gate may probe its own target; everyone else is refused.
        if (currentExclusiveWrite() != null) return block()
        if (!mutex.tryLock()) {
            throw WriteRefusedException("USB write is already in progress")
        }
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

/** Marks coroutines running under [UsbWriteGate.withExclusiveWrite] and carries the claimed device. */
private class ExclusiveWriteScope : AbstractCoroutineContextElement(ExclusiveWriteScope) {
    companion object Key : CoroutineContext.Key<ExclusiveWriteScope>

    @Volatile
    var claimed: ClaimedDevice? = null
}

private class ClaimedDevice(
    val deviceId: String,
    val session: UsbMassStorageSession,
)

private suspend fun currentExclusiveWrite(): ExclusiveWriteScope? = currentCoroutineContext()[ExclusiveWriteScope]

private data class UsbCapacity(
    val blockSize: Int,
    val blockCount: Long,
) {
    val sizeBytes: Long = runCatching { Math.multiplyExact(blockSize.toLong(), blockCount) }
        .getOrElse { throw WriteRefusedException("USB device reported unsupported capacity") }
}

private class UsbMassStorageSession private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : AutoCloseable {
    private val tag = AtomicInteger(1)

    fun testUnitReady() {
        repeat(5) { attempt ->
            try {
                command(byteArrayOf(0x00, 0, 0, 0, 0, 0), dataIn = null, dataOut = null)
                return
            } catch (error: WriteRefusedException) {
                if (attempt == 4) throw error
                Thread.sleep(250)
            }
        }
    }

    fun readCapacity(): UsbCapacity {
        val response = ByteArray(8)
        command(
            commandBlock = byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            dataIn = response,
            dataOut = null,
        )
        val lastLba = response.readUInt32BE(0)
        if (lastLba == 0xffff_ffffL) return readCapacity16()
        val blockSize = response.readUInt32BE(4).toInt()
        validateCapacity(blockSize = blockSize, lastLba = lastLba)
        return UsbCapacity(blockSize = blockSize, blockCount = lastLba + 1)
    }

    private fun readCapacity16(): UsbCapacity {
        val response = ByteArray(32)
        command(
            commandBlock = byteArrayOf(0x9E.toByte(), 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 32, 0, 0),
            dataIn = response,
            dataOut = null,
        )
        val lastLba = response.readUInt64BE(0)
        val blockSize = response.readUInt32BE(8).toInt()
        validateCapacity(blockSize = blockSize, lastLba = lastLba)
        return UsbCapacity(blockSize = blockSize, blockCount = lastLba + 1)
    }

    private fun validateCapacity(blockSize: Int, lastLba: Long) {
        if (blockSize <= 0) throw WriteRefusedException("USB device reported invalid block size")
        if (lastLba == Long.MAX_VALUE) throw WriteRefusedException("USB device reported unsupported capacity")
    }

    /**
     * Asks the device to commit its volatile write cache to media. Devices without the command
     * report ILLEGAL REQUEST, which we treat as write-through; any other failure is fatal.
     */
    fun synchronizeCache() {
        val status = commandStatus(
            commandBlock = byteArrayOf(0x35, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            dataIn = null,
            dataOut = null,
            cswTimeoutMs = SYNCHRONIZE_CACHE_TIMEOUT_MS,
        )
        if (status == CSW_STATUS_PASSED) return
        if (status == CSW_STATUS_FAILED && requestSenseKey() == SENSE_KEY_ILLEGAL_REQUEST) return
        throw WriteRefusedException("USB device failed to flush its write cache (status $status)")
    }

    private fun requestSenseKey(): Int {
        val sense = ByteArray(18)
        command(byteArrayOf(0x03, 0, 0, 0, sense.size.toByte(), 0), dataIn = sense, dataOut = null)
        return sense[2].toInt() and 0x0f
    }

    fun writeBlocks(lba: Long, data: ByteArray, byteCount: Int, blockCount: Int) {
        command(
            commandBlock = scsiReadWrite10(opcode = 0x2A, lba = lba, blockCount = blockCount),
            dataIn = null,
            dataOut = data,
            dataLength = byteCount,
        )
    }

    fun readBlocks(lba: Long, data: ByteArray, byteCount: Int, blockCount: Int) {
        command(
            commandBlock = scsiReadWrite10(opcode = 0x28, lba = lba, blockCount = blockCount),
            dataIn = data,
            dataOut = null,
            dataLength = byteCount,
        )
    }

    fun readByteRange(offset: Long, length: Int, blockSize: Int): ByteArray {
        if (length < 0) throw WriteRefusedException("USB read length is invalid")
        val alignedOffset = (offset / blockSize) * blockSize
        val prefix = (offset - alignedOffset).toInt()
        val transferBytes = roundUpToBlock(prefix + length, blockSize)
        val buffer = ByteArray(transferBytes)
        var read = 0
        var lba = alignedOffset / blockSize
        val maxBytes = MAX_BLOCKS_PER_TRANSFER * blockSize
        while (read < transferBytes) {
            val chunkBytes = min(maxBytes, transferBytes - read)
            val chunk = ByteArray(chunkBytes)
            readBlocks(lba, chunk, chunkBytes, chunkBytes / blockSize)
            chunk.copyInto(buffer, read)
            read += chunkBytes
            lba += chunkBytes / blockSize
        }
        return buffer.copyOfRange(prefix, prefix + length)
    }

    fun writeByteRange(offset: Long, data: ByteArray, blockSize: Int) {
        if (offset % blockSize != 0L || data.size % blockSize != 0) {
            throw WriteRefusedException("USB byte-range writes must be block aligned")
        }
        var written = 0
        var lba = offset / blockSize
        val maxBytes = MAX_BLOCKS_PER_TRANSFER * blockSize
        while (written < data.size) {
            val transferBytes = min(maxBytes, data.size - written)
            val buffer = data.copyOfRange(written, written + transferBytes)
            writeBlocks(lba, buffer, transferBytes, transferBytes / blockSize)
            written += transferBytes
            lba += transferBytes / blockSize
        }
    }

    fun writeByteRangeReadModifyWrite(offset: Long, data: ByteArray, blockSize: Int) {
        val alignedOffset = (offset / blockSize) * blockSize
        val prefix = (offset - alignedOffset).toInt()
        val transferBytes = roundUpToBlock(prefix + data.size, blockSize)
        val buffer = readByteRange(alignedOffset, transferBytes, blockSize)
        data.copyInto(buffer, prefix)
        writeByteRange(alignedOffset, buffer, blockSize)
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
    }

    private fun command(
        commandBlock: ByteArray,
        dataIn: ByteArray?,
        dataOut: ByteArray?,
        dataLength: Int = dataIn?.size ?: dataOut?.size ?: 0,
    ) {
        val status = commandStatus(commandBlock, dataIn, dataOut, dataLength)
        if (status != CSW_STATUS_PASSED) {
            throw WriteRefusedException("USB mass-storage command failed with status $status")
        }
    }

    private fun commandStatus(
        commandBlock: ByteArray,
        dataIn: ByteArray?,
        dataOut: ByteArray?,
        dataLength: Int = dataIn?.size ?: dataOut?.size ?: 0,
        cswTimeoutMs: Int = BULK_TIMEOUT_MS,
    ): Int {
        val commandTag = tag.getAndIncrement()
        sendCbw(commandTag, commandBlock, dataLength, dataIn != null)
        if (dataOut != null && dataLength > 0) bulkOut.writeExact(dataOut, dataLength)
        if (dataIn != null && dataLength > 0) bulkIn.readExact(dataIn, dataLength)
        return readCsw(commandTag, cswTimeoutMs)
    }

    private fun sendCbw(commandTag: Int, commandBlock: ByteArray, dataLength: Int, dataIn: Boolean) {
        require(commandBlock.size <= 16) { "SCSI command block must fit in CBW" }
        val cbw = ByteArray(31)
        ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CBW_SIGNATURE)
            putInt(commandTag)
            putInt(dataLength)
            put(if (dataIn) 0x80.toByte() else 0x00)
            put(0)
            put(commandBlock.size.toByte())
            put(commandBlock)
        }
        bulkOut.writeExact(cbw, cbw.size)
    }

    private fun readCsw(expectedTag: Int, timeoutMs: Int): Int {
        val csw = ByteArray(CSW_LENGTH)
        bulkIn.readExact(csw, csw.size, timeoutMs)
        val buffer = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val signature = buffer.int
        val actualTag = buffer.int
        buffer.int
        val status = buffer.get().toInt() and 0xff
        if (signature != CSW_SIGNATURE || actualTag != expectedTag) {
            throw WriteRefusedException("USB mass-storage command response was invalid")
        }
        return status
    }

    private fun UsbEndpoint.writeExact(bytes: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val transferred = connection.bulkTransfer(this, bytes, offset, length - offset, BULK_TIMEOUT_MS)
            if (transferred <= 0) throw WriteRefusedException("USB bulk write timed out")
            offset += transferred
        }
    }

    private fun UsbEndpoint.readExact(bytes: ByteArray, length: Int, timeoutMs: Int = BULK_TIMEOUT_MS) {
        var offset = 0
        while (offset < length) {
            val transferred = connection.bulkTransfer(this, bytes, offset, length - offset, timeoutMs)
            if (transferred <= 0) throw WriteRefusedException("USB bulk read timed out")
            offset += transferred
        }
    }

    companion object {
        fun open(usbManager: UsbManager, device: UsbDevice): UsbMassStorageSession {
            val descriptor = device.massStorageInterface()
                ?: throw WriteRefusedException("USB device is not mass storage")
            val connection = usbManager.openDevice(device)
                ?: throw WriteRefusedException("USB permission has not been granted")
            if (!connection.claimInterface(descriptor.usbInterface, true)) {
                connection.close()
                throw WriteRefusedException("USB mass-storage interface could not be claimed")
            }
            return UsbMassStorageSession(
                connection = connection,
                usbInterface = descriptor.usbInterface,
                bulkIn = descriptor.bulkIn,
                bulkOut = descriptor.bulkOut,
            )
        }
    }
}

private data class MassStorageInterface(
    val usbInterface: UsbInterface,
    val bulkIn: UsbEndpoint,
    val bulkOut: UsbEndpoint,
)

private fun UsbDevice.massStorageInterface(): MassStorageInterface? {
    for (interfaceIndex in 0 until interfaceCount) {
        val usbInterface = getInterface(interfaceIndex)
        if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE) continue
        if (usbInterface.interfaceSubclass != MASS_STORAGE_SUBCLASS_SCSI_TRANSPARENT) continue
        if (usbInterface.interfaceProtocol != MASS_STORAGE_PROTOCOL_BULK_ONLY) continue

        var bulkIn: UsbEndpoint? = null
        var bulkOut: UsbEndpoint? = null
        for (endpointIndex in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(endpointIndex)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            when (endpoint.direction) {
                UsbConstants.USB_DIR_IN -> bulkIn = endpoint
                UsbConstants.USB_DIR_OUT -> bulkOut = endpoint
            }
        }
        if (bulkIn != null && bulkOut != null) {
            return MassStorageInterface(usbInterface, bulkIn, bulkOut)
        }
    }
    return null
}

/** Requires USB permission: the serial number is only readable once it is granted. */
private fun UsbDevice.fingerprint(capacity: UsbCapacity): DeviceFingerprint = DeviceFingerprint(
    vendorId = vendorId,
    productId = productId,
    manufacturerName = manufacturerName?.ifBlank { null },
    productName = productName?.ifBlank { null },
    serialNumber = runCatching { serialNumber }.getOrNull()?.trim()?.ifBlank { null },
    blockSize = capacity.blockSize,
    blockCount = capacity.blockCount,
)

private fun UsbDevice.toDeviceRef(sizeBytes: Long): DeviceRef {
    val vendorText = vendorId.toString(16).padStart(4, '0')
    val productText = productId.toString(16).padStart(4, '0')
    return DeviceRef(
        id = deviceId.toString(),
        path = "usb://$vendorText:$productText/$deviceName",
        displayName = productName ?: manufacturerName ?: "USB Mass Storage $vendorText:$productText",
        sizeBytes = sizeBytes,
        sizeHuman = if (sizeBytes > 0) sizeBytes.toHumanSize() else "Unknown",
        vendor = manufacturerName ?: vendorText,
        model = productName ?: productText,
        deviceType = DeviceType.USB_STICK,
        removable = true,
        bus = "usb",
    )
}

private fun scsiReadWrite10(opcode: Int, lba: Long, blockCount: Int): ByteArray {
    if (lba !in 0..0xffff_ffffL) throw WriteRefusedException("USB image offset exceeds SCSI READ/WRITE(10) range")
    if (blockCount !in 1..0xffff) throw WriteRefusedException("USB transfer block count is invalid")
    return byteArrayOf(
        opcode.toByte(),
        0,
        ((lba shr 24) and 0xff).toByte(),
        ((lba shr 16) and 0xff).toByte(),
        ((lba shr 8) and 0xff).toByte(),
        (lba and 0xff).toByte(),
        0,
        ((blockCount shr 8) and 0xff).toByte(),
        (blockCount and 0xff).toByte(),
        0,
    )
}

private fun ByteArray.readUInt32BE(offset: Int): Long {
    return ((this[offset].toLong() and 0xff) shl 24) or
        ((this[offset + 1].toLong() and 0xff) shl 16) or
        ((this[offset + 2].toLong() and 0xff) shl 8) or
        (this[offset + 3].toLong() and 0xff)
}

private fun ByteArray.readUInt64BE(offset: Int): Long {
    var value = 0L
    for (index in offset until offset + 8) {
        value = (value shl 8) or (this[index].toLong() and 0xff)
    }
    if (value < 0) throw WriteRefusedException("USB device is larger than this Android writer can address")
    return value
}

private fun InputStream.readFullyOrToEnd(buffer: ByteArray, targetBytes: Int): Int {
    var offset = 0
    while (offset < targetBytes) {
        val read = read(buffer, offset, targetBytes - offset)
        if (read < 0) break
        offset += read
    }
    return offset
}

private fun roundUpToBlock(byteCount: Int, blockSize: Int): Int {
    return ((byteCount + blockSize - 1) / blockSize) * blockSize
}

private fun validateRoundedImageFits(imageBytes: Long, probed: BlockDeviceInfo) {
    val requiredBlocks = (imageBytes + probed.blockSize - 1) / probed.blockSize
    if (requiredBlocks > probed.blockCount) {
        throw WriteRefusedException("USB device is smaller than the padded image write")
    }
}

private fun validateAuxiliaryIntent(intent: WriteIntent, probed: BlockDeviceInfo) {
    val auxiliary = intent.auxiliaryData ?: return
    validateAuxiliarySourceMatch(intent, auxiliary)
    if (!auxiliary.filesystem.equals("vfat", ignoreCase = true)) {
        throw WriteRefusedException("Unsupported auxiliary filesystem: ${auxiliary.filesystem}")
    }
    if (auxiliary.sizeBytes > Int.MAX_VALUE) {
        throw WriteRefusedException("Auxiliary partition is too large for in-memory VFAT creation")
    }
    if (auxiliary.sizeBytes % probed.blockSize != 0L) {
        throw WriteRefusedException("Auxiliary partition size must be block aligned")
    }
    when (auxiliary.placement) {
        "preallocated_in_base_image" -> {
            val sourceHash = auxiliary.sourceSha256?.ifBlank { null }
            val geometry = auxiliary.geometry
                ?: throw WriteRefusedException("Auxiliary delivery is missing resolved partition geometry")
            if (sourceHash != null && !geometry.baseImageSha256.equals(sourceHash, ignoreCase = true)) {
                throw WriteRefusedException("Auxiliary geometry hash does not match source.sha256")
            }
            if (geometry.partitionTable != "gpt") throw WriteRefusedException("Auxiliary geometry must target GPT")
            if (geometry.sectorSize != probed.blockSize) {
                throw WriteRefusedException("Auxiliary geometry sector size does not match USB block size")
            }
            val expectedByteOffset = geometry.startLba * geometry.sectorSize.toLong()
            if (geometry.byteOffset != expectedByteOffset) {
                throw WriteRefusedException(
                    "Auxiliary byte_offset ${geometry.byteOffset} does not equal start_lba * sector_size $expectedByteOffset",
                )
            }
            if (geometry.byteOffset % probed.blockSize != 0L || geometry.byteLength % probed.blockSize != 0L) {
                throw WriteRefusedException("Auxiliary geometry must be block aligned")
            }
            if (geometry.byteOffset + geometry.byteLength > intent.image.sizeBytes) {
                throw WriteRefusedException("Auxiliary geometry exceeds verified image size")
            }
            if (geometry.byteOffset + geometry.byteLength > probed.device.sizeBytes) {
                throw WriteRefusedException("Auxiliary geometry exceeds USB device size")
            }
            if (geometry.byteLength < auxiliary.sizeBytes) {
                throw WriteRefusedException("Auxiliary geometry length is smaller than declared partition size")
            }
        }
        "append_after_image" -> {
            if (probed.blockSize != 512) {
                throw WriteRefusedException("Dynamic auxiliary GPT append requires 512-byte sectors")
            }
            if (auxiliary.geometry != null) {
                throw WriteRefusedException("Dynamic auxiliary delivery must not declare geometry")
            }
            if (intent.image.sizeBytes >= probed.device.sizeBytes) {
                throw WriteRefusedException("USB device has no room for auxiliary partition")
            }
        }
        else -> throw WriteRefusedException("Unsupported auxiliary partition placement: ${auxiliary.placement}")
    }
}

private fun Long.toHumanSize(): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = this.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${this} B"
    } else {
        "%.1f %s".format(value, units[unitIndex])
    }
}

private fun Long.toIntOrNull(): Int? = if (this in 0..Int.MAX_VALUE.toLong()) toInt() else null
