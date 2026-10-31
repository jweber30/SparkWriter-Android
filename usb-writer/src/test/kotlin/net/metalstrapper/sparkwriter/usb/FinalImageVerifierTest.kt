package net.metalstrapper.sparkwriter.usb

import kotlinx.coroutines.runBlocking
import net.metalstrapper.sparkwriter.core.ByteExtent
import net.metalstrapper.sparkwriter.core.VerifiedImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class FinalImageVerifierTest {
    private val blockSize = 512
    private val transferBytes = 4 * blockSize

    /** Base image deliberately not block aligned, so the zero padding is exercised. */
    private val base = ByteArray(20 * blockSize + 100) { (it * 7 % 253 + 1).toByte() }
    private val image = VerifiedImage(
        uri = "file:///base.iso",
        sha256 = base.sha256Hex(),
        sizeBytes = base.size.toLong(),
        mediaType = "application/octet-stream",
        provenance = "test",
        displayName = "base.iso",
    )
    private val paddedBaseLength = 21 * blockSize

    @Test
    fun baseOnlyDigestCoversTheBlockPaddedImage() = runBlocking {
        val device = FakeDevice(64 * blockSize)
        device.writeBase(base)

        val result = verify(device, WriteOverlay())

        assertEquals(listOf(ByteExtent(0, paddedBaseLength.toLong())), result.extents)
        assertEquals(base.copyOf(paddedBaseLength).sha256Hex(), result.sha256)
    }

    @Test
    fun verifiesPatchesInsideAfterAndFarFromTheBaseImage() = runBlocking {
        val device = FakeDevice(256 * blockSize)
        val overlay = WriteOverlay()
        device.writeBase(base)
        // A boot patch inside the ISO, an appended partition, and a "backup GPT" near the end.
        device.patch(overlay, 2L * blockSize, ByteArray(blockSize) { 0x42 })
        device.patch(overlay, paddedBaseLength.toLong(), ByteArray(3 * blockSize).also { it[5] = 9 })
        device.patch(overlay, 250L * blockSize, ByteArray(2 * blockSize) { 0x7e })
        // A later write over part of an earlier one must win.
        device.patch(overlay, 2L * blockSize, ByteArray(blockSize) { 0x43 })

        val result = verify(device, overlay)

        assertEquals(
            listOf(
                ByteExtent(0, paddedBaseLength + 3L * blockSize),
                ByteExtent(250L * blockSize, 2L * blockSize),
            ),
            result.extents,
        )
        val expected = device.bytes.copyOfRange(0, paddedBaseLength + 3 * blockSize) +
            device.bytes.copyOfRange(250 * blockSize, 252 * blockSize)
        assertEquals(expected.sha256Hex(), result.sha256)
        assertEquals(expected.size.toLong(), result.totalBytes)
    }

    @Test
    fun failsWhenAPatchNeverReachedTheDevice() = runBlocking {
        val device = FakeDevice(64 * blockSize)
        val overlay = WriteOverlay()
        device.writeBase(base)
        overlay.record(4L * blockSize, ByteArray(blockSize) { 0x11 })

        val error = assertThrows(WriteRefusedException::class.java) { runBlocking { verify(device, overlay) } }
        assertEquals("Final USB verification failed at byte ${4 * blockSize}", error.message)
    }

    @Test
    fun catchesAFakeCapacityStickWrappingALaterWriteOntoTheImage() = runBlocking {
        // Advertises 256 blocks but only stores 128; offsets wrap like counterfeit flash.
        val device = FakeDevice(advertisedBytes = 256 * blockSize, realBytes = 128 * blockSize)
        val overlay = WriteOverlay()
        device.writeBase(base)
        device.patch(overlay, 130L * blockSize, ByteArray(blockSize) { 0xFF.toByte() }) // lands on block 2

        // Each patch reads back fine on its own address...
        assertArrayEquals(ByteArray(blockSize) { 0xFF.toByte() }, device.read(130L * blockSize, blockSize))
        // ...but the finished image no longer matches.
        val error = assertThrows(WriteRefusedException::class.java) { runBlocking { verify(device, overlay) } }
        assertEquals("Final USB verification failed at byte ${2 * blockSize}", error.message)
    }

    @Test
    fun refusesWhenTheSourceChangedEvenIfTheDeviceMatchesIt() = runBlocking {
        val changed = base.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val device = FakeDevice(64 * blockSize)
        device.writeBase(changed)

        val error = assertThrows(WriteRefusedException::class.java) {
            runBlocking { verify(device, WriteOverlay(), source = changed) }
        }
        assertTrue(error.message!!.startsWith("Image changed after it was verified"))
    }

    @Test
    fun overlayStoresZeroRunsAsExtentsOnlyAndLaterWritesWin() {
        val overlay = WriteOverlay()
        overlay.record(0, ByteArray(4 * blockSize).also { it[blockSize * 3 + 1] = 5 })
        overlay.record(blockSize.toLong(), ByteArray(blockSize) { 1 })
        val window = ByteArray(4 * blockSize) { 0x7f }

        overlay.applyTo(0, window, window.size)

        val expected = ByteArray(4 * blockSize).also {
            it.fill(1, blockSize, 2 * blockSize)
            it[blockSize * 3 + 1] = 5
        }
        assertArrayEquals(expected, window)
        assertEquals(listOf(ByteExtent(0, 4L * blockSize)), overlay.extents())
    }

    @Test
    fun mergesAdjacentAndOverlappingExtents() {
        assertEquals(
            listOf(ByteExtent(0, 30), ByteExtent(40, 5)),
            mergeExtents(listOf(ByteExtent(40, 5), ByteExtent(10, 20), ByteExtent(0, 10), ByteExtent(15, 2), ByteExtent(50, 0))),
        )
    }

    private suspend fun verify(device: FakeDevice, overlay: WriteOverlay, source: ByteArray = base) =
        FinalImageVerifier.verify(
            source = ByteArrayInputStream(source),
            image = image,
            blockSize = blockSize,
            overlay = overlay,
            transferBytes = transferBytes,
            readDevice = { offset, buffer, length -> device.read(offset, length).copyInto(buffer) },
        )

    private inner class FakeDevice(advertisedBytes: Int, realBytes: Int = advertisedBytes) {
        val bytes = ByteArray(realBytes)
        private val advertised = advertisedBytes

        fun writeBase(source: ByteArray) = write(0, source.copyOf(roundUp(source.size)))

        fun patch(overlay: WriteOverlay, offset: Long, data: ByteArray) {
            overlay.record(offset, data)
            write(offset, data)
        }

        fun write(offset: Long, data: ByteArray) {
            require(offset + data.size <= advertised)
            data.forEachIndexed { index, byte -> bytes[((offset + index) % bytes.size).toInt()] = byte }
        }

        fun read(offset: Long, length: Int): ByteArray =
            ByteArray(length) { index -> bytes[((offset + index) % bytes.size).toInt()] }

        private fun roundUp(size: Int) = (size + blockSize - 1) / blockSize * blockSize
    }
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

class ReadModifyWriteOverlayTest {
    private val blockSize = 512

    @Test
    fun deviceFillerIsIgnoredInsideTheBaseImageButKeptBeyondIt() {
        val overlay = WriteOverlay()
        // Inside the base image: the block read back from the device was corrupt (0x66).
        overlay.recordReadModifyWrite(0, ByteArray(blockSize) { 0x66 }, 10, byteArrayOf(1, 2))
        // Beyond the base image: there is no source, so the device's surrounding bytes are kept.
        overlay.recordReadModifyWrite(4L * blockSize, ByteArray(blockSize) { 0x77 }, 4L * blockSize + 3, byteArrayOf(9))
        val baseEnd = 2L * blockSize

        val inside = ByteArray(blockSize) { 0x01 } // the approved base bytes
        overlay.applyTo(0, inside, blockSize, baseEnd)
        val beyond = ByteArray(blockSize)
        overlay.applyTo(4L * blockSize, beyond, blockSize, baseEnd)

        assertArrayEquals(ByteArray(blockSize) { 0x01 }.also { it[10] = 1; it[11] = 2 }, inside)
        assertArrayEquals(ByteArray(blockSize) { 0x77 }.also { it[3] = 9 }, beyond)
        assertEquals(listOf(ByteExtent(0, 512), ByteExtent(2048, 512)), overlay.extents())
    }
}
