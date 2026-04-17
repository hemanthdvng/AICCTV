package com.securecam.data.repository

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)

    fun initializeDefaults() {
        val editor = prefs.edit()
        
        // --- NEW DEFAULTS APPLIED HERE ---
        if (!prefs.contains("live_camera_res")) {
            editor.putString("live_camera_res", "480p")
        }
        if (!prefs.contains("offline_video_res")) {
            editor.putString("offline_video_res", "480p")
        }
        if (!prefs.contains("frame_analysis_interval_ms")) {
            editor.putLong("frame_analysis_interval_ms", 10000L) // 1 frame every 10 seconds
        }
        // ---------------------------------
        
        editor.apply()
    }
    
    fun getLiveResolution(): String = prefs.getString("live_camera_res", "480p") ?: "480p"
    fun getOfflineResolution(): String = prefs.getString("offline_video_res", "480p") ?: "480p"
    fun getFrameAnalysisInterval(): Long = prefs.getLong("frame_analysis_interval_ms", 10000L)
}