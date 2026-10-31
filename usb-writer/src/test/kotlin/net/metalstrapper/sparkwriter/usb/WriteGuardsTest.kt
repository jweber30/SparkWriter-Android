package net.metalstrapper.sparkwriter.usb

import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import net.metalstrapper.sparkwriter.core.BlockDeviceInfo
import net.metalstrapper.sparkwriter.core.DeviceFingerprint
import net.metalstrapper.sparkwriter.core.DeviceRef
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.core.WriteIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    private val stick = DeviceFingerprint(
        vendorId = 0x0781,
        productId = 0x5581,
        manufacturerName = "SanDisk",
        productName = "Ultra",
        serialNumber = "4C530001",
        blockSize = 512,
        blockCount = 2,
    )
    private val probedStick = BlockDeviceInfo(
        device = DeviceRef("usb1", "usb://1", "USB", 1_000, "1 KB"),
        blockCount = 2,
        permissionGranted = true,
        fingerprint = stick,
    )
    private val smallImageIntent = WriteIntent(
        deviceId = "usb1",
        confirmedDeviceId = "usb1",
        image = VerifiedImage("file:///image.iso", "abc", 500, "application/octet-stream", "test", "image.iso"),
        confirmedDevice = stick,
    )

    @Test
    fun refusesDeviceSmallerThanImage() {
        val error = assertThrows(WriteRefusedException::class.java) {
            validateWriteIntent(
                intent = smallImageIntent.copy(
                    image = smallImageIntent.image.copy(sizeBytes = 2_000),
                ),
                probed = probedStick,
            )
        }
        assertEquals("USB device is smaller than the verified image", error.message)
    }

    @Test
    fun acceptsTheConfirmedDevice() {
        validateWriteIntent(smallImageIntent, probedStick)
    }

    @Test
    fun refusesAWriteWithoutAConfirmedIdentity() {
        val error = assertThrows(WriteRefusedException::class.java) {
            validateWriteIntent(smallImageIntent.copy(confirmedDevice = null), probedStick)
        }
        assertEquals("Write has no confirmed device identity", error.message)
    }

    @Test
    fun refusesWhenTheConnectedIdentityCannotBeRead() {
        assertThrows(WriteRefusedException::class.java) {
            validateWriteIntent(smallImageIntent, probedStick.copy(fingerprint = null))
        }
    }

    @Test
    fun refusesADifferentStickOfTheSameModelBySerial() {
        val error = assertThrows(WriteRefusedException::class.java) {
            validateWriteIntent(smallImageIntent, probedStick.copy(fingerprint = stick.copy(serialNumber = "4C530002")))
        }
        assertEquals(
            "Connected USB device is not the one confirmed (serial number changed); confirm the device again",
            error.message,
        )
        // The message names what changed, never the serial values themselves.
        assertTrue(!error.message!!.contains("4C53"))
    }

    @Test
    fun namesEveryPropertyThatChanged() {
        val other = DeviceFingerprint(
            vendorId = 0x090c,
            productId = 0x1000,
            productName = "Flash Disk",
            blockSize = 4096,
            blockCount = 2,
        )
        val error = assertThrows(WriteRefusedException::class.java) { requireSameDevice(stick, other) }
        assertEquals(
            "Connected USB device is not the one confirmed " +
                "(USB vendor/product ID, name, serial number, capacity changed); confirm the device again",
            error.message,
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

class ApprovedDigestTest {
    private val bytes = "approved image bytes".toByteArray()
    private val image = net.metalstrapper.sparkwriter.core.VerifiedImage(
        uri = "file:///image.iso",
        sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.uppercase(),
        sizeBytes = bytes.size.toLong(),
        mediaType = "application/octet-stream",
        provenance = "test",
        displayName = "image.iso",
    )

    @org.junit.Test
    fun acceptsTheApprovedBytesCaseInsensitively() {
        val stream = approvedImageStream(java.io.ByteArrayInputStream(bytes))
        stream.readBytes()
        requireApprovedDigest(stream, image)
    }

    @org.junit.Test
    fun refusesBytesThatChangedAfterApproval() {
        val stream = approvedImageStream(java.io.ByteArrayInputStream("different image bytes".toByteArray()))
        stream.readBytes()
        org.junit.Assert.assertThrows(WriteRefusedException::class.java) { requireApprovedDigest(stream, image) }
    }
}
