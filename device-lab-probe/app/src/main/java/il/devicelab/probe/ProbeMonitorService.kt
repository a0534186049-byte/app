package il.devicelab.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/** Foreground-only opt-in monitoring. A partial wake lock is acquired only while writing each sample. */
class ProbeMonitorService : Service() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var useWakeLock = false
    private var startedAtMs = 0L
    private val sampleRunnable = object : Runnable {
        override fun run() {
            sampleNow()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ACTIVE, false).apply()
                handler.removeCallbacks(sampleRunnable)
                logEvent("service_stop", "mode" to modeName())
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        useWakeLock = intent?.getBooleanExtra(EXTRA_WAKE_LOCK, getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_WAKE_LOCK, false))
            ?: getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_WAKE_LOCK, false)
        startedAtMs = System.currentTimeMillis()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            logEvent("foreground_start_error", "error" to "${t.javaClass.name}: ${t.message ?: ""}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        logEvent("service_start", "mode" to modeName(), "startedAtMs" to startedAtMs, "startId" to startId)
        handler.removeCallbacks(sampleRunnable)
        sampleNow()
        handler.postDelayed(sampleRunnable, INTERVAL_MS)
        return START_STICKY
    }

    private fun sampleNow() {
        var lock: PowerManager.WakeLock? = null
        try {
            if (useWakeLock) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeviceLabProbe:sample")
                lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memory)
            val battery = try { registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Throwable) { null }
            val sample = JSONObject().apply {
                put("event", "sample")
                put("timestampMs", System.currentTimeMillis())
                put("timestamp", java.time.Instant.now().toString())
                put("elapsedRealtimeMs", android.os.SystemClock.elapsedRealtime())
                put("mode", modeName())
                put("wakeLockRequested", useWakeLock)
                put("memory.availMemBytes", memory.availMem)
                put("memory.totalMemBytes", memory.totalMem)
                put("memory.thresholdBytes", memory.threshold)
                put("memory.lowMemory", memory.lowMemory)
                put("battery.levelRaw", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1)
                put("battery.scaleRaw", battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1)
                put("battery.statusRaw", battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1)
                put("battery.pluggedRaw", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1)
                put("battery.temperatureRaw", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE)
                put("battery.voltageRaw", battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE) ?: Int.MIN_VALUE)
            }
            appendLine(sample)
        } catch (t: Throwable) {
            logEvent("sample_error", "mode" to modeName(), "error" to "${t.javaClass.name}: ${t.message ?: ""}")
        } finally {
            try { if (lock?.isHeld == true) lock.release() } catch (_: Throwable) { }
        }
    }

    private fun modeName(): String = if (useWakeLock) "with_partial_wakelock_each_sample" else "without_wakelock"

    private fun appendLine(value: JSONObject) {
        try {
            openFileOutput(LOG_FILE, Context.MODE_APPEND).bufferedWriter(StandardCharsets.UTF_8).use {
                it.write(value.toString())
                it.newLine()
            }
        } catch (t: Throwable) {
            // Avoid crashing the service because of logging failure.
            try { logEvent("log_write_error", "error" to "${t.javaClass.name}: ${t.message ?: ""}") } catch (_: Throwable) { }
        }
    }

    private fun logEvent(event: String, vararg fields: Pair<String, Any?>) {
        val o = JSONObject().put("event", event).put("timestampMs", System.currentTimeMillis()).put("timestamp", java.time.Instant.now().toString())
        fields.forEach { (key, value) -> o.put(key, value ?: JSONObject.NULL) }
        appendLine(o)
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Device Lab Probe monitoring", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, ProbeActivity::class.java)
        val pending = PendingIntent.getActivity(this, 722, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Device Lab Probe")
            .setContentText("ניטור ברקע פעיל — דגימה כל 30 שניות")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sampleRunnable)
        try { logEvent("service_destroyed", "mode" to modeName()) } catch (_: Throwable) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "il.devicelab.probe.action.START_MONITOR"
        const val ACTION_STOP = "il.devicelab.probe.action.STOP_MONITOR"
        const val EXTRA_WAKE_LOCK = "wakeLock"
        const val PREFS = "probe_monitor_prefs"
        const val PREF_ACTIVE = "monitor_active"
        const val PREF_WAKE_LOCK = "monitor_wake_lock"
        const val LOG_FILE = "probe-background.jsonl"
        const val INTERVAL_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 5_000L
        private const val CHANNEL_ID = "device_lab_probe_monitor"
        private const val NOTIFICATION_ID = 7331
    }
}
