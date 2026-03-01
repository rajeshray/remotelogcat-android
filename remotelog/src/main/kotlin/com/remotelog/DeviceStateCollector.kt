package com.remotelog

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.os.PowerManager
import android.os.StatFs
import java.io.File
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.ContextCompat

internal data class DeviceState(
    val timestamp: String,
    val deviceBrand: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val deviceProduct: String,
    val deviceHardware: String,
    val buildFingerprint: String,
    val supportedAbis: String,
    val locale: String,
    val timezone: String,
    val androidVersion: String,
    val apiLevel: Int,
    val appVersionName: String,
    val appVersionCode: Long,
    val freeMemoryMb: Long,
    val totalMemoryMb: Long,
    val batteryPercent: Int,
    val isLowPowerMode: Boolean,
    val networkType: String,
    val networkStrength: String,
    val systemCpuUsagePercent: Double,
    val appCpuUsagePercent: Double,
    val freeDiskMb: Long,
    val screenDensityDpi: Int,
    val screenWidthPx: Int,
    val screenHeightPx: Int
)

internal object DeviceStateCollector {

    fun collect(context: Context): DeviceState {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }

        val batteryStatus = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val batteryPercent = batteryStatus?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale > 0) (level * 100 / scale.toFloat()).toInt() else -1
        } ?: -1

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isLowPowerMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            powerManager.isPowerSaveMode
        } else {
            false
        }

        val hasNetworkPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_NETWORK_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var networkCaps: NetworkCapabilities? = null
        val networkType = runCatching {
            if (!hasNetworkPermission) {
                "Unknown (no ACCESS_NETWORK_STATE permission)"
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                networkCaps = capabilities
                when {
                    capabilities == null -> "No network"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "Other"
                }
            } else {
                "Unknown"
            }
        }.getOrElse { "Unknown (${it.javaClass.simpleName})" }

        val networkStrength = runCatching {
            if (!hasNetworkPermission) {
                "Unknown"
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val strength = networkCaps?.signalStrength ?: NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED
                if (strength == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
                    "Unknown"
                } else {
                    "$strength dBm"
                }
            } else {
                "Unknown"
            }
        }.getOrElse { "Unknown" }

        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        val statFs = StatFs(Environment.getDataDirectory().path)
        val freeDiskMb = (statFs.availableBlocksLong * statFs.blockSizeLong) / (1024 * 1024)

        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        val metrics = context.resources.displayMetrics
        val abis = Build.SUPPORTED_ABIS.joinToString()
        val systemCpuUsagePercent = sampleSystemCpuUsagePercent()
        val appCpuUsagePercent = sampleAppCpuUsagePercent()

        return DeviceState(
            timestamp = formatter.format(Date()),
            deviceBrand = Build.BRAND,
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            deviceProduct = Build.PRODUCT,
            deviceHardware = Build.HARDWARE,
            buildFingerprint = Build.FINGERPRINT,
            supportedAbis = abis,
            locale = Locale.getDefault().toLanguageTag(),
            timezone = TimeZone.getDefault().id,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            appVersionName = packageInfo.versionName ?: "unknown",
            appVersionCode = appVersionCode,
            freeMemoryMb = memInfo.availMem / (1024 * 1024),
            totalMemoryMb = memInfo.totalMem / (1024 * 1024),
            batteryPercent = batteryPercent,
            isLowPowerMode = isLowPowerMode,
            networkType = networkType,
            networkStrength = networkStrength,
            systemCpuUsagePercent = systemCpuUsagePercent,
            appCpuUsagePercent = appCpuUsagePercent,
            freeDiskMb = freeDiskMb,
            screenDensityDpi = metrics.densityDpi,
            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels
        )
    }

    private fun sampleAppCpuUsagePercent(): Double {
        return runCatching {
            val cpuStart = android.os.Process.getElapsedCpuTime()
            val wallStart = SystemClock.elapsedRealtime()
            Thread.sleep(120)
            val cpuEnd = android.os.Process.getElapsedCpuTime()
            val wallEnd = SystemClock.elapsedRealtime()
            val wallDelta = (wallEnd - wallStart).coerceAtLeast(1L).toDouble()
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val usage = ((cpuEnd - cpuStart).toDouble() / (wallDelta * cores)) * 100.0
            usage.coerceIn(0.0, 100.0)
        }.getOrElse { -1.0 }
    }

    private fun sampleSystemCpuUsagePercent(): Double {
        return runCatching {
            val first = readSystemCpuTimes() ?: return -1.0
            Thread.sleep(120)
            val second = readSystemCpuTimes() ?: return -1.0
            val totalDelta = (second.first - first.first).coerceAtLeast(1L).toDouble()
            val idleDelta = (second.second - first.second).coerceAtLeast(0L).toDouble()
            val usage = (1.0 - (idleDelta / totalDelta)) * 100.0
            usage.coerceIn(0.0, 100.0)
        }.getOrElse { -1.0 }
    }

    private fun readSystemCpuTimes(): Pair<Long, Long>? {
        val line = runCatching {
            File("/proc/stat").useLines { lines ->
                lines.firstOrNull { it.startsWith("cpu ") }
            }
        }.getOrNull() ?: return null

        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 8) return null
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 8) return null

        val total = values.sum()
        val idle = values[3] + values[4]
        return total to idle
    }
}
