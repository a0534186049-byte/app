package il.devicelab.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import org.json.JSONObject

/** Records boot delivery; resumes monitoring only when the user had already opted in. */
class ProbeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        appendEvent(context, JSONObject()
            .put("event", "BOOT_COMPLETED_SEEN")
            .put("timestampMs", System.currentTimeMillis())
            .put("timestamp", Instant.now().toString()))
        val prefs = context.getSharedPreferences(ProbeMonitorService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ProbeMonitorService.PREF_ACTIVE, false)) return
        val wakeLock = prefs.getBoolean(ProbeMonitorService.PREF_WAKE_LOCK, false)
        val start = Intent(context, ProbeMonitorService::class.java)
            .setAction(ProbeMonitorService.ACTION_START)
            .putExtra(ProbeMonitorService.EXTRA_WAKE_LOCK, wakeLock)
            .putExtra("bootStart", true)
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start) else context.startService(start)
            appendEvent(context, JSONObject()
                .put("event", "BOOT_SERVICE_START_REQUESTED")
                .put("timestampMs", System.currentTimeMillis())
                .put("timestamp", Instant.now().toString())
                .put("wakeLockRequested", wakeLock))
        } catch (t: Throwable) {
            prefs.edit().putBoolean(ProbeMonitorService.PREF_ACTIVE, false).apply()
            appendEvent(context, JSONObject()
                .put("event", "BOOT_SERVICE_START_ERROR")
                .put("timestampMs", System.currentTimeMillis())
                .put("timestamp", Instant.now().toString())
                .put("error", "${t.javaClass.name}: ${t.message ?: ""}"))
        }
    }

    private fun appendEvent(context: Context, value: JSONObject) {
        try {
            context.openFileOutput(ProbeMonitorService.LOG_FILE, Context.MODE_APPEND).bufferedWriter(Charsets.UTF_8).use {
                it.write(value.toString())
                it.newLine()
            }
        } catch (_: Throwable) { }
    }
}
