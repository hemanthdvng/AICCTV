package com.securecam.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import com.google.gson.Gson
import com.securecam.core.network.LocalSignalingClient
import com.securecam.core.webrtc.*
import com.securecam.data.repository.EventRepository
import com.securecam.data.repository.SecurityEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ViewerViewModel @Inject constructor(val eventRepository: EventRepository) : ViewModel()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(navController: NavController, viewModel: ViewerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    
    var hasMicPerm by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMicPerm = it }
    LaunchedEffect(Unit) { if (!hasMicPerm) permLauncher.launch(Manifest.permission.RECORD_AUDIO) }

    if (hasMicPerm) {
        val remoteRenderer = remember { SurfaceViewRenderer(context) }
        var streamStatus by remember { mutableStateOf("Initializing Viewport...") }
        val alertHistory = remember { mutableStateListOf<String>() }
        
        var dataChannel by remember { mutableStateOf<DataChannel?>(null) }
        var localAudioTrack by remember { mutableStateOf<AudioTrack?>(null) }
        var isMicActive by remember { mutableStateOf(false) }
        
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        val viewerMode = prefs.getString("viewer_mode", "Local WiFi")
        val securityToken = prefs.getString("security_token", "") ?: ""

        var fbClient: FirebaseSignalingClient? = null
        var localClient: LocalSignalingClient? = null

        LaunchedEffect(Unit) {
            var lastAlert = ""
            var lastTime = 0L
            viewModel.eventRepository.securityEvents.collect { event ->
                val now = System.currentTimeMillis()
                if (event.description != lastAlert || now - lastTime > 2000) {
                    lastAlert = event.description
                    lastTime = now
                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    alertHistory.add(0, "[$timeStr] ${event.description}")
                    if (alertHistory.size > 50) alertHistory.removeLast()
                }
            }
        }

        fun sendCommand(cmd: String) {
            dataChannel?.let { dc ->
                if (dc.state() == DataChannel.State.OPEN) {
                    val buffer = ByteBuffer.wrap(cmd.toByteArray(Charsets.UTF_8))
                    dc.send(DataChannel.Buffer(buffer, false))
                }
            }
        }

        DisposableEffect(Unit) {
            val rtcManager = WebRTCManager(context).apply { initRenderer(remoteRenderer) }
            
            val observer = object : SimplePeerConnectionObserver() {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        if (viewerMode == "Local WiFi") {
                            localClient?.send(Gson().toJson(mapOf("type" to "ICE", "sdpMid" to it.sdpMid, "sdpMLineIndex" to it.sdpMLineIndex, "sdp" to it.sdp)))
                        } else {
                            fbClient?.sendSignal("ICE", Gson().toJson(IceCandidateData(it.sdpMid, it.sdpMLineIndex, it.sdp)))
                        }
                    }
                }
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val track = receiver?.track() as? VideoTrack
                    track?.addSink(remoteRenderer)
                }
                override fun onDataChannel(dc: DataChannel?) {
                    dataChannel = dc
                    dc?.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(p0: Long) {}
                        override fun onStateChange() {}
                        override fun onMessage(buffer: DataChannel.Buffer?) {
                            buffer?.data?.let { byteBuffer ->
                                val bytes = ByteArray(byteBuffer.remaining())
                                byteBuffer.get(bytes)
                                val text = String(bytes, Charsets.UTF_8)
                                CoroutineScope(Dispatchers.Main).launch {
                                    viewModel.eventRepository.emitEvent(SecurityEvent("WEBRTC", text, 1.0f))
                                }
                            }
                        }
                    })
                }
            }
            
            val peerConnection = rtcManager.createPeerConnection(observer)
            localAudioTrack = rtcManager.createLocalAudioTrack()
            localAudioTrack?.setEnabled(false)
            localAudioTrack?.let { peerConnection?.addTrack(it, listOf("audio_1")) }

            fun processOffer(sdpStr: String) {
                CoroutineScope(Dispatchers.Main).launch { streamStatus = "Offer Received! Routing ICE..." }
                val sdpMap = Gson().fromJson(sdpStr, Map::class.java)
                val sdpType = sdpMap["typeSDP"] as? String ?: sdpMap["type"] as String
                val sdp = SessionDescription(SessionDescription.Type.fromCanonicalForm(sdpType), sdpMap["sdp"] as String)
                peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
                
                peerConnection?.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        sdp?.let {
                            peerConnection.setLocalDescription(SimpleSdpObserver(), it)
                            if (viewerMode == "Local WiFi") {
                                localClient?.send(Gson().toJson(mapOf("type" to "ANSWER", "typeSDP" to it.type.canonicalForm(), "sdp" to it.description)))
                            } else {
                                fbClient?.sendSignal("ANSWER", Gson().toJson(SdpData(it.type.canonicalForm(), it.description)))
                            }
                        }
                    }
                }, MediaConstraints())
                CoroutineScope(Dispatchers.Main).launch { streamStatus = "LIVE STREAM ACTIVE" }
            }

            if (viewerMode == "Local WiFi") {
                val ip = prefs.getString("target_ip", "") ?: ""
                CoroutineScope(Dispatchers.Main).launch { streamStatus = "Connecting to Local IP: $ip..." }
                localClient = LocalSignalingClient(ip, 8081, securityToken)
                localClient?.onConnected = { CoroutineScope(Dispatchers.Main).launch { streamStatus = "Connected to Camera socket!" } }
                localClient?.onError = { CoroutineScope(Dispatchers.Main).launch { streamStatus = "ERROR: $it" } }
                localClient?.onMessageReceived = { jsonStr ->
                    val map = Gson().fromJson(jsonStr, Map::class.java)
                    when (map["type"]) {
                        "OFFER" -> processOffer(jsonStr)
                        "ICE" -> peerConnection?.addIceCandidate(IceCandidate(map["sdpMid"] as String, (map["sdpMLineIndex"] as Double).toInt(), map["sdp"] as String))
                        "ALERT" -> {
                            val text = map["text"] as? String ?: ""
                            CoroutineScope(Dispatchers.IO).launch {
                                viewModel.eventRepository.emitEvent(SecurityEvent("TCP_ALERT", text, 1.0f))
                            }
                        }
                    }
                }
                localClient?.connect()
            } else {
                fbClient = FirebaseSignalingClient(context, "VIEWER")
                fbClient?.onConnected = { CoroutineScope(Dispatchers.Main).launch { streamStatus = "Firebase Connected. Ready to Join!" } }
                fbClient?.onOfferReceived = { processOffer(it) }
                fbClient?.onIceCandidateReceived = { iceStr ->
                    val data = Gson().fromJson(iceStr, IceCandidateData::class.java)
                    peerConnection?.addIceCandidate(IceCandidate(data.sdpMid, data.sdpMLineIndex, data.sdp))
                }
            }

            onDispose {
                try { remoteRenderer.release() } catch(e: Exception){}
                // CRITICAL FIX: Gracefully disconnect and drop ghost WebRTC sockets so buttons work after reopening
                if (viewerMode == "Local WiFi") {
                    localClient?.send(Gson().toJson(mapOf("type" to "DISCONNECT")))
                }
                localClient?.disconnect()
                peerConnection?.dispose()
                rtcManager.dispose()
            }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("WebRTC Viewer") }, navigationIcon = { TextButton(onClick = { navController.popBackStack() }) { Text("Back") } }) }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { remoteRenderer }, modifier = Modifier.fillMaxSize())
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0x99000000))) {
                        Text(text = "Status: $streamStatus", color = Color.Green, modifier = Modifier.padding(8.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(alertHistory) { alert ->
                            // CRITICAL FIX: Safe UI colors match the Camera screen to prevent false red alarms
                            val isSafe = alert.contains("Safe", ignoreCase = true) || alert.contains("CLEAR", ignoreCase = true) || alert.contains("Authorized Face")
                            val isSystem = alert.contains("[SYSTEM]")
                            val bgColor = if (isSystem) Color(0x991976D2) else if (isSafe) Color(0x884CAF50) else Color(0xCCD32F2F)
                            Card(colors = CardDefaults.cardColors(containerColor = bgColor), modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth()) {
                                Text(text = alert, color = Color.White, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(160.dp)) 
                }

                Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).fillMaxWidth()) {
                    if (streamStatus != "LIVE STREAM ACTIVE") {
                        Button(
                            onClick = { 
                                if (viewerMode == "Local WiFi") localClient?.send(Gson().toJson(mapOf("type" to "JOIN")))
                                else fbClient?.sendSignal("JOIN") 
                            },
                            modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth()
                        ) { Text("JOIN STREAM") }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Button(onClick = { sendCommand("CMD_SIREN") }, shape = CircleShape, modifier = Modifier.size(56.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))) { Text("🚨", fontSize = 24.sp) }
                            Button(onClick = { sendCommand("CMD_FLASH") }, shape = CircleShape, modifier = Modifier.size(56.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF512DA8))) { Text("🔦", fontSize = 24.sp) }
                            Button(onClick = { sendCommand("CMD_SWITCH_CAM") }, shape = CircleShape, modifier = Modifier.size(56.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))) { Text("🔄", fontSize = 24.sp) }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { isMicActive = !isMicActive; localAudioTrack?.setEnabled(isMicActive) },
                            shape = RoundedCornerShape(32.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isMicActive) Color(0xFF388E3C) else Color(0xFF616161)),
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(64.dp)
                        ) { Text(text = if (isMicActive) "🎤 MIC ACTIVE" else "🔇 PUSH TO TALK", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { Text("Waiting for Microphone Permission...", color = Color.White) }
    }
}