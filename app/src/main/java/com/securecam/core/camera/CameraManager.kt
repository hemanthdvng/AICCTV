package com.securecam.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import com.securecam.data.repository.AppPreferences
import kotlinx.coroutines.*

class CameraManager(private val context: Context, private val preferences: AppPreferences) {
    
    private var lastAnalyzedTime = 0L

    fun getTargetResolution(resolutionStr: String): Size {
        // Map the default 480p string to an actual Size object for CameraX / Camera2
        return when (resolutionStr) {
            "1080p" -> Size(1920, 1080)
            "720p" -> Size(1280, 720)
            "480p" -> Size(640, 480) // Applied 480p Default
            else -> Size(640, 480)
        }
    }

    fun configureCamera() {
        val liveRes = getTargetResolution(preferences.getLiveResolution())
        val videoRes = getTargetResolution(preferences.getOfflineResolution())
        
        // TODO: Apply liveRes to PreviewUseCase and videoRes to VideoCaptureUseCase
        println("Camera Configured: Live=$liveRes, Video=$videoRes")
    }

    fun onFrameCaptured(bitmap: Bitmap, onProcess: (Bitmap) -> Unit) {
        val currentTime = System.currentTimeMillis()
        val intervalMs = preferences.getFrameAnalysisInterval() // Defaults to 10000ms (10 sec)
        
        // Throttles frame analysis to 1 frame every 10 seconds
        if (currentTime - lastAnalyzedTime >= intervalMs) {
            lastAnalyzedTime = currentTime
            onProcess(bitmap)
        } else {
            bitmap.recycle() // Discard frame if within the 10-second window
        }
    }
}