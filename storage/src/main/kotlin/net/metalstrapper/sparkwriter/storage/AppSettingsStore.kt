package net.metalstrapper.sparkwriter.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore by preferencesDataStore("spark_writer_settings")

private val LOCAL_ZIP_IMPORT_ENABLED = booleanPreferencesKey("local_zip_import_enabled")

class AppSettingsStore(private val context: Context) {
    // Local ZIP packages are not publisher-verified, so import stays off until the user opts in.
    val localZipImportEnabled: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[LOCAL_ZIP_IMPORT_ENABLED] ?: false }

    suspend fun setLocalZipImportEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[LOCAL_ZIP_IMPORT_ENABLED] = enabled }
    }
}
