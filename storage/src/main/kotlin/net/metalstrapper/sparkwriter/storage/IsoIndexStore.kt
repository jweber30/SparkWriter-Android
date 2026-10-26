package net.metalstrapper.sparkwriter.storage

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import net.metalstrapper.sparkwriter.core.BootPatchTemplatePlan
import net.metalstrapper.sparkwriter.core.SparkManifest
import net.metalstrapper.sparkwriter.core.VerifiedImage

data class IsoIndexMatch(
    val manifestId: String,
    val sourceId: String?,
    val matchKind: String,
)

class IsoIndexStore(
    context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    private val database by lazy { SparkWriterDatabase.open(context.applicationContext) }

    suspend fun refreshDeclared(manifests: List<SparkManifest>) {
        database.isoIndex().clearDeclared()
        val now = System.currentTimeMillis()
        manifests.forEach { manifest ->
            val source = manifest.source ?: return@forEach
            val first64K = source.first64KSha256?.ifBlank { null } ?: return@forEach
            val sizeBytes = source.sizeBytes ?: return@forEach
            database.isoIndex().upsert(
                IsoIndexEntryEntity(
                    first64KSha256 = first64K,
                    sizeBytes = sizeBytes,
                    pvdSha256 = source.pvdSha256,
                    fullSha256 = source.sha256,
                    manifestId = manifest.metadata.id,
                    sourceId = source.id,
                    matchKind = "declared",
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
        }
    }

    suspend fun matches(image: VerifiedImage): List<IsoIndexMatch> {
        val first64K = image.first64KSha256?.ifBlank { null } ?: return emptyList()
        return database.isoIndex().matches(first64K, image.sizeBytes).map { entity ->
            IsoIndexMatch(
                manifestId = entity.manifestId,
                sourceId = entity.sourceId,
                matchKind = entity.matchKind,
            )
        }
    }

    suspend fun learn(manifest: SparkManifest, image: VerifiedImage) {
        val first64K = image.first64KSha256?.ifBlank { null } ?: return
        val now = System.currentTimeMillis()
        database.isoIndex().upsert(
            IsoIndexEntryEntity(
                first64KSha256 = first64K,
                sizeBytes = image.sizeBytes,
                pvdSha256 = image.pvdSha256,
                fullSha256 = image.sha256,
                manifestId = manifest.metadata.id,
                sourceId = manifest.source?.id,
                matchKind = "learned",
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
    }

    suspend fun loadTemplate(
        image: VerifiedImage,
        manifest: SparkManifest,
        signature: String,
    ): BootPatchTemplatePlan? {
        val first64K = image.first64KSha256?.ifBlank { null } ?: return null
        return database.isoPatchTemplates().get(
            first64KSha256 = first64K,
            sizeBytes = image.sizeBytes,
            manifestId = manifest.metadata.id,
            bootPatchSignature = signature,
        )?.let { entity ->
            runCatching { json.decodeFromString<BootPatchTemplatePlan>(entity.payloadJson) }.getOrNull()
        }
    }

    suspend fun saveTemplate(
        image: VerifiedImage,
        manifest: SparkManifest,
        template: BootPatchTemplatePlan,
    ) {
        val first64K = image.first64KSha256?.ifBlank { null } ?: return
        val now = System.currentTimeMillis()
        database.isoPatchTemplates().upsert(
            IsoPatchTemplateEntity(
                first64KSha256 = first64K,
                sizeBytes = image.sizeBytes,
                manifestId = manifest.metadata.id,
                bootPatchSignature = template.signature,
                payloadJson = json.encodeToString(template),
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
    }
}
