package com.sample

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "RemoteLogcat Sample"
            textSize = 22f
        }

        val desc = TextView(this).apply {
            text = "Use notification action \"Share Logs\" anytime.\nTap below to trigger a crash report."
            textSize = 16f
        }

        val crashButton = Button(this).apply {
            text = "Trigger Test Crash"
            setOnClickListener {
                DebugRemoteLogcat.breadcrumb(
                    message = "User tapped crash button",
                    attrs = mapOf("screen" to "MainActivity")
                )
                throw IllegalStateException("Intentional sample crash for RemoteLogcat testing")
            }
        }

        root.addView(title)
        root.addView(desc)
        root.addView(crashButton)

        setContentView(root)
        DebugRemoteLogcat.breadcrumb(
            message = "MainActivity opened",
            attrs = mapOf("intentAction" to (intent?.action ?: "none"))
        )

        ensureNotificationPermissionForDebugService()
    }

    private fun ensureNotificationPermissionForDebugService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                DebugRemoteLogcat.onNotificationPermissionGranted(this)
            }
        }
    }
}
