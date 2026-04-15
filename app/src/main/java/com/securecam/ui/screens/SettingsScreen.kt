package com.securecam.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import com.securecam.core.ai.BiometricEngine
import com.securecam.core.ai.RegisteredFace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.net.Socket
import kotlin.math.roundToInt
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {
    var isImporting by mutableStateOf(false)
        private set
    var currentModelName by mutableStateOf("None")
    var draftCroppedBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var draftFaceName by mutableStateOf("")
    private val _registeredFaces = MutableStateFlow<List<RegisteredFace>>(emptyList())
    val registeredFaces = _registeredFaces.asStateFlow()

    fun loadPrefs(context: Context) {
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        currentModelName = prefs.getString("selected_model", "None") ?: "None"
        try {
            val type = object : TypeToken<List<RegisteredFace>>() {}.type
            _registeredFaces.value = Gson().fromJson(prefs.getString("authorized_faces", "[]") ?: "[]", type) ?: emptyList()
        } catch (e: Exception) { _registeredFaces.value = emptyList() }
    }

    fun removeFace(context: Context, id: String) {
        val updated = _registeredFaces.value.filter { it.id != id }
        _registeredFaces.value = updated
        context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE).edit().putString("authorized_faces", Gson().toJson(updated)).apply()
    }

    fun importModel(uri: Uri, context: Context) {
        isImporting = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var fileName = "custom_model.litertlm"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) fileName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
                val destFile = File(context.filesDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input -> destFile.outputStream().use { output -> input.copyTo(output) } }
                context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE).edit().putString("selected_model", fileName).apply()
                currentModelName = fileName
                withContext(Dispatchers.Main) { Toast.makeText(context, "Model $fileName Loaded!", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {} finally { isImporting = false }
        }
    }

    fun downloadCloudModel(context: Context) {
        try {
            val url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
            val request = DownloadManager.Request(Uri.parse(url)).setTitle("Gemma AI Model").setDescription("Downloading SecureCam AI Engine...").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalFilesDir(context, null, "gemma-4-E2B-it.litertlm")
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(context, "Download started! Check your notification shade.", Toast.LENGTH_LONG).show()
            context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE).edit().putString("selected_model", "gemma-4-E2B-it.litertlm").apply()
            currentModelName = "gemma-4-E2B-it.litertlm"
        } catch (e: Exception) {}
    }

    fun syncToCamera(context: Context, ip: String, token: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
                val socket = Socket(ip, 8081)
                val out = PrintWriter(socket.getOutputStream(), true)
                out.println(token)
                val syncData = mapOf(
                    "type" to "SYNC_SETTINGS",
                    "scan_interval_sec" to prefs.getFloat("scan_interval_sec", 5f).toDouble(),
                    "video_record_len" to prefs.getFloat("video_record_len", 15f).toDouble(),
                    "camera_resolution" to prefs.getInt("camera_resolution", 1080),
                    "video_resolution" to prefs.getInt("video_resolution", 720),
                    "llm_resolution" to prefs.getInt("llm_resolution", 280),
                    "confidence_threshold" to prefs.getFloat("confidence_threshold", 0.60f).toDouble(),
                    "prompt_usr" to prefs.getString("prompt_usr", ""),
                    "llm_enabled" to prefs.getBoolean("llm_enabled", true),
                    "face_recog_enabled" to prefs.getBoolean("face_recog_enabled", false),
                    "authorized_faces" to (prefs.getString("authorized_faces", "[]") ?: "[]")
                )
                out.println(Gson().toJson(syncData))
                socket.close()
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) { withContext(Dispatchers.Main) { onError(e.message ?: "Connection Refused") } }
        }
    }

    fun processFaceRegistration(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { Toast.makeText(context, "Scanning for face...", Toast.LENGTH_SHORT).show() }
            try {
                var bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ -> decoder.isMutableRequired = true; decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE } } else { MediaStore.Images.Media.getBitmap(context.contentResolver, uri) }
                if (bmp.width > 1500 || bmp.height > 1500) { val scale = 1500f / maxOf(bmp.width, bmp.height); bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) }
                val facesList = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()).process(InputImage.fromBitmap(bmp, 0)).await()
                if (facesList.isEmpty()) { withContext(Dispatchers.Main) { Toast.makeText(context, "No face found. Try a clearer photo.", Toast.LENGTH_LONG).show() }; return@launch }
                val bounds = facesList.first().boundingBox
                val size = maxOf(bounds.width(), bounds.height())
                val left = (bounds.centerX() - size / 2).coerceAtLeast(0)
                val top = (bounds.centerY() - size / 2).coerceAtLeast(0)
                val croppedBmp = Bitmap.createBitmap(bmp, left, top, minOf(size, bmp.width - left), minOf(size, bmp.height - top))
                withContext(Dispatchers.Main) { draftCroppedBitmap = croppedBmp; draftFaceName = "Face ${_registeredFaces.value.size + 1}" }
            } catch(e: Exception) {}
        }
    }

    fun confirmFaceRegistration(context: Context) {
        val bmp = draftCroppedBitmap ?: return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { Toast.makeText(context, "Saving...", Toast.LENGTH_SHORT).show() }
            try {
                val biometricEngine = BiometricEngine(context)
                biometricEngine.initialize()
                val embedding = biometricEngine.getFaceEmbedding(bmp)
                biometricEngine.close()
                if (embedding != null) {
                    val updatedList = _registeredFaces.value + RegisteredFace(UUID.randomUUID().toString(), draftFaceName, embedding)
                    context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE).edit().putString("authorized_faces", Gson().toJson(updatedList)).apply()
                    _registeredFaces.value = updatedList
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Face added!", Toast.LENGTH_SHORT).show(); draftCroppedBitmap = null }
                }
            } catch(e: Exception) {}
        }
    }
    fun cancelFaceRegistration() { draftCroppedBitmap = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    val faces by viewModel.registeredFaces.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
    
    var securityToken by remember { mutableStateOf(prefs.getString("security_token", "").takeIf { !it.isNullOrBlank() } ?: UUID.randomUUID().toString().substring(0, 8).also { prefs.edit().putString("security_token", it).apply() }) }
    var viewerMode by remember { mutableStateOf(prefs.getString("viewer_mode", "Local WiFi") ?: "Local WiFi") }
    var menuExpanded by remember { mutableStateOf(false) }
    var appRole by remember { mutableStateOf(prefs.getString("app_role", "Camera") ?: "Camera") }
    var roleExpanded by remember { mutableStateOf(false) }
    
    var targetIp by remember { mutableStateOf(prefs.getString("target_ip", "") ?: "") }
    var fbDbUrl by remember { mutableStateOf(prefs.getString("fb_db_url", "") ?: "") }
    var fbApiKey by remember { mutableStateOf(prefs.getString("fb_api_key", "") ?: "") }
    var fbAppId by remember { mutableStateOf(prefs.getString("fb_app_id", "") ?: "") }
    
    // CRITICAL FIX: Resolution Engines
    var cameraResolution by remember { mutableStateOf(prefs.getInt("camera_resolution", 1080)) }
    var camResExpanded by remember { mutableStateOf(false) }
    val camResOptions = listOf(1080, 720, 480, 320)

    var videoResolution by remember { mutableStateOf(prefs.getInt("video_resolution", 720)) }
    var vidResExpanded by remember { mutableStateOf(false) }
    val vidResOptions = camResOptions.filter { it <= cameraResolution }

    var scanInterval by remember { mutableStateOf(prefs.getFloat("scan_interval_sec", 5f).coerceIn(1f, 60f)) }
    var videoRecordLen by remember { mutableStateOf(prefs.getFloat("video_record_len", 15f).coerceIn(5f, 60f)) }
    var llmResolution by remember { mutableStateOf(prefs.getInt("llm_resolution", 280)) }
    var resExpanded by remember { mutableStateOf(false) }

    var popupNotifications by remember { mutableStateOf(prefs.getBoolean("enable_notifications", true)) }
    var llmEnabled by remember { mutableStateOf(prefs.getBoolean("llm_enabled", true)) }
    var faceRecogEnabled by remember { mutableStateOf(prefs.getBoolean("face_recog_enabled", false)) }
    var debugMode by remember { mutableStateOf(prefs.getBoolean("debug_mode", false)) }
    var aiPrompt by remember { mutableStateOf(prefs.getString("prompt_usr", "Report if you see a clock. If you do not see it, reply EXACTLY with CLEAR.") ?: "") }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { viewModel.processFaceRegistration(it, context) } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.importModel(it, context) } }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        viewModel.loadPrefs(context)
        try { androidx.core.content.ContextCompat.startForegroundService(context, android.content.Intent(context, com.securecam.service.AlertService::class.java)) } catch(e: Exception){}
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (viewModel.draftCroppedBitmap != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelFaceRegistration() }, title = { Text("Confirm Face") },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) { Image(bitmap = viewModel.draftCroppedBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.size(150.dp).clip(CircleShape)); Spacer(modifier = Modifier.height(16.dp)); OutlinedTextField(value = viewModel.draftFaceName, onValueChange = { viewModel.draftFaceName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) } },
            confirmButton = { Button(onClick = { viewModel.confirmFaceRegistration(context) }) { Text("Save") } }, dismissButton = { TextButton(onClick = { viewModel.cancelFaceRegistration() }) { Text("Cancel") } }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = { navController.popBackStack() }) { Text("Back") } }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            
            Text("Device Role & Sync", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            ExposedDropdownMenuBox(expanded = roleExpanded, onExpandedChange = { roleExpanded = !roleExpanded }) {
                OutlinedTextField(value = appRole, onValueChange = {}, readOnly = true, label = { Text("Use this device as:") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = roleExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors())
                ExposedDropdownMenu(expanded = roleExpanded, onDismissRequest = { roleExpanded = false }) {
                    listOf("Camera", "Viewer").forEach { mode -> DropdownMenuItem(text = { Text(mode) }, onClick = { 
                            appRole = mode; prefs.edit().putString("app_role", mode).apply(); roleExpanded = false 
                            if (mode == "Viewer" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                                val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${context.packageName}") }
                                context.startActivity(intent)
                            }
                        }) }
                }
            }
            if (appRole == "Viewer") {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Make sure the Camera device is running its live stream before pushing settings.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.syncToCamera(context, targetIp, securityToken, onSuccess = { Toast.makeText(context, "Settings Synced!", Toast.LENGTH_SHORT).show() }, onError = { Toast.makeText(context, "Sync Failed: $it", Toast.LENGTH_LONG).show() }) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF009688)), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("📡 PUSH SETTINGS TO CAMERA", fontWeight = FontWeight.Bold) }
            }
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Connection & Networking", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Camera & Viewer must be on the same network. Install Tailscale VPN on both devices and enter the Tailscale IP below to access the camera securely from anywhere in the world.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            ExposedDropdownMenuBox(expanded = menuExpanded, onExpandedChange = { menuExpanded = !menuExpanded }) {
                OutlinedTextField(value = viewerMode, onValueChange = {}, readOnly = true, label = { Text("Routing Protocol") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors())
                ExposedDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    listOf("Local WiFi", "Firebase").forEach { mode -> DropdownMenuItem(text = { Text(mode) }, onClick = { viewerMode = mode; prefs.edit().putString("viewer_mode", mode).apply(); menuExpanded = false }) }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = targetIp, onValueChange = { targetIp = it; prefs.edit().putString("target_ip", it).apply() }, label = { Text("Camera IP Address (Tailscale or Local)") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Text("A unique password to encrypt and secure your stream. Must match exactly on both devices.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            OutlinedTextField(value = securityToken, onValueChange = { securityToken = it; prefs.edit().putString("security_token", it).apply() }, label = { Text("Master Auth Token") }, trailingIcon = { IconButton(onClick = { clipboardManager.setText(AnnotatedString(securityToken)) }) { Text("📋") } }, modifier = Modifier.fillMaxWidth())
            
            if (viewerMode == "Firebase") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = fbDbUrl, onValueChange = { fbDbUrl = it; prefs.edit().putString("fb_db_url", it).apply() }, label = { Text("Database URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fbApiKey, onValueChange = { fbApiKey = it; prefs.edit().putString("fb_api_key", it).apply() }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = fbAppId, onValueChange = { fbAppId = it; prefs.edit().putString("fb_app_id", it).apply() }, label = { Text("App ID") }, modifier = Modifier.fillMaxWidth())
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            // CRITICAL FIX: Resolution UI Section
            Text("Camera & Video Quality", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Higher camera resolution improves AI object detection significantly but uses more Wi-Fi bandwidth. The offline video resolution cannot exceed the live camera resolution.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            
            ExposedDropdownMenuBox(expanded = camResExpanded, onExpandedChange = { camResExpanded = !camResExpanded }) {
                OutlinedTextField(value = "${cameraResolution}p HD", onValueChange = {}, readOnly = true, label = { Text("Live Camera Resolution") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = camResExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors())
                ExposedDropdownMenu(expanded = camResExpanded, onDismissRequest = { camResExpanded = false }) {
                    camResOptions.forEach { res ->
                        DropdownMenuItem(text = { Text("${res}p") }, onClick = { 
                            cameraResolution = res; prefs.edit().putInt("camera_resolution", res).apply()
                            if (videoResolution > res) { videoResolution = res; prefs.edit().putInt("video_resolution", res).apply() }
                            camResExpanded = false 
                        })
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            ExposedDropdownMenuBox(expanded = vidResExpanded, onExpandedChange = { vidResExpanded = !vidResExpanded }) {
                OutlinedTextField(value = "${videoResolution}p", onValueChange = {}, readOnly = true, label = { Text("Offline Video Recording Resolution") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = vidResExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors())
                ExposedDropdownMenu(expanded = vidResExpanded, onDismissRequest = { vidResExpanded = false }) {
                    vidResOptions.forEach { res -> DropdownMenuItem(text = { Text("${res}p") }, onClick = { videoResolution = res; prefs.edit().putInt("video_resolution", res).apply(); vidResExpanded = false }) }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Local Biometric Vault", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Upload a photo. The AI will auto-crop the face. Faces are NOT synced remotely.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("📸 Upload Face Photo") }
            if (faces.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                faces.forEach { face ->
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Color(0xFF2C2C2C), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(face.name, modifier = Modifier.weight(1f), color = Color.White); IconButton(onClick = { viewModel.removeFace(context, face.id) }) { Text("🗑️", color = Color.Red) } }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Device Alerts & AI Tuning", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text("Popup Notifications") }; Switch(checked = popupNotifications, onCheckedChange = { popupNotifications = it; prefs.edit().putBoolean("enable_notifications", it).apply() }) }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text("Enable LLM Engine") }; Switch(checked = llmEnabled, onCheckedChange = { llmEnabled = it; prefs.edit().putBoolean("llm_enabled", it).apply() }) }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text("Enable Face Recognition") }; Switch(checked = faceRecogEnabled, onCheckedChange = { faceRecogEnabled = it; prefs.edit().putBoolean("face_recog_enabled", it).apply() }) }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text("Verbose Debug Mode", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }; Switch(checked = debugMode, onCheckedChange = { debugMode = it; prefs.edit().putBoolean("debug_mode", it).apply() }) }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text("Analyze 1 frame every: ${scanInterval.roundToInt()} seconds", style = MaterialTheme.typography.bodyMedium)
            Slider(value = scanInterval, onValueChange = { scanInterval = it }, onValueChangeFinished = { prefs.edit().putFloat("scan_interval_sec", scanInterval).apply() }, valueRange = 1f..60f)
            
            Spacer(modifier = Modifier.height(16.dp))
            Text("Record Video on Alert for: ${videoRecordLen.roundToInt()} seconds", style = MaterialTheme.typography.bodyMedium)
            Slider(value = videoRecordLen, onValueChange = { videoRecordLen = it }, onValueChangeFinished = { prefs.edit().putFloat("video_record_len", videoRecordLen).apply() }, valueRange = 5f..60f)

            Spacer(modifier = Modifier.height(24.dp))
            Text("Gemma 4 Vision Resolution (Token Budget)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Higher tokens (1120) allows the AI to zoom in and detect small objects better. Lower tokens (70) is significantly faster but blurrier.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            ExposedDropdownMenuBox(expanded = resExpanded, onExpandedChange = { resExpanded = !resExpanded }) {
                OutlinedTextField(value = "$llmResolution Tokens", onValueChange = {}, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors())
                ExposedDropdownMenu(expanded = resExpanded, onDismissRequest = { resExpanded = false }) {
                    listOf(70, 140, 280, 560, 1120).forEach { res -> DropdownMenuItem(text = { Text("$res Tokens") }, onClick = { llmResolution = res; prefs.edit().putInt("llm_resolution", res).apply(); resExpanded = false }) }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("AI Vision Prompt", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Tell the AI what to look for. If you tell it to reply 'CLEAR' when nothing happens, the app will silently log it as normal. Anything else triggers an Alert & Video.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = aiPrompt, onValueChange = { aiPrompt = it; prefs.edit().putString("prompt_usr", it).apply() }, label = { Text("Single AI Instruction") }, modifier = Modifier.fillMaxWidth())

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(24.dp))
            
            if (appRole == "Camera") {
                Text("Local LLM Model", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Current model loaded: ${viewModel.currentModelName}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !viewModel.isImporting, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) { Text("📁 Import Model from Device (.litertlm)") }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { viewModel.downloadCloudModel(context) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))) { Text("☁️ Download AI Model") }
            Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}