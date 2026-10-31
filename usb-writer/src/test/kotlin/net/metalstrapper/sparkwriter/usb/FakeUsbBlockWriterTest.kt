package net.metalstrapper.sparkwriter.usb

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.metalstrapper.sparkwriter.core.AuxiliaryDataFile
import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import net.metalstrapper.sparkwriter.core.DeviceRef
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.core.WriteIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeUsbBlockWriterTest {
    @Test
    fun emitsCompleteWriteProgress() = runBlocking {
        val device = DeviceRef("usb1", "usb://1", "USB", 4_096, "4 KB")
        val writer = FakeUsbBlockWriter(listOf(device))
        val final = writer.writeImage(
            WriteIntent(
                deviceId = "usb1",
                confirmedDeviceId = "usb1",
                image = VerifiedImage("file:///image.iso", "abc", 1_024, "application/octet-stream", "test", "image.iso"),
                confirmedDevice = fakeFingerprint(device),
            ),
        ).last()

        assertEquals(100, final.percent)
    }

    @Test
    fun emitsAuxiliaryInstallSubStages() = runBlocking {
        val writer = FakeUsbBlockWriter(listOf(DeviceRef("usb1", "usb://1", "USB", 4_096, "4 KB")))
        val auxiliary = AuxiliaryDataPlan(
            label = "aux",
            filesystem = "fat32",
            placement = "append_after_image",
            sizeBytes = 8_192,
            sourceSha256 = null,
            files = listOf(
                AuxiliaryDataFile(path = "/auto.cfg", content = "hello"),
                AuxiliaryDataFile(path = "/other.cfg", content = "world"),
            ),
        )
        val updates = writer.writeAuxiliaryData(
            WriteIntent(
                deviceId = "usb1",
                confirmedDeviceId = "usb1",
                image = VerifiedImage("file:///image.iso", "abc", 1_024, "application/octet-stream", "test", "image.iso"),
                auxiliaryData = auxiliary,
            ),
        ).toList()

        assertEquals(4, updates.size)
        assertTrue(updates[0].message.startsWith("Auxiliary preflight"))
        assertTrue(updates[1].message.startsWith("Auxiliary image: build"))
        assertTrue(updates[2].message.startsWith("Auxiliary image: write"))
        assertTrue(updates[3].message.startsWith("Auxiliary installer data verified"))
    }
}
