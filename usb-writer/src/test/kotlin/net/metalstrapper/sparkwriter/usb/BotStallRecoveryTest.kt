package net.metalstrapper.sparkwriter.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BotStallRecoveryTest {

    private class ScriptedUsbTransport(
        override val interfaceId: Int = 0,
        override val bulkInAddress: Int = 0x81,
        override val bulkOutAddress: Int = 0x02,
    ) : UsbTransport {
        data class ControlCall(
            val requestType: Int,
            val request: Int,
            val value: Int,
            val index: Int,
        )

        val controlCalls = mutableListOf<ControlCall>()
        var controlTransferResult: (ControlCall) -> Int = { 0 }

        val bulkOutCalls = mutableListOf<ByteArray>()
        var onBulkOut: (ByteArray) -> Int = { it.size }

        private val bulkInResponses = ArrayDeque<() -> ByteArray?>()

        fun queueBulkInResponse(data: ByteArray) {
            bulkInResponses.addLast { data }
        }

        fun queueBulkInStall() {
            bulkInResponses.addLast { null }
        }

        override fun bulkOut(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            val chunk = bytes.copyOfRange(offset, offset + length)
            bulkOutCalls.add(chunk)
            return onBulkOut(chunk)
        }

        override fun bulkIn(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            if (bulkInResponses.isEmpty()) return -1
            val generator = bulkInResponses.removeFirst()
            val data = generator() ?: return -1 // -1 signifies endpoint stall
            val copyLen = minOf(length, data.size)
            System.arraycopy(data, 0, bytes, offset, copyLen)
            return copyLen
        }

        override fun controlTransfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray?,
            length: Int,
            timeoutMs: Int,
        ): Int {
            val call = ControlCall(requestType, request, value, index)
            controlCalls.add(call)
            return controlTransferResult(call)
        }

        var isClosed = false
        override fun close() {
            isClosed = true
        }
    }

    private fun csw(tag: Int, status: Int = 0, dataResidue: Int = 0): ByteArray {
        val buffer = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x53425355) // CSW_SIGNATURE
        buffer.putInt(tag)
        buffer.putInt(dataResidue)
        buffer.put(status.toByte())
        return buffer.array()
    }

    private fun lastCbwTag(transport: ScriptedUsbTransport): Int {
        val lastCbw = transport.bulkOutCalls.last { it.size == 31 }
        return ByteBuffer.wrap(lastCbw).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
    }

    @Test
    fun synchronizeCacheRecoversWhenCswStallsOnUnsupportedCommand() {
        val transport = ScriptedUsbTransport()
        val session = UsbMassStorageSession(transport)

        // 1. synchronizeCache sends CBW (0x35)
        // 2. Drive stalls bulk-in on first readCsw
        transport.queueBulkInStall()

        // 3. Stall recovery issues CLEAR_FEATURE(ENDPOINT_HALT) and retries readCsw
        // The second attempt receives CSW with status = 1 (FAILED)
        transport.queueBulkInResponse(csw(tag = 1, status = 1))

        // 4. synchronizeCache sees status == 1 and calls requestSenseKey (0x03)
        // Sense response has SENSE_KEY_ILLEGAL_REQUEST (0x05) at index 2
        val senseData = ByteArray(18).apply { this[2] = 0x05 }
        transport.queueBulkInResponse(senseData)
        // CSW for requestSenseKey has status = 0 (PASSED)
        transport.queueBulkInResponse(csw(tag = 2, status = 0))

        // Should complete successfully without throwing
        session.synchronizeCache()

        // Verify CLEAR_FEATURE(ENDPOINT_HALT) control transfer was issued on Bulk-In
        assertEquals(1, transport.controlCalls.size)
        val clearHalt = transport.controlCalls[0]
        assertEquals(0x02, clearHalt.requestType) // USB_CONTROL_REQ_TYPE_ENDPOINT
        assertEquals(0x01, clearHalt.request)     // USB_CONTROL_REQ_CLEAR_FEATURE
        assertEquals(0x00, clearHalt.value)       // USB_ENDPOINT_HALT
        assertEquals(0x81, clearHalt.index)       // bulkInAddress
    }

    @Test
    fun bulkInDataReadStallRecoversClearsHaltAndReadsCsw() {
        val transport = ScriptedUsbTransport()
        val session = UsbMassStorageSession(transport)

        // Read command data-in stalls
        transport.queueBulkInStall()
        // CSW read succeeds with status = 1 (FAILED)
        transport.queueBulkInResponse(csw(tag = 1, status = 1))

        val readBuffer = ByteArray(512)
        val error = assertThrows(WriteRefusedException::class.java) {
            session.readBlocks(lba = 0, data = readBuffer, byteCount = 512, blockCount = 1)
        }
        assertEquals("USB mass-storage command failed with status 1", error.message)

        // Verify CLEAR_FEATURE(ENDPOINT_HALT) was issued for bulkIn (0x81)
        assertEquals(1, transport.controlCalls.size)
        assertEquals(0x02, transport.controlCalls[0].requestType)
        assertEquals(0x01, transport.controlCalls[0].request)
        assertEquals(0x00, transport.controlCalls[0].value)
        assertEquals(0x81, transport.controlCalls[0].index)
    }

    @Test
    fun bulkOutDataWriteStallRecoversClearsHaltAndReadsCsw() {
        val transport = ScriptedUsbTransport()
        val session = UsbMassStorageSession(transport)

        // Bulk-out transfer: CBW succeeds (size 31), data write stalls
        transport.onBulkOut = { bytes ->
            if (bytes.size == 31) bytes.size else -1
        }
        // CSW read succeeds with status = 1 (FAILED)
        transport.queueBulkInResponse(csw(tag = 1, status = 1))

        val writeBuffer = ByteArray(512)
        val error = assertThrows(WriteRefusedException::class.java) {
            session.writeBlocks(lba = 0, data = writeBuffer, byteCount = 512, blockCount = 1)
        }
        assertEquals("USB mass-storage command failed with status 1", error.message)

        // Verify CLEAR_FEATURE(ENDPOINT_HALT) was issued for bulkOut (0x02)
        assertEquals(1, transport.controlCalls.size)
        assertEquals(0x02, transport.controlCalls[0].requestType)
        assertEquals(0x01, transport.controlCalls[0].request)
        assertEquals(0x00, transport.controlCalls[0].value)
        assertEquals(0x02, transport.controlCalls[0].index)
    }

    @Test
    fun cswReadStallRetryFailureFallsBackToBulkOnlyReset() {
        val transport = ScriptedUsbTransport(interfaceId = 3, bulkInAddress = 0x81, bulkOutAddress = 0x02)
        val session = UsbMassStorageSession(transport)

        // CSW read attempt 1 stalls
        transport.queueBulkInStall()
        // CSW read attempt 2 (after clear halt) also stalls!
        transport.queueBulkInStall()

        assertThrows(WriteRefusedException::class.java) {
            session.synchronizeCache()
        }

        // Expected control call sequence:
        // 1. CLEAR_FEATURE(ENDPOINT_HALT) on bulk-in (0x81)
        // 2. Recovery failed -> Mass Storage Bulk-Only Reset (0x21, 0xFF) on interface 3
        // 3. Clear Feature HALT on bulk-in (0x81)
        // 4. Clear Feature HALT on bulk-out (0x02)
        assertEquals(4, transport.controlCalls.size)

        // Call 1: initial stall clear
        assertEquals(0x02, transport.controlCalls[0].requestType)
        assertEquals(0x01, transport.controlCalls[0].request)
        assertEquals(0x81, transport.controlCalls[0].index)

        // Call 2: Bulk-Only Reset
        assertEquals(0x21, transport.controlCalls[1].requestType)
        assertEquals(0xFF, transport.controlCalls[1].request)
        assertEquals(3, transport.controlCalls[1].index)

        // Call 3: Reset post-condition clear halt Bulk-In
        assertEquals(0x02, transport.controlCalls[2].requestType)
        assertEquals(0x01, transport.controlCalls[2].request)
        assertEquals(0x81, transport.controlCalls[2].index)

        // Call 4: Reset post-condition clear halt Bulk-Out
        assertEquals(0x02, transport.controlCalls[3].requestType)
        assertEquals(0x01, transport.controlCalls[3].request)
        assertEquals(0x02, transport.controlCalls[3].index)
    }

    @Test
    fun clearEndpointHaltFailureFallsBackToBulkOnlyReset() {
        val transport = ScriptedUsbTransport(interfaceId = 0)
        val session = UsbMassStorageSession(transport)

        // Fail the first CLEAR_FEATURE control transfer
        transport.controlTransferResult = { call ->
            if (call.requestType == 0x02 && transport.controlCalls.size == 1) -1 else 0
        }
        // CSW read stalls
        transport.queueBulkInStall()

        assertThrows(WriteRefusedException::class.java) {
            session.synchronizeCache()
        }

        // Verify fallback to Bulk-Only Reset (0x21, 0xFF)
        val resetCall = transport.controlCalls.firstOrNull { it.requestType == 0x21 && it.request == 0xFF }
        assertTrue("Bulk-Only Reset must be issued when stall recovery fails", resetCall != null)
    }

    @Test
    fun cbwStallFallsBackToBulkOnlyReset() {
        val transport = ScriptedUsbTransport(interfaceId = 2)
        val session = UsbMassStorageSession(transport)

        // Sending CBW stalls
        transport.onBulkOut = { -1 }

        assertThrows(WriteRefusedException::class.java) {
            session.synchronizeCache()
        }

        // Verify Bulk-Only Reset was issued
        assertEquals(3, transport.controlCalls.size)
        assertEquals(0x21, transport.controlCalls[0].requestType)
        assertEquals(0xFF, transport.controlCalls[0].request)
        assertEquals(2, transport.controlCalls[0].index)
    }

    @Test
    fun invalidCswSignatureFallsBackToBulkOnlyReset() {
        val transport = ScriptedUsbTransport(interfaceId = 0)
        val session = UsbMassStorageSession(transport)

        // Return invalid CSW signature (0x12345678)
        val invalidCsw = ByteArray(13).apply {
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(0x12345678)
        }
        transport.queueBulkInResponse(invalidCsw)

        assertThrows(WriteRefusedException::class.java) {
            session.synchronizeCache()
        }

        val resetCall = transport.controlCalls.firstOrNull { it.requestType == 0x21 && it.request == 0xFF }
        assertTrue("Bulk-Only Reset must be issued on invalid CSW", resetCall != null)
    }

    @Test
    fun normalCommandsDoNotIssueControlTransfers() {
        val transport = ScriptedUsbTransport()
        val session = UsbMassStorageSession(transport)

        transport.queueBulkInResponse(csw(tag = 1, status = 0))
        session.testUnitReady()

        assertEquals(0, transport.controlCalls.size)
    }
}
