package com.securecam.data.repository

import com.securecam.data.local.LogDao
import com.securecam.data.local.SecurityLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class SecurityEvent(val type: String, val description: String, val confidence: Float, val videoPath: String? = null)

@Singleton
class EventRepository @Inject constructor(private val logDao: LogDao) {
    private val _securityEvents = MutableSharedFlow<SecurityEvent>(replay = 1)
    val securityEvents = _securityEvents.asSharedFlow()

    suspend fun emitEvent(event: SecurityEvent) {
        _securityEvents.emit(event)
        if (!event.description.contains("[SYSTEM]", ignoreCase = true)) {
            // BUG 6 FIX: Replaced scope leak with context switch to write to DB
            withContext(Dispatchers.IO) {
                try {
                    logDao.insertLog(
                        SecurityLogEntity(
                            logTime = System.currentTimeMillis(),
                            type = event.type,
                            description = event.description,
                            confidence = event.confidence,
                            videoPath = event.videoPath
                        )
                    )
                } catch (e: Exception) {}
            }
        }
    }
}