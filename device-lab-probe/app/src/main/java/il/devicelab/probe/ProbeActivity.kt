package il.devicelab.probe

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodecList
import android.media.MediaDrm
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.HardwarePropertiesManager
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.UserManager
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.Settings
import android.hardware.usb.UsbManager
import android.view.Display
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.util.DisplayMetrics
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Plain, non-designed probe screen. Every individual probe writes a result even on failure. */
class ProbeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var runLabelSpinner: Spinner
    private val runLabels = arrayOf("מחובר לטעינה", "מנותק")
    private var selectedRunLabel = "מחובר לטעינה"
    private var latestChecks = JSONArray()
    private var latestRunMeta = JSONObject()
    private var isRunning = false
    private val jsonLock = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildPlainUi()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3301)
        }
        updateHistoryOnOpen()
    }

    private fun buildPlainUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 16)
        }
        val scroll = ScrollView(this)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(inner)
        inner.addView(TextView(this).apply { text = "Device Lab Probe — שלב 0 מורחב"; textSize = 18f })
        inner.addView(TextView(this).apply {
            text = "תווית הריצה אינה קובעת את מצב הטעינה. הדוח שומר את שניהם. אין הרשאת INTERNET."
            textSize = 13f
        })
        runLabelSpinner = Spinner(this)
        runLabelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, runLabels)
        inner.addView(runLabelSpinner)

        val runAll = Button(this).apply {
            text = "הרץ הכול"
            setOnClickListener { runAllProbes() }
        }
        inner.addView(runAll)
        val export = Button(this).apply {
            text = "ייצוא JSON"
            setOnClickListener { exportReport() }
        }
        inner.addView(export)
        inner.addView(TextView(this).apply {
            text = "בדיקת שרידות ברקע (שתי ריצות נפרדות מומלצות)"
            textSize = 16f
        })
        inner.addView(Button(this).apply {
            text = "התחל ניטור — ללא Wake Lock"
            setOnClickListener { startBackgroundMonitor(false) }
        })
        inner.addView(Button(this).apply {
            text = "התחל ניטור — Wake Lock קצר לכל דגימה"
            setOnClickListener { startBackgroundMonitor(true) }
        })
        inner.addView(Button(this).apply {
            text = "עצור ניטור"
            setOnClickListener { stopBackgroundMonitor() }
        })
        statusView = TextView(this).apply { textSize = 12f; text = "מוכן. עדיין לא בוצעה בדיקת Probe." }
        inner.addView(statusView)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun runAllProbes() {
        if (isRunning) return
        selectedRunLabel = runLabels.getOrElse(runLabelSpinner.selectedItemPosition) { runLabels[0] }
        isRunning = true
        statusView.text = "הבדיקה רצה ברקע. סריקת חיישנים עשויה להימשך. אל תסגור את האפליקציה עד לסיום."
        worker.execute {
            val started = now()
            val results = JSONArray()
            currentOutput = results
            val actualBattery = try {
                val batteryIntent = safeBatteryIntent()
                Pair(batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1,
                    batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1)
            } catch (_: Throwable) { Pair(Int.MIN_VALUE, Int.MIN_VALUE) }
            val actualStatus = actualBattery.first
            val actualPlugged = actualBattery.second
            val runMeta = JSONObject().apply {
                put("userRunLabel", selectedRunLabel)
                put("actualBatteryStatusRaw", actualStatus)
                put("actualPluggedRaw", actualPlugged)
                put("runStartedAt", started)
                put("apiLevel", Build.VERSION.SDK_INT)
                put("packageName", packageName)
                put("appVersion", "0.1.0")
            }
            try {
                runSection("identity") { checkIdentity() }
                runSection("control") { checkControlReads() }
                runSection("cpu") { checkCpu() }
                runSection("memory") { checkMemory() }
                runSection("storage") { checkStorage() }
                runSection("battery") { checkBattery() }
                runSection("display_graphics") { checkDisplayAndGraphics() }
                runSection("sensors") { checkSensors() }
                runSection("media") { checkMedia() }
                runSection("hardware_features") { checkHardwareFeatures() }
                runSection("settings") { checkSettings() }
            } catch (t: Throwable) {
                add("probe.runAll.unhandled", t.javaClass.name, "exception", "internal", "error", "unavailable", t)
            } finally {
                runMeta.put("runFinishedAt", now())
                synchronized(jsonLock) {
                    latestChecks = results
                    latestRunMeta = runMeta
                    isRunning = false
                }
                val summary = resultSummary(results)
                runOnUiThread {
                    statusView.text = "הסתיים. ${summary}. לחץ ייצוא JSON לשמירת כל התוצאות הגולמיות."
                }
            }
        }
    }

    private var currentOutput = JSONArray()

    private fun add(
        name: String,
        rawValue: Any?,
        unit: String,
        source: String,
        status: String? = null,
        classification: String? = null,
        throwable: Throwable? = null
    ) {
        val noValue = hasNoMeasurement(rawValue)
        val finalStatus = status ?: if (noValue) "empty" else "available"
        val finalClass = classification ?: if (noValue) "unavailable" else "reported"
        val error = throwable?.let { "${it.javaClass.name}: ${it.message ?: ""}" }
        val item = JSONObject().apply {
            put("name", name)
            put("rawValue", jsonValue(rawValue))
            put("unit", unit)
            put("source", source)
            put("status", finalStatus)
            put("classification", finalClass)
            put("rawError", error ?: JSONObject.NULL)
            put("timestamp", now())
        }
        synchronized(jsonLock) { currentOutput.put(item) }
    }

    private fun attempt(name: String, unit: String, source: String, classification: String = "reported", block: () -> Any?) {
        try {
            val value = block()
            if (value == null) {
                add(name, JSONObject.NULL, unit, source, "empty", "unavailable")
            } else if (hasNoMeasurement(value)) {
                add(name, value, unit, source, "empty", "unavailable")
            } else {
                add(name, value, unit, source, "available", classification)
            }
        } catch (t: Throwable) {
            val s = when (t) {
                is SecurityException -> "blocked"
                is java.io.FileNotFoundException, is java.nio.file.NoSuchFileException -> "unsupported"
                is UnsupportedOperationException, is NoSuchMethodError, is NoClassDefFoundError -> "unsupported"
                else -> "error"
            }
            add(name, JSONObject.NULL, unit, source, s, "unavailable", t)
        }
    }

    private fun mark(name: String, raw: Any?, unit: String, source: String, status: String, classification: String = "unavailable", error: Throwable? = null) {
        add(name, raw ?: JSONObject.NULL, unit, source, status, classification, error)
    }

    private fun readRaw(path: String, resultName: String = path) {
        attempt(resultName, "raw text; no conversion", path, "reported") {
            val f = File(path)
            if (!f.exists()) throw java.io.FileNotFoundException("path does not exist: $path")
            if (f.isDirectory) throw IllegalArgumentException("path is a directory")
            f.inputStream().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
    }

    private fun checkIdentity() {
        attempt("identity.Build.FINGERPRINT", "string", "Build.FINGERPRINT") { Build.FINGERPRINT }
        attempt("identity.Build.BOARD", "string", "Build.BOARD") { Build.BOARD }
        attempt("identity.Build.DEVICE", "string", "Build.DEVICE") { Build.DEVICE }
        attempt("identity.Build.HARDWARE", "string", "Build.HARDWARE") { Build.HARDWARE }
        attempt("identity.Build.PRODUCT", "string", "Build.PRODUCT") { Build.PRODUCT }
        attempt("identity.Build.SOC_MODEL", "string", "Build.SOC_MODEL") { Build.SOC_MODEL }
        attempt("identity.Build.SOC_MANUFACTURER", "string", "Build.SOC_MANUFACTURER") { Build.SOC_MANUFACTURER }
        attempt("identity.Build.SECURITY_PATCH", "string", "Build.VERSION.SECURITY_PATCH") { Build.VERSION.SECURITY_PATCH }
        attempt("identity.Build.SUPPORTED_ABIS", "string array", "Build.SUPPORTED_ABIS") { Build.SUPPORTED_ABIS.toList() }
        attempt("identity.Build.TAGS", "string", "Build.TAGS") { Build.TAGS }
        attempt("identity.Build.TYPE", "string", "Build.TYPE") { Build.TYPE }
        attempt("identity.Build.MANUFACTURER", "string", "Build.MANUFACTURER") { Build.MANUFACTURER }
        attempt("identity.Build.MODEL", "string", "Build.MODEL") { Build.MODEL }
        attempt("identity.Build.DISPLAY", "string", "Build.DISPLAY") { Build.DISPLAY }
        attempt("identity.os.version", "string", "System.getProperty(os.version)") { System.getProperty("os.version") }
        readRaw("/sys/fs/selinux/enforce", "identity.selinux.enforce")
    }

    private fun checkControlReads() {
        readRaw("/proc/meminfo", "control.proc.meminfo")
        readRaw("/proc/cpuinfo", "control.proc.cpuinfo")
        readRaw("/proc/self/stat", "control.proc.self.stat")
        attempt("control.Process.getElapsedCpuTime", "ms", "android.os.Process.getElapsedCpuTime()", "measured") { Process.getElapsedCpuTime() }
    }

    private fun checkCpu() {
        readRaw("/proc/stat", "cpu.proc.stat")
        readRaw("/proc/loadavg", "cpu.proc.loadavg")
        readRaw("/proc/pressure/cpu", "cpu.proc.pressure")
        val cpuRoot = File("/sys/devices/system/cpu")
        val cpus = try {
            cpuRoot.listFiles()?.filter { it.name.matches(Regex("cpu[0-9]+")) }?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }
        } catch (_: Throwable) { null }
        if (cpus.isNullOrEmpty()) {
            mark("cpu.cpu_directory_list", cpus?.size, "count", cpuRoot.path, "empty")
        } else {
            attempt("cpu.cpu_directory_list", "names", cpuRoot.path) { cpus.map { it.name } }
            val fields = listOf("scaling_cur_freq", "cpuinfo_max_freq", "cpuinfo_min_freq", "scaling_governor", "scaling_available_frequencies")
            cpus.forEach { cpu ->
                fields.forEach { field -> readRaw("${cpu.path}/cpufreq/$field", "cpu.${cpu.name}.cpufreq.$field") }
            }
        }
        val policyRoot = File("/sys/devices/system/cpu/cpufreq")
        val policies = try { policyRoot.listFiles()?.filter { it.name.startsWith("policy") } } catch (_: Throwable) { null }
        policies?.forEach { policy ->
            listOf("scaling_cur_freq", "cpuinfo_max_freq", "cpuinfo_min_freq", "scaling_governor", "scaling_available_frequencies").forEach {
                readRaw("${policy.path}/$it", "cpu.${policy.name}.$it")
            }
        }
        if (policies.isNullOrEmpty()) mark("cpu.cpufreq_policy_list", policies?.size, "count", policyRoot.path, "empty")
        scanThermalZones()
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null) {
            mark("cpu.thermal.powerManager", null, "object", "Context.POWER_SERVICE", "unsupported")
        } else {
            attempt("cpu.thermal.currentStatus", "integer status", "PowerManager.getCurrentThermalStatus()") { pm.currentThermalStatus }
            attempt("cpu.thermal.headroom.10s", "raw API float; estimated headroom", "PowerManager.getThermalHeadroom(10)", "estimated") { pm.getThermalHeadroom(10) }
        }
        attempt("cpu.HardwarePropertiesManager.service", "service availability", "Context.HARDWARE_PROPERTIES_SERVICE") {
            val hpm = getSystemService(Context.HARDWARE_PROPERTIES_SERVICE) as? HardwarePropertiesManager
            if (hpm == null) "null" else "available"
        }
        try {
            val hpm = getSystemService(Context.HARDWARE_PROPERTIES_SERVICE) as? HardwarePropertiesManager
            if (hpm == null) mark("cpu.HardwarePropertiesManager.getCpuUsages", null, "per-core records", "HardwarePropertiesManager.getCpuUsages()", "unsupported")
            else {
                val usages = hpm.cpuUsages
                if (usages == null || usages.isEmpty()) mark("cpu.HardwarePropertiesManager.getCpuUsages", usages?.size, "records", "HardwarePropertiesManager.getCpuUsages()", "empty")
                else usages.forEachIndexed { index, usage ->
                    attempt("cpu.HPM.core$index.active", "ms since boot", "CpuUsageInfo.getActive()", "reported") { usage.active }
                    attempt("cpu.HPM.core$index.total", "ms since boot", "CpuUsageInfo.getTotal()", "reported") { usage.total }
                }
            }
        } catch (t: Throwable) {
            val status = if (t is SecurityException) "blocked" else "error"
            mark("cpu.HardwarePropertiesManager.getCpuUsages", null, "per-core records", "HardwarePropertiesManager.getCpuUsages()", status, "unavailable", t)
        }
        val tempTypes = listOf(
            HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU to "CPU",
            HardwarePropertiesManager.DEVICE_TEMPERATURE_GPU to "GPU",
            HardwarePropertiesManager.DEVICE_TEMPERATURE_BATTERY to "BATTERY",
            HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN to "SKIN"
        )
        val tempSources = listOf(
            HardwarePropertiesManager.TEMPERATURE_CURRENT to "CURRENT",
            HardwarePropertiesManager.TEMPERATURE_THROTTLING to "THROTTLING",
            HardwarePropertiesManager.TEMPERATURE_SHUTDOWN to "SHUTDOWN",
            HardwarePropertiesManager.TEMPERATURE_THROTTLING_BELOW_VR_MIN to "THROTTLING_BELOW_VR_MIN"
        )
        tempTypes.forEach { (type, typeName) ->
            tempSources.forEach { (source, sourceName) ->
                try {
                    val hpm = getSystemService(Context.HARDWARE_PROPERTIES_SERVICE) as? HardwarePropertiesManager
                    if (hpm == null) mark("cpu.HPM.temperature.$typeName.$sourceName", null, "raw float array", "HardwarePropertiesManager.getDeviceTemperatures", "unsupported")
                    else {
                        val values = hpm.getDeviceTemperatures(type, source)
                        if (values.isNullOrEmpty()) mark("cpu.HPM.temperature.$typeName.$sourceName", values?.toList(), "raw float array", "HardwarePropertiesManager.getDeviceTemperatures", "empty")
                        else {
                            values.forEachIndexed { i, value ->
                                val valid = value != HardwarePropertiesManager.UNDEFINED_TEMPERATURE && !value.isNaN() && !value.isInfinite() && value != 0f
                                mark("cpu.HPM.temperature.$typeName.$sourceName.$i", value, "API temperature unit", "HardwarePropertiesManager.getDeviceTemperatures", if (valid) "available" else "empty", if (valid) "reported" else "unavailable")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    mark("cpu.HPM.temperature.$typeName.$sourceName", null, "raw float array", "HardwarePropertiesManager.getDeviceTemperatures", if (t is SecurityException) "blocked" else "error", "unavailable", t)
                }
            }
        }
        runTop()
    }

    private fun scanThermalZones() {
        val root = File("/sys/class/thermal")
        try {
            val zones = root.listFiles()?.filter { it.name.startsWith("thermal_zone") }?.sortedBy { it.name }
            if (zones.isNullOrEmpty()) mark("cpu.thermal_zone_list", zones?.size, "count", root.path, "empty")
            else {
                attempt("cpu.thermal_zone_list", "names", root.path) { zones.map { it.name } }
                zones.forEach { zone ->
                    readRaw("${zone.path}/type", "cpu.${zone.name}.type")
                    readRaw("${zone.path}/temp", "cpu.${zone.name}.temp")
                }
            }
        } catch (t: Throwable) {
            mark("cpu.thermal_zone_list", null, "count", root.path, if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
    }

    private fun runTop() {
        val name = "cpu.top"
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("top", "-b", "-n", "1"))
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                val partial = try { process.inputStream.available().let { n -> if (n > 0) ByteArray(n).also { process.inputStream.read(it) }.toString(Charsets.UTF_8) else "" } } catch (_: Throwable) { "" }
                mark(name, JSONObject().put("timedOut", true).put("partialOutput", partial), "top raw output", "Runtime.exec(top -b -n 1)", "error", "unavailable", java.util.concurrent.TimeoutException("top exceeded 3000 ms"))
            } else {
                val out = process.inputStream.bufferedReader().use { it.readText() }
                val err = process.errorStream.bufferedReader().use { it.readText() }
                mark(name, JSONObject().put("exitCode", process.exitValue()).put("stdout", out).put("stderr", err), "raw process output", "Runtime.exec(top -b -n 1)", if (out.isBlank() && err.isBlank()) "empty" else "available", if (out.isBlank() && err.isBlank()) "unavailable" else "reported")
            }
        } catch (t: Throwable) {
            mark(name, null, "raw process output", "Runtime.exec(top -b -n 1)", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        } finally {
            try { process?.destroy() } catch (_: Throwable) { }
        }
    }

    private fun checkMemory() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            attempt("memory.ActivityManager.MemoryInfo.availMem", "bytes", "ActivityManager.MemoryInfo.availMem") { info.availMem }
            attempt("memory.ActivityManager.MemoryInfo.totalMem", "bytes", "ActivityManager.MemoryInfo.totalMem") { info.totalMem }
            attempt("memory.ActivityManager.MemoryInfo.threshold", "bytes", "ActivityManager.MemoryInfo.threshold") { info.threshold }
            attempt("memory.ActivityManager.MemoryInfo.lowMemory", "boolean", "ActivityManager.MemoryInfo.lowMemory") { info.lowMemory }
            if (Build.VERSION.SDK_INT >= 29) {
                attempt("memory.ActivityManager.MemoryInfo.totalSwap", "bytes", "ActivityManager.MemoryInfo.totalSwap") { info.totalSwap }
                attempt("memory.ActivityManager.MemoryInfo.availSwap", "bytes", "ActivityManager.MemoryInfo.availSwap") { info.availSwap }
            } else mark("memory.ActivityManager.MemoryInfo.swap", null, "bytes", "API level", "unsupported")
        } catch (t: Throwable) {
            mark("memory.ActivityManager.MemoryInfo", null, "object", "ActivityManager.getMemoryInfo", "error", "unavailable", t)
        }
        attempt("memory.isLowRamDevice", "boolean", "ActivityManager.isLowRamDevice()") { am.isLowRamDevice }
        attempt("memory.memoryClass", "MB (API-reported)", "ActivityManager.memoryClass") { am.memoryClass }
        attempt("memory.largeMemoryClass", "MB (API-reported)", "ActivityManager.largeMemoryClass") { am.largeMemoryClass }
        readRaw("/proc/swaps", "memory.proc.swaps")
    }

    private fun checkStorage() {
        val candidates = linkedMapOf<String, File>()
        candidates["data"] = Environment.getDataDirectory()
        candidates["app_files"] = filesDir
        candidates["app_cache"] = cacheDir
        candidates["app_code_cache"] = codeCacheDir
        candidates["external_storage_root"] = Environment.getExternalStorageDirectory()
        getExternalFilesDirs(null).forEachIndexed { i, f -> if (f != null) candidates["external_files_$i"] = f }
        candidates.forEach { (label, path) ->
            attempt("storage.StatFs.$label.path", "path", "File.path") { path.path }
            try {
                val stat = StatFs(path.path)
                attempt("storage.StatFs.$label.blockSize", "bytes/block", "StatFs.blockSizeLong") { stat.blockSizeLong }
                attempt("storage.StatFs.$label.blockCount", "blocks", "StatFs.blockCountLong") { stat.blockCountLong }
                attempt("storage.StatFs.$label.availableBlocks", "blocks", "StatFs.availableBlocksLong") { stat.availableBlocksLong }
                attempt("storage.StatFs.$label.totalBytes", "bytes", "StatFs.totalBytes") { stat.totalBytes }
                attempt("storage.StatFs.$label.availableBytes", "bytes", "StatFs.availableBytes") { stat.availableBytes }
                attempt("storage.StatFs.$label.freeBytes", "bytes", "StatFs.freeBytes") { stat.freeBytes }
            } catch (t: Throwable) {
                mark("storage.StatFs.$label", null, "StatFs", path.path, if (t is SecurityException) "blocked" else "error", "unavailable", t)
            }
        }
        try {
            val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val vols = sm.storageVolumes
            if (vols.isEmpty()) mark("storage.StorageManager.volumes", 0, "count", "StorageManager.storageVolumes", "empty")
            vols.forEachIndexed { i, volume ->
                attempt("storage.volume.$i.id", "string", "StorageVolume.id") { volume.id }
                attempt("storage.volume.$i.uuid", "string", "StorageVolume.uuid") { volume.uuid }
                attempt("storage.volume.$i.state", "string", "StorageVolume.state") { volume.state }
                attempt("storage.volume.$i.isPrimary", "boolean", "StorageVolume.isPrimary") { volume.isPrimary }
                attempt("storage.volume.$i.isRemovable", "boolean", "StorageVolume.isRemovable") { volume.isRemovable }
                attempt("storage.volume.$i.isEmulated", "boolean", "StorageVolume.isEmulated") { volume.isEmulated }
                val volumeDir = try { volume.directory } catch (_: Throwable) { null }
                attempt("storage.volume.$i.directory", "path", "StorageVolume.directory") { volumeDir?.path }
                if (volumeDir != null) {
                    try {
                        val stat = StatFs(volumeDir.path)
                        attempt("storage.volume.$i.StatFs.blockSize", "bytes/block", "StatFs.blockSizeLong") { stat.blockSizeLong }
                        attempt("storage.volume.$i.StatFs.blockCount", "blocks", "StatFs.blockCountLong") { stat.blockCountLong }
                        attempt("storage.volume.$i.StatFs.availableBlocks", "blocks", "StatFs.availableBlocksLong") { stat.availableBlocksLong }
                        attempt("storage.volume.$i.StatFs.totalBytes", "bytes", "StatFs.totalBytes") { stat.totalBytes }
                        attempt("storage.volume.$i.StatFs.availableBytes", "bytes", "StatFs.availableBytes") { stat.availableBytes }
                        attempt("storage.volume.$i.StatFs.freeBytes", "bytes", "StatFs.freeBytes") { stat.freeBytes }
                    } catch (t: Throwable) {
                        mark("storage.volume.$i.StatFs", null, "StatFs", volumeDir.path, if (t is SecurityException) "blocked" else "error", "unavailable", t)
                    }
                    try {
                        val uuid = sm.getUuidForPath(volumeDir)
                        attempt("storage.volume.$i.StorageStats.uuid", "UUID", "StorageManager.getUuidForPath") { uuid.toString() }
                        attempt("storage.volume.$i.StorageStats.totalBytes", "bytes", "StorageStatsManager.getTotalBytes(UUID)") { ssmForVolume().getTotalBytes(uuid) }
                        attempt("storage.volume.$i.StorageStats.freeBytes", "bytes", "StorageStatsManager.getFreeBytes(UUID)") { ssmForVolume().getFreeBytes(uuid) }
                        val ownStats = ssmForVolume().queryStatsForUid(uuid, Process.myUid())
                        attempt("storage.volume.$i.StorageStats.ownUid.codeBytes", "bytes", "StorageStatsManager.queryStatsForUid(own UID).appBytes") { ownStats.appBytes }
                        attempt("storage.volume.$i.StorageStats.ownUid.dataBytes", "bytes", "StorageStatsManager.queryStatsForUid(own UID).dataBytes") { ownStats.dataBytes }
                        attempt("storage.volume.$i.StorageStats.ownUid.cacheBytes", "bytes", "StorageStatsManager.queryStatsForUid(own UID).cacheBytes") { ownStats.cacheBytes }
                    } catch (t: Throwable) {
                        mark("storage.volume.$i.StorageStats", null, "statistics", "StorageStatsManager / StorageVolume UUID", if (t is SecurityException) "blocked" else "error", "unavailable", t)
                    }
                }
            }
        } catch (t: Throwable) {
            mark("storage.StorageManager.volumes", null, "records", "StorageManager.storageVolumes", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
        try {
            val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            attempt("storage.StorageStatsManager.totalBytes.default", "bytes", "StorageStatsManager.getTotalBytes(UUID_DEFAULT)") { ssm.getTotalBytes(StorageManager.UUID_DEFAULT) }
            attempt("storage.StorageStatsManager.freeBytes.default", "bytes", "StorageStatsManager.getFreeBytes(UUID_DEFAULT)") { ssm.getFreeBytes(StorageManager.UUID_DEFAULT) }
            val stats = ssm.queryStatsForUid(StorageManager.UUID_DEFAULT, Process.myUid())
            attempt("storage.StorageStatsManager.ownUid.codeBytes", "bytes", "StorageStatsManager.queryStatsForUid(own UID).appBytes") { stats.appBytes }
            attempt("storage.StorageStatsManager.ownUid.dataBytes", "bytes", "StorageStatsManager.queryStatsForUid(own UID).dataBytes") { stats.dataBytes }
            attempt("storage.StorageStatsManager.ownUid.cacheBytes", "bytes", "StorageStatsManager.queryStatsForUid(own UID).cacheBytes") { stats.cacheBytes }
        } catch (t: Throwable) {
            mark("storage.StorageStatsManager", null, "statistics", "StorageStatsManager", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
    }

    private fun safeBatteryIntent(): Intent? = try {
        registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (_: Throwable) { null }

    private fun ssmForVolume(): StorageStatsManager = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager

    private fun checkBattery() {
        val intent = safeBatteryIntent()
        if (intent == null) {
            mark("battery.ACTION_BATTERY_CHANGED", null, "extras", "registerReceiver(null, ACTION_BATTERY_CHANGED)", "empty")
        } else {
            try {
                val extras = intent.extras
                if (extras == null || extras.isEmpty) mark("battery.ACTION_BATTERY_CHANGED.extras", null, "bundle", "Intent.extras", "empty")
                else for (key in extras.keySet().sorted()) {
                    val value = try { extras.get(key) } catch (t: Throwable) { null }
                    attempt("battery.extra.$key", "raw extra", "ACTION_BATTERY_CHANGED extras[$key]") { value }
                }
            } catch (t: Throwable) {
                mark("battery.ACTION_BATTERY_CHANGED.extras", null, "bundle", "Intent.extras", "error", "unavailable", t)
            }
        }
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val properties = listOf(
            "CAPACITY" to BatteryManager.BATTERY_PROPERTY_CAPACITY,
            "CHARGE_COUNTER" to BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER,
            "CURRENT_NOW" to BatteryManager.BATTERY_PROPERTY_CURRENT_NOW,
            "CURRENT_AVERAGE" to BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE,
            "ENERGY_COUNTER" to BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER
        )
        properties.forEach { (name, id) -> readBatteryLongProperty(bm, name, id) }
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                val healthId = BatteryManager.BATTERY_PROPERTY_STATE_OF_HEALTH
                readBatteryLongProperty(bm, "STATE_OF_HEALTH", healthId)
            } catch (t: Throwable) {
                mark("battery.property.STATE_OF_HEALTH", null, "API property", "BatteryManager.BATTERY_PROPERTY_STATE_OF_HEALTH", "unsupported", "unavailable", t)
            }
        } else mark("battery.property.STATE_OF_HEALTH", "API unavailable on this runtime", "API property", "BatteryManager API level 35+", "unsupported")
        attempt("battery.BatteryManager.isCharging", "boolean", "BatteryManager.isCharging()") { bm.isCharging }
        try {
            val remaining = bm.computeChargeTimeRemaining()
            when {
                remaining == -1L -> mark("battery.BatteryManager.computeChargeTimeRemaining", remaining, "ms; raw API result", "BatteryManager.computeChargeTimeRemaining()", "unsupported")
                remaining == 0L -> mark("battery.BatteryManager.computeChargeTimeRemaining", remaining, "ms; raw API result", "BatteryManager.computeChargeTimeRemaining()", "empty")
                else -> mark("battery.BatteryManager.computeChargeTimeRemaining", remaining, "ms; raw API result", "BatteryManager.computeChargeTimeRemaining()", "available", "reported")
            }
        } catch (t: Throwable) {
            mark("battery.BatteryManager.computeChargeTimeRemaining", null, "ms; raw API result", "BatteryManager.computeChargeTimeRemaining()", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
        scanPowerSupply()
    }

    private fun readBatteryLongProperty(bm: BatteryManager, key: String, id: Int) {
        try {
            val value = bm.getLongProperty(id)
            when {
                value == Long.MIN_VALUE -> mark("battery.property.$key", "Long.MIN_VALUE", "raw API long", "BatteryManager.getLongProperty($id)", "unsupported")
                value == 0L -> mark("battery.property.$key", value, "raw API long", "BatteryManager.getLongProperty($id)", "empty")
                else -> mark("battery.property.$key", value, "raw API long; no conversion", "BatteryManager.getLongProperty($id)", "available", "reported")
            }
        } catch (t: Throwable) {
            mark("battery.property.$key", null, "raw API long", "BatteryManager.getLongProperty($id)", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
    }

    private fun scanPowerSupply() {
        val root = File("/sys/class/power_supply")
        var filesRead = 0
        val visited = HashSet<String>()
        val maxFiles = 700
        try {
            val supplies = root.listFiles()
            if (supplies.isNullOrEmpty()) {
                mark("battery.power_supply.list", supplies?.size, "count", root.path, "empty")
                return
            }
            attempt("battery.power_supply.supplies", "names", root.path) { supplies.map { it.name } }
            fun walk(file: File, depth: Int) {
                if (filesRead >= maxFiles) return
                val canonical = try { file.canonicalPath } catch (_: Throwable) { file.absolutePath }
                if (!visited.add(canonical)) return
                try {
                    if (file.isDirectory) {
                        if (depth < 5) file.listFiles()?.sortedBy { it.name }?.forEach { walk(it, depth + 1) }
                        else mark("battery.power_supply.${file.path}.depth", depth, "depth", file.path, "unsupported")
                    } else {
                        filesRead++
                        val path = file.path
                        attempt("battery.power_supply.$path", "raw text; no conversion", path, "reported") {
                            val text = file.inputStream().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                            if (text.length > 16000) text.substring(0, 16000) + "...[truncated at 16000 characters]" else text
                        }
                    }
                } catch (t: Throwable) {
                    filesRead++
                    mark("battery.power_supply.${file.path}", null, "raw text", file.path, when (t) { is SecurityException -> "blocked"; is java.io.FileNotFoundException -> "unsupported"; else -> "error" }, "unavailable", t)
                }
            }
            supplies.sortedBy { it.name }.forEach { walk(it, 0) }
            mark("battery.power_supply.filesScanned", filesRead, "files", root.path, if (filesRead == 0) "empty" else "available", if (filesRead == 0) "unavailable" else "reported")
            if (filesRead >= maxFiles) mark("battery.power_supply.scanLimitReached", maxFiles, "files maximum", root.path, "partial")
        } catch (t: Throwable) {
            mark("battery.power_supply.list", null, "count", root.path, if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
    }

    private fun checkDisplayAndGraphics() {
        try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            display.getRealMetrics(metrics)
            attempt("display.metrics.widthPixels", "pixels", "DisplayMetrics.widthPixels") { metrics.widthPixels }
            attempt("display.metrics.heightPixels", "pixels", "DisplayMetrics.heightPixels") { metrics.heightPixels }
            attempt("display.metrics.density", "raw float", "DisplayMetrics.density") { metrics.density }
            attempt("display.metrics.densityDpi", "dpi", "DisplayMetrics.densityDpi") { metrics.densityDpi }
            attempt("display.metrics.scaledDensity", "raw float", "DisplayMetrics.scaledDensity") { metrics.scaledDensity }
            attempt("display.metrics.xdpi", "raw float", "DisplayMetrics.xdpi") { metrics.xdpi }
            attempt("display.metrics.ydpi", "raw float", "DisplayMetrics.ydpi") { metrics.ydpi }
            attempt("display.rotation", "integer", "Display.rotation") { display.rotation }
            attempt("display.refreshRate", "Hz", "Display.refreshRate") { display.refreshRate }
            attempt("display.mode.id", "integer", "Display.Mode.getModeId") { display.mode.modeId }
            attempt("display.mode.physicalWidth", "pixels", "Display.Mode.getPhysicalWidth") { display.mode.physicalWidth }
            attempt("display.mode.physicalHeight", "pixels", "Display.Mode.getPhysicalHeight") { display.mode.physicalHeight }
            attempt("display.mode.refreshRate", "Hz", "Display.Mode.getRefreshRate") { display.mode.refreshRate }
            attempt("display.supportedModes", "raw records", "Display.getSupportedModes") { display.supportedModes.map { mapOf("id" to it.modeId, "width" to it.physicalWidth, "height" to it.physicalHeight, "refreshRate" to it.refreshRate) } }
            val hdr = display.hdrCapabilities
            if (hdr == null) mark("display.hdr", null, "capabilities", "Display.getHdrCapabilities", "empty") else {
                attempt("display.hdr.supportedTypes", "integer array", "Display.HdrCapabilities.getSupportedHdrTypes") { hdr.supportedHdrTypes.toList() }
                attempt("display.hdr.maxLuminance", "raw luminance", "Display.HdrCapabilities.getDesiredMaxLuminance") { hdr.desiredMaxLuminance }
                attempt("display.hdr.maxAverageLuminance", "raw luminance", "Display.HdrCapabilities.getDesiredMaxAverageLuminance") { hdr.desiredMaxAverageLuminance }
                attempt("display.hdr.minLuminance", "raw luminance", "Display.HdrCapabilities.getDesiredMinLuminance") { hdr.desiredMinLuminance }
            }
        } catch (t: Throwable) {
            mark("display.metrics", null, "display metrics", "WindowManager/Display", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
        attempt("graphics.OpenGL_ES_feature", "feature value", "PackageManager.FEATURE_OPENGLES") { packageManager.systemAvailableFeatures.filter { it.name == PackageManager.FEATURE_OPENGLES }.map { it.reqGlEsVersion } }
        attempt("graphics.OpenGL_ES_version", "integer feature version", "PackageManager.getSystemAvailableFeatures") { packageManager.systemAvailableFeatures.filter { it.name == PackageManager.FEATURE_OPENGLES }.map { it.reqGlEsVersion } }
        attempt("graphics.Vulkan.hardwareLevel", "integer feature version", "PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL") { packageManager.systemAvailableFeatures.filter { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }.map { it.version } }
        attempt("graphics.Vulkan.version", "integer feature version", "PackageManager.FEATURE_VULKAN_HARDWARE_VERSION") { packageManager.systemAvailableFeatures.filter { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }.map { it.version } }
        probeEgl()
    }

    private fun probeEgl() {
        var eglDisplay: EGLDisplay? = null
        var eglSurface: EGLSurface? = null
        var eglContext: EGLContext? = null
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("eglGetDisplay returned EGL_NO_DISPLAY")
            val versions = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) throw IllegalStateException("eglInitialize failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            val configAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, count, 0) || count[0] == 0) throw IllegalStateException("eglChooseConfig failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            val pbufferAttrs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttrs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreatePbufferSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttrs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) throw IllegalStateException("eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            attempt("graphics.GL_RENDERER", "string", "GLES20.glGetString(GL_RENDERER)") { GLES20.glGetString(GLES20.GL_RENDERER) }
            attempt("graphics.GL_VENDOR", "string", "GLES20.glGetString(GL_VENDOR)") { GLES20.glGetString(GLES20.GL_VENDOR) }
            attempt("graphics.GL_VERSION", "string", "GLES20.glGetString(GL_VERSION)") { GLES20.glGetString(GLES20.GL_VERSION) }
            attempt("graphics.EGL_VERSION", "version string", "EGL14.eglInitialize") { "${versions[0]}.${versions[1]}" }
        } catch (t: Throwable) {
            mark("graphics.EGL_offscreen", null, "EGL context", "EGL14 offscreen pbuffer", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        } finally {
            try { if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } catch (_: Throwable) { }
            try { if (eglDisplay != null && eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface) } catch (_: Throwable) { }
            try { if (eglDisplay != null && eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext) } catch (_: Throwable) { }
            try { if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(eglDisplay) } catch (_: Throwable) { }
        }
    }

    private fun checkSensors() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = try { sm.getSensorList(Sensor.TYPE_ALL) } catch (t: Throwable) {
            mark("sensors.list", null, "records", "SensorManager.getSensorList(TYPE_ALL)", if (t is SecurityException) "blocked" else "error", "unavailable", t)
            return
        }
        if (sensors.isEmpty()) mark("sensors.list", 0, "count", "SensorManager.getSensorList(TYPE_ALL)", "empty")
        attempt("sensors.list", "sensor records", "SensorManager.getSensorList(TYPE_ALL)") { sensors.map { sensorMap(it) } }
        sensors.forEachIndexed { index, sensor ->
            attempt("sensors.$index.name", "string", "Sensor.name") { sensor.name }
            attempt("sensors.$index.type", "integer", "Sensor.type") { sensor.type }
            attempt("sensors.$index.vendor", "string", "Sensor.vendor") { sensor.vendor }
            attempt("sensors.$index.version", "integer", "Sensor.version") { sensor.version }
            attempt("sensors.$index.maximumRange", "API raw float", "Sensor.maximumRange") { sensor.maximumRange }
            attempt("sensors.$index.resolution", "API raw float", "Sensor.resolution") { sensor.resolution }
            attempt("sensors.$index.power", "mA as documented by API", "Sensor.power") { sensor.power }
            attempt("sensors.$index.minDelay", "microseconds as documented by API", "Sensor.minDelay") { sensor.minDelay }
            attempt("sensors.$index.maxDelay", "microseconds as documented by API", "Sensor.maxDelay") { sensor.maxDelay }
            attempt("sensors.$index.wakeUp", "boolean", "Sensor.isWakeUpSensor") { sensor.isWakeUpSensor }
            attempt("sensors.$index.reportingMode", "integer", "Sensor.reportingMode") { sensor.reportingMode }
            attempt("sensors.$index.stringType", "string", "Sensor.stringType") { sensor.stringType }
            attempt("sensors.$index.requiredPermission", "string", "Sensor.requiredPermission") { sensor.requiredPermission }
            if (!sensor.requiredPermission.isNullOrBlank()) {
                mark("sensors.$index.sample", "skipped: sensor requires permission", "event values", "Sensor.requiredPermission", "blocked")
            } else {
                sampleSensor(sm, sensor, index)
            }
        }
    }

    private fun sensorMap(s: Sensor): Map<String, Any?> = mapOf(
        "name" to s.name, "type" to s.type, "vendor" to s.vendor, "version" to s.version,
        "maximumRange" to s.maximumRange, "resolution" to s.resolution, "power" to s.power,
        "minDelay" to s.minDelay, "maxDelay" to s.maxDelay, "wakeUp" to s.isWakeUpSensor,
        "reportingMode" to s.reportingMode, "stringType" to s.stringType, "requiredPermission" to s.requiredPermission
    )

    private fun sampleSensor(sm: SensorManager, sensor: Sensor, index: Int) {
        val latch = CountDownLatch(1)
        var values: List<Float>? = null
        var accuracy: Int? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null && values == null) {
                    values = event.values.toList()
                    latch.countDown()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, value: Int) { accuracy = value }
        }
        try {
            val registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            if (!registered) {
                mark("sensors.$index.sample", null, "event values", "SensorManager.registerListener", "unsupported")
                return
            }
            val got = latch.await(500, TimeUnit.MILLISECONDS)
            if (got && values != null) {
                mark("sensors.$index.sample", values, "raw float array", "SensorEvent.values", "available", "measured")
                if (accuracy != null) attempt("sensors.$index.accuracy", "integer", "SensorEventListener.onAccuracyChanged") { accuracy!! }
            } else mark("sensors.$index.sample", null, "event values", "SensorManager; waited up to 500ms", "empty")
        } catch (t: Throwable) {
            mark("sensors.$index.sample", null, "event values", "SensorManager.registerListener", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        } finally {
            try { sm.unregisterListener(listener, sensor) } catch (_: Throwable) { }
        }
    }

    private fun checkMedia() {
        checkCodecs()
        checkAudio()
        checkWidevine()
        checkCameras()
    }

    private fun checkCodecs() {
        try {
            val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            if (codecs.isEmpty()) mark("media.codecs.list", 0, "count", "MediaCodecList(ALL_CODECS)", "empty")
            else attempt("media.codecs.list", "count", "MediaCodecList(ALL_CODECS)") { codecs.size }
            codecs.forEachIndexed { ci, codec ->
                attempt("media.codec.$ci.name", "string", "MediaCodecInfo.getName") { codec.name }
                attempt("media.codec.$ci.isEncoder", "boolean", "MediaCodecInfo.isEncoder") { codec.isEncoder }
                if (Build.VERSION.SDK_INT >= 29) {
                    attempt("media.codec.$ci.isHardwareAccelerated", "boolean", "MediaCodecInfo.isHardwareAccelerated") { codec.isHardwareAccelerated }
                    attempt("media.codec.$ci.isSoftwareOnly", "boolean", "MediaCodecInfo.isSoftwareOnly") { codec.isSoftwareOnly }
                    attempt("media.codec.$ci.isVendor", "boolean", "MediaCodecInfo.isVendor") { codec.isVendor }
                }
                val types = codec.supportedTypes.toList()
                attempt("media.codec.$ci.supportedTypes", "MIME strings", "MediaCodecInfo.getSupportedTypes") { types }
                types.forEachIndexed { ti, type ->
                    try {
                        val caps = codec.getCapabilitiesForType(type)
                        attempt("media.codec.$ci.type.$ti.mime", "MIME", "MediaCodecInfo.getSupportedTypes") { type }
                        attempt("media.codec.$ci.type.$ti.colorFormats", "integer array", "CodecCapabilities.colorFormats") { caps.colorFormats.toList() }
                        attempt("media.codec.$ci.type.$ti.profileLevels", "raw records", "CodecCapabilities.profileLevels") { caps.profileLevels.map { mapOf("profile" to it.profile, "level" to it.level) } }
                        if (type.startsWith("video/")) {
                            val video = caps.videoCapabilities
                            if (video == null) mark("media.codec.$ci.type.$ti.videoCapabilities", null, "object", "CodecCapabilities.videoCapabilities", "empty")
                            else {
                                attempt("media.codec.$ci.type.$ti.widthRange", "pixels range", "VideoCapabilities.supportedWidths") { listOf(video.supportedWidths.lower, video.supportedWidths.upper) }
                                attempt("media.codec.$ci.type.$ti.heightRange", "pixels range", "VideoCapabilities.supportedHeights") { listOf(video.supportedHeights.lower, video.supportedHeights.upper) }
                                attempt("media.codec.$ci.type.$ti.widthAlignment", "pixels", "VideoCapabilities.widthAlignment") { video.widthAlignment }
                                attempt("media.codec.$ci.type.$ti.heightAlignment", "pixels", "VideoCapabilities.heightAlignment") { video.heightAlignment }
                                attempt("media.codec.$ci.type.$ti.supportedFrameRates", "frames/s range", "VideoCapabilities.supportedFrameRates") { "${video.supportedFrameRates.lower}..${video.supportedFrameRates.upper}" }
                                val maxW = video.supportedWidths.upper
                                val maxH = video.supportedHeights.upper
                                attempt("media.codec.$ci.type.$ti.maxReportedWidth", "pixels", "VideoCapabilities.supportedWidths.upper") { maxW }
                                attempt("media.codec.$ci.type.$ti.maxReportedHeight", "pixels", "VideoCapabilities.supportedHeights.upper") { maxH }
                            }
                        }
                    } catch (t: Throwable) {
                        mark("media.codec.$ci.type.$ti", null, "codec type capability", "MediaCodecInfo.getCapabilitiesForType($type)", if (t is SecurityException) "blocked" else "error", "unavailable", t)
                    }
                }
            }
        } catch (t: Throwable) {
            mark("media.codecs.list", null, "codec records", "MediaCodecList(ALL_CODECS)", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
    }

    private fun checkAudio() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            attempt("media.audio.mode", "integer", "AudioManager.mode") { am.mode }
            attempt("media.audio.isMusicActive", "boolean", "AudioManager.isMusicActive") { am.isMusicActive }
            attempt("media.audio.outputSampleRateProperty", "string; API property", "AudioManager.getProperty(PROPERTY_OUTPUT_SAMPLE_RATE)") { am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) }
            attempt("media.audio.outputFramesPerBuffer", "string; API property", "AudioManager.getProperty(PROPERTY_OUTPUT_FRAMES_PER_BUFFER)") { am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) }
            val devices = am.getDevices(AudioManager.GET_DEVICES_ALL)
            if (devices.isEmpty()) mark("media.audio.devices", 0, "count", "AudioManager.getDevices(GET_DEVICES_ALL)", "empty")
            devices.forEachIndexed { i, device ->
                attempt("media.audio.device.$i.id", "integer", "AudioDeviceInfo.getId") { device.id }
                attempt("media.audio.device.$i.type", "integer", "AudioDeviceInfo.getType") { device.type }
                attempt("media.audio.device.$i.productName", "string", "AudioDeviceInfo.getProductName") { device.productName?.toString() }
                attempt("media.audio.device.$i.isSource", "boolean", "AudioDeviceInfo.isSource") { device.isSource }
                attempt("media.audio.device.$i.isSink", "boolean", "AudioDeviceInfo.isSink") { device.isSink }
                attempt("media.audio.device.$i.sampleRates", "Hz values; raw API array", "AudioDeviceInfo.getSampleRates") { device.sampleRates.toList() }
                attempt("media.audio.device.$i.channelCounts", "channel counts", "AudioDeviceInfo.getChannelCounts") { device.channelCounts.toList() }
                attempt("media.audio.device.$i.channelMasks", "integer array", "AudioDeviceInfo.getChannelMasks") { device.channelMasks.toList() }
                attempt("media.audio.device.$i.encodings", "integer array", "AudioDeviceInfo.getEncodings") { device.encodings.toList() }
            }
        } catch (t: Throwable) {
            mark("media.audio.devices", null, "devices", "AudioManager", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
        listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000).forEach { rate ->
            attempt("media.audio.AudioTrack.minBufferSize.$rate", "raw integer bytes or error code", "AudioTrack.getMinBufferSize($rate, stereo, PCM16)") { AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) }
            attempt("media.audio.AudioRecord.minBufferSize.$rate", "raw integer bytes or error code", "AudioRecord.getMinBufferSize($rate, mono, PCM16)") { AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) }
        }
    }

    private fun checkWidevine() {
        val uuid = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
        var drm: MediaDrm? = null
        try {
            drm = MediaDrm(uuid)
            attempt("media.widevine.vendor", "string", "MediaDrm(Widevine).getPropertyString(vendor)") { drm.getPropertyString(MediaDrm.PROPERTY_VENDOR) }
            attempt("media.widevine.version", "string", "MediaDrm(Widevine).getPropertyString(version)") { drm.getPropertyString(MediaDrm.PROPERTY_VERSION) }
            attempt("media.widevine.description", "string", "MediaDrm(Widevine).getPropertyString(description)") { drm.getPropertyString(MediaDrm.PROPERTY_DESCRIPTION) }
            attempt("media.widevine.securityLevel", "string", "MediaDrm(Widevine).getPropertyString(securityLevel)") { drm.getPropertyString("securityLevel") }
        } catch (t: Throwable) {
            mark("media.widevine", null, "DRM properties", "MediaDrm(Widevine UUID)", when (t) { is SecurityException -> "blocked"; else -> "unsupported" }, "unavailable", t)
        } finally {
            try { drm?.release() } catch (_: Throwable) { }
        }
    }

    private fun checkCameras() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = cm.cameraIdList
            attempt("media.camera.ids", "camera IDs", "CameraManager.cameraIdList") { ids.toList() }
            ids.forEachIndexed { i, id ->
                attempt("media.camera.$i.id", "string", "CameraManager.cameraIdList") { id }
                try {
                    val c = cm.getCameraCharacteristics(id)
                    attempt("media.camera.$i.lensFacing", "integer", "CameraCharacteristics.LENS_FACING") { c.get(CameraCharacteristics.LENS_FACING) }
                    attempt("media.camera.$i.sensorOrientation", "degrees; raw API value", "CameraCharacteristics.SENSOR_ORIENTATION") { c.get(CameraCharacteristics.SENSOR_ORIENTATION) }
                    attempt("media.camera.$i.hardwareLevel", "integer", "CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL") { c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) }
                    attempt("media.camera.$i.flashAvailable", "boolean", "CameraCharacteristics.FLASH_INFO_AVAILABLE") { c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) }
                    attempt("media.camera.$i.capabilities", "integer array", "CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES") { c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() }
                    val rect = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    attempt("media.camera.$i.activeArray", "raw coordinate array", "CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE") { rect?.let { listOf(it.left, it.top, it.right, it.bottom) } }
                } catch (t: Throwable) {
                    mark("media.camera.$i.characteristics", null, "characteristics", "CameraManager.getCameraCharacteristics($id)", if (t is SecurityException) "blocked" else "error", "unavailable", t)
                }
            }
        } catch (t: Throwable) {
            mark("media.camera.ids", null, "camera IDs", "CameraManager.cameraIdList without CAMERA permission", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
    }

    private fun checkHardwareFeatures() {
        try {
            val features = packageManager.systemAvailableFeatures
            attempt("features.count", "count", "PackageManager.getSystemAvailableFeatures") { features.size }
            features.forEachIndexed { i, feature ->
                attempt("features.$i.name", "string", "FeatureInfo.name") { feature.name }
                attempt("features.$i.reqGlEsVersion", "integer", "FeatureInfo.reqGlEsVersion") { feature.reqGlEsVersion }
                attempt("features.$i.flags", "integer", "FeatureInfo.flags") { feature.flags }
                attempt("features.$i.version", "integer", "FeatureInfo.version") { feature.version }
            }
        } catch (t: Throwable) {
            mark("features.list", null, "features", "PackageManager.getSystemAvailableFeatures", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
        val featureNames = listOf(
            "wifi" to PackageManager.FEATURE_WIFI,
            "bluetooth" to PackageManager.FEATURE_BLUETOOTH,
            "bluetooth_le" to PackageManager.FEATURE_BLUETOOTH_LE,
            "nfc" to PackageManager.FEATURE_NFC,
            "usb_host" to PackageManager.FEATURE_USB_HOST,
            "usb_accessory" to PackageManager.FEATURE_USB_ACCESSORY,
            "telephony" to PackageManager.FEATURE_TELEPHONY,
            "telephony_gsm" to PackageManager.FEATURE_TELEPHONY_GSM,
            "telephony_cdma" to PackageManager.FEATURE_TELEPHONY_CDMA,
            "camera" to PackageManager.FEATURE_CAMERA,
            "camera_front" to PackageManager.FEATURE_CAMERA_FRONT,
            "camera_flash" to PackageManager.FEATURE_CAMERA_FLASH,
            "microphone" to PackageManager.FEATURE_MICROPHONE,
            "location" to PackageManager.FEATURE_LOCATION,
            "location_gps" to PackageManager.FEATURE_LOCATION_GPS,
            "location_network" to PackageManager.FEATURE_LOCATION_NETWORK,
            "ethernet" to "android.hardware.ethernet",
            "consumer_ir" to PackageManager.FEATURE_CONSUMER_IR,
            "fingerprint" to PackageManager.FEATURE_FINGERPRINT,
            "vulkan_level" to PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL,
            "vulkan_version" to PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
            "opengles" to PackageManager.FEATURE_OPENGLES
        )
        featureNames.forEach { (label, feature) ->
            attempt("features.has.$label", "boolean", "PackageManager.hasSystemFeature($feature)") { packageManager.hasSystemFeature(feature) }
        }
        try {
            val usb = getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = usb.deviceList
            if (devices.isEmpty()) mark("hardware.usb.deviceList", 0, "count", "UsbManager.getDeviceList()", "empty")
            devices.entries.forEachIndexed { i, entry ->
                val d = entry.value
                attempt("hardware.usb.$i.key", "string", "UsbManager.deviceList key") { entry.key }
                attempt("hardware.usb.$i.deviceName", "string", "UsbDevice.deviceName") { d.deviceName }
                attempt("hardware.usb.$i.vendorId", "integer", "UsbDevice.vendorId") { d.vendorId }
                attempt("hardware.usb.$i.productId", "integer", "UsbDevice.productId") { d.productId }
                attempt("hardware.usb.$i.deviceClass", "integer", "UsbDevice.deviceClass") { d.deviceClass }
                attempt("hardware.usb.$i.deviceSubclass", "integer", "UsbDevice.deviceSubclass") { d.deviceSubclass }
                attempt("hardware.usb.$i.deviceProtocol", "integer", "UsbDevice.deviceProtocol") { d.deviceProtocol }
                attempt("hardware.usb.$i.interfaceCount", "integer", "UsbDevice.interfaceCount") { d.interfaceCount }
            }
        } catch (t: Throwable) {
            mark("hardware.usb.deviceList", null, "USB devices", "UsbManager.getDeviceList()", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
    }

    private fun checkSettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        attempt("settings.powerSaveMode", "boolean", "PowerManager.isPowerSaveMode()") { pm.isPowerSaveMode }
        attempt("settings.deviceIdleMode", "boolean", "PowerManager.isDeviceIdleMode()") { pm.isDeviceIdleMode }
        attempt("settings.isIgnoringBatteryOptimizations", "boolean", "PowerManager.isIgnoringBatteryOptimizations(packageName)") { pm.isIgnoringBatteryOptimizations(packageName) }
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            attempt("settings.appStandbyBucket", "integer bucket", "UsageStatsManager.appStandbyBucket") { usm.appStandbyBucket }
        } catch (t: Throwable) {
            mark("settings.appStandbyBucket", null, "integer bucket", "UsageStatsManager.appStandbyBucket", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
        attempt("settings.externalStorageState", "string", "Environment.getExternalStorageState()") { Environment.getExternalStorageState() }
        attempt("settings.externalStorageEmulated", "boolean", "Environment.isExternalStorageEmulated()") { Environment.isExternalStorageEmulated() }
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            attempt("settings.storageEncryptionStatus", "integer status", "DevicePolicyManager.getStorageEncryptionStatus()") { dpm.storageEncryptionStatus }
        } catch (t: Throwable) {
            mark("settings.storageEncryptionStatus", null, "integer status", "DevicePolicyManager.getStorageEncryptionStatus()", if (t is SecurityException) "blocked" else "error", "unavailable", t)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val um = getSystemService(Context.USER_SERVICE) as UserManager
                attempt("settings.isUserUnlocked", "boolean", "UserManager.isUserUnlocked()") { um.isUserUnlocked }
            } catch (t: Throwable) {
                mark("settings.isUserUnlocked", null, "boolean", "UserManager.isUserUnlocked()", "error", "unavailable", t)
            }
        }
        attempt("settings.lowPowerModeGlobal", "integer raw setting", "Settings.Global.LOW_POWER_MODE") {
            Settings.Global.getInt(contentResolver, Settings.Global.LOW_POWER_MODE, -1)
        }
    }

    private fun updateHistoryOnOpen() {
        worker.execute {
            val exits = historicalExitReasons()
            val bg = backgroundSummary()
            val s = "פתיחה: exit reasons=${exits.optJSONArray("records")?.length() ?: 0}; " +
                "דגימות רקע=${bg.optInt("sampleCount")}; פערים=${bg.optInt("gapCount")}. " +
                "הפרטים ייכללו בדוח JSON."
            runOnUiThread { if (!isRunning) statusView.text = s }
        }
    }

    private fun historicalExitReasons(): JSONObject {
        val result = JSONObject()
        try {
            if (Build.VERSION.SDK_INT < 30) {
                result.put("status", "unsupported")
                result.put("records", JSONArray())
                return result
            }
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val records = am.getHistoricalProcessExitReasons(packageName, 0, 20)
            val arr = JSONArray()
            records.forEach { info ->
                arr.put(JSONObject().apply {
                    put("reasonRaw", info.reason)
                    put("timestampMs", info.timestamp)
                    put("processName", info.processName ?: JSONObject.NULL)
                    put("description", info.description ?: JSONObject.NULL)
                    put("status", info.status)
                    put("importance", info.importance)
                })
            }
            result.put("status", if (arr.length() == 0) "empty" else "available")
            result.put("source", "ActivityManager.getHistoricalProcessExitReasons(packageName, 0, 20)")
            result.put("records", arr)
        } catch (t: Throwable) {
            result.put("status", if (t is SecurityException) "blocked" else "error")
            result.put("rawError", "${t.javaClass.name}: ${t.message ?: ""}")
            result.put("records", JSONArray())
        }
        return result
    }

    private fun backgroundSummary(): JSONObject {
        val records = JSONArray()
        val samples = mutableListOf<JSONObject>()
        val file = File(filesDir, ProbeMonitorService.LOG_FILE)
        if (file.exists()) {
            try {
                file.forEachLine(StandardCharsets.UTF_8) { line ->
                    if (line.isNotBlank()) try {
                        val entry = JSONObject(line)
                        records.put(entry)
                        if (entry.optString("event") == "sample" && entry.optLong("timestampMs", 0L) > 0L) samples.add(entry)
                    } catch (_: Throwable) { }
                }
            } catch (_: Throwable) { }
        }
        samples.sortBy { it.optLong("timestampMs", 0L) }
        val gaps = JSONArray()
        val byMode = JSONObject()
        val modeGroups = samples.groupBy { it.optString("mode", "unknown") }
        modeGroups.forEach { (mode, group) ->
            val sorted = group.sortedBy { it.optLong("timestampMs", 0L) }
            val modeGaps = JSONArray()
            for (i in 1 until sorted.size) {
                val a = sorted[i - 1].optLong("timestampMs", 0L)
                val b = sorted[i].optLong("timestampMs", 0L)
                val diff = b - a
                if (diff > 45_000L) modeGaps.put(JSONObject().put("mode", mode).put("fromTimestampMs", a).put("toTimestampMs", b).put("gapMs", diff))
            }
            byMode.put(mode, JSONObject().put("sampleCount", sorted.size).put("gaps", modeGaps))
            for (i in 0 until modeGaps.length()) gaps.put(modeGaps.getJSONObject(i))
        }
        return JSONObject().apply {
            put("logPath", file.absolutePath)
            put("records", records)
            put("sampleCount", samples.size)
            put("gapCount", gaps.length())
            put("gaps", gaps)
            put("byMode", byMode)
            put("expectedIntervalMs", ProbeMonitorService.INTERVAL_MS)
            put("gapThresholdMs", 45_000L)
        }
    }

    private fun exportReport() {
        val snapshotChecks: JSONArray
        val snapshotRun: JSONObject
        synchronized(jsonLock) {
            snapshotChecks = latestChecks
            snapshotRun = JSONObject(latestRunMeta.toString())
        }
        val report = JSONObject().apply {
            put("app", "Device Lab Probe")
            put("version", "0.1.0")
            put("createdAt", now())
            put("targetSdk", 33)
            put("minSdk", 33)
            put("internetPermissionDeclared", false)
            put("allowedDeclaredPermissions", JSONArray(listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.RECEIVE_BOOT_COMPLETED)))
            put("run", snapshotRun)
            put("checks", snapshotChecks)
            put("historicalProcessExitReasons", historicalExitReasons())
            put("backgroundMonitoring", backgroundSummary())
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "device-lab-probe-${System.currentTimeMillis()}.json")
        }
        pendingReport = report.toString(2)
        try {
            startActivityForResult(intent, EXPORT_REQUEST)
        } catch (t: Throwable) {
            statusView.text = "לא ניתן לפתוח בורר שמירה: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private var pendingReport: String = ""

    @Deprecated("Deprecated by Android but retained for API 33 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXPORT_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter(StandardCharsets.UTF_8)?.use { it.write(pendingReport) }
                statusView.text = "דוח JSON נשמר. מספר בדיקות: ${latestChecks.length()}"
            } catch (t: Throwable) {
                statusView.text = "שגיאה בשמירת הדוח: ${t.javaClass.name}: ${t.message}"
            }
        }
    }

    private fun startBackgroundMonitor(wakeLock: Boolean) {
        val prefs = getSharedPreferences(ProbeMonitorService.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(ProbeMonitorService.PREF_ACTIVE, true).putBoolean(ProbeMonitorService.PREF_WAKE_LOCK, wakeLock).apply()
        val intent = Intent(this, ProbeMonitorService::class.java).apply {
            action = ProbeMonitorService.ACTION_START
            putExtra(ProbeMonitorService.EXTRA_WAKE_LOCK, wakeLock)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            statusView.text = "בקשת התחלה נשלחה. מצב=${if (wakeLock) "Wake Lock קצר לכל דגימה" else "ללא Wake Lock"}."
        } catch (t: Throwable) {
            prefs.edit().putBoolean(ProbeMonitorService.PREF_ACTIVE, false).apply()
            statusView.text = "התחלת השירות נכשלה: ${t.javaClass.name}: ${t.message}"
        }
    }

    private fun stopBackgroundMonitor() {
        getSharedPreferences(ProbeMonitorService.PREFS, Context.MODE_PRIVATE).edit().putBoolean(ProbeMonitorService.PREF_ACTIVE, false).apply()
        try {
            startService(Intent(this, ProbeMonitorService::class.java).setAction(ProbeMonitorService.ACTION_STOP))
            statusView.text = "נשלחה בקשת עצירה. פתח/ייצא דוח כדי לבדוק את פערי הדגימות."
        } catch (t: Throwable) {
            stopService(Intent(this, ProbeMonitorService::class.java))
            statusView.text = "נשלחה עצירה מקומית; ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun runSection(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            add("section.$name", t.javaClass.name, "exception", "section wrapper", "error", "unavailable", t)
        }
    }

    private fun resultSummary(arr: JSONArray): String {
        var available = 0
        var partial = 0
        var blocked = 0
        var failed = 0
        for (i in 0 until arr.length()) {
            val status = arr.optJSONObject(i)?.optString("status") ?: continue
            when (status) {
                "available" -> available++
                "partial" -> partial++
                "blocked" -> blocked++
                "error", "unsupported", "empty" -> failed++
            }
        }
        return "${arr.length()} רשומות: available=$available, partial=$partial, blocked=$blocked, empty/unsupported/error=$failed"
    }

    private fun hasNoMeasurement(value: Any?): Boolean {
        if (value == null || value === JSONObject.NULL) return true
        return when (value) {
            is Byte -> value.toInt() == 0 || value == Byte.MIN_VALUE
            is Short -> value.toInt() == 0 || value == Short.MIN_VALUE
            is Int -> value == 0 || value == Int.MIN_VALUE
            is Long -> value == 0L || value == Long.MIN_VALUE
            is Float -> value == 0f || value == Float.MIN_VALUE || value == -Float.MAX_VALUE || value.isNaN() || value.isInfinite()
            is Double -> value == 0.0 || value == Double.MIN_VALUE || value == -Double.MAX_VALUE || value.isNaN() || value.isInfinite()
            is String -> value.isBlank() || value.trim() == "0" || value.trim() == "Long.MIN_VALUE" || value.trim() == "Int.MIN_VALUE" || value.trim() == "-2147483648" || value.trim().equals("unknown", true)
            is Collection<*> -> value.isEmpty()
            is Map<*, *> -> value.isEmpty()
            is JSONArray -> value.length() == 0
            is JSONObject -> value.length() == 0
            else -> false
        }
    }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        JSONObject.NULL -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Number, is Boolean -> value
        is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k?.toString() ?: "null", jsonValue(v)) } }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        is IntArray -> JSONArray().apply { value.forEach { put(it) } }
        is LongArray -> JSONArray().apply { value.forEach { put(it) } }
        is FloatArray -> JSONArray().apply { value.forEach { put(it.toDouble()) } }
        is DoubleArray -> JSONArray().apply { value.forEach { put(it) } }
        is BooleanArray -> JSONArray().apply { value.forEach { put(it) } }
        else -> value.toString()
    }

    private fun now(): String = Instant.now().toString()

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object { private const val EXPORT_REQUEST = 4401 }
}
