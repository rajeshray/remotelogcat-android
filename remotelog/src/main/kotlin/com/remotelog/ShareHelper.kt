package com.remotelog

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object ShareHelper {

    fun share(context: Context, file: File, buffer: CircularLogBuffer? = null) {
        runCatching {
            val authority = "${context.packageName}.remotelog.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)

            val subject = "RemoteLogcat Report - ${
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            }"

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(intent, "Share Debug Logs").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { throwable ->
            buffer?.push("[REMOTELOGCAT][ERROR] Share failed: ${throwable.message}")
        }
    }
}
