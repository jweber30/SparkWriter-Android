package net.metalstrapper.sparkwriter

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.automirrored.filled.List
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.metalstrapper.sparkwriter.core.DeviceFingerprint
import net.metalstrapper.sparkwriter.core.DeviceRef
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import net.metalstrapper.sparkwriter.core.BootPatchPlan
import net.metalstrapper.sparkwriter.core.BootPatchTemplatePlan
import net.metalstrapper.sparkwriter.core.FixedByteRangePlan
import net.metalstrapper.sparkwriter.core.VerifyProgress
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.core.WriteIntent
import net.metalstrapper.sparkwriter.core.WriteReturnPlan
import net.metalstrapper.sparkwriter.storage.IsoIndexStore
import net.metalstrapper.sparkwriter.usb.IsoBootPatchWriter

private const val USB_WRITE_UNIQUE_WORK_NAME = "sparkwriter-active-usb-write"
private const val USB_WRITE_TAG = "sparkwriter-usb-write"
internal suspend fun publishWriteStartCallbacks(
    writeIntent: WriteIntent,
    reportStatus: suspend (String) -> Unit,
    onDelivery: suspend (PreparedReturnDelivery) -> Unit,
    log: (String) -> Unit = {},
): List<String> {
    val deliveredEvents = mutableListOf<String>()
    val workflowStartedDeliveries = prepareWorkflowStartedReturnDeliveries(writeIntent)
    log("workflow_started_deliveries=${workflowStartedDeliveries.size}")
    if (workflowStartedDeliveries.isNotEmpty()) {
        reportStatus("Publishing workflow-started callback")
        executePreparedReturnDeliveries(
            deliveries = workflowStartedDeliveries,
            post = { delivery ->
            deliveredEvents += delivery.event
            onDelivery(delivery)
            },
            logger = log,
        )
    }
    val writeReadyDeliveries = prepareWriteReadyReturnDeliveries(writeIntent)
    log("prepared_write_ready_deliveries=${writeReadyDeliveries.size}")
    if (writeReadyDeliveries.isNotEmpty()) {
        reportStatus("Publishing boot payload")
        writeReadyDeliveries.forEach { delivery ->
            log(
                "prepared_delivery event=${delivery.event} " +
                    "method=${delivery.method} required=${delivery.required} " +
                    "url=${delivery.url} bodySize=${delivery.body.length} mediaType=${delivery.mediaType}",
            )
        }
        executePreparedReturnDeliveries(
            deliveries = writeReadyDeliveries,
            post = { delivery ->
            deliveredEvents += delivery.event
            onDelivery(delivery)
            },
            logger = log,
        )
    }
    return deliveredEvents
}


internal data class ProbedUsbDevice(
    val device: DeviceRef,
    val permissionGranted: Boolean,
)

internal suspend fun writeUsbWithWorkManager(
    context: android.content.Context,
    deviceId: String,
    confirmedDevice: DeviceFingerprint,
    image: VerifiedImage,
    verifyAfter: Boolean,
    auxiliaryData: AuxiliaryDataPlan?,
    fixedByteRanges: FixedByteRangePlan?,
    bootPatch: BootPatchPlan?,
    bootPatchTemplate: BootPatchTemplatePlan?,
    returnPlan: WriteReturnPlan?,
    tags: Map<String, String>,
    onProgress: (Float, String) -> Unit,
): WorkInfo {
    val workManager = WorkManager.getInstance(context)
    if (activeUsbWriteWorkInfo(workManager) != null) {
        error("A USB write is already in progress")
    }
    val request = OneTimeWorkRequestBuilder<UsbImageWriteWorker>()
        .setInputData(
            UsbImageWriteWorker.inputData(
                context = context,
                deviceId = deviceId,
                confirmedDevice = confirmedDevice,
                image = image,
                verifyAfter = verifyAfter,
                auxiliaryData = auxiliaryData,
                fixedByteRanges = fixedByteRanges,
                bootPatch = bootPatch,
                bootPatchTemplate = bootPatchTemplate,
                returnPlan = returnPlan,
                tags = tags,
            ),
        )
        .addTag(USB_WRITE_TAG)
        .addTag("sparkwriter-device-$deviceId")
        .build()

    workManager.enqueueUniqueWork(
        USB_WRITE_UNIQUE_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        request,
    )
    return pollUsbWriteWork(workManager, request.id, onProgress)
}

internal suspend fun prepareBootPatchTemplate(
    context: Context,
    isoIndexStore: IsoIndexStore,
    manifest: SparkManifest,
    image: VerifiedImage,
    bootPatch: BootPatchPlan?,
): BootPatchTemplatePlan? = withContext(Dispatchers.IO) {
    val plan = bootPatch ?: return@withContext null
    val signature = manifest.bootPatchSignature() ?: return@withContext null
    Log.d(
        "SparkWriter",
        "Preparing boot patch template manifest=${manifest.metadata.id} source=${manifest.source?.id} " +
            "kernel_patches=${plan.kernelArgPatches.size} signature=$signature",
    )
    isoIndexStore.loadTemplate(image, manifest, signature)?.let { return@withContext it }
    val template = IsoBootPatchWriter.buildTemplatePlan(signature, plan) { offset, length ->
        context.readImageRange(image, offset, length)
    }
    isoIndexStore.saveTemplate(image, manifest, template)
    template
}

internal suspend fun activeUsbWriteWorkInfo(workManager: WorkManager): WorkInfo? =
    withContext(Dispatchers.IO) {
        workManager.getWorkInfosForUniqueWork(USB_WRITE_UNIQUE_WORK_NAME).get()
            .firstOrNull { !it.state.isFinished }
            ?: workManager.getWorkInfosByTag(USB_WRITE_TAG).get()
                .firstOrNull { !it.state.isFinished }
    }

internal fun logDeliveryPlan(plan: LocalDeliveryPlan?) {
    when (plan) {
        is LocalDeliveryPlan.Auxiliary -> {
            val auxiliaryData = plan.plan
            Log.d(
                "SparkWriter",
                "Prepared auxiliary data label=${auxiliaryData.label} " +
                    "placement=${auxiliaryData.placement} " +
                    "offset=${auxiliaryData.geometry?.byteOffset ?: "dynamic"} " +
                    "length=${auxiliaryData.geometry?.byteLength ?: auxiliaryData.sizeBytes} " +
                    "files=${auxiliaryData.files.joinToString { it.path }}",
            )
        }
        is LocalDeliveryPlan.FixedByteRanges -> {
            val fixed = plan.plan
            Log.d(
                "SparkWriter",
                "Prepared fixed byte-range data patches=${fixed.patches.joinToString { it.id }}",
            )
        }
        null -> Unit
    }
}

internal fun SparkManifest.writeTags(): Map<String, String> {
    return buildMap {
        put("manifest_id", metadata.id)
        source?.id?.ifBlank { null }?.let { put("source_id", it) }
        put("package_id", metadata.id)
        packageRootSha256?.let { put("package_root_sha256", it) }
        source?.adaptation?.status?.let { put("adaptation_status", it) }
    }
}

internal suspend fun pollUsbWriteWork(
    workManager: WorkManager,
    id: java.util.UUID,
    onProgress: (Float, String) -> Unit,
): WorkInfo {
    while (true) {
        val workInfo = withContext(Dispatchers.IO) {
            workManager.getWorkInfoById(id).get()
        } ?: error("USB write worker disappeared")
        val percent = workInfo.progress.getInt(UsbWriteData.KEY_PERCENT, 0)
        val message = workInfo.progress.getString(UsbWriteData.KEY_MESSAGE) ?: "Writing USB image"
        onProgress(percent / 100f, message)
        if (workInfo.state.isFinished) {
            val terminalMessage = workInfo.outputData.getString(UsbWriteData.KEY_MESSAGE)
                ?: workInfo.progress.getString(UsbWriteData.KEY_MESSAGE)
            if (terminalMessage != null) {
                onProgress(percent / 100f, terminalMessage)
            }
            return workInfo
        }
        delay(1000)
    }
}

internal fun WorkInfo.usbWriteFailureMessage(lastProgressText: String?): String {
    val outputMessage = outputData.getString(UsbWriteData.KEY_MESSAGE)?.ifBlank { null }
    val progressMessage = progress.getString(UsbWriteData.KEY_MESSAGE)?.ifBlank { null }
    val stage = progress.getString(UsbWriteData.KEY_STAGE)?.ifBlank { null }
    val lastProgress = lastProgressText?.ifBlank { null }
    val base = outputMessage ?: progressMessage ?: lastProgress ?: "Worker stopped without returning an error message"
    return buildString {
        append("USB write failed")
        stage?.let { append(" during ").append(it) }
        append(": ").append(base)
        append(" (WorkManager state ").append(state).append(")")
    }
}

internal fun Throwable.visibleWriteFailureMessage(): String {
    val direct = message?.ifBlank { null }
    return if (direct?.startsWith("USB write failed") == true) direct else sparkWriterFailureMessage()
}

private fun VerifyProgress.percent(): Int {
    return if (totalBytes <= 0) 0 else ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
}
