package il.devicelab

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class MainActivity : Activity() {
    private val ink = Color.rgb(23, 32, 51)
    private val muted = Color.rgb(105, 117, 139)
    private val blue = Color.rgb(49, 92, 246)
    private val canvas = Color.rgb(245, 247, 252)
    private val green = Color.rgb(19, 150, 112)
    private val orange = Color.rgb(224, 139, 35)
    private lateinit var content: LinearLayout
    private val df = DecimalFormat("#,##0.0")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = canvas
        window.navigationBarColor = canvas
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        render()
    }

    private fun render() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(canvas)
            isFillViewport = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(content)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val mark = TextView(this).apply {
            text = "D"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(blue, 18)
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }
        header.addView(mark)
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(13), 0, 0, 0)
        }
        titleColumn.addView(text("Device Lab", 24f, ink, true))
        titleColumn.addView(text("מרכז הבקרה של המכשיר שלך", 13f, muted))
        header.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(header)
        spacer(22)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(ink, 24)
        }
        hero.addView(text("סקירה כללית", 14f, Color.rgb(191, 202, 226), true))
        spacerIn(hero, 8)
        hero.addView(text(deviceName(), 23f, Color.WHITE, true))
        spacerIn(hero, 7)
        hero.addView(text("Android ${Build.VERSION.RELEASE}  ·  API ${Build.VERSION.SDK_INT}", 14f, Color.rgb(206, 215, 235)))
        spacerIn(hero, 17)
        val status = TextView(this).apply {
            text = "●  הבדיקה המקומית מוכנה"
            textSize = 12f
            setTextColor(Color.rgb(177, 246, 220))
            background = rounded(Color.rgb(40, 62, 83), 30)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        hero.addView(status)
        content.addView(hero, matchWrap())
        spacer(22)

        section("במבט מהיר")
        val mem = memoryInfo()
        val storage = storageInfo()
        val battery = batteryInfo()
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        row1.addView(metricCard("זיכרון RAM", mem.first, mem.second, blue), weighted())
        row1.addView(spaceView(10))
        row1.addView(metricCard("אחסון פנוי", storage.first, storage.second, green), weighted())
        content.addView(row1, matchWrap())
        spacer(10)
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        row2.addView(metricCard("סוללה", battery.first, battery.second, orange), weighted())
        row2.addView(spaceView(10))
        row2.addView(metricCard("מערכת", "Android ${Build.VERSION.RELEASE}", "גרסת API ${Build.VERSION.SDK_INT}", blue), weighted())
        content.addView(row2, matchWrap())
        spacer(22)

        section("פרטי המכשיר")
        val details = card()
        addDetail(details, "יצרן", Build.MANUFACTURER)
        addDetail(details, "דגם", Build.MODEL)
        addDetail(details, "שם המכשיר", Build.DEVICE)
        addDetail(details, "לוח / חומרה", "${Build.BOARD} / ${Build.HARDWARE}")
        addDetail(details, "ארכיטקטורה", Build.SUPPORTED_ABIS.joinToString(", "))
        addDetail(details, "גרסת מערכת", Build.DISPLAY)
        content.addView(details, matchWrap())
        spacer(22)

        section("בדיקות ומידע נוסף")
        val checks = card()
        addDetail(checks, "זיכרון בשימוש", mem.third)
        addDetail(checks, "אחסון בשימוש", storage.third)
        addDetail(checks, "טמפרטורת סוללה", battery.third)
        addDetail(checks, "זמן פעילות המכשיר", uptime())
        addDetail(checks, "צריכת CPU של האפליקציה", processCpuNote())
        content.addView(checks, matchWrap())
        spacer(10)
        val note = TextView(this).apply {
            text = "חשוב לדעת: Android מגביל גישה לחלק מנתוני החומרה. בריאות סוללה, מחזורי טעינה ושימוש CPU כולל אינם זמינים תמיד לאפליקציה רגילה; לא נציג הערכות כאילו היו מדידות רשמיות."
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.RIGHT
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        content.addView(note, matchWrap())
        spacer(12)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val refresh = Button(this).apply {
            text = "רענון נתונים"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(blue, 15)
            setOnClickListener { render() }
        }
        actions.addView(refresh, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(spaceView(10))
        val export = Button(this).apply {
            text = "ייצוא דוח JSON"
            isAllCaps = false
            setTextColor(blue)
            background = rounded(Color.rgb(229, 235, 255), 15)
            setOnClickListener { exportReport() }
        }
        actions.addView(export, LinearLayout.LayoutParams(0, dp(52), 1f))
        content.addView(actions, matchWrap())
        spacer(16)
        content.addView(text("Device Lab  •  גרסה 1.0.0  •  הנתונים נשארים במכשיר", 11f, muted).apply {
            gravity = Gravity.CENTER
        })
    }

    private fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "המכשיר שלי" }

    private fun memoryInfo(): Triple<String, String, String> {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val total = info.totalMem.toDouble() / 1024.0 / 1024.0
        val available = info.availMem.toDouble() / 1024.0 / 1024.0
        val used = (total - available).coerceAtLeast(0.0)
        val percent = if (total > 0) (used / total * 100).toInt() else 0
        return Triple("${percent}%", "פנוי ${df.format(available / 1024)} GB", "${df.format(used / 1024)} GB בשימוש מתוך ${df.format(total / 1024)} GB")
    }

    private fun storageInfo(): Triple<String, String, String> {
        return try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val total = stat.totalBytes.toDouble()
            val free = stat.availableBytes.toDouble()
            val used = (total - free).coerceAtLeast(0.0)
            val percent = if (total > 0) (used / total * 100).toInt() else 0
            Triple("${df.format(free / 1e9)} GB", "${percent}% בשימוש", "בשימוש ${df.format(used / 1e9)} GB מתוך ${df.format(total / 1e9)} GB")
        } catch (_: Exception) {
            Triple("לא זמין", "לא ניתן לקרוא", "מידע האחסון אינו זמין")
        }
    }

    private fun batteryInfo(): Triple<String, String, String> {
        val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "בטעינה"
            BatteryManager.BATTERY_STATUS_FULL -> "טעונה במלואה"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "לא בטעינה"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "לא נטענת כרגע"
            else -> "מצב לא ידוע"
        }
        val tempText = if (temp >= 0) "${df.format(temp / 10.0)}°C" else "לא זמין"
        return Triple(if (pct >= 0) "${pct}%" else "לא זמין", statusText, tempText)
    }

    private fun processCpuNote(): String {
        val cpuMs = android.os.Process.getElapsedCpuTime()
        return "זמן CPU מצטבר לתהליך: ${cpuMs} ms; אין זו מדידת CPU של כל המכשיר"
    }

    private fun uptime(): String {
        val total = SystemClock.elapsedRealtime() / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        return "${hours} שעות ו-${minutes} דקות"
    }

    private fun exportReport() {
        val mem = memoryInfo()
        val storage = storageInfo()
        val battery = batteryInfo()
        val report = JSONObject().apply {
            put("app", "Device Lab")
            put("version", "1.0.0")
            put("createdAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("board", Build.BOARD)
                put("hardware", Build.HARDWARE)
                put("supportedAbis", Build.SUPPORTED_ABIS.joinToString(", "))
                put("androidVersion", Build.VERSION.RELEASE)
                put("apiLevel", Build.VERSION.SDK_INT)
                put("buildDisplay", Build.DISPLAY)
            })
            put("memory", JSONObject().apply {
                put("summary", mem.first)
                put("detail", mem.third)
            })
            put("storage", JSONObject().apply {
                put("free", storage.first)
                put("usage", storage.second)
                put("detail", storage.third)
            })
            put("battery", JSONObject().apply {
                put("level", battery.first)
                put("status", battery.second)
                put("temperature", battery.third)
            })
            put("cpu", JSONObject().apply {
                put("processElapsedCpuTimeMs", android.os.Process.getElapsedCpuTime())
                put("deviceWideCpuUsage", JSONObject.NULL)
                put("note", "Device-wide CPU usage is not reported because it is not reliably accessible to a regular app.")
            })
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "device-lab-report.json")
        }
        pendingReport = report.toString(2)
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    private var pendingReport: String = ""

    @Deprecated("Deprecated in Android SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXPORT_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingReport) }
                android.widget.Toast.makeText(this, "הדוח נשמר בהצלחה", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "לא ניתן לשמור את הדוח: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun section(label: String) {
        content.addView(text(label, 17f, ink, true))
        spacer(11)
    }

    private fun metricCard(label: String, value: String, detail: String, accent: Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(15), dp(16), dp(15), dp(16))
            background = rounded(Color.WHITE, 20)
        }
        val dot = View(this).apply {
            background = rounded(accent, 30)
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9))
        }
        box.addView(dot)
        spacerIn(box, 12)
        box.addView(text(label, 12f, muted, true))
        spacerIn(box, 5)
        box.addView(text(value, 21f, ink, true))
        spacerIn(box, 5)
        box.addView(text(detail, 11f, muted))
        return box
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(16), dp(5), dp(16), dp(5))
        background = rounded(Color.WHITE, 20)
    }

    private fun addDetail(parent: LinearLayout, label: String, value: String) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, dp(11), 0, dp(11))
        }
        line.addView(text(label, 12f, muted))
        spacerIn(line, 4)
        line.addView(text(value.ifBlank { "לא זמין" }, 14f, ink, true))
        parent.addView(line, matchWrap())
        val divider = View(this).apply { setBackgroundColor(Color.rgb(237, 240, 247)) }
        parent.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f)
    private fun spaceView(widthDp: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1) }
    private fun spacer(heightDp: Int) { content.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp))) }
    private fun spacerIn(parent: LinearLayout, heightDp: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp))) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXPORT_REQUEST = 2410
    }
}
