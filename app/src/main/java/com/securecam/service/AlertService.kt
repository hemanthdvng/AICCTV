package com.securecam.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.securecam.data.local.SecurityLogEntity
import com.securecam.data.repository.EventRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

@AndroidEntryPoint
class AlertService : Service() {
    @Inject lateinit var eventRepository: EventRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // BUG 3 FIX: WakeLock prevents CPU sleep during socket polling
    private var wakeLock: PowerManager.WakeLock? = null

    // CRITICAL FIX: @Volatile secures immediate cross-thread visibility on socket teardown
    @Volatile private var viewerSocket: Socket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // BUG 3 FIX: Acquire partial wake lock so CPU stays alive for socket polling
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SecureCam:AlertServiceWakeLock"
        ).also { it.acquire() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                buildNotification("AI CCTV Background Service Active"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } else {
            startForeground(1, buildNotification("AI CCTV Background Service Active"))
        }

        val prefs = getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)

        serviceScope.launch {
            eventRepository.securityEvents.collect { event ->
                val appRole = prefs.getString("app_role", "Camera") ?: "Camera"
                if (appRole == "Camera") {
                    val isSafe = event.description.contains("Safe", ignoreCase = true) || event.description.contains("CLEAR", ignoreCase = true)
                    if (!isSafe && !event.description.contains("[SYSTEM]")) {
                        showPopupNotification(event.description)
                    }
                }
            }
        }

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

                                val localIds = eventRepository.getLocalLogIds()

                                remoteLogs.forEach { log ->
                                    if (!localIds.contains(log.logTime)) {
                                        eventRepository.saveLog(log)
                                    }
                                }
                            }
                        }
                    } catch(e: Exception){}
                }
                delay(15000)
            }
        }

        serviceScope.launch {
            while (isActive) {
                val appRole = prefs.getString("app_role", "Camera") ?: "Camera"
                if (appRole == "Viewer") {
                    try {
                        val ip = prefs.getString("target_ip", "") ?: ""
                        val token = prefs.getString("security_token", "") ?: ""
                        if (ip.isNotBlank()) {
                            viewerSocket = Socket(ip, 8081)

                            val out = java.io.PrintWriter(viewerSocket!!.getOutputStream(), true)
                            out.println(token)

                            val reader = BufferedReader(InputStreamReader(viewerSocket!!.getInputStream()))
                            while (isActive && prefs.getString("app_role", "Camera") == "Viewer") {
                                val line = reader.readLine() ?: break
                                val map = Gson().fromJson(line, Map::class.java)
                                if (map["type"] == "ALERT") {
                                    val text = map["text"] as? String ?: ""
                                    val vidPath = map["videoPath"] as? String
                                    val isSafe = text.contains("Safe", ignoreCase = true) || text.contains("CLEAR", ignoreCase = true)

                                    val exactTime = (map["timestamp"] as? Double)?.toLong() ?: System.currentTimeMillis()

                                    eventRepository.saveLog(SecurityLogEntity(
                                        logTime = exactTime,
                                        type = if(text.contains("Face")) "BIOMETRIC" else "LLM_INSIGHT",
                                        description = text,
                                        confidence = 1.0f,
                                        videoPath = vidPath
                                    ))

                                    if (!isSafe && !text.contains("[SYSTEM]")) {
                                        showPopupNotification(text)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
                delay(5000)
            }
        }
    }

    private fun showPopupNotification(text: String) {
        val prefs = getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enable_notifications", true)) return

        val notif = NotificationCompat.Builder(this, "securecam_alerts")
            .setContentTitle("🚨 Security Alert")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel("securecam_service", "AI CCTV Background", NotificationManager.IMPORTANCE_LOW)
            val alertChannel = NotificationChannel("securecam_alerts", "AI CCTV Alerts", NotificationManager.IMPORTANCE_HIGH)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, "securecam_service")
            .setContentTitle("AI CCTV")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            // BUG 2 FIX: setOngoing prevents user from swiping away the foreground
            // service notification, which would stop the service on Android 13+
            .setOngoing(true)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, AlertService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try { viewerSocket?.close() } catch (e: Exception) {}
        // BUG 3 FIX: Release wake lock on destroy
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
