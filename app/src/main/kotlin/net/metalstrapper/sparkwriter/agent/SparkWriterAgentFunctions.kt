package net.metalstrapper.sparkwriter.agent

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.service.AppFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.metalstrapper.sparkwriter.ExternalServiceGate
import net.metalstrapper.sparkwriter.MainActivity
import net.metalstrapper.sparkwriter.buildDraftUri
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.isOfferedForOwnSource
import net.metalstrapper.sparkwriter.loadBundledManifests
import net.metalstrapper.sparkwriter.manifest.ManifestRuntime
import net.metalstrapper.sparkwriter.storage.InstalledManifestStore
import net.metalstrapper.sparkwriter.withReleaseExternalServicePolicy

/**
 * SparkWriter's AppFunctions ("Android MCP"). An assistant can list SparkPlugs,
 * read a SparkPlug's settings, and prepare a draft of them. None of these write
 * anything: the draft opens SparkWriter, where the user reviews it and starts the
 * USB write or ISO export.
 *
 * The appfunctions-service library declares the platform service (enabled on
 * API 36+ only) and creates this class for each call.
 */
class SparkWriterAgentFunctions {

    /**
     * Lists the SparkPlugs installed in SparkWriter. Each SparkPlug builds an
     * unattended installer for one operating system, such as Proxmox VE.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun listSparkPlugs(appFunctionContext: AppFunctionContext): List<SparkPlugSummary> =
        loadOfferedSparkPlugs(appFunctionContext.context).map { it.toAgentSummary() }

    /**
     * Describes the settings of one SparkPlug: hostname, network, disks, keys and so
     * on, with their allowed values. Call this before prepareSparkPlugDraft.
     *
     * @param sparkPlugId The id from listSparkPlugs.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun describeSparkPlug(appFunctionContext: AppFunctionContext, sparkPlugId: String): SparkPlugDetail =
        findSparkPlug(appFunctionContext.context, sparkPlugId).toAgentDetail()

    /**
     * Fills in a SparkPlug's settings from what the user described, and returns an
     * intent that opens SparkWriter on them. Nothing is written: the user reviews the
     * settings, enters any passwords, and starts the USB write or ISO export.
     * Values that cannot be used are listed in the result's issues, so you can fix
     * them and call again before opening SparkWriter.
     *
     * @param sparkPlugId The id from listSparkPlugs.
     * @param values Settings to fill in, using field ids from describeSparkPlug. Leave
     *   out anything the user did not ask for, so its default is kept.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun prepareSparkPlugDraft(
        appFunctionContext: AppFunctionContext,
        sparkPlugId: String,
        values: List<SparkPlugFieldValue>,
    ): SparkPlugDraft {
        val context = appFunctionContext.context
        val manifest = findSparkPlug(context, sparkPlugId)
        val validation = manifest.validateAgentDraft(values)
        // Only values that passed go into the link; the activity checks them again.
        val accepted = validation.accepted.map { (formId, value) -> SparkPlugFieldValue(formId, value) }
        return validation.toAgentDraft(manifest, draftPendingIntent(context, accepted.toDraftRequest(manifest.metadata.id)))
    }

    private suspend fun findSparkPlug(context: Context, id: String): SparkManifest =
        loadOfferedSparkPlugs(context).firstOrNull { it.metadata.id == id }
            ?: throw AppFunctionElementNotFoundException("No installed SparkPlug has the id $id. Call listSparkPlugs for the ids.")
}

/** The SparkPlugs the app offers in its catalog. A bundled SparkPlug wins over an installed one with the same id, as in the app. */
internal suspend fun loadOfferedSparkPlugs(context: Context): List<SparkManifest> = withContext(Dispatchers.IO) {
    val runtime = ManifestRuntime(externalServicesEnabled = ExternalServiceGate.enabled)
    val bundled = context.loadBundledManifests(runtime)
    val installed = runCatching {
        InstalledManifestStore(context.applicationContext).loadInstalled().map { it.withReleaseExternalServicePolicy() }
    }.getOrElse { emptyList() }
    (bundled + installed)
        .distinctBy { it.metadata.id }
        .filter { it.isOfferedForOwnSource() && it.isAndroidRunnable }
}

private fun draftPendingIntent(context: Context, request: net.metalstrapper.sparkwriter.ConfigDraftRequest): PendingIntent {
    val uri = buildDraftUri(request)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    // A distinct request code per draft keeps one draft's intent from replacing another's.
    return PendingIntent.getActivity(
        context,
        uri.hashCode(),
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
