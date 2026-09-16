package to.eyed.inferno.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * dataSync foreground service that drives [ModelRepository.runQueue] and shows one progress notification
 * (spec 5.3). It exists only so a multi-GB transfer survives the app going to the background; every decision
 * (queue order, pause reasons, resume policy) lives in the repository, which the Application exposes through
 * [Host]. Holds a PARTIAL_WAKE_LOCK (30 min timeout, renewed while a file keeps transferring) + a high-performance
 * Wi-Fi lock while a file transfers; both are released in `finally`.
 */
class DownloadService : Service() {

    /** Implemented by `InfernoApp` (WP5): `override val modelRepository get() = container.models`. */
    interface Host { val modelRepository: ModelRepository }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var queueJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lockedFile: Pair<String, Int>? = null
    private var lockAcquiredMs = 0L
    private var lastNotifyMs = 0L
    private var lastPct = -1
    /** Last posted notification, re-used when a Cancel action re-enters onStartCommand while another file transfers. */
    private var shown: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = (application as? Host)?.modelRepository
        if (repo == null) { Log.w(TAG, "Application does not implement DownloadService.Host"); stopSelf(startId); return START_NOT_STICKY }
        // The notification's Cancel action is a start too: it must fall through so its startId gets stopped below,
        // otherwise the running job's stopSelf(olderId) is refused and an idle service outlives the queue.
        val cancel = intent?.action == ACTION_CANCEL
        if (cancel) intent?.getStringExtra(EXTRA_MODEL_ID)?.let(repo::cancelDownload)
        val n = shown?.takeIf { cancel } ?: buildNotification("Preparing download", null, 0, null)
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // Every start gets its own job, serialized behind the previous one, and stops the service only with its own
        // startId: an enqueue that reaches AMS while the previous job is exiting (runQueue saw an empty queue, the
        // repository re-added an id a moment later) is refused by stopSelf(oldId) and drained by the new job instead
        // of leaving the row "Queued" forever.
        val previous = queueJob
        queueJob = serviceScope.launch {
            previous?.join()
            try {
                if (!repo.isQueueEmpty) repo.runQueue { id, state -> onState(repo, id, state) }
            } catch (e: Exception) {
                Log.w(TAG, "queue ended: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                releaseLocks()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun onState(repo: ModelRepository, id: String, state: DownloadState) {
        val name = repo.displayName(id)
        when (state) {
            is DownloadState.Downloading -> {
                val key = id to state.fileIndex
                val now = System.currentTimeMillis()
                if (lockedFile != key) { lockedFile = key; acquireLocks() }
                // A single 2-4 GB file on a slow link outlives the 30 min timeout: renew while it is still transferring.
                else if (now - lockAcquiredMs > WAKE_TIMEOUT_MS * 2 / 3) { lockAcquiredMs = now; wakeLock?.acquire(WAKE_TIMEOUT_MS) }
                val pct = (state.fraction * 100).toInt().coerceIn(0, 100)
                // NotificationManager rate-limits updates; ticks arrive every 250 ms so post at most twice a second.
                if (pct != lastPct && now - lastNotifyMs >= 500) {
                    lastPct = pct; lastNotifyMs = now
                    notify(buildNotification(name, "${fmt(state.downloadedBytes)} of ${fmt(state.totalBytes)} · ${fmt(state.bytesPerSec)}/s", pct, id))
                }
            }
            is DownloadState.Verifying -> notify(buildNotification(name, "Verifying", 100, id))
            is DownloadState.Downloaded, is DownloadState.Paused, is DownloadState.Failed -> {
                releaseLocks(); lockedFile = null; lastPct = -1
                if (!repo.isQueueEmpty) notify(buildNotification("Preparing download", null, 0, null))
            }
            else -> Unit
        }
    }

    private fun acquireLocks() {
        releaseLocks()
        val pm = getSystemService(PowerManager::class.java)
        lockAcquiredMs = System.currentTimeMillis()
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply { setReferenceCounted(false); acquire(WAKE_TIMEOUT_MS) }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKE_TAG)?.apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }; wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }; wifiLock = null
    }

    /** Downloads are user-initiated: keep going when the task is swiped away, unless there is nothing left. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val repo = (application as? Host)?.modelRepository
        if (repo == null || repo.isQueueEmpty) stopSelf()
    }

    /** dataSync budget exhausted (API 35+): pause, keep the .part, and let ON_START restart the queue. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as? Host)?.modelRepository?.pauseAll("Paused by the system")
        stopSelf()
    }

    override fun onDestroy() {
        queueJob?.cancel()
        serviceScope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun notify(n: Notification) { shown = n; getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }

    private fun buildNotification(title: String, text: String?, pct: Int, cancelId: String?): Notification {
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, false)
        text?.let { b.setContentText(it) }
        launchIntent()?.let { b.setContentIntent(it) }
        if (cancelId != null) {
            val cancel = Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_MODEL_ID, cancelId)
            val pi = PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "Cancel", pi).build())
        }
        return b.build()
    }

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val TAG = "Inferno/DownloadService"
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 2
        const val ACTION_CANCEL = "to.eyed.inferno.action.CANCEL_DOWNLOAD"
        const val EXTRA_MODEL_ID = "modelId"
        private const val WAKE_TAG = "inferno:download"
        private const val WAKE_TIMEOUT_MS = 30L * 60 * 1000

        /**
         * False when the OS refuses the start (ForegroundServiceStartNotAllowedException: background app on API 31+,
         * exhausted dataSync budget on API 35+). The caller keeps the id for ON_START instead of crashing the process.
         */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, DownloadService::class.java)); true
        } catch (e: Exception) {
            Log.w(TAG, "start refused: ${e.javaClass.simpleName}: ${e.message}"); false
        }

        /** Idempotent; InfernoApp creates the same channel at startup, this covers a cold start straight into the service. */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Model download progress"; setShowBadge(false)
                })
            }
        }

        /** "1.2 GB", "824 MB", "12 MB" (decimal units, like the catalog and the storage bar). */
        fun fmt(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
            bytes >= 1_000_000L -> "${bytes / 1_000_000} MB"
            bytes >= 1_000L -> "${bytes / 1_000} kB"
            else -> "$bytes B"
        }
    }
}
