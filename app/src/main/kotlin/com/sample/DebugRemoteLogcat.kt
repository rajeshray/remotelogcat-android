package com.sample

import android.content.Context
import android.util.Log
import kotlin.jvm.functions.Function1

object DebugRemoteLogcat {
    private const val TAG = "DebugRemoteLogcat"

    fun init(context: Context) {
        runCatching {
            val clazz = Class.forName("com.remotelog.RemoteLogcat")
            val instance = clazz.getField("INSTANCE").get(null)
            val initMethod = clazz.methods.firstOrNull { method ->
                method.name == "init" &&
                    method.parameterTypes.size == 2 &&
                    Context::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    Function1::class.java.isAssignableFrom(method.parameterTypes[1])
            } ?: return

            val noOpConfig = object : Function1<Any?, kotlin.Unit> {
                override fun invoke(p1: Any?): kotlin.Unit = kotlin.Unit
            }

            initMethod.invoke(instance, context, noOpConfig)
        }.onFailure { throwable ->
            Log.w(TAG, "RemoteLogcat.init reflection failed", throwable)
        }
    }

    fun breadcrumb(message: String, attrs: Map<String, String> = emptyMap()) {
        invokeRemoteLogcat(
            methodName = "breadcrumb",
            paramTypes = arrayOf(String::class.java, Map::class.java),
            args = arrayOf(message, attrs)
        )
    }

    fun onNotificationPermissionGranted(context: Context) {
        init(context)
    }

    private fun invokeRemoteLogcat(methodName: String, paramTypes: Array<Class<*>>, args: Array<Any>) {
        runCatching {
            val clazz = Class.forName("com.remotelog.RemoteLogcat")
            val instance = clazz.getField("INSTANCE").get(null)
            val method = clazz.getMethod(methodName, *paramTypes)
            method.invoke(instance, *args)
        }.onFailure { throwable ->
            Log.v(TAG, "RemoteLogcat.$methodName not available in this variant", throwable)
        }
    }
}
