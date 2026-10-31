package net.metalstrapper.sparkwriter.usb

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.metalstrapper.sparkwriter.core.BlockDeviceInfo
import net.metalstrapper.sparkwriter.core.DeviceFingerprint
import net.metalstrapper.sparkwriter.core.DeviceRef
import net.metalstrapper.sparkwriter.core.PermissionResult
import net.metalstrapper.sparkwriter.core.VerifyProgress
import net.metalstrapper.sparkwriter.core.WriteIntent
import net.metalstrapper.sparkwriter.core.WriteProgress

class FakeUsbBlockWriter(
    private val devices: List<DeviceRef>,
) : UsbBlockWriter {
    override suspend fun listDevices(): List<DeviceRef> = devices

    override suspend fun requestPermission(deviceId: String): PermissionResult = PermissionResult.Granted

    override suspend fun probe(deviceId: String): BlockDeviceInfo {
        val device = devices.firstOrNull { it.id == deviceId } ?: throw WriteRefusedException("USB device not found")
        return BlockDeviceInfo(
            device = device,
            blockSize = 512,
            blockCount = device.sizeBytes / 512,
            permissionGranted = true,
            fingerprint = fakeFingerprint(device),
        )
    }

    override fun writeImage(intent: WriteIntent): Flow<WriteProgress> = flow {
        val probed = probe(intent.deviceId)
        validateWriteIntent(intent, probed)
        val total = intent.image.sizeBytes
        val steps = 8
        for (step in 1..steps) {
            delay(5)
            emit(WriteProgress((total * step) / steps, total, "Writing test block stream"))
        }
    }

    override fun writeAuxiliaryData(intent: WriteIntent): Flow<WriteProgress> = flow {
        val auxiliary = intent.auxiliaryData ?: return@flow
        val total = auxiliary.files.sumOf { it.content.toByteArray(Charsets.UTF_8).size }.toLong().coerceAtLeast(1L)
        emit(WriteProgress(0, total, "Auxiliary preflight: resolve partition"))
        delay(5)
        emit(WriteProgress((total * 20) / 100, total, "Auxiliary image: build content"))
        delay(5)
        emit(WriteProgress((total * 80) / 100, total, "Auxiliary image: write partition"))
        delay(5)
        emit(WriteProgress(total, total, "Auxiliary installer data verified"))
    }

    override fun writeBootPatch(intent: WriteIntent): Flow<WriteProgress> = flow {
        intent.bootPatch ?: return@flow
        emit(WriteProgress(1, 1, "Writing test boot patch"))
    }

    override fun writeFixedByteRanges(intent: WriteIntent): Flow<WriteProgress> = flow {
        val fixed = intent.fixedByteRanges ?: return@flow
        val total = fixed.patches.sumOf { it.byteLength }
        emit(WriteProgress(total, total, "Writing test fixed byte-range data"))
    }

    override fun verifyImage(intent: WriteIntent): Flow<VerifyProgress> = flow {
        val total = intent.image.sizeBytes
        emit(VerifyProgress(total, total, "Fake full-image verification complete", completed = true, verified = true))
    }
}

/** The identity [FakeUsbBlockWriter.probe] reports for [device]. */
fun fakeFingerprint(device: DeviceRef): DeviceFingerprint = DeviceFingerprint(
    vendorId = 0x1d6b,
    productId = 0x0104,
    productName = device.displayName,
    serialNumber = "fake-${device.id}",
    blockSize = 512,
    blockCount = device.sizeBytes / 512,
)
