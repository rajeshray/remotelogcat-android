package com.remotelog

import android.content.Context
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

internal class CrashHandler(
    private val context: Context,
    private val buffer: CircularLogBuffer,
    private val lifecycleTracker: LifecycleTracker
) {

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                LogFileWriter.writeCrashReport(
                    context = context,
                    buffer = buffer,
                    lifecycleTracker = lifecycleTracker,
                    throwable = throwable
                )
            }.onFailure { error ->
                buffer.push("[REMOTELOGCAT][ERROR] Failed to write crash report: ${error.message}")
                writeFallbackCrashReport(throwable, error)
            }

            val previous = previousHandler
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun writeFallbackCrashReport(originalThrowable: Throwable, writerError: Throwable) {
        runCatching {
            val dir = File(context.cacheDir, "remotelog").also { it.mkdirs() }
            val file = File(dir, "crash_${System.currentTimeMillis()}_fallback.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date())
            val logLines = buffer.snapshot()
            file.bufferedWriter().use { writer ->
                writer.appendLine("REMOTELOGCAT FALLBACK CRASH REPORT")
                writer.appendLine("Time: $timestamp")
                writer.appendLine("Package: ${context.packageName}")
                writer.appendLine("Primary writer error: ${writerError.stackTraceToString()}")
                writer.appendLine()
                writer.appendLine("=== STACK TRACE ===")
                writer.appendLine(originalThrowable.stackTraceToString())
                writer.appendLine()
                writer.appendLine("=== LAST ${logLines.size} LOG LINES ===")
                logLines.forEach { writer.appendLine(it) }
            }
        }
    }
}
