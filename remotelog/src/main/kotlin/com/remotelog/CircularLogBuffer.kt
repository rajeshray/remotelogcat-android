package com.remotelog

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class LogEntry(
    val timestampMs: Long,
    val line: String
)

internal class CircularLogBuffer(private val capacity: Int) {

    private val buffer = ArrayDeque<LogEntry>(capacity)
    private val lock = ReentrantLock()

    fun push(line: String) {
        lock.withLock {
            if (buffer.size >= capacity) {
                buffer.removeFirst()
            }
            buffer.addLast(
                LogEntry(
                    timestampMs = System.currentTimeMillis(),
                    line = line
                )
            )
        }
    }

    fun snapshot(): List<String> = lock.withLock { buffer.map { it.line } }

    fun snapshotEntries(): List<LogEntry> = lock.withLock { buffer.toList() }

    fun size(): Int = lock.withLock { buffer.size }
}
