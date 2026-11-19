package dev.mark.lineover.feature.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.mark.lineover.core.io.export.ExportTarget
import dev.mark.lineover.core.model.ExportSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Runs an export in the foreground (Stage 12) so it finishes even if the person leaves the
 * app: a notification shows the progress (with Cancel), then "Saved" with Open and Share.
 * Type mediaProcessing on Android 15+, dataSync on 14. One export at a time.
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }
        val request = intent?.let { requestOf(it) }
        if (request == null || job?.isActive == true) {
            if (job?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureChannel(this)
        startInForeground(progressNotification(ExportStatus.Running("", ExportStep.Opening, 0f)))
        val environment = (application as ExportEnvironment.Provider).exportEnvironment
        job =
            scope.launch {
                val result =
                    try {
                        ExportRunner(this@ExportService, environment).run(request) { running ->
                            ExportJobs.update(running)
                            notifyProgress(running)
                        }
                    } catch (e: CancellationException) {
                        ExportStatus.Cancelled
                    } catch (e: Exception) {
                        Log.e(TAG, "export failed", e)
                        ExportStatus.Failed(e.message ?: e.javaClass.simpleName)
                    } catch (e: OutOfMemoryError) {
                        Log.e(TAG, "export ran out of memory", e)
                        ExportStatus.Failed("Not enough memory: choose a smaller size")
                    }
                ExportJobs.update(result)
                finish(result)
            }
        ExportJobs.update(ExportStatus.Running("", ExportStep.Opening, 0f))
        return START_NOT_STICKY
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        job?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        startForeground(NOTIFICATION_PROGRESS, notification, type)
    }

    private fun notifyProgress(status: ExportStatus.Running) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotified < NOTIFY_INTERVAL_MS) return
        lastNotified = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_PROGRESS, progressNotification(status))
    }

    private fun finish(result: ExportStatus) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val manager = getSystemService(NotificationManager::class.java)
        resultNotification(result)?.let { manager.notify(NOTIFICATION_RESULT, it) }
        stopSelf()
    }

    private fun progressNotification(status: ExportStatus.Running): Notification {
        val cancel =
            PendingIntent.getService(
                this,
                0,
                Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (status.projectName.isEmpty()) "Exporting" else "Exporting ${status.projectName}")
            .setContentText(status.step.label)
            .setProgress(PROGRESS_MAX, (status.progress * PROGRESS_MAX).roundToInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    private fun resultNotification(result: ExportStatus): Notification? {
        val builder = Notification.Builder(this, CHANNEL).setAutoCancel(true)
        when (result) {
            is ExportStatus.Done -> {
                val view =
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(result.uri, result.mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                val share = Intent.createChooser(shareIntent(result.uri, result.mimeType), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle("Exported ${result.fileName}")
                    .setContentText(if (result.inPictures) "Saved to Pictures/LineOver" else "Saved")
                    .setContentIntent(PendingIntent.getActivity(this, 1, view, PendingIntent.FLAG_IMMUTABLE))
                    .addAction(
                        Notification.Action
                            .Builder(null, "Share", PendingIntent.getActivity(this, 2, share, PendingIntent.FLAG_IMMUTABLE))
                            .build(),
                    )
            }

            is ExportStatus.Failed -> {
                builder
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Export failed")
                    .setContentText(result.message)
            }

            else -> {
                return null
            }
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "LineOverExport"
        private const val CHANNEL = "export"
        private const val NOTIFICATION_PROGRESS = 1201
        private const val NOTIFICATION_RESULT = 1202
        private const val PROGRESS_MAX = 100
        private const val NOTIFY_INTERVAL_MS = 400L
        private const val ACTION_START = "dev.mark.lineover.export.START"
        private const val ACTION_CANCEL = "dev.mark.lineover.export.CANCEL"
        private const val EXTRA_PROJECT = "project"
        private const val EXTRA_SETTINGS = "settings"
        private const val EXTRA_TARGET = "target"

        /** Starts [request] in the foreground; the sheet follows it through [ExportJobs]. */
        fun start(
            context: Context,
            request: ExportRequest,
        ) {
            val intent =
                Intent(context, ExportService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PROJECT, request.projectId)
                    .putExtra(EXTRA_SETTINGS, ExportSettings.encode(request.settings))
            (request.target as? ExportTarget.Document)?.let { intent.putExtra(EXTRA_TARGET, it.uri.toString()) }
            ExportJobs.update(ExportStatus.Running("", ExportStep.Opening, 0f))
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ExportService::class.java).setAction(ACTION_CANCEL))
        }

        fun shareIntent(
            uri: Uri,
            mimeType: String,
        ): Intent =
            Intent(Intent.ACTION_SEND)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        private fun requestOf(intent: Intent): ExportRequest? {
            val id = intent.getLongExtra(EXTRA_PROJECT, -1L).takeIf { it >= 0 } ?: return null
            val settings = ExportSettings.decode(intent.getStringExtra(EXTRA_SETTINGS))
            val target = intent.getStringExtra(EXTRA_TARGET)?.let { ExportTarget.Document(Uri.parse(it)) } ?: ExportTarget.Pictures
            return ExportRequest(id, settings, target)
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Exports", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
