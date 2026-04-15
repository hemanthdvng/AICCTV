package com.securecam.core.ai

import android.content.Context
import android.graphics.Bitmap
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
    private var currentConversation: Conversation? = null
    private var lastSystemPrompt: String = ""

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
        try { currentConversation?.cancelProcess() } catch (e: Exception) {}
        llmScope.coroutineContext.cancelChildren()
        busy.set(false)
    }

    fun close() {
        initialized.set(false)
        busy.set(false)
        try { currentConversation?.close() } catch(e: Exception){} finally { currentConversation = null }
        try { engine?.close() } catch (e: Exception) {} finally { engine = null }
        System.gc()
    }

    @OptIn(ExperimentalApi::class)
    fun initialize(onResult: (InitResult) -> Unit) {
        llmScope.launch {
            initialized.set(false)
            busy.set(false)
            try { currentConversation?.close() } catch(e: Exception){} finally { currentConversation = null }
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
                    "NPU" -> Backend.CPU() 
                    else  -> Backend.CPU()
                }

                val cfg = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backendConfig,
                    visionBackend = Backend.GPU(),
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

                if (currentConversation == null || lastSystemPrompt != systemPrompt) {
                    currentConversation?.close()
                    currentConversation = eng.createConversation(
                        ConversationConfig(
                            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.4),
                            systemInstruction = Contents.of(systemPrompt)
                        )
                    )
                    lastSystemPrompt = systemPrompt
                }
                
                val conversation = currentConversation ?: throw IllegalStateException("Conversation null")
                val imageBytes = bitmap.toJpegBytes(maxDim = imgMaxDim)
                val contents = Contents.of(listOf(Content.ImageBytes(imageBytes), Content.Text(userPrompt)))
                val sb = java.lang.StringBuilder()
                
                conversation.sendMessageAsync(
                    contents,
                    object : MessageCallback {
                        override fun onMessage(message: Message) { sb.append(message.toString()) }
                        override fun onDone() {
                            CoroutineScope(Dispatchers.Main).launch {
                                onDone(sb.toString().trim())
                                busy.set(false)
                            }
                        }
                        override fun onError(throwable: Throwable) {
                            CoroutineScope(Dispatchers.Main).launch {
                                onError(throwable.message ?: "Native Inference Error")
                                busy.set(false)
                            }
                        }
                    },
                    emptyMap()
                )
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Native Inference Exception") }
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