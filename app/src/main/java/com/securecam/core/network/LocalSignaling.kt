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
    
    // CRITICAL FIX: Managed scope for instant teardown
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (isRunning) return
        isRunning = true
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        scope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                while (isRunning && isActive) {
                    val clientSocket = serverSocket?.accept() ?: continue
                    
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
                            while (isRunning && !clientSocket.isClosed && isActive) {
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
        scope.launch { clients.forEach { it.println(msg) } } 
    }

    fun stop() {
        isRunning = false
        // CRITICAL FIX: Instantly purge all active and pending coroutines attached to this instance
        scope.cancel()
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
    
    // CRITICAL FIX: Managed scope for UI client connections
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect() {
        if (isRunning) return
        isRunning = true
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        scope.launch {
            try {
                socket = Socket(ip, port)
                out = PrintWriter(socket!!.getOutputStream(), true)
                out?.println(token) 
                
                withContext(Dispatchers.Main) { onConnected?.invoke() }
                
                val reader = BufferedReader(InputStreamReader(socket!!.inputStream))
                while (isRunning && !socket!!.isClosed && isActive) {
                    val msg = reader.readLine() ?: break
                    withContext(Dispatchers.Main) { onMessageReceived?.invoke(msg) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError?.invoke(e.message ?: "Connection failed") }
            }
        }
    }

    fun send(msg: String) { 
        scope.launch { out?.println(msg) } 
    }

    fun disconnect() {
        isRunning = false
        // CRITICAL FIX: Prevent memory leaks by destroying the listener coroutine when UI unmounts
        scope.cancel()
        try { socket?.close() } catch (e: Exception) {}
    }
}