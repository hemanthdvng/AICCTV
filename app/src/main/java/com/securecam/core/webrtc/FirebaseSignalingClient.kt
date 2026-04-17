package com.securecam.core.webrtc

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.ValueEventListener

class FirebaseSignalingClient(context: Context, private val role: String) {
    private val TAG = "SignalingClient"
    private var database: FirebaseDatabase? = null
    private val initTime = System.currentTimeMillis()
    
    var onConnected: (() -> Unit)? = null
    var onJoinReceived: (() -> Unit)? = null
    var onOfferReceived: ((String) -> Unit)? = null
    var onAnswerReceived: ((String) -> Unit)? = null
    var onIceCandidateReceived: ((String) -> Unit)? = null

    init {
        val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
        val dbUrl = prefs.getString("fb_db_url", "") ?: ""
        val apiKey = prefs.getString("fb_api_key", "") ?: ""
        val appId = prefs.getString("fb_app_id", "") ?: ""

        if (dbUrl.isNotBlank() && apiKey.isNotBlank() && appId.isNotBlank()) {
            try {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    val options = FirebaseOptions.Builder()
                        .setDatabaseUrl(dbUrl)
                        .setApiKey(apiKey)
                        .setApplicationId(appId)
                        .build()
                    FirebaseApp.initializeApp(context, options)
                }
                database = FirebaseDatabase.getInstance()
                
                database?.getReference(".info/connected")?.addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val connected = snapshot.getValue(Boolean::class.java) ?: false
                        if (connected) onConnected?.invoke()
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })

                listenForSignals()
            } catch (e: Exception) {
                Log.e(TAG, "Firebase Init Error", e)
            }
        }
    }

    private fun listenForSignals() {
        database?.getReference("securecam/signals")?.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val sender = snapshot.child("sender").getValue(String::class.java)
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                
                // CRASH FIX: Ignore historical Ghost Signals from before this app instance opened
                if (sender == role || timestamp < initTime - 10000) return 

                val type = snapshot.child("type").getValue(String::class.java)
                val sdp = snapshot.child("sdp").getValue(String::class.java)
                
                if (type == "JOIN") onJoinReceived?.invoke()
                if (type == "OFFER" && sdp != null) onOfferReceived?.invoke(sdp)
                if (type == "ANSWER" && sdp != null) onAnswerReceived?.invoke(sdp)
                if (type == "ICE" && sdp != null) onIceCandidateReceived?.invoke(sdp)
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    fun sendSignal(type: String, sdp: String = "") {
        val payload = mapOf(
            "type" to type, 
            "sdp" to sdp, 
            "sender" to role,
            "timestamp" to System.currentTimeMillis()
        )
        database?.getReference("securecam/signals")?.push()?.setValue(payload)
    }

    fun clearSignals() {
        database?.getReference("securecam/signals")?.removeValue()
    }
}