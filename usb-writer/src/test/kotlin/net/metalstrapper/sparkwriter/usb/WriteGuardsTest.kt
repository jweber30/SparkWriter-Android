package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import net.metalstrapper.sparkwriter.core.BlockDeviceInfo
import net.metalstrapper.sparkwriter.core.DeviceRef
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.core.WriteIntent
import org.junit.Assert.assertThrows
import org.junit.Test

class WriteGuardsTest {
    private val image = VerifiedImage(
        uri = "file:///image.iso",
        sha256 = "feedface".repeat(8),
        sizeBytes = 64L * 1024L * 1024L,
        mediaType = "application/x-iso9660-image",
        provenance = "test",
        displayName = "test.iso",
    )
    private val intent = WriteIntent(
        deviceId = "usb1",
        confirmedDeviceId = "usb1",
        image = image,
    )

    @Test(expected = WriteRefusedException::class)
    fun refusesDeviceSmallerThanImage() {
        validateWriteIntent(
            intent = WriteIntent(
                deviceId = "usb1",
                confirmedDeviceId = "usb1",
                image = VerifiedImage("file:///image.iso", "abc", 2_000, "application/octet-stream", "test", "image.iso"),
            ),
            probed = BlockDeviceInfo(
                device = DeviceRef("usb1", "usb://1", "USB", 1_000, "1 KB"),
                blockCount = 2,
                permissionGranted = true,
            ),
        )
    }

    @Test
    fun allowsAppendAfterImageAuxiliaryWithoutSourceHash() {
        val auxiliary = AuxiliaryDataPlan(
            label = "CIDATA",
            filesystem = "vfat",
            placement = "append_after_image",
            sizeBytes = 64L * 1024L * 1024L,
            sourceSha256 = null,
            files = emptyList(),
        )
        validateAuxiliarySourceMatch(intent = intent, auxiliary = auxiliary)
    }

    @Test
    fun rejectsPreallocatedAuxiliaryWithoutSourceHash() {
        val auxiliary = AuxiliaryDataPlan(
            label = "CIDATA",
            filesystem = "vfat",
            placement = "preallocated_in_base_image",
            sizeBytes = 64L * 1024L * 1024L,
            sourceSha256 = null,
            files = emptyList(),
        )
        assertThrows(WriteRefusedException::class.java) {
            validateAuxiliarySourceMatch(intent = intent, auxiliary = auxiliary)
        }
    }

    @Test
    fun allowsAppendAfterImageAuxiliaryWhenSourceHashDoesNotMatchImage() {
        val auxiliary = AuxiliaryDataPlan(
            label = "CIDATA",
            filesystem = "vfat",
            placement = "append_after_image",
            sizeBytes = 64L * 1024L * 1024L,
            sourceSha256 = "wrong".repeat(13),
            files = emptyList(),
        )
        validateAuxiliarySourceMatch(intent = intent, auxiliary = auxiliary)
    }
}
