package io.github.mukhtharcm.simbridge

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.telecom.TelecomManager
import android.util.Log
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Keeps the process in the foreground with the microphone capability, which Android requires
 * before it gives a background app real audio from a record stream instead of silence.
 */
class BridgeService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("bridge", "simbridge", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "bridge")
            .setContentTitle("simbridge is running")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .build()
        startForeground(
            1, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        // If the app died during a call, the handset's microphone and earpiece are still switched off
        // at the mixer. Put them back, unless a call is up and the bridge is using them now.
        val inCall = try { getSystemService(TelecomManager::class.java).isInCall } catch (_: SecurityException) { false }
        if (!inCall) thread { Profile.forThisDevice(this)?.let { Root.tinymix(it.mixerRestore) } }
        Hub.start(this)
        Log.i("simbridge", "foreground service up (inCall=$inCall)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}

/** Exists because Android only offers the default-phone-app role to apps with a dial screen. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, BridgeService::class.java))
        setContentView(TextView(this).apply { text = "simbridge is running. It has no screen; see docs/API.md." })
    }
}
