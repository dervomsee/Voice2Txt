package de.dervomsee.voice2txt.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import de.dervomsee.voice2txt.whisper.availableModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {

    companion object {
        val SELECTED_MODEL_FILE = stringPreferencesKey("selected_model_file")
        val SELECTED_LANGUAGE = stringPreferencesKey("selected_language")
        val USE_GPU = booleanPreferencesKey("use_gpu")
    }

    val selectedModelFile: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_MODEL_FILE] ?: availableModels[1].fileName // Default to Tiny Q8_0
    }

    val selectedLanguage: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_LANGUAGE] ?: "de"
    }

    val useGpu: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[USE_GPU] ?: false
    }

    suspend fun setSelectedModelFile(fileName: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_MODEL_FILE] = fileName
        }
    }

    suspend fun setSelectedLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_LANGUAGE] = language
        }
    }

    suspend fun setUseGpu(useGpu: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[USE_GPU] = useGpu
        }
    }
}
