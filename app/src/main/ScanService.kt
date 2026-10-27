package com.juliano.limpacel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ScanState(
    val running: Boolean = false,
    val progress: MegaProgress? = null,
    val report: MegaReport? = null,
    val error: String? = null,
    val finishedAt: Long? = null
)

/** Estado enquanto o processo Android está vivo; nenhum resultado fica guardado na nuvem. */
object ScanRuntime {
    private val mutable = MutableStateFlow(ScanState())
    val state = mutable.asStateFlow()
    fun update(block: (ScanState) -> ScanState) { mutable.value = block(mutable.value) }
}

class ScanService : Service() {
    companion object {
        const val START = "com.juliano.limpacel.SCAN"
        const val CANCEL = "com.juliano.limpacel.CANCEL_SCAN"
        private const val CHANNEL = "smart_clean_scan"
        private const val NOTIFICATION_ID = 1008
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            job?.cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Progresso do Mega Scan",
            NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification("Preparando análise", 0))
        ScanRuntime.update { ScanState(running = true,
            progress = MegaProgress("Iniciando", 0, 0, "Armazenamento local")) }
        job = scope.launch {
            try {
                val result = MegaScanner(applicationContext).scan { progress ->
                    ScanRuntime.update { it.copy(progress = progress) }
                    notifications.notify(NOTIFICATION_ID, notification(progress.stage, progress.files))
                }
                ScanRuntime.update { it.copy(running = false, progress = null,
                    report = result, finishedAt = System.currentTimeMillis()) }
            } catch (_: CancellationException) {
                ScanRuntime.update { it.copy(running = false, progress = null,
                    error = "Análise interrompida. Nenhum arquivo foi apagado.") }
            } catch (e: Exception) {
                ScanRuntime.update { it.copy(running = false, progress = null,
                    error = e.message ?: "Não foi possível analisar o armazenamento.") }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(message: String, files: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SmartCleanActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, ScanService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Smart Clean · Mega Scan")
            .setContentText("$message · $files arquivos localizados")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                "Interromper", cancel).build())
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        ScanRuntime.update { it.copy(error = "Limite de execução em segundo plano atingido.") }
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
