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

    // FIX #6: Cancel running inference (e.g. on timeout) without destroying the scope
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

                // FIX #4: GPU is the fast path. NPU maps to GPU since NPU requires
                // nativeLibraryDir which we don't have, and CPU is the safe fallback.
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

    // FIX #2: imgMaxDim controls pixel size sent to AI (default 512, not 1920).
    // FIX #11: Token budget passed as prompt text — the only mechanism the litertlm
    //          Kotlin API exposes (SamplerConfig has no maxOutputTokens parameter).
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

                // FIX #3+#4 (compile errors):
                //   topP and temperature must be Double (not Float) per the litertlm API.
                //   SamplerConfig has NO maxOutputTokens — only topK, topP, temperature.
                val conversation = eng.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(
                            topK = 40,
                            topP = 0.95,       // Double — not 0.95f
                            temperature = 0.4  // Double — not 0.4f
                        ),
                        systemInstruction = Contents.of(systemPrompt)
                    )
                )

                // FIX #2: Scale to imgMaxDim before encoding.
                val imageBytes = bitmap.toJpegBytes(maxDim = imgMaxDim)
                val contents = Contents.of(listOf(Content.ImageBytes(imageBytes), Content.Text(userPrompt)))

                val sb = StringBuilder()
                // FIX (compile error line 133): Message has no .text property.
                // Official pattern per Google docs: message.toString() returns the generated text.
                conversation.sendMessageAsync(contents).collect { message ->
                    sb.append(message.toString())
                }

                withContext(Dispatchers.Main) { onDone(sb.toString().trim()) }
                conversation.close()

            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { onError(e.message ?: "Native Inference Error") }
            } finally {
                busy.set(false)
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    // FIX #5: Recycle the scaled bitmap copy to prevent memory leak on every inference call
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