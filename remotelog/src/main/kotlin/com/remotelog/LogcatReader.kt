package com.remotelog

import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader

internal class LogcatReader(private val buffer: CircularLogBuffer) {

    private var process: java.lang.Process? = null
    private var thread: Thread? = null

    @Volatile
    private var running: Boolean = false

    fun start() {
        running = true
        buffer.push("[REMOTELOGCAT] LogcatReader start requested")
        thread = Thread(
            {
                runCatching { readLogcat() }
                    .onFailure { throwable ->
                        buffer.push("[REMOTELOGCAT][ERROR] LogcatReader failed: ${throwable.message}")
                    }
            },
            "RemoteLogcat-Reader"
        ).also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun readLogcat() {
        val pid = Process.myPid().toString()
        buffer.push("[REMOTELOGCAT] Starting logcat stream for pid=$pid")
        val command = mutableListOf("logcat", "-v", "threadtime")
        val backfillLines = RemoteLogcat.config.startupBackfillLines.coerceAtLeast(0)
        if (backfillLines > 0) {
            command.addAll(listOf("-T", backfillLines.toString()))
        }
        command.addAll(listOf("--pid", pid))

        process = Runtime.getRuntime().exec(command.toTypedArray())

        BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
            var line: String? = ""
            while (running && reader.readLine().also { line = it } != null) {
                buffer.push(line.orEmpty())
            }
        }
        buffer.push("[REMOTELOGCAT] Logcat stream ended")
    }

    fun stop() {
        running = false
        process?.destroy()
        thread?.interrupt()
    }
}
