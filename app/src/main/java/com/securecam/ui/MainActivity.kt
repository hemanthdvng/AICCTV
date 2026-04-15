package com.securecam.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.securecam.ui.screens.*
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. GLOBAL CRASH REPORTER: Catch fatal errors and write them to the Downloads folder.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            try {
                val time = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val trace = Log.getStackTraceString(exception)
                
                // Attempt to write to public Downloads folder for easy user access
                val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (publicDir != null) {
                    publicDir.mkdirs()
                    File(publicDir, "AICCTV_Crash_$time.txt").writeText("CRASH TIME: $time\n\n$trace")
                }
                
                // Fallback: Write to App-specific external storage
                val appDir = getExternalFilesDir(null)
                if (appDir != null) {
                    File(appDir, "AICCTV_Crash_$time.txt").writeText("CRASH TIME: $time\n\n$trace")
                }
            } catch (e: Exception) {
                // Failsafe ignored
            } finally {
                defaultHandler?.uncaughtException(thread, exception)
            }
        }

        super.onCreate(savedInstanceState)
        
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {}
        
        // 2. CRASH FIX: Removed the premature startForegroundService(AlertService). 
        // Booting it here without POST_NOTIFICATIONS permission causes fatal crashes on Android 13+.
        // It is now safely deferred to CameraScreen and ViewerScreen.

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") { MainScreen(navController) }
                        composable("camera") { CameraScreen(navController) }
                        composable("settings") { SettingsScreen(navController) }
                        composable("viewer") { ViewerScreen(navController) }
                        composable("logs") { LogsScreen(navController) }
                    }
                }
            }
        }
    }
}