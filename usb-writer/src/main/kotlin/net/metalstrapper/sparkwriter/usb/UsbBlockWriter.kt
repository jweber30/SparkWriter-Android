package net.metalstrapper.sparkwriter.usb

import kotlinx.coroutines.flow.Flow
import net.metalstrapper.sparkwriter.core.BlockDeviceInfo
import net.metalstrapper.sparkwriter.core.DeviceRef
import net.metalstrapper.sparkwriter.core.PermissionResult
import net.metalstrapper.sparkwriter.core.VerifyProgress
import net.metalstrapper.sparkwriter.core.WriteIntent
import net.metalstrapper.sparkwriter.core.WriteProgress

interface UsbBlockWriter {
    suspend fun listDevices(): List<DeviceRef>
    suspend fun requestPermission(deviceId: String): PermissionResult
    suspend fun probe(deviceId: String): BlockDeviceInfo
    fun writeImage(intent: WriteIntent): Flow<WriteProgress>
    fun writeBootPatch(intent: WriteIntent): Flow<WriteProgress>
    fun writeAuxiliaryData(intent: WriteIntent): Flow<WriteProgress>
    fun writeFixedByteRanges(intent: WriteIntent): Flow<WriteProgress>
    fun verifyImage(intent: WriteIntent): Flow<VerifyProgress>

    /** Verifies the finished device contents after every phase; the last emission carries the digest. */
    fun verifyFinalImage(intent: WriteIntent): Flow<VerifyProgress>
}
