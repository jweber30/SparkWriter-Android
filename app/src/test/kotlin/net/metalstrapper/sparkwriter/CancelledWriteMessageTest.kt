package net.metalstrapper.sparkwriter

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class CancelledWriteMessageTest {
    @Test
    fun userCancellationNamesTheStageAndWarnsTheDeviceIsIncomplete() {
        assertEquals(
            "USB write cancelled during boot_patch; the device contents are incomplete and must be rewritten",
            cancelledWriteMessage("boot_patch", WorkInfo.STOP_REASON_CANCELLED_BY_APP),
        )
    }

    @Test
    fun systemStopIncludesTheStopReason() {
        assertEquals(
            "USB write stopped by the system (reason ${WorkInfo.STOP_REASON_PREEMPT}) during write_image; " +
                "the device contents are incomplete and must be rewritten",
            cancelledWriteMessage("write_image", WorkInfo.STOP_REASON_PREEMPT),
        )
    }
}
