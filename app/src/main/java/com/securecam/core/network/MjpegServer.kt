package com.securecam.core.network

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket

class MjpegServer(private val context: Context) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var latestJpeg: ByteArray? = null

    fun updateFrame(bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        latestJpeg = stream.toByteArray()
    }

    fun start(port: Int = 8080, requiredToken: String) {
        if (isRunning) return
        isRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                while (isRunning) {
                    val client = serverSocket?.accept()
                    launch {
                        try {
                            val reader = BufferedReader(InputStreamReader(client?.inputStream))
                            val requestLine = reader.readLine() ?: ""
                            
                            if (!requestLine.contains("token=$requiredToken")) {
                                val out = client?.getOutputStream()
                                out?.write("HTTP/1.0 403 Forbidden\r\n\r\nAccess Denied".toByteArray())
                                out?.flush()
                                client?.close()
                                return@launch
                            }

                            val out = client?.getOutputStream()
                            
                            // ROUTING ENGINE: Serve Vault HTML or Live MJPEG
                            if (requestLine.contains("GET /vault")) {
                                val html = buildVaultHtml(requiredToken)
                                out?.write(("HTTP/1.0 200 OK\r\nContent-Type: text/html\r\n\r\n$html").toByteArray())
                                out?.flush()
                            } else if (requestLine.contains("GET /play")) {
                                val id = requestLine.substringAfter("id=").substringBefore("&").substringBefore(" ")
                                val html = buildPlayerHtml(id, requiredToken)
                                out?.write(("HTTP/1.0 200 OK\r\nContent-Type: text/html\r\n\r\n$html").toByteArray())
                                out?.flush()
                            } else if (requestLine.contains("GET /frame")) {
                                val id = requestLine.substringAfter("id=").substringBefore("&")
                                val frame = requestLine.substringAfter("frame=").substringBefore("&").substringBefore(" ")
                                val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "$id/$frame")
                                if (file.exists()) {
                                    out?.write(("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\n\r\n").toByteArray())
                                    out?.write(file.readBytes())
                                }
                                out?.flush()
                            } else {
                                out?.write(("HTTP/1.0 200 OK\r\n" +
                                        "Connection: close\r\n" +
                                        "Cache-Control: no-cache\r\n" +
                                        "Content-Type: multipart/x-mixed-replace; boundary=--BoundaryString\r\n\r\n").toByteArray())
                                
                                while (isRunning && client?.isClosed == false) {
                                    latestJpeg?.let { jpeg ->
                                        out?.write(("--BoundaryString\r\nContent-type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n").toByteArray())
                                        out?.write(jpeg)
                                        out?.write("\r\n\r\n".toByteArray())
                                        out?.flush()
                                    }
                                    delay(200) 
                                }
                            }
                        } catch(e: Exception) {} finally {
                            try { client?.close() } catch(e: Exception){}
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun buildVaultHtml(token: String): String {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val folders = dir?.listFiles()?.filter { it.isDirectory && it.name.startsWith("SecureCam_Alert_") }?.sortedByDescending { it.name } ?: emptyList()
        
        var listHtml = ""
        for (f in folders) {
            val formattedName = f.name.replace("SecureCam_Alert_", "").replace("_", " at ")
            listHtml += "<div style='padding:16px; margin:12px 0; background:#161b22; border-radius:12px; border:1px solid #30363d;'>" +
                        "<h3 style='margin:0 0 12px 0; color:#58a6ff; font-size:16px;'>📅 $formattedName</h3>" +
                        "<a href='/play?id=${f.name}&token=$token' style='display:block; text-align:center; background:#238636; padding:10px; border-radius:6px; color:white; text-decoration:none; font-weight:bold;'>▶ Play Recording</a>" +
                        "</div>"
        }
        if (folders.isEmpty()) listHtml = "<p style='text-align:center; color:#8b949e;'>No recorded alerts found on the Camera.</p>"

        return """
            <html><head><meta name='viewport' content='width=device-width, initial-scale=1'>
            <style>body{background:#0d1117; color:#c9d1d9; font-family:-apple-system, sans-serif; padding:16px; margin:0;}</style></head>
            <body><h2 style='text-align:center;'>🛡️ Remote DVR Vault</h2>$listHtml</body></html>
        """.trimIndent()
    }

    private fun buildPlayerHtml(id: String, token: String): String {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), id)
        val frames = dir.listFiles()?.filter { it.name.endsWith(".jpg") }?.sortedBy { it.name }?.map { it.name } ?: emptyList()
        val framesJson = if (frames.isEmpty()) "[]" else frames.joinToString("','", "['", "']")
        val firstFrame = frames.firstOrNull() ?: ""
        
        // COMPILER FIX: Construct script tags outside raw string to bypass both HTML and Kotlin parsers
        val sOpen = "<scr" + "ipt>"
        val sClose = "</scr" + "ipt>"
        
        return """
            <html><head><meta name='viewport' content='width=device-width, initial-scale=1'>
            <style>body{background:#0d1117; color:#c9d1d9; font-family:-apple-system, sans-serif; text-align:center; padding:16px; margin:0;} img{width:100%; border-radius:8px; border:2px solid #30363d;}</style></head>
            <body><h3 style='color:#f85149;'>⚠️ Intruder Event</h3>
            <img id='player' src='/frame?id=$id&frame=$firstFrame&token=$token' />
            <p style='color:#8b949e; font-size:12px;'>Playing 10-Second Buffer...</p>
            <br><a href='/vault?token=$token' style='display:inline-block; padding:12px 24px; background:#1f6feb; border-radius:6px; color:white; text-decoration:none; font-weight:bold;'>⬅ Back to Vault</a>
            $sOpen
                const frames = $framesJson;
                let i = 0;
                if(frames.length > 1) {
                    setInterval(() => {
                        i = (i + 1) % frames.length;
                        document.getElementById('player').src = '/frame?id=$id&frame=' + frames[i] + '&token=$token';
                    }, 200);
                }
            $sClose
            </body></html>
        """.trimIndent()
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch(e: Exception) {}
    }
}