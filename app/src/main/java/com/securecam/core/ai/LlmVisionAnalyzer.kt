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

                val backendConfig = when (backendType) {
                    "GPU" -> Backend.GPU()
                    "NPU" -> Backend.GPU()
                    else  -> Backend.CPU()
                }

                val cfg = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backendConfig,
                    visionBackend = backendConfig,
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
            var scaled: Bitmap? = null
            try {
                val eng = engine ?: throw IllegalStateException("Engine null")

                val conversation = eng.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(
                            topK = 40,
                            topP = 0.95,
                            temperature = 0.4
                        ),
                        systemInstruction = Contents.of(systemPrompt)
                    )
                )

                // FIX: Use proper Content.image() parsing expected by the vision-enabled model
                val scale = if (maxOf(bitmap.width, bitmap.height) > imgMaxDim) imgMaxDim.toFloat() / maxOf(bitmap.width, bitmap.height) else 1f
                scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
                
                val contents = Contents.of(listOf(Content.image(scaled), Content.Text(userPrompt)))

                val sb = StringBuilder()
                conversation.sendMessageAsync(contents).collect { message ->
                    sb.append(message.text)
                }

                withContext(Dispatchers.Main) { onDone(sb.toString().trim()) }
                conversation.close()

            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Native Inference Error") }
            } finally {
                busy.set(false)
                if (scaled != null && scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }
}