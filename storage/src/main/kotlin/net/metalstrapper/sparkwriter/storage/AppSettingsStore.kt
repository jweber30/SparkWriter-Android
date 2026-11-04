package net.metalstrapper.sparkwriter.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore by preferencesDataStore("spark_writer_settings")

private val LOCAL_ZIP_IMPORT_ENABLED = booleanPreferencesKey("local_zip_import_enabled")

private val ISO_RETENTION = stringPreferencesKey("iso_retention")
private val MAX_RETAINED_IMAGES = intPreferencesKey("max_retained_images")

const val MIN_RETAINED_IMAGES = 1
const val MAX_RETAINED_IMAGES_LIMIT = 999
const val DEFAULT_RETAINED_IMAGES = 3

enum class IsoRetention {
    DeleteAfterWrite,
    KeepRecent,
    KeepUntilDeleted,
}

class AppSettingsStore(private val context: Context) {
    // Local ZIP packages are not publisher-verified, so import stays off until the user opts in.
    val localZipImportEnabled: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[LOCAL_ZIP_IMPORT_ENABLED] ?: false }

    suspend fun setLocalZipImportEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[LOCAL_ZIP_IMPORT_ENABLED] = enabled }
    }

    val isoRetention: Flow<IsoRetention> = context.appSettingsDataStore.data.map { prefs ->
        prefs[ISO_RETENTION]?.let { name -> IsoRetention.entries.firstOrNull { it.name == name } }
            ?: IsoRetention.KeepRecent
    }

    val maxRetainedImages: Flow<Int> = context.appSettingsDataStore.data.map {
        (it[MAX_RETAINED_IMAGES] ?: DEFAULT_RETAINED_IMAGES).coerceIn(MIN_RETAINED_IMAGES, MAX_RETAINED_IMAGES_LIMIT)
    }

    suspend fun setIsoRetention(retention: IsoRetention) {
        context.appSettingsDataStore.edit { it[ISO_RETENTION] = retention.name }
    }

    suspend fun setMaxRetainedImages(count: Int) {
        context.appSettingsDataStore.edit {
            it[MAX_RETAINED_IMAGES] = count.coerceIn(MIN_RETAINED_IMAGES, MAX_RETAINED_IMAGES_LIMIT)
        }
    }
}
