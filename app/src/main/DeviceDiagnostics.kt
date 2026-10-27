package com.juliano.limpacel

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

data class AppUsage(val packageName: String, val label: String, val foregroundMs: Long)

data class DeviceHealth(
    val batteryCelsius: Float?,
    val batteryPercent: Int?,
    val charging: Boolean,
    val thermalStatus: Int?,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val lowMemory: Boolean,
    val appUsage: List<AppUsage>,
    val usagePermissionGranted: Boolean
)

/** Leituras reais disponíveis nas APIs públicas; não representa temperatura de CPU nem consumo instantâneo por app. */
class DeviceDiagnostics(private val context: Context) {
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun read(): DeviceHealth {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperatureTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val thermal = if (Build.VERSION.SDK_INT >= 29)
            context.getSystemService(PowerManager::class.java).currentThermalStatus else null
        val granted = hasUsageAccess()
        val usage = if (granted) recentApps() else emptyList()
        return DeviceHealth(
            if (temperatureTenths >= 0) temperatureTenths / 10f else null,
            if (level >= 0 && scale > 0) (100 * level / scale) else null,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            thermal, memory.totalMem, memory.availMem, memory.lowMemory, usage, granted
        )
    }

    private fun recentApps(): List<AppUsage> = runCatching {
        val usageManager = context.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val recent = usageManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
            end - 24L * 60L * 60L * 1000L, end)
        recent.orEmpty().filter { it.totalTimeInForeground > 0 && it.packageName != context.packageName }
            .groupBy { it.packageName }.map { (name, values) ->
                val foreground = values.sumOf { it.totalTimeInForeground }
                val label = runCatching {
                    @Suppress("DEPRECATION")
                    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(name, 0)).toString()
                }.getOrDefault(name)
                AppUsage(name, label, foreground)
            }.sortedByDescending { it.foregroundMs }.take(15)
    }.getOrDefault(emptyList())

    companion object {
        val USAGE_SETTINGS: String = Settings.ACTION_USAGE_ACCESS_SETTINGS

        fun thermalText(status: Int?): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "Sem limitação térmica"
            PowerManager.THERMAL_STATUS_LIGHT -> "Aquecimento leve"
            PowerManager.THERMAL_STATUS_MODERATE -> "Aquecimento moderado"
            PowerManager.THERMAL_STATUS_SEVERE -> "Aquecimento elevado"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Aquecimento crítico"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergência térmica"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Risco de desligamento"
            else -> "Indisponível"
        }
    }
}
