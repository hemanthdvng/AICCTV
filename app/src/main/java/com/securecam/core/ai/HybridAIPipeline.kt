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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridAIPipeline @Inject constructor(@ApplicationContext private val context: Context, private val eventRepository: EventRepository) {
    private val aiDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val aiScope = CoroutineScope(aiDispatcher + SupervisorJob())
    private val llmAnalyzer = LlmVisionAnalyzer(context)
    private val biometricEngine = BiometricEngine(context)
    
    private val isLlmBusy = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    companion object {
        var activeVideoPath: String? = null
        var activeVideoEndTime: Long = 0L
    }

    fun start() {
        isRunning.set(true)
        llmAnalyzer.initialize {}
        aiScope.launch { try { biometricEngine.initialize() } catch (e: Exception) {} }
    }
    
    fun stop() { 
        isRunning.set(false)
        aiScope.coroutineContext[Job]?.cancelChildren()
        
        try { llmAnalyzer.close() } catch (e: Exception) {}
        try { biometricEngine.close() } catch (e: Exception) {}
        isLlmBusy.set(false) 
    }

    fun processFrame(bitmap: Bitmap) {
        if (!isRunning.get()) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        
        aiScope.launch {
            try {
                val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                var skipLlm = false
                
                if (prefs.getBoolean("face_recog_enabled", false)) {
                    try {
                        val inputImage = InputImage.fromBitmap(bitmap, 0)
                        val facesList = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()).process(inputImage).await()
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
                                            if (biometricEngine.calculateCosineSimilarity(currentFaceVector, face.vector) > 0.65f) { recognizedNames.add(face.name); break }
                                        }
                                    }
                                    croppedFace.recycle()
                                }
                                if (recognizedNames.isNotEmpty()) {
                                    val namesList = recognizedNames.joinToString(", ")
                                    
                                    val now = System.currentTimeMillis()
                                    val recordLenMs = (prefs.getFloat("video_record_len", 8f) * 1000).toLong()
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
                
                if (skipLlm || !prefs.getBoolean("llm_enabled", true) || isLlmBusy.get()) { 
                    bitmap.recycle()
                    return@launch 
                }
                triggerLlmAnalysis(bitmap)
            } catch (e: Throwable) { 
                bitmap.recycle() 
            }
        }
    }

    private suspend fun triggerLlmAnalysis(bitmap: Bitmap) {
        if (!isLlmBusy.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }
        
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        val confThreshold = prefs.getFloat("confidence_threshold", 0.60f)
        val customPrompt = prefs.getString("prompt_usr", "Report EXACTLY with YES if you see a clock else reply EXACTLY with CLEAR.") ?: ""
        val recordLenMs = (prefs.getFloat("video_record_len", 8f) * 1000).toLong()
        val llmResolution = prefs.getInt("llm_resolution", 1120)

        try {
            withTimeoutOrNull(15000L) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val sysPrompt = "You are a direct computer vision evaluator. Process this image using a token budget of $llmResolution tokens."
                    
                    val maxDim = if (llmResolution > 500) 1024 else 512
                    val scaledBmp = resizeKeepAspect(bitmap, maxDim)

                    llmAnalyzer.analyze(
                        bitmap = scaledBmp, 
                        systemPrompt = sysPrompt, 
                        userPrompt = customPrompt, 
                        onToken = { },
                        onDone = { text -> 
                            val output = text.trim()
                            val isSafe = output.contains("CLEAR", ignoreCase = true)
                            
                            aiScope.launch {
                                val now = System.currentTimeMillis()
                                if (!isSafe) {
                                    if (now > activeVideoEndTime) {
                                        activeVideoPath = "alert_${now}.mp4"
                                    }
                                    activeVideoEndTime = now + recordLenMs
                                    prefs.edit().putString("active_dvr_file", activeVideoPath).apply()
                                }

                                val vidPath = if (isSafe) null else activeVideoPath
                                val finalDesc = if (isSafe) "🔍 SCAN: Safe / No Trigger found" else "🚨 $output"
                                eventRepository.emitEvent(SecurityEvent("LLM_INSIGHT", finalDesc, confThreshold, vidPath))
                            }
                            if (scaledBmp != bitmap) scaledBmp.recycle()
                            if (continuation.isActive) continuation.resume(true)
                        },
                        onError = { 
                            if (scaledBmp != bitmap) scaledBmp.recycle()
                            if (continuation.isActive) continuation.resume(false) 
                        }
                    )
                }
            }
        } finally { 
            isLlmBusy.set(false)
            if (!bitmap.isRecycled) bitmap.recycle() 
        }
    }

    private fun resizeKeepAspect(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}