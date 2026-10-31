package net.metalstrapper.sparkwriter.usb

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.metalstrapper.sparkwriter.core.DeviceRef
import net.metalstrapper.sparkwriter.core.PermissionResult
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.core.WriteIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Drives [AndroidUsbHostBlockWriter] against a virtual USB stick: permission, SCSI probe,
 * WRITE(10), READ(10) verify, SYNCHRONIZE CACHE, all over real Android bulk transfers.
 *
 * Only a device named "QEMU USB HARDDRIVE" is ever touched, so this cannot write to a
 * real drive. Without one the tests are skipped. tools/emulator-test/README.md covers
 * attaching the stick; check-stick.sh compares what landed on the host-side disk image.
 */
@RunWith(AndroidJUnit4::class)
class VirtualUsbStickTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val writer = AndroidUsbHostBlockWriter(context, usbManager)
    private lateinit var stick: DeviceRef

    @Before
    fun findVirtualStick(): Unit = runBlocking {
        val found = writer.listDevices().filter { it.displayName == VIRTUAL_STICK_NAME }
        assumeTrue("No $VIRTUAL_STICK_NAME attached; see tools/emulator-test/README.md", found.size == 1)
        stick = found.single()
        grantPermission(stick.id)
    }

    @Test
    fun writeThenVerifyRoundTrips(): Unit = runBlocking {
        val image = testImage(seed = 1, sizeBytes = IMAGE_BYTES)
        val intent = writeIntent(image)

        val (written, verified) = UsbWriteGate.withExclusiveWrite {
            writer.withClaimedDevice(stick.id) {
                writer.writeImage(intent).toList() to writer.verifyImage(intent).toList()
            }
        }

        assertEquals(IMAGE_BYTES, written.last().bytesWritten)
        assertTrue(verified.last().verified)
        // The host script reads this line and compares it with the disk image file.
        Log.i(TAG, "STICK_IMAGE sha256=${image.sha256} bytes=${image.sizeBytes}")
    }

    @Test
    fun verifyCatchesADifferentImage(): Unit = runBlocking {
        val first = testImage(seed = 2, sizeBytes = IMAGE_BYTES)
        val other = testImage(seed = 3, sizeBytes = IMAGE_BYTES)

        UsbWriteGate.withExclusiveWrite {
            writer.withClaimedDevice(stick.id) {
                writer.writeImage(writeIntent(first)).toList()
                try {
                    writer.verifyImage(writeIntent(other)).toList()
                    fail("Verify passed against an image that was never written")
                } catch (expected: WriteRefusedException) {
                    assertTrue(expected.message.orEmpty().startsWith("USB verification failed at byte"))
                }
            }
        }
    }

    @Test
    fun probeReportsTheStickCapacityAndRefusesAnImageThatDoesNotFit(): Unit = runBlocking {
        val probed = writer.probe(stick.id)
        assertTrue(probed.permissionGranted)
        assertEquals(512, probed.blockSize)
        assertTrue("Probed ${probed.device.sizeBytes} bytes", probed.device.sizeBytes >= IMAGE_BYTES)

        val tooBig = testImage(seed = 4, sizeBytes = 4096).copy(sizeBytes = probed.device.sizeBytes + 512)
        try {
            UsbWriteGate.withExclusiveWrite {
                writer.withClaimedDevice(stick.id) { writer.writeImage(writeIntent(tooBig)).toList() }
            }
            fail("Wrote an image larger than the stick")
        } catch (expected: WriteRefusedException) {
            assertEquals("USB device is smaller than the verified image", expected.message)
        }
    }

    private fun grantPermission(deviceId: String) = runBlocking {
        if (writer.requestPermission(deviceId) == PermissionResult.Granted) return@runBlocking
        // The system shows "Allow ... to access QEMU USB HARDDRIVE?"; button1 is OK.
        val ui = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ui.wait(Until.findObject(By.res("android", "button1")), DIALOG_WAIT_MS)?.click()
        val device = usbManager.deviceList.values.single { it.deviceId.toString() == deviceId }
        val deadline = System.currentTimeMillis() + DIALOG_WAIT_MS
        while (!usbManager.hasPermission(device) && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("USB permission was not granted", usbManager.hasPermission(device))
    }

    private fun writeIntent(image: VerifiedImage) = WriteIntent(
        deviceId = stick.id,
        confirmedDeviceId = stick.id,
        image = image,
    )

    private fun testImage(seed: Int, sizeBytes: Long): VerifiedImage {
        val bytes = Random(seed).nextBytes(sizeBytes.toInt())
        val file = File(context.cacheDir, "virtual-stick-$seed.img").apply { writeBytes(bytes) }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return VerifiedImage(
            uri = Uri.fromFile(file).toString(),
            sha256 = sha256,
            sizeBytes = sizeBytes,
            mediaType = "application/octet-stream",
            provenance = "test",
            displayName = file.name,
        )
    }

    private companion object {
        const val TAG = "VirtualUsbStickTest"
        const val VIRTUAL_STICK_NAME = "QEMU USB HARDDRIVE"
        const val DIALOG_WAIT_MS = 10_000L

        /** Not a multiple of 64 KiB transfers, so the last block is padded. */
        const val IMAGE_BYTES = 8L * 1024 * 1024 + 1000
    }
}
