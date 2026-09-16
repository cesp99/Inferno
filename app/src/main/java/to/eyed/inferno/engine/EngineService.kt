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
 * Policy: NOT started per message. InferenceEngine starts it from onAppForeground(false) only while a
 * generation (or, via EngineCoordinator.onImageJob, an image run) is running and stops it when the app returns or
 * the job ends, so a 2-second reply with the screen on never touches the notification shade. Tap opens MainActivity; "Stop" delivers EXTRA_ACTION = ACTION_STOP to
 * the singleTop activity (onNewIntent -> PendingAction.Stop -> chatVm.cancel()).
 */
class EngineService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val heading = intent?.getStringExtra(EXTRA_HEADING) ?: HEADING_TEXT
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Inferno"
        ensureChannel(this)
        ServiceCompat.startForeground(this, NOTIF_ID, notification(heading, title), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        // A stop() that arrived while startForegroundService() was still in flight is honoured only now: stopping a
        // service before its startForeground() ran kills the process with RemoteServiceException.
        if (settleStart()) stopForegroundAndSelf()
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
        /** Second notification type: the image-generation repository passes this heading while it owns the job. */
        const val HEADING_IMAGE = "Inferno is generating an image"

        // Start/stop bookkeeping. start()/stop() are called from the engine (any thread), onStartCommand on Main.
        private val lock = Any()
        /** startForegroundService() calls whose onStartCommand (and thus startForeground) has not run yet. */
        private var pendingStarts = 0
        /** A stop() arrived while a start was pending; onStartCommand performs it once nothing is pending. */
        private var stopRequested = false

        /** startForegroundService; idempotent (a second start just refreshes the notification text). */
        fun start(context: Context, title: String, heading: String = HEADING_TEXT) {
            val app = context.applicationContext
            synchronized(lock) { stopRequested = false; pendingStarts++ }
            try {
                ContextCompat.startForegroundService(app, Intent(app, EngineService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_HEADING, heading))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException & co.: the generation continues without the badge.
                synchronized(lock) { pendingStarts = (pendingStarts - 1).coerceAtLeast(0) }
                EngineLog.w("EngineService", "start refused: ${e.message}")
            }
        }

        /**
         * stopService is allowed from any process state, but never while a startForegroundService() has not yet
         * reached startForeground(): AMS treats that as "did not then call startForeground()" and kills the process.
         * In that window the stop is recorded and issued from onStartCommand instead.
         */
        fun stop(context: Context) {
            synchronized(lock) {
                if (pendingStarts > 0) { stopRequested = true; return }
            }
            val app = context.applicationContext
            app.stopService(Intent(app, EngineService::class.java))
        }

        /** onStartCommand: one pending start has reached startForeground(); true when a deferred stop is now due. */
        private fun settleStart(): Boolean = synchronized(lock) {
            pendingStarts = (pendingStarts - 1).coerceAtLeast(0)
            if (pendingStarts == 0 && stopRequested) { stopRequested = false; true } else false
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
