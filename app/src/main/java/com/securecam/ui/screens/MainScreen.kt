package com.securecam.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
                Text("Welcome to AI CCTV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text("To begin, how do you want to use this specific device?", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(48.dp))
                ElevatedCard(onClick = { appRole = "Camera"; prefs.edit().putString("app_role", "Camera").apply() }, modifier = Modifier.fillMaxWidth().height(100.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text("\uD83D\uDCF7 Use as Camera Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                }
                Spacer(modifier = Modifier.height(16.dp))
                ElevatedCard(onClick = { appRole = "Viewer"; prefs.edit().putString("app_role", "Viewer").apply() }, modifier = Modifier.fillMaxWidth().height(100.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text("\uD83D\uDC41\uFE0F Use as Viewer Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                }
            }
        }
        return
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("How to use AI CCTV", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                    HelpBlock(
                        title = "\uD83C\uDFAF  WHAT IS AI CCTV?",
                        body  = "AI CCTV turns two Android phones into a private, AI-powered security system. No cloud. No subscription. Your footage never leaves your devices."
                    )

                    HelpBlock(
                        title = "\uD83D\uDCF7  CAMERA DEVICE — STEP BY STEP",
                        body  = "1. Install AI CCTV on the phone you want as camera.\n" +
                                "2. Open the app \u2192 tap \u201cUse as Camera Device\u201d.\n" +
                                "3. Grant Camera, Microphone & Notification permissions.\n" +
                                "4. Tap \u201cRun as Camera Device\u201d from the home screen.\n" +
                                "5. WatchTower AI Engine starts and begins analyzing the scene.\n\n" +
                                "\uD83D\uDCA1 Tip: Keep it plugged in and set screen timeout to Never for 24/7 monitoring."
                    )

                    HelpBlock(
                        title = "\uD83D\uDC41\uFE0F  VIEWER DEVICE — STEP BY STEP",
                        body  = "1. Install AI CCTV on your main phone.\n" +
                                "2. Open the app \u2192 tap \u201cUse as Viewer Device\u201d.\n" +
                                "3. Tap \u201cRun as Viewer Device\u201d from the home screen.\n" +
                                "4. Enter the Camera phone's IP address \u2192 tap Connect.\n" +
                                "5. Live feed, AI alerts & Face Recognition events appear in real time."
                    )

                    HelpBlock(
                        title = "\uD83E\uDD16  AI MODEL — DOWNLOAD & IMPORT",
                        body  = "The on-device AI requires a one-time model download (~1.5 GB).\n\n" +
                                "OPTION A \u2014 Download inside app:\n" +
                                "Settings \u2192 AI Monitor \u2192 tap \u201cDownload AI Model\u201d. Keep the screen on until complete.\n\n" +
                                "OPTION B \u2014 Import from local storage:\n" +
                                "Download the .litertlm model file on any device, transfer it to your phone, then go to Settings \u2192 AI Monitor \u2192 tap \u201cImport from Local\u201d and select the file.\n\n" +
                                "\uD83D\uDCA1 Once downloaded, the model works fully offline with no internet needed."
                    )

                    HelpBlock(
                        title = "\uD83D\uDC64  FACE DETECTION & RECOGNITION",
                        body  = "AI CCTV continuously detects all faces in the frame. You can enroll specific people so the app recognizes them by name.\n\n" +
                                "TO ENROLL A FACE:\n" +
                                "1. Settings \u2192 Face Recognition \u2192 tap \u201cAdd Person\u201d.\n" +
                                "2. Enter the person\u2019s name.\n" +
                                "3. Take or select up to 5 clear, well-lit photos of their face.\n" +
                                "4. Tap Save \u2014 the app builds a unique face profile.\n\n" +
                                "\uD83D\uDCA1 Use photos from different angles and lighting for best accuracy. Only enrolled faces trigger named alerts; all other detected faces show as \u201cUnknown\u201d."
                    )

                    HelpBlock(
                        title = "\uD83D\uDCE1  LOCAL WI-FI CONNECTION",
                        body  = "Both phones must be on the same Wi-Fi network.\n\n" +
                                "\u2022 Camera phone: Settings \u2192 Wi-Fi \u2192 tap your network name \u2192 find IP address (e.g. 192.168.1.105).\n" +
                                "\u2022 Enter that IP on the Viewer device and connect."
                    )

                    HelpBlock(
                        title = "\uD83C\uDF0D  REMOTE ACCESS — TAILSCALE",
                        body  = "Tailscale creates a private encrypted tunnel. No port-forwarding required.\n\n" +
                                "1. Install Tailscale (free) on BOTH phones from the Play Store.\n" +
                                "2. Sign in with the same Google or GitHub account on both.\n" +
                                "3. Camera phone: open Tailscale \u2192 tap your device name \u2192 copy the 100.x.x.x IP shown.\n" +
                                "4. Enter that IP on the Viewer \u2014 works from anywhere in the world."
                    )

                    HelpBlock(
                        title = "\uD83D\uDCF6  NO WI-FI? USE CAMERA AS HOTSPOT",
                        body  = "1. Camera phone: Settings \u2192 Network \u2192 Hotspot & Tethering \u2192 Wi-Fi Hotspot \u2192 ON.\n" +
                                "2. Connect your Viewer phone to that hotspot.\n" +
                                "3. Camera\u2019s hotspot IP is usually 192.168.43.1 \u2014 enter it on the Viewer."
                    )

                    HelpBlock(
                        title = "\u2699\uFE0F  SYNCING SETTINGS",
                        body  = "On the Viewer device, open Settings. Adjust AI Prompts, Face Recognition, or LLM Monitor settings, then tap \u201cSync Settings to Camera\u201d to push your config over the network instantly."
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Got it") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI CCTV", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = { showHelp = true }) { Icon(Icons.Default.Info, contentDescription = "Help") }
                    TextButton(onClick = { navController.navigate("settings") }) { Text("\u2699\uFE0F Settings") }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("WatchTower AI Engine", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(48.dp))
            ElevatedCard(onClick = { navController.navigate("camera") }, modifier = Modifier.fillMaxWidth().height(90.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text("\uD83D\uDCF7 Run as Camera Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            ElevatedCard(onClick = { navController.navigate("viewer") }, modifier = Modifier.fillMaxWidth().height(90.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text("\uD83D\uDC41\uFE0F Run as Viewer Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            ElevatedCard(onClick = { navController.navigate("logs") }, modifier = Modifier.fillMaxWidth().height(90.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text("\uD83D\uDCCB Offline Security Logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun HelpBlock(title: String, body: String) {
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Text(
            text  = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = body, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(modifier = Modifier.padding(top = 12.dp), thickness = 0.5.dp)
    }
}