package com.cloudflare.tunnel

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class TunnelService : Service() {

    companion object {
        const val CHANNEL_ID = "tunnel_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE = "ACTION_UPDATE"
        const val ACTION_STOP_ALL = "ACTION_STOP_ALL"
    }

    inner class TunnelBinder : Binder() {
        fun getService(): TunnelService = this@TunnelService
    }

    private val binder = TunnelBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var runningCount = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val instanceName = intent.getStringExtra("instance_name") ?: "Tunnel"
                runningCount++
                startForeground(NOTIFICATION_ID, buildNotification("$instanceName is running", runningCount))
            }
            null -> {
                // প্রসেস কিলের পর রিস্টার্ট — নোটিফিকেশন ছাড়া চালানো যাবে না
                stopSelf()
            }
            ACTION_UPDATE -> {
                runningCount = intent.getIntExtra("running_count", 0)
                if (runningCount > 0) {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification("$runningCount tunnel(s) active", runningCount))
                } else {
                    stopForeground(true)
                    stopSelf()
                }
            }
            ACTION_STOP, ACTION_STOP_ALL -> {
                // নোটিফিকেশনের "Stop All" — রানিং টানেলও বন্ধ করি
                (application as App).tunnelManager.stopAllTunnels()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun buildNotification(content: String, count: Int): Notification {
        val stopAllIntent = PendingIntent.getService(
            this, 0,
            Intent(this, TunnelService::class.java).apply { action = ACTION_STOP_ALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("☁ Cloudflare Tunnel")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop All", stopAllIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tunnel Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Cloudflare Tunnel background service"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CloudflareTunnel::TunnelService"
        ).apply {
            acquire(10 * 60 * 60 * 1000L) // 10 hours max
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
    }
}