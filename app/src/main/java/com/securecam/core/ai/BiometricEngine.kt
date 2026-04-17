package com.securecam.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

data class RegisteredFace(val id: String, val name: String, val vector: FloatArray)

class BiometricEngine(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val IMAGE_SIZE = 112
    private val EMBEDDING_SIZE = 192

    suspend fun initialize() = withContext(Dispatchers.IO) {
        // Copy bundled asset to cacheDir on first run for reliable Interpreter loading
        val modelFile = File(context.cacheDir, "mobilefacenet.tflite")
        if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
            context.assets.open("mobilefacenet.tflite").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val options = Interpreter.Options().apply { numThreads = 4 }
        interpreter = Interpreter(modelFile, options)
    }

    suspend fun getFaceEmbedding(bitmap: Bitmap): FloatArray? = withContext(Dispatchers.Default) {
        if (interpreter == null) return@withContext null
        
        val swBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: bitmap
        val scaledBitmap = Bitmap.createScaledBitmap(swBitmap, IMAGE_SIZE, IMAGE_SIZE, false)
        
        try {
            val byteBuffer = ByteBuffer.allocateDirect(4 * IMAGE_SIZE * IMAGE_SIZE * 3)
            byteBuffer.order(ByteOrder.nativeOrder())
            
            val intValues = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            scaledBitmap.getPixels(intValues, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)
            
            var pixel = 0
            for (i in 0 until IMAGE_SIZE) {
                for (j in 0 until IMAGE_SIZE) {
                    val `val` = intValues[pixel++]
                    byteBuffer.putFloat((((`val` shr 16) and 0xFF) - 127.5f) / 128.0f)
                    byteBuffer.putFloat((((`val` shr 8) and 0xFF) - 127.5f) / 128.0f)
                    byteBuffer.putFloat(((`val` and 0xFF) - 127.5f) / 128.0f)
                }
            }
            
            val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
            interpreter?.run(byteBuffer, output)
            return@withContext output[0]
        } finally {
            if (swBitmap != bitmap && !swBitmap.isRecycled) swBitmap.recycle()
            if (!scaledBitmap.isRecycled) scaledBitmap.recycle()
        }
    }

    fun calculateCosineSimilarity(emb1: FloatArray, emb2: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in emb1.indices) {
            dotProduct += emb1[i] * emb2[i]
            normA += emb1[i] * emb1[i]
            normB += emb2[i] * emb2[i]
        }
        return (dotProduct / (sqrt(normA.toDouble()) * sqrt(normB.toDouble()))).toFloat()
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}