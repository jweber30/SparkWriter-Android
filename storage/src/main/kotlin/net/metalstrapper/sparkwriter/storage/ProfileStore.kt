package net.metalstrapper.sparkwriter.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.profileDataStore by preferencesDataStore("spark_writer_profile")

class ProfileStore(private val context: Context) {
    fun observe(standardField: String): Flow<String> {
        val key = stringPreferencesKey(standardField)
        return context.profileDataStore.data.map { it[key].orEmpty() }
    }

    suspend fun load(standardField: String): String {
        val key = stringPreferencesKey(standardField)
        return context.profileDataStore.data.first()[key].orEmpty()
    }

    suspend fun loadAll(standardFields: Collection<String>): Map<String, String> {
        val keys = standardFields.associateWith(::stringPreferencesKey)
        val preferences = context.profileDataStore.data.first()
        return keys.mapValues { (_, key) -> preferences[key].orEmpty() }
    }

    suspend fun save(standardField: String, value: String) {
        val key = stringPreferencesKey(standardField)
        context.profileDataStore.edit { it[key] = value }
    }

    suspend fun saveAll(values: Map<String, String>) {
        context.profileDataStore.edit { preferences ->
            values.forEach { (standardField, value) ->
                preferences[stringPreferencesKey(standardField)] = value
            }
        }
    }

    suspend fun remove(standardField: String) {
        val key = stringPreferencesKey(standardField)
        context.profileDataStore.edit { it.remove(key) }
    }
}
