package com.example.myapplication.camera

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R

/** Foreground-service boundary used only while a short DAT stream is active. */
class DatStreamingService : Service() {
    companion object {
        private const val ACTION_START = "com.example.myapplication.camera.START_DAT_STREAM"
        private const val CHANNEL_ID = "dat_camera_stream"
        private const val NOTIFICATION_ID = 4107
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, DatStreamingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DatStreamingService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("따라쿡 카메라 확인 중")
            .setContentText("안경 카메라를 잠시 사용하고 있습니다.")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "안경 카메라",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DdaRaCook:DatCamera"
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }
}
