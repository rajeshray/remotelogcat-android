package com.remotelog

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class BreadcrumbEntry(
    val timestamp: Long,
    val threadName: String,
    val message: String,
    val attributes: Map<String, String>
)

internal class BreadcrumbStore(capacity: Int) {

    private val lock = ReentrantLock()
    private val items = ArrayDeque<BreadcrumbEntry>()
    private var maxCapacity = capacity.coerceAtLeast(1)

    fun push(entry: BreadcrumbEntry) {
        lock.withLock {
            if (items.size >= maxCapacity) {
                items.removeFirst()
            }
            items.addLast(entry)
        }
    }

    fun snapshot(): List<BreadcrumbEntry> = lock.withLock { items.toList() }

    fun resize(newCapacity: Int) {
        lock.withLock {
            maxCapacity = newCapacity.coerceAtLeast(1)
            while (items.size > maxCapacity) {
                items.removeFirst()
            }
        }
    }
}
