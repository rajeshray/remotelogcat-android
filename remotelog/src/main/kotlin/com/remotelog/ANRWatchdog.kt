package com.remotelog

import android.os.Handler
import android.os.Looper

internal class ANRWatchdog(
    private val thresholdMs: Long,
    private val buffer: CircularLogBuffer
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: Thread? = null

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var lastPingProcessed: Boolean = true

    @Volatile
    private var lastPingTime: Long = 0L

    fun start() {
        running = true
        thread = Thread(
            {
                runCatching {
                    while (running) {
                        lastPingProcessed = false
                        lastPingTime = System.currentTimeMillis()
                        mainHandler.post { lastPingProcessed = true }

                        Thread.sleep(thresholdMs)

                        if (!lastPingProcessed && running) {
                            val elapsed = System.currentTimeMillis() - lastPingTime
                            val mainThread = Looper.getMainLooper().thread
                            val stackTrace = mainThread.stackTrace.joinToString(separator = "\n") {
                                "    at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                            }
                            val warning = buildString {
                                appendLine("[ANR-WATCHDOG] Main thread slow (${elapsed}ms) - possible ANR")
                                appendLine(stackTrace)
                            }
                            buffer.push(warning)
                        }
                    }
                }.onFailure { throwable ->
                    if (running) {
                        buffer.push("[REMOTELOGCAT][ERROR] ANRWatchdog failed: ${throwable.message}")
                    }
                }
            },
            "RemoteLogcat-ANRWatchdog"
        ).also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }
}
