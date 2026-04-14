package com.securecam.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LlmVisionAnalyzer(private val context: Context) {
    private val TAG = "LlmVisionAnalyzer"
    private var engine: Engine? = null
    // ARCHITECTURE FIX: Persist Conversation to prevent KV cache explosion per frame
    private var currentConversation: Conversation? = null
    private var lastSystemInstruction: String? = null

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
        // ARCHITECTURE FIX: Explicitly halt native C++ processing
        try { currentConversation?.cancelProcess() } catch (e: Exception) { Log.e(TAG, "Failed to cancel native process", e) }
        llmScope.coroutineContext.cancelChildren()
        busy.set(false)
    }

    fun close() {
        initialized.set(false)
        busy.set(false)
        try { currentConversation?.close() } catch (e: Exception) {} finally { currentConversation = null }
        try { engine?.close() } catch (e: Exception) {} finally { engine = null }
        System.gc()
    }

    @OptIn(ExperimentalApi::class)
    fun initialize(onResult: (InitResult) -> Unit) {
        llmScope.launch {
            initialized.set(false)
            busy.set(false)
            try { currentConversation?.close() } catch (e: Exception) {} finally { currentConversation = null }
            try { engine?.close() } catch (e: Exception) {} finally { engine = null }

            val modelFile = LlmModelManager.getInstalledModel(context)
            if (modelFile == null) {
                withContext(Dispatchers.Main) { onResult(InitResult.ModelNotFound) }
                return@launch
            }

            var backendType = "GPU"
            try {
                val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                backendType = prefs.getString("ai_backend", "GPU") ?: "GPU"

                val backendConfig = when (backendType) {
                    "GPU" -> Backend.GPU()
                    "NPU" -> Backend.CPU()
                    else  -> Backend.CPU()
                }

                // ARCHITECTURE FIX: Multimodal models heavily restrict Vision backend. GPU is strongly advised for Gemma.
                val visionConfig = Backend.GPU()

                val cfg = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backendConfig,
                    visionBackend = visionConfig,
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

                // ARCHITECTURE FIX: Create conversation only once or when the system rules change
                if (currentConversation == null || lastSystemInstruction != systemPrompt) {
                    currentConversation?.close()
                    currentConversation = eng.createConversation(
                        ConversationConfig(
                            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.4),
                            systemInstruction = Contents.of(systemPrompt)
                        )
                    )
                    lastSystemInstruction = systemPrompt
                }

                val conversation = currentConversation ?: throw IllegalStateException("Conversation null")
                val imageBytes = bitmap.toJpegBytes(maxDim = imgMaxDim)
                val contents = Contents.of(listOf(Content.ImageBytes(imageBytes), Content.Text(userPrompt)))
                val sb = java.lang.StringBuilder()

                // ARCHITECTURE FIX: Use proper modern LiteRT MessageCallback API to prevent deadlocks
                conversation.sendMessageAsync(
                    contents,
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val token = message.toString()
                            sb.append(token)
                            // Main thread dispatch if UI stream is needed: CoroutineScope(Dispatchers.Main).launch { onToken(token) }
                        }
                        override fun onDone() {
                            CoroutineScope(Dispatchers.Main).launch { 
                                onDone(sb.toString().trim()) 
                                busy.set(false)
                            }
                        }
                        override fun onError(throwable: Throwable) {
                            CoroutineScope(Dispatchers.Main).launch { 
                                if (throwable is java.util.concurrent.CancellationException) {
                                    onError("Inference Cancelled")
                                } else {
                                    onError(throwable.message ?: "Inference error")
                                }
                                busy.set(false)
                            }
                        }
                    },
                    emptyMap()
                )
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Native Inference Error") }
                busy.set(false)
            } finally {
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
        if (scaled !== this) scaled.recycle()
        return bytes
    }
}