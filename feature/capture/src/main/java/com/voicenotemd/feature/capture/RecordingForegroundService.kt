package com.voicenotemd.feature.capture

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
 * Minimal foreground service whose only job is to keep the recording process alive and to
 * signal legitimate microphone use while the screen is off or the app is backgrounded — the
 * enabler for hands-free / in-car dictation (ADR 0018).
 *
 * It does NOT own the `AudioRecord`: capture still runs in the capture session/ViewModel.
 * This service only holds foreground status (type microphone) plus a persistent notification
 * so the OS neither kills the process nor revokes background mic access mid-dictation.
 *
 * Privacy: the ongoing notification keeps recording visible to the user at all times. No
 * audio is touched here, so the no-persistence guarantees (ADR 0002) are unchanged.
 */
class RecordingForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        createChannel()
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.capture_notification_title))
                .setContentText(getString(R.string.capture_notification_text))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // The capture session, not the OS, decides when recording ends; we don't want the
        // service recreated after a process kill.
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.capture_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.capture_notification_channel_description) },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "notari_recording"
        private const val NOTIFICATION_ID = 4711

        /** Start the recording foreground service (safe to call repeatedly). */
        fun start(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java)
            context.startForegroundService(intent)
        }

        /** Stop the service. No-op if it isn't running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }
    }
}
