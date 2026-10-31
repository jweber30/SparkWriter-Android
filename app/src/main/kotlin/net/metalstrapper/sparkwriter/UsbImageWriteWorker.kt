package net.metalstrapper.sparkwriter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import net.metalstrapper.sparkwriter.core.BootKernelArgPatchSpec
import net.metalstrapper.sparkwriter.core.BootPatchPlan
import net.metalstrapper.sparkwriter.core.BootPatchTemplatePlan
import net.metalstrapper.sparkwriter.core.BootPlaceholderPatchSpec
import net.metalstrapper.sparkwriter.core.BootScriptPatchSpec
import net.metalstrapper.sparkwriter.core.DeviceFingerprint
import net.metalstrapper.sparkwriter.core.BootTextPatchSpec
import net.metalstrapper.sparkwriter.core.FixedByteRangePlan
import net.metalstrapper.sparkwriter.core.FieldCondition
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.core.WriteIntent
import net.metalstrapper.sparkwriter.core.WriteReturnPlan
import net.metalstrapper.sparkwriter.core.WriteStatus
import net.metalstrapper.sparkwriter.core.WorkflowAction
import net.metalstrapper.sparkwriter.storage.ReceiptEntity
import net.metalstrapper.sparkwriter.storage.SparkWriterDatabase
import net.metalstrapper.sparkwriter.usb.AndroidUsbHostBlockWriter
import net.metalstrapper.sparkwriter.usb.UsbWriteGate
import net.metalstrapper.sparkwriter.usb.WriteRefusedException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

private const val USB_WRITE_CHANNEL_ID = "sparkwriter_usb_writes"
private const val USB_WRITE_NOTIFICATION_ID = 3001
private const val USB_WRITE_COMPLETE_CHANNEL_ID = "sparkwriter_usb_writes_complete"
private const val USB_WRITE_COMPLETE_NOTIFICATION_ID = 3002

object UsbWriteData {
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_CONFIRMED_DEVICE = "confirmed_device"
    const val KEY_IMAGE = "image"
    const val KEY_VERIFY_AFTER = "verify_after"
    const val KEY_AUXILIARY_DATA_FILE = "auxiliary_data_file"
    const val KEY_FIXED_BYTE_RANGE_FILE = "fixed_byte_range_file"
    const val KEY_BOOT_PATCH_FILE = "boot_patch_file"
    const val KEY_BOOT_PATCH_TEMPLATE_FILE = "boot_patch_template_file"
    const val KEY_RETURN_PLAN_FILE = "return_plan_file"
    const val KEY_TAGS = "tags"

    const val KEY_PERCENT = "percent"
    const val KEY_MESSAGE = "message"
    const val KEY_STAGE = "stage"
    const val KEY_SUB_STAGE = "sub_stage"
}

class UsbImageWriteWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    private val usbWriter = AndroidUsbHostBlockWriter(context = appContext)

    override suspend fun doWork(): Result {
        val auxiliaryDataFile = inputData.getString(UsbWriteData.KEY_AUXILIARY_DATA_FILE)
            ?.ifBlank { null }
            ?.let(::File)
        val fixedByteRangeFile = inputData.getString(UsbWriteData.KEY_FIXED_BYTE_RANGE_FILE)
            ?.ifBlank { null }
            ?.let(::File)
        val bootPatchFile = inputData.getString(UsbWriteData.KEY_BOOT_PATCH_FILE)
            ?.ifBlank { null }
            ?.let(::File)
        val bootPatchTemplateFile = inputData.getString(UsbWriteData.KEY_BOOT_PATCH_TEMPLATE_FILE)
            ?.ifBlank { null }
            ?.let(::File)
        val returnPlanFile = inputData.getString(UsbWriteData.KEY_RETURN_PLAN_FILE)
            ?.ifBlank { null }
            ?.let(::File)
        var writeIntent: WriteIntent? = null
        var completedReceiptPersisted = false
        return try {
            UsbWriteGate.withExclusiveWrite {
                ensureNotificationChannel()
                updateProgress(0, applicationContext.getString(R.string.preparing_usb_write), "foreground")

                var intent = inputData.toWriteIntent()
                writeIntent = intent

                // A destructive write must never restart on its own: after process death or a
                // system stop, the device that was confirmed may no longer be the one attached.
                if (runAttemptCount > 0) {
                    throw WriteRefusedException(
                        "USB write was interrupted and will not restart automatically; start it again to rewrite the device",
                    )
                }

                publishWriteStartCallbacks(
                    writeIntent = intent,
                    log = { message -> Log.d("SparkWriter", message) },
                    reportStatus = { message -> updateProgress(0, message, "write_ready") },
                    onDelivery = { delivery ->
                        val published = deliverReturnDelivery(
                            delivery.method, delivery.url, delivery.body, delivery.mediaType,
                        )
                        if (published != null) {
                            intent = intent.withPublishedCompactArtifact(delivery.url, published.url, delivery.compactReadUrl)
                            writeIntent = intent
                        }
                    },
                )

                // One claim spans every phase so usb-storage cannot rebind to a half-written image.
                usbWriter.withClaimedDevice(intent.deviceId, intent.confirmedDevice) {
                    updateProgress(0, applicationContext.getString(R.string.opening_usb_image), "write_image")
                    usbWriter.writeImage(intent).collect { write ->
                        updateProgress(write.percent, "${write.message}: ${write.percent}%", "write_image")
                    }

                    if (intent.verifyAfter) {
                        updateProgress(0, applicationContext.getString(R.string.starting_usb_verification), "verify_image")
                        usbWriter.verifyImage(intent).collect { verify ->
                            val percent = if (verify.totalBytes <= 0) {
                                0
                            } else {
                                ((verify.bytesRead * 100) / verify.totalBytes).toInt().coerceIn(0, 100)
                            }
                            updateProgress(percent, "${verify.message}: $percent%", "verify_image")
                        }
                    }

                    if (intent.bootPatch != null) {
                        updateProgress(0, applicationContext.getString(R.string.preparing_boot_patch), "boot_patch")
                        usbWriter.writeBootPatch(intent).collect { patch ->
                            updateProgress(patch.percent, "${patch.message}: ${patch.percent}%", "boot_patch")
                        }
                    }

                    if (intent.auxiliaryData != null) {
                        updateProgress(0, applicationContext.getString(R.string.preparing_auxiliary_data), "auxiliary_data", "starting")
                        usbWriter.writeAuxiliaryData(intent).collect { auxiliary ->
                            updateProgress(
                                auxiliary.percent,
                                "${auxiliary.message}: ${auxiliary.percent}%",
                                "auxiliary_data",
                                auxiliary.message.lowercase().replace(Regex("\\W+"), "_"),
                            )
                        }
                    }

                    if (intent.fixedByteRanges != null) {
                        updateProgress(0, applicationContext.getString(R.string.preparing_fixed_byte_ranges), "fixed_byte_ranges", "starting")
                        usbWriter.writeFixedByteRanges(intent).collect { fixed ->
                            updateProgress(fixed.percent, "${fixed.message}: ${fixed.percent}%", "fixed_byte_ranges")
                        }
                    }
                }

                updateProgress(100, applicationContext.getString(R.string.usb_write_complete), "complete")
                showCompletionNotification(applicationContext.getString(R.string.usb_write_complete))
                val receiptPayload = tryPersistReceipt(intent, WriteStatus.COMPLETED, null)
                completedReceiptPersisted = true
                val completionMessage = runCatching {
                    deliverWriteCompleteReturns(intent, receiptPayload)
                    applicationContext.getString(R.string.usb_write_complete)
                }.getOrElse { error ->
                    val message = applicationContext.getString(
                        R.string.usb_write_completed_return_failed,
                        error.sparkWriterFailureMessage(),
                    )
                    Log.e("SparkWriter", "USB write completed but completion return failed", error)
                    updateProgress(100, message, "complete_return_failed")
                    message
                }
                Result.success(workDataOf(UsbWriteData.KEY_MESSAGE to completionMessage))
            }
        } catch (error: Throwable) {
            if (error is CancellationException && isStopped) {
                // The coroutine is already cancelled, so suspending work (the receipt's Room write)
                // only runs under NonCancellable. Rethrow so WorkManager records CANCELLED/STOPPED.
                val message = cancelledWriteMessage(currentStage, stopReason)
                Log.w("SparkWriter", "USB write worker stopped: $message")
                if (!completedReceiptPersisted) {
                    withContext(NonCancellable) {
                        writeIntent?.let { tryPersistReceipt(it, WriteStatus.CANCELLED, message) }
                    }
                }
                throw error
            }
            val message = error.sparkWriterFailureMessage()
            Log.e("SparkWriter", "USB write worker failed: $message", error)
            runCatching { updateProgress(0, applicationContext.getString(R.string.usb_write_failed, message), "failed") }
            if (!completedReceiptPersisted) {
                writeIntent?.let { tryPersistReceipt(it, WriteStatus.FAILED, message) }
            }
            Result.failure(workDataOf(UsbWriteData.KEY_MESSAGE to message))
        } finally {
            auxiliaryDataFile?.delete()
            fixedByteRangeFile?.delete()
            bootPatchFile?.delete()
            bootPatchTemplateFile?.delete()
            returnPlanFile?.delete()
        }
    }

    /** Last stage reported through [updateProgress], recorded on cancellation receipts. */
    private var currentStage: String = "foreground"

    private suspend fun updateProgress(
        percent: Int,
        message: String,
        stage: String? = null,
        subStage: String? = null,
    ) {
        val clamped = percent.coerceIn(0, 100)
        stage?.ifBlank { null }?.let { currentStage = it }
        setProgress(
            workDataOf(
                UsbWriteData.KEY_PERCENT to clamped,
                UsbWriteData.KEY_MESSAGE to message,
                UsbWriteData.KEY_STAGE to stage.orEmpty(),
                UsbWriteData.KEY_SUB_STAGE to subStage.orEmpty(),
            ),
        )
        setForeground(foregroundInfo(clamped, message))
    }

    private fun foregroundInfo(percent: Int, message: String): ForegroundInfo {
        val notification = buildNotification(percent, message)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                USB_WRITE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(USB_WRITE_NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(percent: Int, message: String): Notification {
        val launchIntent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

        return NotificationCompat.Builder(applicationContext, USB_WRITE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(applicationContext.getString(R.string.writing_usb_image))
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(R.string.cancel),
                cancelIntent,
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val progressChannel = NotificationChannel(
            USB_WRITE_CHANNEL_ID,
            applicationContext.getString(R.string.usb_writes),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(R.string.usb_write_progress_channel)
        }
        val completionChannel = NotificationChannel(
            USB_WRITE_COMPLETE_CHANNEL_ID,
            applicationContext.getString(R.string.usb_write_complete),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = applicationContext.getString(R.string.usb_write_complete_channel)
        }
        manager.createNotificationChannels(listOf(progressChannel, completionChannel))
    }

    private fun showCompletionNotification(message: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val launchIntent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(applicationContext, USB_WRITE_COMPLETE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(applicationContext.getString(R.string.usb_write_complete))
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(USB_WRITE_COMPLETE_NOTIFICATION_ID, notification)
    }

    companion object {
        fun inputData(
            context: Context,
            deviceId: String,
            confirmedDevice: DeviceFingerprint,
            image: VerifiedImage,
            verifyAfter: Boolean,
            auxiliaryData: AuxiliaryDataPlan?,
            fixedByteRanges: FixedByteRangePlan?,
            bootPatch: BootPatchPlan? = null,
            bootPatchTemplate: BootPatchTemplatePlan? = null,
            returnPlan: WriteReturnPlan?,
            tags: Map<String, String> = emptyMap(),
        ): Data = workDataOf(
            UsbWriteData.KEY_DEVICE_ID to deviceId,
            UsbWriteData.KEY_CONFIRMED_DEVICE to workerJson.encodeToString(confirmedDevice),
            UsbWriteData.KEY_IMAGE to workerJson.encodeToString(image),
            UsbWriteData.KEY_VERIFY_AFTER to verifyAfter,
            UsbWriteData.KEY_AUXILIARY_DATA_FILE to auxiliaryData?.let { persistAuxiliaryData(context, it) },
            UsbWriteData.KEY_FIXED_BYTE_RANGE_FILE to fixedByteRanges?.let { persistFixedByteRanges(context, it) },
            UsbWriteData.KEY_BOOT_PATCH_FILE to bootPatch?.let { persistBootPatch(context, it) },
            UsbWriteData.KEY_BOOT_PATCH_TEMPLATE_FILE to bootPatchTemplate?.let { persistBootPatchTemplate(context, it) },
            UsbWriteData.KEY_RETURN_PLAN_FILE to returnPlan?.let { persistReturnPlan(context, it) },
            UsbWriteData.KEY_TAGS to workerJson.encodeToString(tags),
        )

        private fun persistAuxiliaryData(context: Context, auxiliaryData: AuxiliaryDataPlan): String {
            val jobsDir = File(context.filesDir, "sparkwriter/write-jobs").apply { mkdirs() }
            val file = File(jobsDir, "auxiliary-${UUID.randomUUID()}.json")
            file.writeText(workerJson.encodeToString(auxiliaryData))
            return file.absolutePath
        }

        private fun persistFixedByteRanges(context: Context, fixedByteRanges: FixedByteRangePlan): String {
            val jobsDir = File(context.filesDir, "sparkwriter/write-jobs").apply { mkdirs() }
            val file = File(jobsDir, "fixed-ranges-${UUID.randomUUID()}.json")
            file.writeText(workerJson.encodeToString(fixedByteRanges))
            return file.absolutePath
        }

        private fun persistBootPatch(context: Context, bootPatch: BootPatchPlan): String {
            val jobsDir = File(context.filesDir, "sparkwriter/write-jobs").apply { mkdirs() }
            val file = File(jobsDir, "boot-patch-${UUID.randomUUID()}.json")
            file.writeText(workerJson.encodeToString(bootPatch))
            return file.absolutePath
        }

        private fun persistBootPatchTemplate(context: Context, bootPatchTemplate: BootPatchTemplatePlan): String {
            val jobsDir = File(context.filesDir, "sparkwriter/write-jobs").apply { mkdirs() }
            val file = File(jobsDir, "boot-patch-template-${UUID.randomUUID()}.json")
            file.writeText(workerJson.encodeToString(bootPatchTemplate))
            return file.absolutePath
        }

        private fun persistReturnPlan(context: Context, returnPlan: WriteReturnPlan): String {
            val jobsDir = File(context.filesDir, "sparkwriter/write-jobs").apply { mkdirs() }
            val file = File(jobsDir, "return-${UUID.randomUUID()}.json")
            file.writeText(workerJson.encodeToString(returnPlan))
            return file.absolutePath
        }
    }
}

private suspend fun UsbImageWriteWorker.tryPersistReceipt(
    intent: WriteIntent,
    status: WriteStatus,
    errorMessage: String?,
): Map<String, String> {
    return runCatching { persistReceipt(intent, status, errorMessage) }
        .getOrDefault(emptyMap())
}

private suspend fun UsbImageWriteWorker.persistReceipt(
    intent: WriteIntent,
    status: WriteStatus,
    errorMessage: String?,
): Map<String, String> {
    val completedAt = System.currentTimeMillis()
    val receiptId = UUID.randomUUID().toString()
    val payloadObject = buildReceiptPayload(receiptId, intent, status, completedAt, errorMessage)
    val payload = payloadObject.toString()
    withContext(Dispatchers.IO) {
        SparkWriterDatabase.open(applicationContext).receipts().insert(
            ReceiptEntity(
                id = receiptId,
                manifestId = intent.tags["manifest_id"],
                sourceId = intent.tags["source_id"],
                payloadJson = payload,
                completedAtEpochMillis = completedAt,
            ),
        )
    }
    return payloadObject.stringValues()
}

private suspend fun UsbImageWriteWorker.deliverWriteCompleteReturns(
    intent: WriteIntent,
    receiptVariables: Map<String, String>,
) {
    executePreparedReturnDeliveries(
        deliveries = prepareWriteCompleteReturnDeliveries(intent, receiptVariables),
        post = { delivery ->
            deliverReturnDelivery(delivery.method, delivery.url, delivery.body, delivery.mediaType)
        },
    )
}

internal data class PreparedReturnDelivery(
    val event: String,
    val method: String,
    val url: String,
    val body: String,
    val mediaType: String,
    val required: Boolean,
    val headers: Map<String, String> = emptyMap(),
    val compactReadUrl: String? = null,
)

internal data class PublishedCompactArtifact(
    val id: String,
    val url: String,
    val expiresAtEpochSeconds: Long,
)

internal fun prepareWriteCompleteReturnDeliveries(
    intent: WriteIntent,
    receiptVariables: Map<String, String>,
): List<PreparedReturnDelivery> = prepareReturnDeliveries(
    intent = intent,
    extraVariables = receiptVariables,
    event = "write_complete",
    status = WriteStatus.COMPLETED,
)

internal fun prepareWriteReadyReturnDeliveries(intent: WriteIntent): List<PreparedReturnDelivery> = prepareReturnDeliveries(
    intent = intent,
    extraVariables = emptyMap(),
    event = "write_ready",
    status = WriteStatus.PENDING,
)

internal fun prepareWorkflowStartedReturnDeliveries(
    intent: WriteIntent,
): List<PreparedReturnDelivery> = prepareReturnDeliveries(
    intent = intent,
    extraVariables = emptyMap(),
    event = "workflow.started",
    status = WriteStatus.RUNNING,
)

private fun prepareReturnDeliveries(
    intent: WriteIntent,
    extraVariables: Map<String, String>,
    event: String,
    status: WriteStatus,
): List<PreparedReturnDelivery> {
    val plan = intent.returnPlan ?: return emptyList()
    val variables = plan.variables.toMutableMap()
    variables += extraVariables
    variables += mapOf(
        "event" to event,
        "status" to status.name,
        "manifest_id" to (intent.tags["manifest_id"] ?: ""),
        "package_id" to (intent.tags["package_id"] ?: ""),
        "device_id" to intent.deviceId,
        // Redacted: the URI is a local path or document ID, and these variables reach remote templates.
        "image_uri" to intent.image.uri.logSafe(),
        "image_sha256" to intent.image.sha256,
        "image_size_bytes" to intent.image.sizeBytes.toString(),
        "image_media_type" to intent.image.mediaType,
    )
    val actions = when (event) {
        "workflow.started" -> plan.actionPhases["on_workflow_started"] ?: emptyList()
        "write_complete" -> plan.actionPhases["on_$event"] ?: plan.actions
        else -> plan.actionPhases["on_$event"] ?: emptyList()
    }
    val generatedArtifacts = plan.artifacts.toMutableMap()
    runReturnActions(actions, variables, plan.templates, generatedArtifacts)

    return plan.hostEvents.mapNotNull { subscription ->
        if (subscription.event != event) return@mapNotNull null
        val delivery = subscription.returnDelivery ?: return@mapNotNull null
        val fromValue = delivery.from
        val contentVarValue = delivery.contentVar
        val templateId = delivery.template
        val body = when {
            fromValue != null -> {
                val artifactId = if (fromValue.startsWith("artifacts.")) {
                    fromValue.substringAfter("artifacts.")
                } else {
                    fromValue
                }
                generatedArtifacts[artifactId] ?: error(
                    "Return delivery for ${subscription.event} references missing artifact: $fromValue",
                )
            }
            contentVarValue != null -> variables[contentVarValue]
            templateId != null -> renderSparkTemplate(
                plan.templates[templateId] ?: error("Return delivery template $templateId not found"),
                variables,
            )
            event == "workflow.started" -> buildDefaultWorkflowStartedBody(variables)
            else -> null
        } ?: return@mapNotNull null
        val url = renderSparkTemplate(delivery.url, variables).trim()
        PreparedReturnDelivery(
            event = subscription.event,
            method = delivery.method,
            url = url,
            body = body,
            mediaType = delivery.mediaType ?: "application/json",
            required = delivery.deliveryMode == "required",
            compactReadUrl = if (event == "write_ready" && url.isCompactMailroomPublishUrl()) {
                variables.compactReadUrlForDeliveryTemplate(delivery.url)
            } else {
                null
            },
        )
    }
}

private fun buildDefaultWorkflowStartedBody(variables: Map<String, String>): String {
    return Json.encodeToString(
        buildJsonObject {
            put("event", variables["event"])
            put("status", variables["status"])
            put("manifest_id", variables["manifest_id"])
            variables["package_id"]?.takeIf { it.isNotBlank() }?.let { put("package_id", it) }
            put("device_id", variables["device_id"])
            put("image_sha256", variables["image_sha256"])
        },
    )
}

internal suspend fun executePreparedReturnDeliveries(
    deliveries: List<PreparedReturnDelivery>,
    post: suspend (PreparedReturnDelivery) -> Unit,
    logger: (String) -> Unit = {},
) {
    deliveries.forEach { delivery ->
        logger(
            "prepared_delivery event=${delivery.event} " +
                "method=${delivery.method} required=${delivery.required} " +
                "url=${delivery.url.logSafe()} bodySize=${delivery.body.length} mediaType=${delivery.mediaType}",
        )
        val result = runCatching {
            post(delivery)
        }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            logger(
                "delivery_failed event=${delivery.event} method=${delivery.method} url=${delivery.url.logSafe()}: ${failure.message}",
            )
            val shouldBlock = delivery.required
            runCatching {
                Log.w(
                    "SparkWriter",
                    "Return delivery for ${delivery.event} failed: ${failure.message}",
                    failure,
                )
            }
            if (shouldBlock) {
                throw failure
            }
        } else {
            logger(
                "delivery_succeeded event=${delivery.event} " +
                    "method=${delivery.method} url=${delivery.url.logSafe()}",
            )
        }
    }
}

internal class ReturnDeliveryException(
    message: String,
    val responseCode: Int,
) : IllegalStateException(message)

private fun runReturnActions(
    actions: List<WorkflowAction>,
    variables: MutableMap<String, String>,
    templates: Map<String, String>,
    generatedArtifacts: MutableMap<String, String>,
) {
    actions.forEach { action ->
        if (!action.whenCondition.matches(variables)) return@forEach
        when (action.type) {
            "generate_ephemeral_password" -> {
                action.outputVar?.let { variables[it] = randomPassword() }
            }
            "render_template" -> {
                val templateId = action.template ?: error("render_template ${action.id} is missing template")
                val rendered = renderSparkTemplate(
                    templates[templateId] ?: error("Template $templateId not found"),
                    variables,
                )
                action.outputVar?.let { variables[it] = rendered }
            }
            "hash_password" -> {
                val outputVar = action.outputVar ?: error("hash_password ${action.id} is missing output_var")
                val inputVar = action.inputVar ?: error("hash_password ${action.id} is missing input_var")
                val password = variables[inputVar] ?: error("hash_password ${action.id} references missing input_var $inputVar")
                variables[outputVar] = hashPassword(password, action.algorithm)
            }
            "create_artifact" -> {
                val artifactId = action.artifactId ?: error("create_artifact ${action.id} is missing artifact_id")
                val contentVar = action.contentVar ?: error("create_artifact ${action.id} is missing content_var")
                generatedArtifacts[artifactId] = variables[contentVar] ?: ""
            }
        }
    }
}

internal suspend fun deliverReturnDelivery(
    method: String,
    url: String,
    body: String,
    mediaType: String,
    headers: Map<String, String> = emptyMap(),
): PublishedCompactArtifact? {
    ExternalServiceGate.require(if (url.isMailroomRendezvousUrl()) "Mailroom artifact handoff" else "return_delivery")
    if (!url.startsWith("https://", ignoreCase = true)) {
        throw IllegalArgumentException("Return delivery URLs must use HTTPS")
    }
    return withContext(Dispatchers.IO) {
        val requestBody = body.toRequestBody(mediaType.compatibleReturnDeliveryMediaType(url).toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        val request = when (method) {
            "PUT" -> requestBuilder.put(requestBody).build()
            else -> requestBuilder.post(requestBody).build()
        }
        returnHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ReturnDeliveryException(
                    "Return delivery failed with HTTP ${response.code}",
                    response.code,
                )
            }
            if (url.isCompactMailroomPublishUrl()) {
                val text = response.body?.string().orEmpty()
                val root = Json.parseToJsonElement(text).jsonObject
                val id = root["id"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalStateException("Compact Mailroom publish response missing id")
                val readUrl = root["url"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalStateException("Compact Mailroom publish response missing url")
                val expectedReadUrls = setOf("http://wri7e.net/$id", "https://wri7e.net/$id")
                if (!id.matches(COMPACT_ARTIFACT_ID_REGEX) || readUrl !in expectedReadUrls) {
                    throw IllegalStateException("Compact Mailroom publish response returned unexpected artifact URL")
                }
                PublishedCompactArtifact(
                    id = id,
                    url = readUrl,
                    expiresAtEpochSeconds = root["expires_at"]?.jsonPrimitive?.longOrNull
                        ?: throw IllegalStateException("Compact Mailroom publish response missing expires_at"),
                )
            } else {
                null
            }
        }
    }
}

internal fun String.compatibleReturnDeliveryMediaType(url: String): String {
    if (!url.isMailroomRendezvousUrl()) {
        return this
    }
    return when (this.lowercase()) {
        "application/toml" -> "text/plain"
        "text/x-shellscript" -> "text/plain"
        else -> this
    }
}

private fun String.isCanonicalMailroomRendezvousUrl(): Boolean =
    startsWith("https://mailroom.metalstrapper.net/netboot/v1/rendezvous/")

private fun String.isCompactMailroomArtifactUrl(): Boolean =
    startsWith("https://mr.metalstrapper.net/a/") ||
        startsWith("http://mr.metalstrapper.net/a/") ||
        isCompactMailroomPublishUrl()

private fun String.isCompactMailroomPublishUrl(): Boolean =
    this == "https://mailroom.metalstrapper.net/compact/v1/artifacts"

internal fun String.isMailroomRendezvousUrl(): Boolean =
    isCanonicalMailroomRendezvousUrl() ||
        startsWith("http://mailroom.metalstrapper.net/netboot/v1/rendezvous/") ||
        isCompactMailroomArtifactUrl()

internal fun WriteIntent.withPublishedCompactArtifact(
    writeUrl: String,
    publishedReadUrl: String,
    compactReadUrl: String? = null,
): WriteIntent {
    val plan = returnPlan ?: return this
    val oldReadUrls = compactReadUrl?.let(::listOf) ?: plan.variables.compactReadUrlsForWriteUrl(writeUrl)
    if (oldReadUrls.isEmpty()) return this
    val replacements = oldReadUrls.associateWith { publishedReadUrl }
    return copy(
        auxiliaryData = auxiliaryData?.replaceText(replacements),
        fixedByteRanges = fixedByteRanges?.replaceText(replacements),
        bootPatch = bootPatch?.replaceText(replacements),
        returnPlan = plan.copy(
            variables = plan.variables.mapValues { (_, value) ->
                replacements.entries.fold(value) { updated, (old, new) -> updated.replace(old, new) }
            },
        ),
    )
}

private fun Map<String, String>.compactReadUrlForDeliveryTemplate(urlTemplate: String): String? {
    val variableNames = SPARK_TEMPLATE_VARIABLE_REGEX.findAll(urlTemplate)
        .map { it.groupValues[1].trim() }
        .toList()
    return variableNames.firstNotNullOfOrNull { writeKey ->
        if (!writeKey.endsWith("_write_url")) return@firstNotNullOfOrNull null
        this[writeKey.removeSuffix("_write_url") + "_url"]
    }
}

private fun Map<String, String>.compactReadUrlsForWriteUrl(writeUrl: String): List<String> {
    return entries
        .filter { (key, value) -> key.endsWith("_write_url") && value == writeUrl }
        .flatMap { (key, _) ->
            val readKey = key.removeSuffix("_write_url") + "_url"
            listOfNotNull(this[readKey])
        }
        .distinct()
}

private fun BootPatchPlan.replaceText(replacements: Map<String, String>): BootPatchPlan = copy(
    appendArgs = appendArgs.replaceText(replacements),
    textPatches = textPatches.map { it.replaceText(replacements) },
    kernelArgPatches = kernelArgPatches.map { it.replaceText(replacements) },
    scriptPatches = scriptPatches.map { it.replaceText(replacements) },
    placeholderPatches = placeholderPatches.map { it.replaceText(replacements) },
)

private fun AuxiliaryDataPlan.replaceText(replacements: Map<String, String>): AuxiliaryDataPlan = copy(
    files = files.map { file ->
        file.copy(content = file.content.replaceText(replacements))
    },
)

private fun FixedByteRangePlan.replaceText(replacements: Map<String, String>): FixedByteRangePlan = copy(
    patches = patches.map { patch ->
        patch.copy(content = patch.content.replaceText(replacements))
    },
)

private fun BootTextPatchSpec.replaceText(replacements: Map<String, String>): BootTextPatchSpec = copy(
    find = find.replaceText(replacements),
    replace = replace.replaceText(replacements),
)

private fun BootKernelArgPatchSpec.replaceText(replacements: Map<String, String>): BootKernelArgPatchSpec = copy(
    ensureArgs = ensureArgs.replaceText(replacements),
    removeArgs = removeArgs.replaceText(replacements),
    removeLinesContaining = removeLinesContaining.replaceText(replacements),
)

private fun BootScriptPatchSpec.replaceText(replacements: Map<String, String>): BootScriptPatchSpec = copy(
    script = script.replaceText(replacements),
)

private fun BootPlaceholderPatchSpec.replaceText(replacements: Map<String, String>): BootPlaceholderPatchSpec = copy(
    placeholder = placeholder.replaceText(replacements),
    replacement = replacement.replaceText(replacements),
)

private fun List<String>.replaceText(replacements: Map<String, String>): List<String> =
    map { it.replaceText(replacements) }

private fun String.replaceText(replacements: Map<String, String>): String =
    replacements.entries.fold(this) { updated, (old, new) -> updated.replace(old, new) }

private val COMPACT_ARTIFACT_ID_REGEX = Regex("[A-Za-z0-9]{7}")
private val SPARK_TEMPLATE_VARIABLE_REGEX = Regex("""\{\{\s*([^}]+?)\s*\}\}""")

private fun buildReceiptPayload(
    id: String,
    intent: WriteIntent,
    status: WriteStatus,
    completedAtEpochMillis: Long,
    errorMessage: String?,
) = buildJsonObject {
    put("id", id)
    put("status", status.name)
    put("completed_at_epoch_millis", completedAtEpochMillis)
    put("manifest_id", intent.tags["manifest_id"])
    put("package_id", intent.tags["package_id"])
    put("package_root_sha256", intent.tags["package_root_sha256"])
    put("source_id", intent.tags["source_id"])
    put("adaptation_status", intent.tags["adaptation_status"])
    put("device_id", intent.deviceId)
    put("image_uri", intent.image.uri.logSafe())
    put("image_sha256", intent.image.sha256)
    put("image_size_bytes", intent.image.sizeBytes)
    put("image_media_type", intent.image.mediaType)
    errorMessage?.let { put("error", it) }
    intent.auxiliaryData?.let { auxiliary ->
        put("delivery_mode", "auxiliary_partition")
        put("auxiliary_label", auxiliary.label)
        put("auxiliary_filesystem", auxiliary.filesystem)
        put("auxiliary_placement", auxiliary.placement)
        put("auxiliary_size_bytes", auxiliary.sizeBytes)
        auxiliary.geometry?.let { geometry ->
            put("auxiliary_byte_offset", geometry.byteOffset)
            put("auxiliary_byte_length", geometry.byteLength)
        }
        put("delivered_files", buildJsonArray {
            auxiliary.files.forEach { file ->
                add(buildJsonObject {
                    put("path", file.path)
                    put("media_type", file.mediaType)
                    val bytes = file.content.toByteArray(Charsets.UTF_8)
                    put("size_bytes", bytes.size)
                    put("sha256", bytes.sha256())
                })
            }
        })
    }
    intent.fixedByteRanges?.let { fixed ->
        put("delivery_mode", "fixed_byte_ranges")
        put("base_image_sha256", fixed.baseImageSha256)
        put("patches", buildJsonArray {
            fixed.patches.forEach { patch ->
                add(buildJsonObject {
                    put("id", patch.id)
                    put("logical_name", patch.logicalName)
                    put("media_type", patch.mediaType)
                    put("byte_offset", patch.byteOffset)
                    put("byte_length", patch.byteLength)
                    put("content_sha256", patch.contentSha256)
                })
            }
        })
    }
    intent.bootPatch?.let { patch ->
        put("boot_patch_filesystem", patch.filesystem)
        put("boot_patch_config_family", patch.configFamily)
        put("boot_patch_candidate_paths", buildJsonArray {
            patch.candidatePaths.forEach { add(JsonPrimitive(it)) }
        })
        put("boot_patch_append_args", buildJsonArray {
            patch.appendArgs.forEach { add(JsonPrimitive(it)) }
        })
        put("boot_patch_kernel_arg_patches", buildJsonArray {
            patch.kernelArgPatches.forEach { kernelPatch ->
                add(buildJsonObject {
                    put("config_family", kernelPatch.configFamily)
                    put("candidate_paths", buildJsonArray {
                        kernelPatch.candidatePaths.forEach { add(JsonPrimitive(it)) }
                    })
                    put("selector", buildJsonObject {
                        kernelPatch.selector.menuentry?.let { put("menuentry", it) }
                        kernelPatch.selector.label?.let { put("label", it) }
                        kernelPatch.selector.kernel?.let { put("kernel", it) }
                    })
                    put("ensure_args", buildJsonArray {
                        kernelPatch.ensureArgs.forEach { add(JsonPrimitive(it)) }
                    })
                    put("insert_before", kernelPatch.insertBefore)
                })
            }
        })
        put("boot_patch_script_patches", buildJsonArray {
            patch.scriptPatches.forEach { scriptPatch ->
                add(buildJsonObject {
                    put("kind", scriptPatch.kind)
                    put("candidate_paths", buildJsonArray {
                        scriptPatch.candidatePaths.forEach { add(JsonPrimitive(it)) }
                    })
                    scriptPatch.espImagePath?.let { put("esp_image_path", it) }
                    scriptPatch.espScriptPath?.let { put("esp_script_path", it) }
                    put("script_sha256", scriptPatch.script.toByteArray(Charsets.UTF_8).sha256())
                    put("script_size_bytes", scriptPatch.script.toByteArray(Charsets.UTF_8).size)
                })
            }
        })
        put("boot_patch_placeholder_patches", buildJsonArray {
            patch.placeholderPatches.forEach { placeholderPatch ->
                add(buildJsonObject {
                    put("candidate_paths", buildJsonArray {
                        placeholderPatch.candidatePaths.forEach { add(JsonPrimitive(it)) }
                    })
                    put("placeholder_sha256", placeholderPatch.placeholder.toByteArray(Charsets.US_ASCII).sha256())
                    put("placeholder_size_bytes", placeholderPatch.placeholder.toByteArray(Charsets.US_ASCII).size)
                    put("replacement_sha256", placeholderPatch.replacement.toByteArray(Charsets.US_ASCII).sha256())
                    put("replacement_size_bytes", placeholderPatch.replacement.toByteArray(Charsets.US_ASCII).size)
                    put("encoding", placeholderPatch.encoding)
                    put("padding", placeholderPatch.padding)
                })
            }
        })
    }
}

private val workerJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private fun Data.toWriteIntent(): WriteIntent {
    val deviceId = getString(UsbWriteData.KEY_DEVICE_ID)?.ifBlank { null }
        ?: throw IllegalArgumentException("USB write worker missing device ID")
    val image = workerJson.decodeFromString<VerifiedImage>(
        getString(UsbWriteData.KEY_IMAGE)?.ifBlank { null }
            ?: throw IllegalArgumentException("USB write worker missing image"),
    )
    val auxiliary = getString(UsbWriteData.KEY_AUXILIARY_DATA_FILE)
        ?.ifBlank { null }
        ?.let { workerJson.decodeFromString<AuxiliaryDataPlan>(File(it).readText()) }
    val fixedByteRanges = getString(UsbWriteData.KEY_FIXED_BYTE_RANGE_FILE)
        ?.ifBlank { null }
        ?.let { workerJson.decodeFromString<FixedByteRangePlan>(File(it).readText()) }
    val bootPatch = getString(UsbWriteData.KEY_BOOT_PATCH_FILE)
        ?.ifBlank { null }
        ?.let { workerJson.decodeFromString<BootPatchPlan>(File(it).readText()) }
    val bootPatchTemplate = getString(UsbWriteData.KEY_BOOT_PATCH_TEMPLATE_FILE)
        ?.ifBlank { null }
        ?.let { workerJson.decodeFromString<BootPatchTemplatePlan>(File(it).readText()) }
    val returnPlan = getString(UsbWriteData.KEY_RETURN_PLAN_FILE)
        ?.ifBlank { null }
        ?.let { workerJson.decodeFromString<WriteReturnPlan>(File(it).readText()) }
    val confirmedDevice = getString(UsbWriteData.KEY_CONFIRMED_DEVICE)
        ?.ifBlank { null }
        ?.let { workerJson.decodeFromString<DeviceFingerprint>(it) }
    val tags = getString(UsbWriteData.KEY_TAGS)
        ?.ifBlank { null }
        ?.let { workerJson.decodeFromString<Map<String, String>>(it) }
        .orEmpty()

    return WriteIntent(
        deviceId = deviceId,
        confirmedDeviceId = deviceId,
        image = image,
        verifyAfter = getBoolean(UsbWriteData.KEY_VERIFY_AFTER, true),
        auxiliaryData = auxiliary,
        fixedByteRanges = fixedByteRanges,
        bootPatch = bootPatch,
        bootPatchTemplate = bootPatchTemplate,
        returnPlan = returnPlan,
        tags = tags,
        confirmedDevice = confirmedDevice,
    )
}

private fun JsonElement.scalarString(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun Map<String, JsonElement>.toStringValues(): Map<String, String> = mapNotNull { (key, value) ->
    value.scalarString()?.let { key to it }
}.toMap()

private fun kotlinx.serialization.json.JsonObject.stringValues(): Map<String, String> = toStringValues()

private fun randomPassword(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789_-"
    val random = SecureRandom()
    return buildString {
        repeat(24) {
            append(alphabet[random.nextInt(alphabet.length)])
        }
    }
}

/**
 * Receipt message for a write that stopped before completing. Stages after write_image mean the
 * base image may be fully written but unpatched; any stage means the device must not be trusted.
 */
internal fun cancelledWriteMessage(stage: String, stopReason: Int): String {
    val cause = when (stopReason) {
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled"
        WorkInfo.STOP_REASON_NOT_STOPPED -> "cancelled"
        else -> "stopped by the system (reason $stopReason)"
    }
    return "USB write $cause during $stage; the device contents are incomplete and must be rewritten"
}

internal fun Throwable.sparkWriterFailureMessage(): String {
    val messages = generateSequence(this) { it.cause }
        .map { error ->
            val className = error::class.simpleName ?: "Error"
            val message = error.message?.ifBlank { null }
            if (message == null) className else "$className: $message"
        }
        .distinct()
        .take(3)
        .toList()
    return messages.joinToString(" caused by ").ifBlank { "Unknown USB write failure" }
}

// Never follow an HTTPS -> HTTP redirect with a return delivery body.
private val returnHttpClient = OkHttpClient.Builder()
    .followSslRedirects(false)
    .build()
