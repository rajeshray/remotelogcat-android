package com.remotelog

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

internal class ReportPreviewActivity : Activity() {

    companion object {
        const val EXTRA_REPORT_FILE_PATH = "com.remotelog.extra.REPORT_FILE_PATH"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reportPath = intent.getStringExtra(EXTRA_REPORT_FILE_PATH)
        val reportFile = reportPath?.let { File(it) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "RemoteLogcat Report"
            textSize = 20f
        }

        val content = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            typeface = Typeface.MONOSPACE
            textSize = 12f
            movementMethod = ScrollingMovementMethod()
            text = runCatching {
                if (reportFile != null && reportFile.exists()) {
                    reportFile.readText()
                } else {
                    "No report file found."
                }
            }.getOrElse { throwable ->
                "Failed to load report: ${throwable.message}"
            }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val shareButton = Button(this).apply {
            text = "Share"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (reportFile != null && reportFile.exists()) {
                    ShareHelper.share(this@ReportPreviewActivity, reportFile)
                }
            }
        }

        val closeButton = Button(this).apply {
            text = "Close"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { finish() }
        }

        actions.addView(shareButton)
        actions.addView(closeButton)

        root.addView(title)
        root.addView(content)
        root.addView(actions)

        setContentView(root)
    }
}
