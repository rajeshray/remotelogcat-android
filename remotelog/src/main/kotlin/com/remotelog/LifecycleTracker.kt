package com.remotelog

import android.app.Activity
import android.app.Application
import android.os.Bundle

internal data class LifecycleEvent(
    val type: String,
    val timestamp: Long,
    val durationMs: Long?
)

internal class LifecycleTracker(private val application: Application) {

    private val lock = Any()
    private val events = mutableListOf<LifecycleEvent>()
    private var activeActivityCount = 0
    private var lastTransitionTime = System.currentTimeMillis()
    private val sessionStartTime = System.currentTimeMillis()

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            activeActivityCount++
            if (activeActivityCount == 1) {
                val now = System.currentTimeMillis()
                val duration = now - lastTransitionTime
                synchronized(lock) {
                    events.add(LifecycleEvent(type = "FOREGROUNDED", timestamp = now, durationMs = duration))
                }
                lastTransitionTime = now
            }
        }

        override fun onActivityStopped(activity: Activity) {
            activeActivityCount--
            if (activeActivityCount == 0) {
                val now = System.currentTimeMillis()
                val duration = now - lastTransitionTime
                synchronized(lock) {
                    events.add(LifecycleEvent(type = "BACKGROUNDED", timestamp = now, durationMs = duration))
                }
                lastTransitionTime = now
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    fun start() {
        synchronized(lock) {
            events.add(LifecycleEvent(type = "SESSION_START", timestamp = sessionStartTime, durationMs = null))
        }
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    fun stop() {
        application.unregisterActivityLifecycleCallbacks(callbacks)
    }

    fun getEvents(): List<LifecycleEvent> = synchronized(lock) { events.toList() }

    fun getSessionStartTimeMs(): Long = sessionStartTime

    fun getSessionDurationMs(): Long = System.currentTimeMillis() - sessionStartTime

    fun getForegroundCount(): Int = getEvents().count { it.type == "FOREGROUNDED" }
}
