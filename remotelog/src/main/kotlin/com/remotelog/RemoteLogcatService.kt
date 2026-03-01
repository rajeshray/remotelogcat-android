package com.remotelog

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File

internal class RemoteLogcatService : Service() {

    companion object {
        private const val CHANNEL_ID = "remotelog_channel"
        private const val NOTIFICATION_ID = 7829
        internal const val ACTION_OPEN_REPORT = "com.remotelog.ACTION_OPEN_REPORT"
    }

    private lateinit var logcatReader: LogcatReader
    private lateinit var buffer: CircularLogBuffer
    private lateinit var crashHandler: CrashHandler
    private lateinit var anrWatchdog: ANRWatchdog
    private lateinit var lifecycleTracker: LifecycleTracker

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        if (canShowNotification()) {
            startForeground(NOTIFICATION_ID, buildNotification(isCrash = false))
        }

        buffer = CircularLogBuffer(RemoteLogcat.config.maxLogLines)
        buffer.push("[REMOTELOGCAT] Service created")
        RemoteLogcat.breadcrumb("Service created")
        lifecycleTracker = LifecycleTracker(application)
        lifecycleTracker.start()
        buffer.push("[REMOTELOGCAT] Lifecycle tracker started")
        RemoteLogcat.breadcrumb("Lifecycle tracker started")

        logcatReader = LogcatReader(buffer)
        logcatReader.start()

        crashHandler = CrashHandler(
            context = applicationContext,
            buffer = buffer,
            lifecycleTracker = lifecycleTracker
        )
        crashHandler.install()
        buffer.push("[REMOTELOGCAT] Crash handler installed")
        RemoteLogcat.breadcrumb("Crash handler installed")

        Handler(Looper.getMainLooper()).postDelayed(
            {
                runCatching {
                    anrWatchdog = ANRWatchdog(
                        thresholdMs = RemoteLogcat.config.anrThresholdMs,
                        buffer = buffer
                    )
                    anrWatchdog.start()
                }.onFailure { throwable ->
                    buffer.push("[REMOTELOGCAT][ERROR] Failed to start ANR watchdog: ${throwable.message}")
                }
            },
            10_000L
        )

        if (hasPendingCrashFile()) {
            updateNotificationToCrashState()
            buffer.push("[REMOTELOGCAT] Pending crash file detected")
            RemoteLogcat.breadcrumb("Pending crash file detected")
        }

        cleanupExcessFiles()
        buffer.push("[REMOTELOGCAT] Service initialization complete")
        RemoteLogcat.breadcrumb("Service initialization complete")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OPEN_REPORT) {
            handleShareAction()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        runCatching { logcatReader.stop() }
            .onFailure { buffer.push("[REMOTELOGCAT][ERROR] Failed stopping reader: ${it.message}") }
        if (::anrWatchdog.isInitialized) {
            runCatching { anrWatchdog.stop() }
                .onFailure { buffer.push("[REMOTELOGCAT][ERROR] Failed stopping watchdog: ${it.message}") }
        }
        runCatching { lifecycleTracker.stop() }
            .onFailure { buffer.push("[REMOTELOGCAT][ERROR] Failed stopping lifecycle tracker: ${it.message}") }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RemoteLogcat Debug",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Debug log recording session"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isCrash: Boolean): Notification {
        val config = RemoteLogcat.config

        val viewReportIntent = Intent(this, RemoteLogcatService::class.java).apply {
            action = ACTION_OPEN_REPORT
        }
        val viewReportPendingIntent = PendingIntent.getService(
            this,
            0,
            viewReportIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isCrash) "Crash detected" else config.notificationTitle
        val text = if (isCrash) "Tap to see crash report" else config.notificationText

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(viewReportPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_menu_view, "View Report", viewReportPendingIntent)
            .build()
    }

    private fun updateNotificationToCrashState() {
        if (!canShowNotification()) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(isCrash = true))
    }

    private fun handleShareAction() {
        buffer.push("[REMOTELOGCAT] Share action tapped")
        RemoteLogcat.breadcrumb("Share action tapped")
        val pendingCrashFile = getPendingCrashFile()
        val file = pendingCrashFile ?: runCatching {
            LogFileWriter.writeManualReport(
                context = applicationContext,
                buffer = buffer,
                lifecycleTracker = lifecycleTracker
            )
        }.getOrElse { throwable ->
            buffer.push("[REMOTELOGCAT][ERROR] Failed to create manual report: ${throwable.message}")
            return
        }

        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }.onFailure { throwable ->
            buffer.push("[REMOTELOGCAT][ERROR] Failed to dismiss notification: ${throwable.message}")
        }

        runCatching {
            buffer.push("[REMOTELOGCAT] Opening report preview")
            val previewIntent = Intent(this, ReportPreviewActivity::class.java).apply {
                putExtra(ReportPreviewActivity.EXTRA_REPORT_FILE_PATH, file.absolutePath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(previewIntent)
        }.onFailure { throwable ->
            buffer.push("[REMOTELOGCAT][ERROR] Failed to open report preview: ${throwable.message}")
        }
    }

    private fun getLogDir(): File = File(cacheDir, "remotelog").also { it.mkdirs() }

    private fun hasPendingCrashFile(): Boolean = getPendingCrashFile() != null

    private fun getPendingCrashFile(): File? =
        getLogDir()
            .listFiles { file -> file.name.startsWith("crash_") }
            ?.maxByOrNull { it.lastModified() }

    private fun cleanupExcessFiles() {
        val maxStoredReports = RemoteLogcat.config.maxStoredReports.coerceAtLeast(1)
        val dir = getLogDir()
        val pendingCrash = getPendingCrashFile()

        val reportFiles = dir.listFiles { file ->
            file.name.startsWith("crash_") || file.name.startsWith("report_")
        }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        var toDelete = reportFiles.drop(maxStoredReports)
        if (pendingCrash != null) {
            toDelete = toDelete.filterNot { it.absolutePath == pendingCrash.absolutePath }
        }

        toDelete.forEach { file ->
            runCatching { file.delete() }
                .onFailure { throwable ->
                    buffer.push("[REMOTELOGCAT][ERROR] Failed deleting report file ${file.name}: ${throwable.message}")
                }
        }
    }

    private fun canShowNotification(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
