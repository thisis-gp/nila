package com.nila.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * A short foreground service for the length of one sign-in. While a browser tab covers NILA, Android's
 * cached-app freezer suspends the process (isFrozen=true on the iQOO 15), so the loopback thread that waits
 * for the OAuth redirect never runs. A foreground service keeps the process out of the freezer.
 */
class SignInKeepAlive : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val service = intent?.getStringExtra(EXTRA_SERVICE) ?: "your account"
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Signing in to $service")
            .setContentText("Finish in the browser, NILA is waiting.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    /** shortService allows about 3 minutes; past that the sign-in has its own timeout, so just stop. */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "sign_in"
        private const val NOTIFICATION_ID = 41
        private const val EXTRA_SERVICE = "service"

        fun start(context: Context, service: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Sign-in", NotificationManager.IMPORTANCE_LOW))
            runCatching {
                context.startForegroundService(Intent(context, SignInKeepAlive::class.java).putExtra(EXTRA_SERVICE, service))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SignInKeepAlive::class.java))
        }
    }
}
