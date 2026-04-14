package com.securecam.core.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LlmVisionAnalyzer(private val context: Context) {
    private val TAG = "LlmVisionAnalyzer"
    private var engine: Engine? = null

    private val llmDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val llmScope = CoroutineScope(llmDispatcher + SupervisorJob())

    private val initialized = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    sealed class InitResult {
        object Success : InitResult()
        data class Error(val message: String) : InitResult()
        object ModelNotFound : InitResult()
    }

    fun cancelCurrentInference() {
        llmScope.coroutineContext.cancelChildren()
        busy.set(false)
    }

    fun close() {
        initialized.set(false)
        busy.set(false)
        try { engine?.close() } catch (e: Exception) {} finally { engine = null }
        System.gc()
    }

    @OptIn(ExperimentalApi::class)
    fun initialize(onResult: (InitResult) -> Unit) {
        llmScope.launch {
            initialized.set(false)
            busy.set(false)
            try { engine?.close() } catch (e: Exception) {} finally { engine = null }

            val modelFile = LlmModelManager.getInstalledModel(context)
            if (modelFile == null) {
                withContext(Dispatchers.Main) { onResult(InitResult.ModelNotFound) }
                return@launch
            }

            var backendType = "CPU"
            try {
                val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                backendType = prefs.getString("ai_backend", "CPU") ?: "CPU"

                // OPTION 4B: NPU falls back to CPU to prevent native SIGSEGV on unsupported chips
                val backendConfig = when (backendType) {
                    "GPU" -> Backend.GPU()
                    "NPU" -> Backend.CPU() 
                    else  -> Backend.CPU()
                }

                val cfg = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backendConfig,
                    visionBackend = backendConfig, // GPU is passed if selected
                    cacheDir = context.cacheDir.absolutePath
                )

                engine = Engine(cfg).also { it.initialize() }
                initialized.set(true)
                withContext(Dispatchers.Main) { onResult(InitResult.Success) }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { onResult(InitResult.Error("Init failed ($backendType): ${e.message}")) }
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    fun analyze(
        bitmap: Bitmap,
        systemPrompt: String,
        userPrompt: String,
        imgMaxDim: Int = 512,
        onToken: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!initialized.get()) { bitmap.recycle(); onError("Engine not initialized yet"); return }
        if (!busy.compareAndSet(false, true)) { bitmap.recycle(); onError("Engine is busy"); return }

        llmScope.launch {
            try {
                val eng = engine ?: throw IllegalStateException("Engine null")

                // OPTION 1A: Dropped native maxOutputTokens, relying on system prompt.
                val conversation = eng.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95f, temperature = 0.4f),
                        systemInstruction = Contents.of(systemPrompt)
                    )
                )

                val imageBytes = bitmap.toJpegBytes(maxDim = imgMaxDim)
                val contents = Contents.of(listOf(Content.ImageBytes(imageBytes), Content.Text(userPrompt)))

                val sb = StringBuilder()
                
                // BUG 3 FIX: Catch JNI exceptions on the flow stream
                conversation.sendMessageAsync(contents)
                    .catch { e -> withContext(Dispatchers.Main) { onError(e.message ?: "Stream error") } }
                    .collect { message -> sb.append(message.text ?: "") }

                // BUG 8 FIX: Close conversation before dispatching onDone to avoid cancellation race
                conversation.close()
                withContext(Dispatchers.Main) { onDone(sb.toString().trim()) }

            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Native Inference Error") }
            } finally {
                busy.set(false)
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun Bitmap.toJpegBytes(maxDim: Int = 512): ByteArray {
        val scale = if (maxOf(width, height) > maxDim) maxDim.toFloat() / maxOf(width, height) else 1f
        val scaled = if (scale < 1f)
            Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
        else this
        val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        if (scaled !== this) scaled.recycle() // Recycle scaled copy
        return bytes
    }
}