package to.eyed.inferno.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import to.eyed.inferno.MainActivity
import to.eyed.inferno.R

/**
 * specialUse foreground service that keeps a user-started generation alive while the app is in the background.
 * Policy (spec 5.2): NOT started per message. InferenceEngine starts it from onAppForeground(false) only while a
 * generation is running and stops it when the app returns or the turn ends, so a 2-second reply with the screen
 * on never touches the notification shade. Tap opens MainActivity; "Stop" delivers EXTRA_ACTION = ACTION_STOP to
 * the singleTop activity (onNewIntent -> PendingAction.Stop -> chatVm.cancel()).
 */
class EngineService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val heading = intent?.getStringExtra(EXTRA_HEADING) ?: HEADING_TEXT
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Inferno"
        ensureChannel(this)
        ServiceCompat.startForeground(this, NOTIF_ID, notification(heading, title), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_NOT_STICKY
    }

    /** API 35+: the system may time a specialUse service out; the native job keeps running in-process regardless. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // stopService() from InferenceEngine lands here: drop the notification with the service, never leak it.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(heading: String, modelName: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Distinct request code + extra: PendingIntents with equal Intents (ignoring extras) would collapse into one.
        val stop = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_ACTION, ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(heading)
            .setContentText(modelName)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val CHANNEL = "engine"
        const val NOTIF_ID = 1001
        /** Intent extra read by MainActivity.onCreate/onNewIntent; value [ACTION_STOP]. */
        const val EXTRA_ACTION = "to.eyed.inferno.extra.ACTION"
        const val ACTION_STOP = "stop"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEADING = "heading"
        const val HEADING_TEXT = "Inferno is generating"
        /** Second notification type (12.5): the image-generation repository passes this heading while it owns the job. */
        const val HEADING_IMAGE = "Inferno is generating an image"

        /** startForegroundService; idempotent (a second start just refreshes the notification text). */
        fun start(context: Context, title: String, heading: String = HEADING_TEXT) {
            val app = context.applicationContext
            try {
                ContextCompat.startForegroundService(app, Intent(app, EngineService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_HEADING, heading))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException & co.: the generation continues without the badge.
                EngineLog.w("EngineService", "start refused: ${e.message}")
            }
        }

        /** stopService is allowed from any process state; a start still in flight is cancelled with it. */
        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, EngineService::class.java))
        }

        /** InfernoApp creates the channel too; creating it twice with the same parameters is a no-op. */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Engine", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown only while a reply is being generated in the background"
                    setShowBadge(false)
                })
            }
        }
    }
}
