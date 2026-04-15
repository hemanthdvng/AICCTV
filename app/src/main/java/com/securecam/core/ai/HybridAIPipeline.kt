package com.securecam.core.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import com.securecam.data.repository.EventRepository
import com.securecam.data.repository.SecurityEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridAIPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventRepository: EventRepository
) {
    private val aiDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val aiScope = CoroutineScope(aiDispatcher + SupervisorJob())
    private val llmAnalyzer = LlmVisionAnalyzer(context)
    private val biometricEngine = BiometricEngine(context)
    private var isLlmBusy = false

    companion object {
        var activeVideoPath: String? = null
        var activeVideoEndTime: Long = 0L
    }

    fun start() {
        llmAnalyzer.initialize { result ->
            if (result is LlmVisionAnalyzer.InitResult.Error) {
                aiScope.launch { eventRepository.emitEvent(SecurityEvent("SYSTEM_ERROR", "🚨 LLM Init Failed: ${result.message}", 1.0f)) }
            } else if (result is LlmVisionAnalyzer.InitResult.ModelNotFound) {
                aiScope.launch { eventRepository.emitEvent(SecurityEvent("SYSTEM_ERROR", "🚨 LLM Model Not Found. Go to Settings and import/download the model.", 1.0f)) }
            }
        }
        aiScope.launch { 
            try { biometricEngine.initialize() } 
            catch (e: Exception) { eventRepository.emitEvent(SecurityEvent("SYSTEM_ERROR", "🚨 Face Rec Init Failed: ${e.message}", 1.0f)) } 
        }
    }

    fun stop() {
        llmAnalyzer.close()
        try { biometricEngine.close() } catch (e: Exception) {}
        isLlmBusy = false
    }

    fun isBusy(): Boolean = isLlmBusy

    fun processFrame(bitmap: Bitmap) {
        if (isLlmBusy) { bitmap.recycle(); return }
        isLlmBusy = true
        
        aiScope.launch {
            try {
                val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                var skipLlm = false

                if (prefs.getBoolean("face_recog_enabled", false)) {
                    try {
                        val inputImage = InputImage.fromBitmap(bitmap, 0)
                        val facesList = FaceDetection.getClient(
                            FaceDetectorOptions.Builder()
                                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                                .build()
                        ).process(inputImage).await()

                        if (facesList.isNotEmpty()) {
                            val savedFacesJson = prefs.getString("authorized_faces", "[]") ?: "[]"
                            val type = object : TypeToken<List<RegisteredFace>>() {}.type
                            val savedFaces: List<RegisteredFace> = Gson().fromJson(savedFacesJson, type) ?: emptyList()
                            val recognizedNames = mutableSetOf<String>()

                            if (savedFaces.isNotEmpty()) {
                                for (mlFace in facesList) {
                                    val bounds = mlFace.boundingBox
                                    val size = maxOf(bounds.width(), bounds.height())
                                    val left = (bounds.centerX() - size / 2).coerceAtLeast(0)
                                    val top = (bounds.centerY() - size / 2).coerceAtLeast(0)
                                    val w = minOf(size, bitmap.width - left).coerceAtLeast(1)
                                    val h = minOf(size, bitmap.height - top).coerceAtLeast(1)
                                    val croppedFace = Bitmap.createBitmap(bitmap, left, top, w, h)
                                    val currentFaceVector = biometricEngine.getFaceEmbedding(croppedFace)
                                    if (currentFaceVector != null) {
                                        for (face in savedFaces) {
                                            if (biometricEngine.calculateCosineSimilarity(currentFaceVector, face.vector) > 0.65f) {
                                                recognizedNames.add(face.name); break
                                            }
                                        }
                                    }
                                    croppedFace.recycle()
                                }
                                if (recognizedNames.isNotEmpty()) {
                                    val namesList = recognizedNames.joinToString(", ")
                                    val now = System.currentTimeMillis()
                                    val recordLenMs = (prefs.getFloat("video_record_len", 15f) * 1000).toLong()
                                    if (now > activeVideoEndTime) { activeVideoPath = "face_${now}.mp4" }
                                    activeVideoEndTime = now + recordLenMs
                                    prefs.edit().putString("active_dvr_file", activeVideoPath).apply()
                                    eventRepository.emitEvent(SecurityEvent("BIOMETRIC", "🛡️ Authorized Face(s) Detected: $namesList", 1.0f, activeVideoPath))
                                    skipLlm = true
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }

                if (skipLlm || !prefs.getBoolean("llm_enabled", true)) {
                    isLlmBusy = false
                    bitmap.recycle(); return@launch
                }
                triggerLlmAnalysis(bitmap)
            } catch (e: Throwable) { 
                isLlmBusy = false
                bitmap.recycle() 
            }
        }
    }

    private suspend fun triggerLlmAnalysis(bitmap: Bitmap) {
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        
        // WATCHDOG: Detect if the C++ engine crashed on the previous run
        if (prefs.getBoolean("ai_native_crash_flag", false)) {
            prefs.edit()
                .putBoolean("llm_enabled", false)
                .putBoolean("ai_native_crash_flag", false)
                .commit()
            
            aiScope.launch { 
                eventRepository.emitEvent(SecurityEvent(
                    "SYSTEM_ERROR", 
                    "🚨 Fatal AI Crash Detected! The local model is likely corrupted or missing image capabilities. LLM auto-disabled to prevent bootloops.", 
                    1.0f
                )) 
            }
            isLlmBusy = false
            bitmap.recycle()
            return
        }

        val confThreshold = prefs.getFloat("confidence_threshold", 0.60f)
        val customPrompt = prefs.getString("prompt_usr", "Report if you see a clock. If you do not see it, reply EXACTLY with CLEAR.") ?: ""
        val recordLenMs = (prefs.getFloat("video_record_len", 15f) * 1000).toLong()
        val llmResolution = prefs.getInt("llm_resolution", 280)
        val aiImgResolution = prefs.getInt("ai_img_resolution", 512)

        try {
            // ARM THE WATCHDOG: Force write to physical storage before hitting C++ native code
            prefs.edit().putBoolean("ai_native_crash_flag", true).commit()

            val timedOut = withTimeoutOrNull(15000L) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val sysPrompt = "You are a concise security camera AI. Analyze the image and respond in $llmResolution tokens or fewer."
                    llmAnalyzer.analyze(
                        bitmap = bitmap,
                        systemPrompt = sysPrompt,
                        userPrompt = customPrompt,
                        imgMaxDim = aiImgResolution,
                        onToken = { },
                        onDone = { text ->
                            // DISARM THE WATCHDOG: C++ inference completed safely
                            prefs.edit().putBoolean("ai_native_crash_flag", false).commit()
                            
                            val output = text.trim()
                            val isSafe = output.contains("CLEAR", ignoreCase = true)
                            aiScope.launch {
                                val now = System.currentTimeMillis()
                                if (!isSafe) {
                                    if (now > activeVideoEndTime) { activeVideoPath = "alert_${now}.mp4" }
                                    activeVideoEndTime = now + recordLenMs
                                    prefs.edit().putString("active_dvr_file", activeVideoPath).apply()
                                }
                                val vidPath = if (isSafe) null else activeVideoPath
                                val finalDesc = if (isSafe) "🔍 SCAN: Safe / No Trigger found" else "🚨 $output"
                                eventRepository.emitEvent(SecurityEvent("LLM_INSIGHT", finalDesc, confThreshold, vidPath))
                            }
                            if (continuation.isActive) continuation.resume(true)
                        },
                        onError = { errorMsg -> 
                            // DISARM THE WATCHDOG: Inference failed but caught safely in Kotlin
                            prefs.edit().putBoolean("ai_native_crash_flag", false).commit()
                            
                            aiScope.launch { eventRepository.emitEvent(SecurityEvent("SYSTEM_ERROR", "🚨 AI Engine Failed: $errorMsg", 1.0f)) }
                            if (continuation.isActive) continuation.resume(false) 
                        }
                    )
                }
            }
            if (timedOut == null) {
                // DISARM THE WATCHDOG: Timeout
                prefs.edit().putBoolean("ai_native_crash_flag", false).commit()
                llmAnalyzer.cancelCurrentInference()
                aiScope.launch { eventRepository.emitEvent(SecurityEvent("SYSTEM_ERROR", "🚨 AI Engine Timed Out (15s)", 1.0f)) }
            }
        } catch (e: Exception) {
            // DISARM THE WATCHDOG: General exception
            prefs.edit().putBoolean("ai_native_crash_flag", false).commit()
        } finally {
            isLlmBusy = false
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}