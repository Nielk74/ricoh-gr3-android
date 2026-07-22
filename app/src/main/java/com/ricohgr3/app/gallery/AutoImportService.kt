package com.ricohgr3.app.gallery

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ricohgr3.app.MainActivity
import com.ricohgr3.app.RicohApplication
import com.ricohgr3.app.data.EditedExportQuality
import com.ricohgr3.app.data.PhotoExporter
import com.ricohgr3.app.data.PhotoRepository
import com.ricohgr3.app.looks.emulation.FilmLookLoader
import com.ricohgr3.app.looks.emulation.RenderingIntent
import com.ricohgr3.app.wifi.SessionBoundWifiController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/** Foreground owner for durable auto-import while the activity is hidden or the screen is off. */
class AutoImportService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var workJob: Job? = null
    private var runner: AutoImportRunner? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotificationAt = 0L

    private val ricohApp: RicohApplication get() = application as RicohApplication
    private val manager: AutoImportManager get() = ricohApp.autoImportManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote(manager.state.value)
        when (intent?.action ?: ACTION_RESUME) {
            ACTION_START -> {
                val requested = intent?.readPreset()
                val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
                    ?: UUID.randomUUID().toString()
                // START_REDELIVER_INTENT may replay the original start after process death. If a
                // durable manifest belongs to this exact request, continue it rather than
                // clearing and scanning the camera into a second job. A distinct request id is a
                // deliberate new import and replaces an older terminal/paused manifest.
                val existing = manager.store.load()
                startWork(
                    newPreset = if (existing?.id == requestId) null else requested,
                    newJobId = requestId,
                )
            }
            ACTION_RESUME -> {
                // A scan can fail before a manifest exists. In that case retry the remembered
                // request from the beginning; otherwise resume the durable queue.
                val restartPreset = if (manager.store.load() == null) {
                    intent?.readPresetOrNull() ?: manager.state.value.preset
                } else {
                    null
                }
                startWork(newPreset = restartPreset)
            }
            ACTION_PAUSE -> {
                val active = runner
                if (active == null) {
                    finishForegroundWork()
                } else {
                    active.requestPause()
                    updateNotification(manager.state.value.copy(stopRequested = true), force = true)
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    @SuppressLint("WakelockTimeout")
    private fun startWork(newPreset: TransferPreset?, newJobId: String? = null) {
        if (workJob?.isActive == true) return
        acquireCpuWakeLock()
        val session = ricohApp.wifiSession
        if (session == null) {
            manager.publishFailure("Auto import requires Android 10 or newer.", newPreset)
            finishForegroundWork()
            return
        }

        val activeRunner = AutoImportRunner(
            repository = PhotoRepository(SessionBoundWifiController(session)),
            exporter = PhotoExporter(applicationContext),
            loader = FilmLookLoader(applicationContext),
            store = manager.store,
            publishTransient = { state ->
                manager.publishTransient(state)
                updateNotification(state)
            },
            publish = { job, live ->
                manager.publish(job, live)
                updateNotification(job.toUiState(live))
            },
            onCameraPhase = { active ->
                if (active) acquireWifiLock() else releaseWifiLock()
            },
            disconnectCamera = session::disconnect,
            memorySnapshot = TransferMemoryMonitor(applicationContext)::snapshot,
        )
        runner = activeRunner
        workJob = serviceScope.launch {
            try {
                if (newPreset != null) {
                    activeRunner.startNew(newPreset, newJobId ?: UUID.randomUUID().toString())
                } else {
                    activeRunner.resume()
                }
            } catch (cancelled: CancellationException) {
                // Preserve a truthful resumable state if Android tears the service down. The
                // store converts any RUNNING item back to PENDING and drops only its .part file.
                manager.store.load()?.let { recovered -> manager.publish(recovered) }
                throw cancelled
            } catch (failure: Throwable) {
                val reason = transferFailureReason(failure)
                val stored = manager.store.load()
                if (stored != null) {
                    val failed = stored.copy(phase = AutoImportJobPhase.FAILED, message = reason)
                    manager.store.save(failed)
                    manager.publish(failed)
                } else {
                    manager.publishFailure(reason, newPreset)
                }
            } finally {
                runner = null
                finishForegroundWork()
            }
        }
    }

    private fun promote(state: TransferUiState) {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(state),
            types,
        )
    }

    private fun updateNotification(state: TransferUiState, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationAt < NOTIFICATION_UPDATE_INTERVAL_MS) return
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: TransferUiState): android.app.Notification {
        val title = when (state.phase) {
            TransferPhase.SCANNING -> "Reading camera roll"
            TransferPhase.DOWNLOADING -> "Downloading originals"
            TransferPhase.PROCESSING -> "Developing downloaded photos"
            TransferPhase.CANCELLED -> "Auto import paused"
            TransferPhase.COMPLETED -> "Auto import complete"
            TransferPhase.FAILED -> "Auto import needs attention"
            else -> "Auto import"
        }
        val detail = when {
            state.stopRequested -> "Finishing the current file before pausing"
            state.downloading != null ->
                "${state.downloadCompleted}/${state.downloadTotal} · ${state.downloading.file}"
            state.processing != null && state.processingParts > 0 ->
                "${state.completed}/${state.total} · region ${state.processingPart}/${state.processingParts}"
            state.processing != null ->
                "${state.completed}/${state.total} · ${state.processing.file}"
            !state.message.isNullOrBlank() -> state.message
            else -> "Camera files are staged before processing starts"
        }
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(state.isActive)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (state.isActive) {
            builder.setProgress(1000, (state.progress * 1000f).toInt().coerceIn(0, 1000), false)
            builder.addAction(
                0,
                if (state.stopRequested) "Pausing" else "Pause",
                servicePendingIntent(ACTION_PAUSE, REQUEST_PAUSE),
            )
        } else if (state.phase == TransferPhase.CANCELLED ||
            (state.phase == TransferPhase.COMPLETED && state.failures.isNotEmpty()) ||
            state.phase == TransferPhase.FAILED
        ) {
            builder.setProgress(0, 0, false)
            builder.addAction(
                0,
                "Continue",
                servicePendingIntent(ACTION_RESUME, REQUEST_RESUME, state.preset),
            )
        } else {
            builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    private fun servicePendingIntent(
        action: String,
        requestCode: Int,
        preset: TransferPreset? = null,
    ): PendingIntent =
        PendingIntent.getForegroundService(
            this,
            requestCode,
            Intent(this, AutoImportService::class.java).setAction(action).apply {
                preset?.let { selected -> putPreset(selected) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    @SuppressLint("WakelockTimeout")
    private fun acquireCpuWakeLock() {
        if (cpuWakeLock?.isHeld == true) return
        cpuWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:auto-import")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:auto-import")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { lock -> if (lock.isHeld) lock.release() }
        wifiLock = null
    }

    private fun releaseCpuWakeLock() {
        cpuWakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        cpuWakeLock = null
    }

    private fun finishForegroundWork() {
        releaseWifiLock()
        releaseCpuWakeLock()
        val state = manager.state.value
        updateNotification(state, force = true)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Camera auto import",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress while camera files download and develop"
                setShowBadge(false)
            },
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWifiLock()
        releaseCpuWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.ricohgr3.app.action.START_AUTO_IMPORT"
        private const val ACTION_RESUME = "com.ricohgr3.app.action.RESUME_AUTO_IMPORT"
        private const val ACTION_PAUSE = "com.ricohgr3.app.action.PAUSE_AUTO_IMPORT"
        private const val EXTRA_LOOK = "look"
        private const val EXTRA_INTENSITY = "intensity"
        private const val EXTRA_RENDERING_INTENT = "rendering_intent"
        private const val EXTRA_GRAIN_ENABLED = "grain_enabled"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_OUTPUT_MODE = "output_mode"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val CHANNEL_ID = "auto_import"
        private const val NOTIFICATION_ID = 4206
        private const val REQUEST_OPEN = 1
        private const val REQUEST_PAUSE = 2
        private const val REQUEST_RESUME = 3
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 300L

        fun start(context: Context, preset: TransferPreset) {
            val intent = Intent(context, AutoImportService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_REQUEST_ID, UUID.randomUUID().toString())
                .putPreset(preset)
            ContextCompat.startForegroundService(context, intent)
        }

        fun resume(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutoImportService::class.java).setAction(ACTION_RESUME),
            )
        }

        fun pause(context: Context) {
            context.startService(Intent(context, AutoImportService::class.java).setAction(ACTION_PAUSE))
        }

        fun dismissNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }

        private fun Intent.readPreset(): TransferPreset = TransferPreset(
            look = getStringExtra(EXTRA_LOOK),
            intensity = getFloatExtra(EXTRA_INTENSITY, 1f),
            renderingIntent = getStringExtra(EXTRA_RENDERING_INTENT)
                ?.let { name -> RenderingIntent.entries.firstOrNull { it.name == name } }
                ?: RenderingIntent.SMART,
            grainEnabled = getBooleanExtra(EXTRA_GRAIN_ENABLED, true),
            quality = getStringExtra(EXTRA_QUALITY)
                ?.let { name -> EditedExportQuality.entries.firstOrNull { it.name == name } }
                ?: EditedExportQuality.MAXIMUM,
            outputMode = getStringExtra(EXTRA_OUTPUT_MODE)
                ?.let { name -> TransferOutputMode.entries.firstOrNull { it.name == name } }
                ?: TransferOutputMode.ORIGINAL_AND_EDITED,
        ).normalized()

        private fun Intent.readPresetOrNull(): TransferPreset? =
            if (hasExtra(EXTRA_OUTPUT_MODE) || hasExtra(EXTRA_LOOK)) readPreset() else null

        private fun Intent.putPreset(preset: TransferPreset): Intent = apply {
            putExtra(EXTRA_LOOK, preset.look)
            putExtra(EXTRA_INTENSITY, preset.intensity)
            putExtra(EXTRA_RENDERING_INTENT, preset.renderingIntent.name)
            putExtra(EXTRA_GRAIN_ENABLED, preset.grainEnabled)
            putExtra(EXTRA_QUALITY, preset.quality.name)
            putExtra(EXTRA_OUTPUT_MODE, preset.outputMode.name)
        }
    }
}
