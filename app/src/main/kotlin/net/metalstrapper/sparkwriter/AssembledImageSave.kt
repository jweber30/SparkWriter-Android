package net.metalstrapper.sparkwriter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.AuxiliaryDataPlan
import net.metalstrapper.sparkwriter.core.BootPatchPlan
import net.metalstrapper.sparkwriter.core.FixedByteRangePlan
import net.metalstrapper.sparkwriter.core.VerifiedImage
import net.metalstrapper.sparkwriter.core.WriteIntent
import net.metalstrapper.sparkwriter.core.WriteReturnPlan
import net.metalstrapper.sparkwriter.usb.AssembledImageProgress
import net.metalstrapper.sparkwriter.usb.AssembledImageWriter
import java.net.URI
import java.io.File
import java.io.FileInputStream

internal data class PendingAssembledImageSave(
    val image: VerifiedImage,
    val auxiliaryData: AuxiliaryDataPlan?,
    val fixedByteRanges: FixedByteRangePlan?,
    val bootPatch: BootPatchPlan?,
    val returnPlan: WriteReturnPlan?,
    val saveAsIso: Boolean,
    val tags: Map<String, String>,
)

internal data class AssembledImageSaveTarget(
    val fileName: String,
    val mimeType: String,
)

internal class AssembledImageDocumentCreator : ActivityResultContract<AssembledImageSaveTarget, Uri?>() {
    override fun createIntent(context: Context, input: AssembledImageSaveTarget): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent?.data
    }
}

internal suspend fun saveAssembledImage(
    context: Context,
    request: PendingAssembledImageSave,
    destination: Uri,
    onProgress: (AssembledImageProgress) -> Unit,
) = withContext(Dispatchers.IO) {
    val tempFile = File.createTempFile("sparkwriter-assembled-", ".img", context.cacheDir)
    try {
        var writeIntent = WriteIntent(
            deviceId = "assembled-image",
            confirmedDeviceId = "assembled-image",
            image = request.image,
            verifyAfter = false,
            auxiliaryData = request.auxiliaryData,
            fixedByteRanges = request.fixedByteRanges,
            bootPatch = request.bootPatch,
            returnPlan = request.returnPlan,
            tags = request.tags,
        )
        publishWriteStartCallbacks(
            writeIntent = writeIntent,
            reportStatus = { message ->
                onProgress(AssembledImageProgress(message, completed = 0L, total = 0L))
            },
            onDelivery = { delivery ->
                val published = deliverReturnDelivery(
                    delivery.method, delivery.url, delivery.body, delivery.mediaType,
                )
                if (published != null) {
                    writeIntent = writeIntent.withPublishedCompactArtifact(delivery.url, published.url, delivery.compactReadUrl)
                }
            },
            log = { message -> Log.d("SparkWriter", message) },
        )
        context.openImportedImageInputStream(Uri.parse(request.image.uri)).use { input ->
            if (input == null) error("Image URI could not be opened")
            AssembledImageWriter.assemble(
                source = input,
                outputFile = tempFile,
                image = writeIntent.image,
                auxiliaryData = writeIntent.auxiliaryData,
                fixedByteRanges = writeIntent.fixedByteRanges,
                bootPatch = writeIntent.bootPatch,
                onProgress = onProgress,
            )
        }
        context.contentResolver.openOutputStream(destination, "wt").use { output ->
            if (output == null) error("Save destination could not be opened")
            FileInputStream(tempFile).use { input ->
                input.copyTo(output)
            }
        }
    } finally {
        tempFile.delete()
    }
}

internal fun SparkManifest.prefersAssembledIsoSave(image: VerifiedImage?): Boolean {
    val mediaType = image?.mediaType ?: source?.mediaType
    return mediaType.equals(ISO_IMAGE_MEDIA_TYPE, ignoreCase = true) &&
        delivery?.mode != "auxiliary_partition"
}

internal fun SparkManifest.assembledImageSaveTarget(image: VerifiedImage, saveAsIso: Boolean): AssembledImageSaveTarget {
    val base = metadata.name.ifBlank { image.displayName.substringBeforeLast('.') }
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .ifBlank { "sparkwriter-image" }
    val extension = if (saveAsIso) "iso" else "img"
    val mimeType = if (saveAsIso) ISO_IMAGE_MEDIA_TYPE else "application/octet-stream"
    return AssembledImageSaveTarget("$base-assembled.$extension", mimeType)
}
