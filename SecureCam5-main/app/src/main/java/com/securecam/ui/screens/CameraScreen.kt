package com.securecam.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import com.google.gson.Gson
import com.securecam.core.ai.HybridAIPipeline
import com.securecam.core.network.DvrEngine
import com.securecam.core.network.LocalSignalingServer
import com.securecam.core.network.MjpegServer
import com.securecam.core.network.LocalApiServer
import com.securecam.core.webrtc.*
import com.securecam.data.repository.EventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(val aiPipeline: HybridAIPipeline, val eventRepository: EventRepository) : ViewModel()

fun getLocalIpAddress(): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) { val addr = addrs.nextElement(); if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr.hostAddress ?: "No IP" }
        }
    } catch (e: Exception) {}
    return "No WiFi"
}

@Composable
fun CameraScreen(navController: NavController, viewModel: CameraViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    
    var hasCamPerm by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var hasMicPerm by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        hasCamPerm = perms[Manifest.permission.CAMERA] ?: hasCamPerm
        hasMicPerm = perms[Manifest.permission.RECORD_AUDIO] ?: hasMicPerm
    }

    LaunchedEffect(Unit) {
        val reqs = mutableListOf<String>()
        if (!hasCamPerm) reqs.add(Manifest.permission.CAMERA)
        if (!hasMicPerm) reqs.add(Manifest.permission.RECORD_AUDIO)
        if (reqs.isNotEmpty()) permLauncher.launch(reqs.toTypedArray())
    }

    if (hasCamPerm && hasMicPerm) {
        val localRenderer = remember { SurfaceViewRenderer(context) }
        var streamStatus by remember { mutableStateOf("Initializing Hardware...") }
        val alertHistory = remember { mutableStateListOf<String>() }
        var dataChannel by remember { mutableStateOf<DataChannel?>(null) }
        
        var forceScanTrigger by remember { mutableStateOf(0) }
        var isScreaming by remember { mutableStateOf(false) }
        var tts: TextToSpeech? by remember { mutableStateOf(null) }
        
        val mjpegServer = remember { MjpegServer(context) }
        val dvrEngine = remember { DvrEngine(context) }
        val ipAddress = remember { getLocalIpAddress() }

        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        var scanIntervalMs by remember { mutableStateOf((prefs.getFloat("scan_interval_sec", 5f) * 1000).toLong()) }
        val securityToken = remember { prefs.getString("security_token", "") ?: "" }
        val localServer = remember { LocalSignalingServer(8081, securityToken) }
        val apiServer = remember { LocalApiServer(8082, securityToken, context) }
        val latestBitmapRef = remember { java.util.concurrent.atomic.AtomicReference<Bitmap>(null) }
        
        var isFrontCamera by remember { mutableStateOf(true) }
        var isFlashActive by remember { mutableStateOf(false) }
        val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager }

        var isCurrentlyRecording by remember { mutableStateOf(false) }
        var isSavingVideo by remember { mutableStateOf(false) }

        LaunchedEffect(isFlashActive) {
            if (isFrontCamera) {
                val window = activity?.window
                val params = window?.attributes
                params?.screenBrightness = if (isFlashActive) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window?.attributes = params
            } else {
                try {
                    val cameraId = cameraManager.cameraIdList.firstOrNull { cameraManager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK } ?: cameraManager.cameraIdList[0]
                    cameraManager.setTorchMode(cameraId, isFlashActive)
                } catch (e: Exception) {
                    val window = activity?.window
                    val params = window?.attributes
                    params?.screenBrightness = if (isFlashActive) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window?.attributes = params
                }
            }
        }

        DisposableEffect(Unit) {
            tts = TextToSpeech(context) { status -> if (status == TextToSpeech.SUCCESS) { tts?.language = Locale.US; tts?.setPitch(1.3f); tts?.setSpeechRate(1.2f) } }
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { tts?.shutdown(); activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }

        LaunchedEffect(isScreaming) {
            if (isScreaming) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                while(isScreaming && isActive) { tts?.speak("WARNING! INTRUDER DETECTED! LEAVE THE PREMISES IMMEDIATELY!", TextToSpeech.QUEUE_FLUSH, null, null); delay(4000) }
            } else { tts?.stop() }
        }

        LaunchedEffect(Unit) {
            while(isActive) {
                try {
                    // CRITICAL FIX: Changed 0.5f to 1.0f to pass raw uncompressed 1080p frame directly to AI and Recording Engine
                    localRenderer.addFrameListener({ bitmap ->
                        try {
                            val bmpCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            if (bmpCopy != null) { 
                                mjpegServer.updateFrame(bmpCopy)

                                val now = System.currentTimeMillis()
                                val endTime = HybridAIPipeline.activeVideoEndTime

                                if (now < endTime && HybridAIPipeline.activeVideoPath != null) {
                                    val vidRes = prefs.getInt("video_resolution", 720)
                                    val scaledBmp = if (bmpCopy.height > vidRes) {
                                        val ratio = bmpCopy.width.toFloat() / bmpCopy.height.toFloat()
                                        Bitmap.createScaledBitmap(bmpCopy, (vidRes * ratio).toInt(), vidRes, true)
                                    } else bmpCopy

                                    if (!isCurrentlyRecording) {
                                        isCurrentlyRecording = true
                                        isSavingVideo = false
                                        try {
                                            // MediaCodec requires width/height to be even numbers
                                            val w = scaledBmp.width - (scaledBmp.width % 16)
                                            val h = scaledBmp.height - (scaledBmp.height % 16)
                                            dvrEngine.triggerRecording(HybridAIPipeline.activeVideoPath ?: "alert_${now}.mp4", w, h) 
                                        } catch(e: Exception){}
                                    }
                                    
                                    try { dvrEngine.appendFrame(scaledBmp) } catch(e: Exception){}
                                    if (scaledBmp != bmpCopy) scaledBmp.recycle()

                                } else {
                                    if (isCurrentlyRecording) {
                                        isCurrentlyRecording = false
                                        isSavingVideo = true
                                        try { dvrEngine.stopRecording() } catch(e: Exception){}
                                        CoroutineScope(Dispatchers.Main).launch { delay(3000); isSavingVideo = false }
                                    }
                                }

                                val old = latestBitmapRef.getAndSet(bmpCopy)
                                old?.recycle() 
                            }
                        } catch(e: Exception) {}
                    }, 1.0f)
                } catch(e: Exception) {}
                delay(200) 
            }
        }

        LaunchedEffect(forceScanTrigger, scanIntervalMs) {
            while(isActive) {
                delay(if (forceScanTrigger > 0) 500 else scanIntervalMs)
                latestBitmapRef.getAndSet(null)?.let { bmp ->
                    if (!bmp.isRecycled) {
                        try { val copy = bmp.copy(Bitmap.Config.ARGB_8888, false); if (copy != null) { viewModel.aiPipeline.processFrame(copy) } } catch (e: Exception) {}
                        bmp.recycle()
                    }
                }
                if (forceScanTrigger > 0) forceScanTrigger = 0
            }
        }

        LaunchedEffect(Unit) {
            viewModel.eventRepository.securityEvents.collect { event ->
                val text = event.description
                val vidPath = event.videoPath
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                alertHistory.add(0, "[$timeStr] $text")
                if (alertHistory.size > 50) alertHistory.removeLast()
                
                // CRITICAL FIX: The Camera now broadcasts the videoPath over the socket so the Viewer gets it instantly
                localServer.broadcast(Gson().toJson(mapOf("type" to "ALERT", "text" to text, "videoPath" to vidPath)))
                dataChannel?.let { dc ->
                    if (dc.state() == DataChannel.State.OPEN) { val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)); dc.send(DataChannel.Buffer(buffer, false)) }
                }
            }
        }

        DisposableEffect(Unit) {
            viewModel.aiPipeline.start()
            mjpegServer.start(8080, securityToken)
            try { apiServer.start() } catch (e: Exception) {}
            
            val rtcManager = WebRTCManager(context).apply { initRenderer(localRenderer) }
            val fbUrl = prefs.getString("fb_db_url", "") ?: ""
            var firebaseClient: FirebaseSignalingClient? = null
            if (fbUrl.isNotBlank()) {
                firebaseClient = FirebaseSignalingClient(context, "CAMERA")
                firebaseClient.clearSignals()
                firebaseClient.onConnected = { CoroutineScope(Dispatchers.Main).launch { streamStatus = "Listening on WiFi & Firebase" } }
            } else { CoroutineScope(Dispatchers.Main).launch { streamStatus = "Listening on Local WiFi only" } }
            localServer.start()
            
            var activeSignaler: String? = null
            var peerConnection: PeerConnection? = null

            val observer = object : SimplePeerConnectionObserver() {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        val json = Gson().toJson(mapOf("type" to "ICE", "sdpMid" to it.sdpMid, "sdpMLineIndex" to it.sdpMLineIndex, "sdp" to it.sdp))
                        if (activeSignaler == "LOCAL") localServer.broadcast(json)
                        else if (activeSignaler == "FIREBASE") firebaseClient?.sendSignal("ICE", Gson().toJson(IceCandidateData(it.sdpMid, it.sdpMLineIndex, it.sdp)))
                    }
                }
            }
            
            fun buildPeerConnection() {
                try { peerConnection?.dispose() } catch(e:Exception){}
                peerConnection = rtcManager.createPeerConnection(observer)
                val localAudio = rtcManager.createLocalAudioTrack()
                localAudio?.let { peerConnection?.addTrack(it, listOf("audio_1")) }
                val localTrack = rtcManager.createLocalVideoTrack(context, localRenderer)
                localTrack?.let { peerConnection?.addTrack(it, listOf("stream_1")) }

                dataChannel = peerConnection?.createDataChannel("telemetry", DataChannel.Init())
                dataChannel?.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(p0: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer?) {
                        buffer?.data?.let { byteBuffer ->
                            val bytes = ByteArray(byteBuffer.remaining())
                            byteBuffer.get(bytes)
                            val command = String(bytes, Charsets.UTF_8)
                            
                            CoroutineScope(Dispatchers.Main).launch {
                                when (command) {
                                    "CMD_SIREN" -> { isScreaming = !isScreaming; alertHistory.add(0, "[SYSTEM] Siren toggled.") }
                                    "CMD_SWITCH_CAM" -> { isFrontCamera = !isFrontCamera; rtcManager.switchCamera(); alertHistory.add(0, "[SYSTEM] Lens switched.") }
                                    "CMD_FORCE_SCAN" -> { forceScanTrigger++; alertHistory.add(0, "[SYSTEM] Scan forced.") }
                                    "CMD_FLASH" -> { isFlashActive = !isFlashActive; alertHistory.add(0, "[SYSTEM] Camera Flash toggled.") }
                                }
                            }
                        }
                    }
                })
            }
            
            buildPeerConnection()

            fun processOffer(sdpStr: String) {
                val sdpMap = Gson().fromJson(sdpStr, Map::class.java)
                val sdp = SessionDescription(SessionDescription.Type.fromCanonicalForm(sdpMap["type"] as String), sdpMap["sdp"] as String)
                peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
                CoroutineScope(Dispatchers.Main).launch { streamStatus = "LIVE STREAM ACTIVE" }
            }
            fun processJoin(signalerType: String) {
                activeSignaler = signalerType
                CoroutineScope(Dispatchers.Main).launch { streamStatus = "Viewer Connected! Gathering ICE..." }
                buildPeerConnection()
                peerConnection?.createOffer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        sdp?.let {
                            peerConnection?.setLocalDescription(SimpleSdpObserver(), it)
                            if (signalerType == "LOCAL") localServer.broadcast(Gson().toJson(mapOf("type" to "OFFER", "typeSDP" to it.type.canonicalForm(), "sdp" to it.description)))
                            else firebaseClient?.sendSignal("OFFER", Gson().toJson(SdpData(it.type.canonicalForm(), it.description)))
                        }
                    }
                }, MediaConstraints())
            }

            firebaseClient?.onJoinReceived = { processJoin("FIREBASE") }
            firebaseClient?.onAnswerReceived = { processOffer(it) }
            firebaseClient?.onIceCandidateReceived = { iceStr -> val data = Gson().fromJson(iceStr, IceCandidateData::class.java); peerConnection?.addIceCandidate(IceCandidate(data.sdpMid, data.sdpMLineIndex, data.sdp)) }
            
            localServer.onMessageReceived = { jsonStr ->
                val map = Gson().fromJson(jsonStr, Map::class.java)
                when (map["type"]) {
                    "JOIN" -> processJoin("LOCAL")
                    "DISCONNECT" -> { CoroutineScope(Dispatchers.Main).launch { streamStatus = "Listening on WiFi" } }
                    "ANSWER" -> { processOffer(Gson().toJson(mapOf("type" to map["typeSDP"], "sdp" to map["sdp"]))) }
                    "ICE" -> { peerConnection?.addIceCandidate(IceCandidate(map["sdpMid"] as String, (map["sdpMLineIndex"] as Double).toInt(), map["sdp"] as String)) }
                    "SYNC_SETTINGS" -> {
                        prefs.edit().apply {
                            (map["scan_interval_sec"] as? Double)?.let { putFloat("scan_interval_sec", it.toFloat()) }
                            (map["video_record_len"] as? Double)?.let { putFloat("video_record_len", it.toFloat()) }
                            (map["camera_resolution"] as? Double)?.let { putInt("camera_resolution", it.toInt()) }
                            (map["video_resolution"] as? Double)?.let { putInt("video_resolution", it.toInt()) }
                            (map["llm_resolution"] as? Double)?.let { putInt("llm_resolution", it.toInt()) }
                            (map["confidence_threshold"] as? Double)?.let { putFloat("confidence_threshold", it.toFloat()) }
                            (map["prompt_usr"] as? String)?.let { putString("prompt_usr", it) }
                            (map["llm_enabled"] as? Boolean)?.let { putBoolean("llm_enabled", it) }
                            (map["face_recog_enabled"] as? Boolean)?.let { putBoolean("face_recog_enabled", it) }
                            (map["authorized_faces"] as? String)?.let { putString("authorized_faces", it) }
                        }.apply()
                        CoroutineScope(Dispatchers.Main).launch {
                            scanIntervalMs = ((map["scan_interval_sec"] as? Double)?.toFloat() ?: 5f).toLong() * 1000
                            alertHistory.add(0, "[SYSTEM] Settings Synced from Viewer. Please restart stream to apply resolution.")
                        }
                    }
                }
            }

            onDispose {
                try { localRenderer.release() } catch(e: Exception){}
                isScreaming = false
                isFlashActive = false
                mjpegServer.stop()
                localServer.stop()
                apiServer.stop()
                dataChannel?.dispose()
                peerConnection?.dispose()
                rtcManager.dispose()
                viewModel.aiPipeline.stop()
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(if(isFlashActive && isFrontCamera) Color.White else Color.Black)) {
            if(!(isFlashActive && isFrontCamera)) AndroidView(factory = { localRenderer }, modifier = Modifier.fillMaxSize())

            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0x99000000))) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = "Status: $streamStatus", color = Color.Green)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "PC Web Server: http://$ipAddress:8080/?token=$securityToken", color = Color.Cyan)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                if (isSavingVideo) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0x99FFC107))) {
                        Text(text = "💾 Saving Video to Vault...", color = Color.Black, modifier = Modifier.padding(8.dp), fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(alertHistory) { alert ->
                        val isSafe = alert.contains("Safe", ignoreCase = true) || alert.contains("CLEAR", ignoreCase = true) || alert.contains("Authorized Face")
                        val isSystem = alert.contains("[SYSTEM]")
                        val bgColor = if (isSystem) Color(0x991976D2) else if (isSafe) Color(0x884CAF50) else Color(0xCCD32F2F)
                        Card(colors = CardDefaults.cardColors(containerColor = bgColor), modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth()) {
                            Text(text = alert, color = Color.White, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(72.dp))
            }

            Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).height(56.dp).fillMaxWidth(0.6f)) {
                Text("END STREAM", style = MaterialTheme.typography.titleMedium)
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { Text("Waiting for Camera & Microphone Permissions...", color = Color.White) }
    }
}