package com.remotelog

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

object RemoteLogcat {

    internal var config: RemoteLogcatConfig = RemoteLogcatConfig()
        private set
    internal val breadcrumbStore = BreadcrumbStore(capacity = 200)

    fun init(context: Context, configure: RemoteLogcatConfig.() -> Unit = {}) {
        config = RemoteLogcatConfig().apply(configure)
        breadcrumbStore.resize(config.maxBreadcrumbs)
        breadcrumb(
            "RemoteLogcat initialized",
            mapOf(
                "maxLogLines" to config.maxLogLines.toString(),
                "reportWindowMin" to config.reportWindowMinutes.toString()
            )
        )

        val appContext = context.applicationContext
        val intent = Intent(appContext, RemoteLogcatService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasNotificationPermission(appContext)) {
            context.applicationContext.startForegroundService(intent)
        } else {
            context.applicationContext.startService(intent)
        }
    }

    fun openReport(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, RemoteLogcatService::class.java).apply {
            action = RemoteLogcatService.ACTION_OPEN_REPORT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasNotificationPermission(appContext)) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    fun breadcrumb(message: String, attrs: Map<String, String> = emptyMap()) {
        breadcrumbStore.push(
            BreadcrumbEntry(
                timestamp = System.currentTimeMillis(),
                threadName = Thread.currentThread().name,
                message = message,
                attributes = attrs
            )
        )
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}

data class RemoteLogcatConfig(
    var maxLogLines: Int = 1000,
    var anrThresholdMs: Long = 5000L,
    var notificationTitle: String = "Debug Recording Active",
    var notificationText: String = "Tap to see report",
    var maxStoredReports: Int = 20,
    var maxBreadcrumbs: Int = 200,
    var reportWindowMinutes: Int = 3,
    var includeSdkInternalLogs: Boolean = false,
    var startupBackfillLines: Int = 0
)
