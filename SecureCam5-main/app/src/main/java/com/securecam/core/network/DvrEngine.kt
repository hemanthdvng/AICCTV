package com.securecam.core.network

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.view.Surface
import java.io.File

class DvrEngine(private val context: Context) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var surface: Surface? = null
    private var trackIndex = -1
    private var isRecording = false
    private var muxerStarted = false
    private var frameCount = 0L

    fun triggerRecording(fileName: String, width: Int, height: Int) {
        if (isRecording) return
        try {
            val file = File(context.filesDir, fileName)
            if (file.exists()) file.delete()
            
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 3000000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 5)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec?.createInputSurface()
            codec?.start()
            isRecording = true
            muxerStarted = false
            frameCount = 0L
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun appendFrame(bitmap: Bitmap) {
        if (!isRecording || surface == null) return
        try {
            // CRITICAL FIX: lockHardwareCanvas() enables native OS surface rendering, fixing the 0-byte blank video bug
            val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) surface?.lockHardwareCanvas() else surface?.lockCanvas(null)
            canvas?.drawBitmap(bitmap, 0f, 0f, null)
            surface?.unlockCanvasAndPost(canvas!!)
            drainCodec(false)
            frameCount++
        } catch (e: Exception) {}
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            drainCodec(true)
            codec?.stop()
            codec?.release()
            if (muxerStarted) muxer?.stop()
            muxer?.release()
        } catch (e: Exception) {}
        codec = null; muxer = null; surface = null
    }

    private fun drainCodec(endOfStream: Boolean) {
        if (endOfStream) { try { codec?.signalEndOfInputStream() } catch(e: Exception){} }
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val status = codec?.dequeueOutputBuffer(bufferInfo, 10000) ?: break
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break else continue
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    trackIndex = muxer?.addTrack(codec!!.outputFormat) ?: -1
                    muxer?.start()
                    muxerStarted = true
                }
            } else if (status >= 0) {
                val encodedData = codec?.getOutputBuffer(status)
                if (encodedData != null && bufferInfo.size != 0) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    bufferInfo.presentationTimeUs = frameCount * 200000L // 5 fps
                    muxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                }
                codec?.releaseOutputBuffer(status, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }
}