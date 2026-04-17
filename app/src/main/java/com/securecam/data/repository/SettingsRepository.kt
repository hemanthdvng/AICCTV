package com.securecam.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "securecam_settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    
    private val dataStore = context.dataStore

    companion object {
        val LLM_ENABLED = booleanPreferencesKey("llm_enabled")
        val FACE_RECOG_ENABLED = booleanPreferencesKey("face_recog_enabled")
        val INTRUDER_MODE = booleanPreferencesKey("intruder_mode")
        val MOTION_SENSITIVITY = intPreferencesKey("motion_sensitivity")
        val USE_BACK_CAMERA = booleanPreferencesKey("use_back_camera")
    }

    val isLlmEnabled: Flow<Boolean> = dataStore.data.map { it[LLM_ENABLED] ?: true }
    val isFaceRecogEnabled: Flow<Boolean> = dataStore.data.map { it[FACE_RECOG_ENABLED] ?: true }
    val isIntruderModeEnabled: Flow<Boolean> = dataStore.data.map { it[INTRUDER_MODE] ?: false }
    val motionSensitivity: Flow<Int> = dataStore.data.map { it[MOTION_SENSITIVITY] ?: 50 }
    val useBackCamera: Flow<Boolean> = dataStore.data.map { it[USE_BACK_CAMERA] ?: true }

    suspend fun setLlmEnabled(enabled: Boolean) {
        dataStore.edit { it[LLM_ENABLED] = enabled }
    }

    suspend fun setFaceRecogEnabled(enabled: Boolean) {
        dataStore.edit { it[FACE_RECOG_ENABLED] = enabled }
    }
    
    suspend fun toggleCamera() {
        dataStore.edit { it[USE_BACK_CAMERA] = !(it[USE_BACK_CAMERA] ?: true) }
    }
}