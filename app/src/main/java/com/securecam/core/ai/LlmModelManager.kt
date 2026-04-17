package com.securecam.core.ai

import android.content.Context
import java.io.File

object LlmModelManager {
    fun getInstalledModel(context: Context): File? {
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        val modelName = prefs.getString("selected_model", "None")
        if (modelName == null || modelName == "None") return null
        
        val modelFile = File(context.filesDir, modelName)
        return if (modelFile.exists()) modelFile else null
    }
}