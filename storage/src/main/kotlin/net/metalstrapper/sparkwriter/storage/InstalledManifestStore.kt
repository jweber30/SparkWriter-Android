package net.metalstrapper.sparkwriter.storage

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import net.metalstrapper.sparkwriter.core.SparkManifest

class InstalledManifestStore(
    private val context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) {
    private val database by lazy { SparkWriterDatabase.open(context.applicationContext) }

    fun observeInstalled(): Flow<List<SparkManifest>> {
        return database.manifests().observeAll().map { entities ->
            entities.mapNotNull { decodeManifest(it.rawJson) }
        }
    }

    suspend fun loadInstalled(): List<SparkManifest> {
        return database.manifests().observeAll().first().mapNotNull { entity ->
            decodeManifest(entity.rawJson)
        }
    }

    suspend fun upsertManifest(manifest: SparkManifest) {
        database.manifests().upsert(
            InstalledManifestEntity(
                id = manifest.metadata.id,
                name = manifest.metadata.name,
                origin = manifest.installOrigin,
                rawJson = json.encodeToString(manifest),
                installedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun decodeManifest(rawJson: String): SparkManifest? {
        return runCatching { json.decodeFromString<SparkManifest>(rawJson) }.getOrNull()
    }
}
