package com.remotelog

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object LogFileWriter {

    private data class RawEvent(
        val timestampMs: Long,
        val line: String
    )

    private fun getLogDir(context: Context): File =
        File(context.cacheDir, "remotelog").also { it.mkdirs() }

    fun writeCrashReport(
        context: Context,
        buffer: CircularLogBuffer,
        lifecycleTracker: LifecycleTracker,
        throwable: Throwable
    ): File {
        val file = File(getLogDir(context), "crash_${System.currentTimeMillis()}.txt")
        val logEntries = prepareEntries(
            entries = buffer.snapshotEntries(),
            lifecycleTracker = lifecycleTracker
        )
        val breadcrumbs = prepareBreadcrumbs(
            breadcrumbs = RemoteLogcat.breadcrumbStore.snapshot(),
            lifecycleTracker = lifecycleTracker
        )
        val rawEvents = buildRawEvents(
            logEntries = logEntries,
            breadcrumbs = breadcrumbs,
            throwable = throwable
        )

        file.bufferedWriter().use { writer ->
            safeWrite(writer, "header") {
                writeHeader(writer, context = context, isCrash = true)
            }
            safeWrite(writer, "summary") {
                writeSummary(
                    writer = writer,
                    context = context,
                    lifecycleTracker = lifecycleTracker,
                    rawEvents = rawEvents,
                    throwable = throwable
                )
            }
            safeWrite(writer, "raw chronological") {
                writeRawChronological(writer, rawEvents)
            }
        }
        return file
    }

    fun writeManualReport(
        context: Context,
        buffer: CircularLogBuffer,
        lifecycleTracker: LifecycleTracker
    ): File {
        val file = File(getLogDir(context), "report_${System.currentTimeMillis()}.txt")
        val logEntries = prepareEntries(
            entries = buffer.snapshotEntries(),
            lifecycleTracker = lifecycleTracker
        )
        val breadcrumbs = prepareBreadcrumbs(
            breadcrumbs = RemoteLogcat.breadcrumbStore.snapshot(),
            lifecycleTracker = lifecycleTracker
        )
        val rawEvents = buildRawEvents(
            logEntries = logEntries,
            breadcrumbs = breadcrumbs,
            throwable = null
        )

        file.bufferedWriter().use { writer ->
            writeHeader(writer, context = context, isCrash = false)
            writeSummary(
                writer = writer,
                context = context,
                lifecycleTracker = lifecycleTracker,
                rawEvents = rawEvents,
                throwable = null
            )
            writeRawChronological(writer, rawEvents)
        }
        return file
    }

    private fun writeHeader(writer: BufferedWriter, context: Context, isCrash: Boolean) {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        writer.appendLine("+----------------------------------------------+")
        writer.appendLine("|                 REMOTELOG REPORT             |")
        writer.appendLine("+----------------------------------------------+")
        writer.appendLine("Type    : ${if (isCrash) "CRASH REPORT" else "MANUAL SHARE"}")
        writer.appendLine("Time    : ${formatter.format(Date())}")
        writer.appendLine("Package : ${context.packageName}")
        writer.newLine()
    }

    private fun writeSummary(
        writer: BufferedWriter,
        context: Context,
        lifecycleTracker: LifecycleTracker,
        rawEvents: List<RawEvent>,
        throwable: Throwable?
    ) {
        val state = runCatching { DeviceStateCollector.collect(context) }.getOrNull()
        writeSeparator(writer)
        writer.appendLine("=== SUMMARY ===")
        writer.appendLine("Session duration : ${lifecycleTracker.getSessionDurationMs() / 1000}s")
        writer.appendLine("Total raw events : ${rawEvents.size}")
        if (state != null) {
            writer.appendLine("Memory           : ${state.freeMemoryMb}MB free / ${state.totalMemoryMb}MB total")
            writer.appendLine(
                "CPU usage        : system ${formatPercent(state.systemCpuUsagePercent)} | app ${formatPercent(state.appCpuUsagePercent)}"
            )
            val battery = if (state.isLowPowerMode) {
                "${state.batteryPercent}% (Low Power Mode ON)"
            } else {
                "${state.batteryPercent}%"
            }
            writer.appendLine("Battery          : $battery")
            writer.appendLine("Network          : ${state.networkType} | strength ${state.networkStrength}")
            writer.appendLine("Disk             : ${state.freeDiskMb}MB free")
            writer.appendLine("Screen           : ${state.screenWidthPx}x${state.screenHeightPx} @ ${state.screenDensityDpi}dpi")
            writer.appendLine("Device           : ${state.deviceModel} | Android ${state.androidVersion} (API ${state.apiLevel})")
            writer.appendLine("App              : ${state.appVersionName} (${state.appVersionCode})")
        } else {
            writer.appendLine("Device health    : unavailable")
        }

        if (throwable != null) {
            writer.appendLine("Crash            : ${throwable::class.java.name}: ${throwable.message ?: "no message"}")
        }
        writer.newLine()
    }

    private fun writeRawChronological(writer: BufferedWriter, rawEvents: List<RawEvent>) {
        writeSeparator(writer)
        writer.appendLine("=== RAW CHRONOLOGICAL (${rawEvents.size} EVENTS) ===")
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        rawEvents.forEach { event ->
            writer.appendLine("${formatter.format(Date(event.timestampMs))} ${event.line}")
        }
    }

    private fun buildRawEvents(
        logEntries: List<LogEntry>,
        breadcrumbs: List<BreadcrumbEntry>,
        throwable: Throwable?
    ): List<RawEvent> {
        val events = mutableListOf<RawEvent>()

        logEntries.forEach { entry ->
            events.add(RawEvent(timestampMs = entry.timestampMs, line = entry.line))
        }

        breadcrumbs.forEach { crumb ->
            val attrs = if (crumb.attributes.isEmpty()) {
                ""
            } else {
                " | " + crumb.attributes.entries.joinToString { "${it.key}=${it.value}" }
            }
            events.add(
                RawEvent(
                    timestampMs = crumb.timestamp,
                    line = "[BREADCRUMB][${crumb.threadName}] ${crumb.message}$attrs"
                )
            )
        }

        if (throwable != null) {
            val crashTs = System.currentTimeMillis()
            events.add(
                RawEvent(
                    timestampMs = crashTs,
                    line = "[CRASH] ${throwable::class.java.name}: ${throwable.message ?: "no message"}"
                )
            )
            throwable.stackTraceToString().lineSequence().forEach { stackLine ->
                events.add(
                    RawEvent(
                        timestampMs = crashTs,
                        line = "[STACK] $stackLine"
                    )
                )
            }
        }

        return events.sortedBy { it.timestampMs }
    }

    private fun prepareEntries(
        entries: List<LogEntry>,
        lifecycleTracker: LifecycleTracker
    ): List<LogEntry> {
        val config = RemoteLogcat.config
        val now = System.currentTimeMillis()
        val sessionStart = lifecycleTracker.getSessionStartTimeMs()
        val windowStart = now - (config.reportWindowMinutes.coerceAtLeast(1) * 60_000L)
        val minTimestamp = maxOf(sessionStart, windowStart)

        return entries
            .asSequence()
            .filter { it.timestampMs >= minTimestamp }
            .filter {
                config.includeSdkInternalLogs || !it.line.startsWith("[REMOTELOGCAT]")
            }
            .toList()
    }

    private fun prepareBreadcrumbs(
        breadcrumbs: List<BreadcrumbEntry>,
        lifecycleTracker: LifecycleTracker
    ): List<BreadcrumbEntry> {
        val config = RemoteLogcat.config
        val now = System.currentTimeMillis()
        val sessionStart = lifecycleTracker.getSessionStartTimeMs()
        val windowStart = now - (config.reportWindowMinutes.coerceAtLeast(1) * 60_000L)
        val minTimestamp = maxOf(sessionStart, windowStart)
        return breadcrumbs.filter { it.timestamp >= minTimestamp }
    }

    private fun formatPercent(value: Double): String {
        if (value < 0.0) return "unknown"
        return String.format(Locale.US, "%.1f%%", value)
    }

    private fun writeSeparator(writer: BufferedWriter) {
        writer.appendLine("-".repeat(50))
    }

    private fun safeWrite(writer: BufferedWriter, section: String, block: () -> Unit) {
        runCatching { block() }
            .onFailure { throwable ->
                writer.appendLine("=== REMOTELOGCAT INTERNAL ERROR ===")
                writer.appendLine("Failed while writing $section: ${throwable.message}")
                writer.newLine()
            }
    }
}
