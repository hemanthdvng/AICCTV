package com.securecam.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.securecam.data.local.LogDatabase
import com.securecam.data.local.SecurityLogEntity
import com.securecam.data.repository.EventRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import androidx.room.Room

@AndroidEntryPoint
class AlertService : Service() {
    @Inject lateinit var eventRepository: EventRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY 
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification("SecureCam Background Service Active"))
        
        val prefs = getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)

        // PATCH: Unified Notification Listener. Triggers safely via internal repository for both Camera and Viewer architectures.
        serviceScope.launch {
            eventRepository.securityEvents.collect { event ->
                val isSafe = event.description.contains("Safe", ignoreCase = true) || event.description.contains("CLEAR", ignoreCase = true)
                if (!isSafe && !event.description.contains("[SYSTEM]")) {
                    showPopupNotification(event.description)
                }
            }
        }

        // Keeps fetching remote logs strictly for database sync, avoiding duplicate raw-socket popup triggers.
        serviceScope.launch {
            while (isActive) {
                val appRole = prefs.getString("app_role", "Camera") ?: "Camera"
                if (appRole == "Viewer") {
                    try {
                        val ip = prefs.getString("target_ip", "") ?: ""
                        val token = prefs.getString("security_token", "") ?: ""
                        if (ip.isNotBlank()) {
                            val url = URL("http://$ip:8082/api/logs?token=$token")
                            val connection = url.openConnection() as HttpURLConnection
                            connection.connectTimeout = 5000
                            if (connection.responseCode == 200) {
                                val json = connection.inputStream.bufferedReader().readText()
                                val type = object : TypeToken<List<SecurityLogEntity>>() {}.type
                                val remoteLogs: List<SecurityLogEntity> = Gson().fromJson(json, type)
                                
                                val db = Room.databaseBuilder(applicationContext, LogDatabase::class.java, "securecam_db").build()
                                val localLogs = db.logDao().getAllLogsSync()
                                val localIds = localLogs.map { it.logTime }.toSet()
                                
                                remoteLogs.forEach { log ->
                                    if (!localIds.contains(log.logTime)) {
                                        db.logDao().insertLog(log)
                                    }
                                }
                                db.close()
                            }
                        }
                    } catch(e: Exception){}
                }
                delay(15000) 
            }
        }
    }

    private fun showPopupNotification(text: String) {
        val prefs = getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enable_notifications", true)) return
        
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        try {
            val notif = NotificationCompat.Builder(this, "securecam_alerts")
                .setContentTitle("🚨 Security Alert")
                .setContentText(text)
                .setSmallIcon(applicationInfo.icon) // PATCH: Bypasses Android 14+ OEM system drawable suppression
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val safeId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            manager.notify(safeId, notif)
        } catch(e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel("securecam_service", "SecureCam Background", NotificationManager.IMPORTANCE_LOW)
            val alertChannel = NotificationChannel("securecam_alerts", "SecureCam Alerts", NotificationManager.IMPORTANCE_HIGH)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "securecam_service")
            .setContentTitle("AI CCTV")
            .setContentText(text)
            .setSmallIcon(applicationInfo.icon) // PATCH: Native icon enforcing
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}