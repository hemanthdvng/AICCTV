package com.securecam.core.network

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class LocalSignalingServer(private val port: Int, private val expectedToken: String) {
    var onMessageReceived: ((String) -> Unit)? = null
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<PrintWriter>()
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: continue
                    
                    // FIX: Spawn a new coroutine per client so Viewer UI and Background Service can run simultaneously
                    launch {
                        var out: PrintWriter? = null
                        try {
                            out = PrintWriter(clientSocket.getOutputStream(), true)
                            val reader = BufferedReader(InputStreamReader(clientSocket.inputStream))
                            
                            val token = reader.readLine()
                            if (token != expectedToken) {
                                clientSocket.close()
                                return@launch
                            }
                            
                            clients.add(out)
                            while (isRunning && !clientSocket.isClosed) {
                                val msg = reader.readLine() ?: break
                                withContext(Dispatchers.Main) { onMessageReceived?.invoke(msg) }
                            }
                        } catch (e: Exception) {} finally {
                            out?.let { clients.remove(it) }
                            try { clientSocket.close() } catch(e: Exception){}
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun broadcast(msg: String) { 
        CoroutineScope(Dispatchers.IO).launch { clients.forEach { it.println(msg) } } 
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        clients.clear()
    }
}

class LocalSignalingClient(private val ip: String, private val port: Int, private val token: String) {
    var onMessageReceived: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    private var socket: Socket? = null
    private var out: PrintWriter? = null
    private var isRunning = false

    fun connect() {
        isRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = Socket(ip, port)
                out = PrintWriter(socket!!.getOutputStream(), true)
                out?.println(token) 
                
                withContext(Dispatchers.Main) { onConnected?.invoke() }
                
                val reader = BufferedReader(InputStreamReader(socket!!.inputStream))
                while (isRunning && !socket!!.isClosed) {
                    val msg = reader.readLine() ?: break
                    withContext(Dispatchers.Main) { onMessageReceived?.invoke(msg) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError?.invoke(e.message ?: "Connection failed") }
            }
        }
    }

    fun send(msg: String) { CoroutineScope(Dispatchers.IO).launch { out?.println(msg) } }

    fun disconnect() {
        isRunning = false
        try { socket?.close() } catch (e: Exception) {}
    }
}