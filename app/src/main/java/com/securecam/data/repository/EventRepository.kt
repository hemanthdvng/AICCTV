package com.securecam.data.repository

import com.securecam.data.local.LogDao
import com.securecam.data.local.SecurityLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class SecurityEvent(val type: String, val description: String, val confidence: Float, val videoPath: String? = null)

@Singleton
class EventRepository @Inject constructor(private val logDao: LogDao) {
    // ARCHITECTURE PATCH: High-capacity, non-blocking conduit.
    // Prevents UI socket locks from permanently deadlocking the AI pipeline.
    private val _securityEvents = MutableSharedFlow<SecurityEvent>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val securityEvents = _securityEvents.asSharedFlow()

    // ARCHITECTURE PATCH: Detached suspension lock to allow synchronous event pumping
    fun emitEvent(event: SecurityEvent) {
        _securityEvents.tryEmit(event)
        
        if (!event.description.contains("[SYSTEM]", ignoreCase = true)) {
            CoroutineScope(Dispatchers.IO).launch {
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