package com.securecam.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
    
    private val isLlmBusy = AtomicBoolean(false)
    private val isStarted = AtomicBoolean(false)

    val activeVideoPath = AtomicReference<String?>(null)
    val activeVideoEndTime = AtomicLong(0L)

    private var lastLlmAnalysisTime = 0L
    private var firstFrameLogged = false
    private var firstSubmissionLogged = false

    private val prefs by lazy { context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE) }
    
    private val faceDetector by lazy { 
        FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
    }
    
    private var cachedFaces: List<RegisteredFace> = emptyList()
    private var cachedFacesJson: String = ""

    fun start() {
        if (isStarted.getAndSet(true)) return 
        Log.d("AILOGS", "Starting HybridAIPipeline")
        
        llmAnalyzer.initialize { result ->
            when (result) {
                is LlmVisionAnalyzer.InitResult.ModelNotFound -> 
                    aiScope.launch { eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] LLM model not found. Download from Settings.", 1.0f)) }
                is LlmVisionAnalyzer.InitResult.Error -> 
                    aiScope.launch { eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] LLM init failed: ${result.message}", 1.0f)) }
                LlmVisionAnalyzer.InitResult.Success -> { 
                    Log.d("AILOGS", "LLM Engine Online.")
                    aiScope.launch { eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] LLM Engine Online and Ready.", 1.0f)) }
                }
            }
        }
        aiScope.launch { try { biometricEngine.initialize() } catch (e: Exception) {} }
    }

    fun stop() {
        Log.d("AILOGS", "Stopping HybridAIPipeline")
        llmAnalyzer.close()
        try { biometricEngine.close() } catch (e: Exception) {}
        try { faceDetector.close() } catch (e: Exception) {}
        isLlmBusy.set(false)
        isStarted.set(false)
        firstFrameLogged = false
        firstSubmissionLogged = false
    }

    fun isBusy(): Boolean = isLlmBusy.get()

    fun processFrame(bitmap: Bitmap) {
        if (!firstFrameLogged) {
            firstFrameLogged = true
            aiScope.launch { eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] DIAGNOSTIC: Pipeline is actively receiving frames from Camera.", 1.0f)) }
        }
        
        Log.d("AILOGS", "processFrame invoked. LLM Busy = ${isLlmBusy.get()}")

        aiScope.launch {
            try {
                var skipLlm = false

                if (prefs.getBoolean("face_recog_enabled", false)) {
                    Log.d("AILOGS", "Executing Face Detection await...")
                    try {
                        val inputImage = InputImage.fromBitmap(bitmap, 0)
                        val facesList = faceDetector.process(inputImage).await()
                        Log.d("AILOGS", "Face Detection finished. Found: ${facesList.size}")

                        if (facesList.isNotEmpty()) {
                            val json = prefs.getString("authorized_faces", "[]") ?: "[]"
                            if (json != cachedFacesJson) {
                                cachedFacesJson = json
                                val type = object : TypeToken<List<RegisteredFace>>() {}.type
                                cachedFaces = Gson().fromJson(json, type) ?: emptyList()
                            }
                            
                            val recognizedNames = mutableSetOf<String>()

                            if (cachedFaces.isNotEmpty()) {
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
                                        for (face in cachedFaces) {
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
                                    if (now > activeVideoEndTime.get()) { activeVideoPath.set("face_${now}.mp4") }
                                    activeVideoEndTime.set(now + recordLenMs)
                                    prefs.edit().putString("active_dvr_file", activeVideoPath.get()).apply()
                                    eventRepository.emitEvent(SecurityEvent("BIOMETRIC", "🛡️ Authorized Face(s) Detected: $namesList", 1.0f, activeVideoPath.get()))
                                    skipLlm = true
                                }
                            }
                        }
                    } catch (e: Exception) { Log.e("AILOGS", "Face detection crashed", e) }
                }

                val llmEnabled = prefs.getBoolean("llm_enabled", true)
                if (skipLlm || !llmEnabled || isLlmBusy.get()) {
                    Log.d("AILOGS", "Dropping frame. skipLlm=$skipLlm, enabled=$llmEnabled, busy=${isLlmBusy.get()}")
                    bitmap.recycle(); return@launch
                }

                val analysisIntervalSec = try {
                    prefs.getFloat("llm_analysis_interval", 5f)
                } catch (e: ClassCastException) {
                    prefs.getInt("llm_analysis_interval", 5).toFloat()
                }
                
                val analysisIntervalMs = (analysisIntervalSec * 1000).toLong()
                val now = System.currentTimeMillis()
                val timeSinceLast = now - lastLlmAnalysisTime

                if (timeSinceLast < analysisIntervalMs) {
                    Log.d("AILOGS", "Dropping frame for interval. Wait: $timeSinceLast / $analysisIntervalMs")
                    bitmap.recycle(); return@launch
                }

                Log.d("AILOGS", "FRAME ACCEPTED. Triggering LLM.")
                if (!firstSubmissionLogged) {
                    firstSubmissionLogged = true
                    aiScope.launch { eventRepository.emitEvent(SecurityEvent("SYSTEM", "[SYSTEM] DIAGNOSTIC: Submitting first image to LLM engine.", 1.0f)) }
                }

                lastLlmAnalysisTime = System.currentTimeMillis()
                triggerLlmAnalysis(bitmap)

            } catch (e: Throwable) { 
                Log.e("AILOGS", "Frame processing error", e)
                bitmap.recycle() 
            }
        }
    }

    private suspend fun triggerLlmAnalysis(bitmap: Bitmap) {
        isLlmBusy.set(true)
        val customPrompt = prefs.getString("prompt_usr", "Report if you see a clock. If you do not see it, reply EXACTLY with CLEAR.") ?: ""
        
        val recordLenMs = try { prefs.getFloat("video_record_len", 15f).toLong() * 1000 } catch(e: Exception){ 15000L }
        val llmResolution = try { prefs.getInt("llm_resolution", 280) } catch(e:Exception){ 280 }
        val aiImgResolution = try { prefs.getInt("ai_img_resolution", 512) } catch(e:Exception){ 512 }

        try {
            val timedOut = withTimeoutOrNull(25000L) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val sysPrompt = "You are a concise security camera AI. Analyze the image and respond in $llmResolution tokens or fewer."
                    Log.d("AILOGS", "llmAnalyzer.analyze() called. Awaiting callback...")
                    llmAnalyzer.analyze(
                        bitmap = bitmap,
                        systemPrompt = sysPrompt,
                        userPrompt = customPrompt,
                        imgMaxDim = aiImgResolution,
                        onToken = { },
                        onDone = { text ->
                            Log.d("AILOGS", "LLM Response Received: $text")
                            val output = text.trim()
                            val isSafe = output.contains("CLEAR", ignoreCase = true) || output.isEmpty()
                            aiScope.launch {
                                val now = System.currentTimeMillis()
                                if (!isSafe) {
                                    if (now > activeVideoEndTime.get()) { activeVideoPath.set("alert_${now}.mp4") }
                                    activeVideoEndTime.set(now + recordLenMs)
                                    prefs.edit().putString("active_dvr_file", activeVideoPath.get()).apply()
                                }
                                val vidPath = if (isSafe) null else activeVideoPath.get()
                                val finalDesc = if (isSafe) "🔍 SCAN: Safe / No Trigger found" else "🚨 $output"
                                eventRepository.emitEvent(SecurityEvent("LLM_INSIGHT", finalDesc, 1.0f, vidPath))
                            }
                            if (continuation.isActive) continuation.resume(true)
                        },
                        onError = { err ->
                            Log.e("AILOGS", "LLM Native Error: $err")
                            if (!err.contains("busy")) aiScope.launch { eventRepository.emitEvent(SecurityEvent("SYSTEM", "⚠️ [SYSTEM] LLM Error: $err", 1.0f)) }
                            if (continuation.isActive) continuation.resume(false)
                        }
                    )
                }
            }
            if (timedOut == null) {
                Log.e("AILOGS", "LLM INFERENCE TIMED OUT AFTER 25s")
                aiScope.launch { eventRepository.emitEvent(SecurityEvent("SYSTEM", "⚠️ [SYSTEM] Inference Timed Out (25s). Restarting native engine...", 1.0f)) }
                llmAnalyzer.cancelCurrentInference()
            }
        } finally {
            Log.d("AILOGS", "Releasing isLlmBusy lock.")
            isLlmBusy.set(false)
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}