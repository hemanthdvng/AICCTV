package com.securecam.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
    var appRole by remember { mutableStateOf(prefs.getString("app_role", null)) }
    var showHelp by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    if (appRole == null) {
        Scaffold { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Welcome to SecureCam", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text("To begin, how do you want to use this specific device?", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(48.dp))
                ElevatedCard(onClick = { appRole = "Camera"; prefs.edit().putString("app_role", "Camera").apply() }, modifier = Modifier.fillMaxWidth().height(100.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text("📷 Use as Camera Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                }
                Spacer(modifier = Modifier.height(16.dp))
                ElevatedCard(onClick = { appRole = "Viewer"; prefs.edit().putString("app_role", "Viewer").apply() }, modifier = Modifier.fillMaxWidth().height(100.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text("👁️ Use as Viewer Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                }
            }
        }
        return
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("How to use SecureCam") },
            text = { Text("SecureCam uses two devices to create a private security network.\n\n1. CAMERA DEVICE:\nPlace your old phone in the room and open 'Camera Mode'. It will analyze the room using AI.\n\n2. VIEWER DEVICE:\nUse your main phone and open 'Viewer Mode' to watch the live feed.\n\nSYNCING:\nGo to Settings on your Viewer device, adjust your AI Prompts or Face Recognition, and click 'Sync Settings to Camera' to push your rules remotely over Wi-Fi.") },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Got it") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SecureCam 5", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer, titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                actions = {
                    IconButton(onClick = { showHelp = true }) { Icon(Icons.Default.Info, contentDescription = "Help") }
                    TextButton(onClick = { navController.navigate("settings") }) { Text("⚙️ Settings") }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("WatchTower AI Engine", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(48.dp))
            ElevatedCard(onClick = { navController.navigate("camera") }, modifier = Modifier.fillMaxWidth().height(90.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text("📷 Run as Camera Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            ElevatedCard(onClick = { navController.navigate("viewer") }, modifier = Modifier.fillMaxWidth().height(90.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text("👁️ Run as Viewer Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            ElevatedCard(onClick = { navController.navigate("logs") }, modifier = Modifier.fillMaxWidth().height(90.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text("📋 Offline Security Logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
        }
    }
}