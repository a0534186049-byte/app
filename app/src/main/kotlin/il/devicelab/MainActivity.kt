package il.devicelab

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** Device Lab 2.0: Hebrew-first diagnostics. No Internet permission or root required. */
class MainActivity : Activity() {
    private enum class Page { HOME, PERFORMANCE, BATTERY, HARDWARE, SETTINGS }
    private enum class ThemeMode(val label: String) { SYSTEM("לפי המכשיר"), LIGHT("בהיר"), DARK("כהה") }
    private data class Palette(val bg:Int,val surface:Int,val raised:Int,val text:Int,val muted:Int,val line:Int,val accent:Int,val accentSoft:Int,val good:Int,val warn:Int,val heroA:Int,val heroB:Int)
    private data class Memory(val total:Long,val available:Long,val threshold:Long,val low:Boolean) {
        val used get() = (total-available).coerceAtLeast(0L)
        val percent get() = if (total>0) (used*100.0/total).toInt().coerceIn(0,100) else 0
    }
    private data class Disk(val path:String,val total:Long?,val available:Long?,val free:Long?) {
        val usedPercent:Int? get() = if (total!=null && available!=null && total>0) (((total-available).coerceAtLeast(0)*100.0)/total).toInt().coerceIn(0,100) else null
    }
    private data class VolumeInfo(val description:String,val path:String?,val state:String,val isPrimary:Boolean,val isRemovable:Boolean,val disk:Disk?)
    private data class Battery(val level:Int?,val status:Int,val plugged:Int,val tempRaw:Int?,val voltageRaw:Int?,val healthRaw:Int?,val tech:String?,val isCharging:Boolean?,val currentNow:Long?,val currentAvg:Long?,val chargeCounter:Long?,val energyCounter:Long?,val extras:Map<String,String>) {
        val statusText get() = when(status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "בטעינה"
            BatteryManager.BATTERY_STATUS_FULL -> "טעונה במלואה"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "בפריקה"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "לא נטענת כרגע"
            else -> "מצב לא ידוע"
        }
        val pluggedText get() = when(plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "חשמל (AC)"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "טעינה אלחוטית"
            0 -> "מנותק"
            else -> "ערך גולמי: " + plugged
        }
        val tempC:Double? get() = tempRaw?.takeIf { it>=0 }?.div(10.0)
        val voltageV:Double? get() = voltageRaw?.takeIf { it>=0 }?.div(1000.0)
    }
    private data class Thermal(val name:String,val type:String,val raw:String,val celsius:Double?,val invalid:Boolean)
    private data class Cpu(val percent:Double?=null,val cores:Map<String,Double> = emptyMap(),val load:String?=null,val cpuInfo:String?=null,val stat1:String?=null,val stat2:String?=null,val sampledAt:String?=null,val intervalMs:Long?=null,val frequencies:List<Pair<String,String>> = emptyList(),val thermals:List<Thermal> = emptyList(),val coreCount:Int=0,val error:String?=null)
    private data class Sysfs(val name:String,val path:String,val raw:String?,val status:String)

    private val fmt = DecimalFormat("#,##0.0")
    private val worker = Executors.newSingleThreadExecutor()
    private var page = Page.HOME
    private var themeMode = ThemeMode.SYSTEM
    private lateinit var colors: Palette
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var cpu = Cpu()
    private var cpuLoading = false
    private var pendingJson = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeMode = runCatching { ThemeMode.valueOf(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_THEME, "SYSTEM") ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
        render()
        refreshCpu()
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun palette(): Palette {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when(themeMode) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
        return if(dark) Palette(Color.rgb(14,19,30),Color.rgb(25,33,48),Color.rgb(32,42,60),Color.rgb(238,243,251),Color.rgb(157,170,191),Color.rgb(47,59,79),Color.rgb(145,170,255),Color.rgb(39,54,83),Color.rgb(91,215,170),Color.rgb(245,190,104),Color.rgb(26,39,63),Color.rgb(39,61,99))
        else Palette(Color.rgb(245,247,251),Color.WHITE,Color.rgb(239,243,250),Color.rgb(27,38,59),Color.rgb(105,119,142),Color.rgb(228,233,242),Color.rgb(49,87,236),Color.rgb(231,237,255),Color.rgb(20,139,99),Color.rgb(181,111,23),Color.rgb(26,38,63),Color.rgb(53,78,129))
    }

    private fun render() {
        colors = palette()
        window.statusBarColor = colors.bg
        window.navigationBarColor = colors.bg
        val night = themeMode == ThemeMode.DARK || (themeMode == ThemeMode.SYSTEM && (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        window.decorView.systemUiVisibility = if(night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutDirection=View.LAYOUT_DIRECTION_RTL; setBackgroundColor(colors.bg) }
        val scroll = ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false; setBackgroundColor(colors.bg) }
        content = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutDirection=View.LAYOUT_DIRECTION_RTL; setPadding(dp(18),dp(14),dp(18),dp(24)) }
        scroll.addView(content, -1, -2)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        header()
        when(page) { Page.HOME -> homePage(); Page.PERFORMANCE -> performancePage(); Page.BATTERY -> batteryPage(); Page.HARDWARE -> hardwarePage(); Page.SETTINGS -> settingsPage() }
        root.addView(navBar(),LinearLayout.LayoutParams(-1,-2))
        setContentView(root)
    }

    private fun titleForPage() = when(page) {
        Page.HOME -> "מרכז הבקרה של המכשיר"; Page.PERFORMANCE -> "ביצועים ומעבד"
        Page.BATTERY -> "סוללה וטעינה"; Page.HARDWARE -> "מערכת ורכיבים"; Page.SETTINGS -> "הגדרות ואבחון"
    }
    private fun header() {
        val h=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; layoutDirection=View.LAYOUT_DIRECTION_RTL; gravity=Gravity.CENTER_VERTICAL }
        h.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); scaleType=ImageView.ScaleType.FIT_CENTER; background=rounded(colors.surface,17); setPadding(dp(2),dp(2),dp(2),dp(2)); contentDescription="Device Lab logo" },LinearLayout.LayoutParams(dp(50),dp(50)))
        val title=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutDirection=View.LAYOUT_DIRECTION_RTL; setPadding(dp(12),0,0,0) }
        title.addView(txt("Device Lab",22f,colors.text,true)); title.addView(txt(titleForPage(),12f,colors.muted))
        h.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        h.addView(TextView(this).apply { text="↻ רענון"; textSize=12f; setTextColor(colors.accent); gravity=Gravity.CENTER; setPadding(dp(10),dp(10),dp(10),dp(10)); background=rounded(colors.accentSoft,13); setOnClickListener { refreshAll() } })
        content.addView(h,match()); gap(19)
    }

    private fun homePage() {
        val m=memory(); val d=disk(Environment.getDataDirectory()); val b=battery(); val removable=removableVolumes()
        val hero=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutDirection=View.LAYOUT_DIRECTION_RTL; setPadding(dp(20),dp(21),dp(20),dp(21)); background=gradient(colors.heroA,colors.heroB,24) }
        hero.addView(txt("סקירה כללית",13f,Color.rgb(205,218,243),true)); gapIn(hero,8)
        hero.addView(txt(deviceName(),23f,Color.WHITE,true)); gapIn(hero,6)
        hero.addView(txt("Android "+Build.VERSION.RELEASE+" · API "+Build.VERSION.SDK_INT,13f,Color.rgb(217,226,244))); gapIn(hero,14)
        hero.addView(txt("●  "+b.statusText,12f,Color.WHITE,true).apply { background=rounded(Color.argb(55,255,255,255),24); setPadding(dp(12),dp(8),dp(12),dp(8)) },LinearLayout.LayoutParams(-2,-2))
        content.addView(hero,match()); gap(20)
        section("במבט מהיר","מדדים שהמערכת מדווחת כרגע")
        val r1=row()
        r1.addView(metric("סוללה",b.level?.let { "$it%" } ?: "לא זמין",b.tempC?.let { "טמפרטורה "+fmt.format(it)+"°C" } ?: b.statusText,colors.warn,b.level),weight())
        r1.addView(space(9))
        r1.addView(metric("זיכרון RAM",m.percent.toString()+"%",gb(m.used)+" GB בשימוש בקירוב",colors.accent,m.percent),weight())
        content.addView(r1,match()); gap(9)
        val r2=row()
        r2.addView(metric("מעבד",cpu.percent?.let { fmt.format(it)+"%" } ?: if(cpuLoading) "דוגם…" else "לא זמין",if(cpu.sampledAt!=null) "אומדן מדגימות /proc/stat" else "לחץ רענון למדידה",colors.good,cpu.percent?.toInt()),weight())
        r2.addView(space(9))
        r2.addView(metric("אחסון פנוי",d.available?.let { gb(it)+" GB" } ?: "לא זמין",d.usedPercent?.let { "$it% בשימוש" } ?: "לא דווח",colors.accent,d.usedPercent),weight())
        content.addView(r2,match()); gap(10)
        val storageCard=card("כרטיס זיכרון נשלף","זיהוי דרך StorageManager — בנפרד מהאחסון המדומה של המשתמש")
        if(removable.isEmpty()) storageCard.addView(txt("לא זוהה כרגע כרטיס נשלף דרך StorageManager.",12f,colors.muted))
        removable.forEachIndexed { index, volume ->
            addDetail(storageCard,"כרטיס",volume.description)
            addDetail(storageCard,"מצב",volume.state)
            addDetail(storageCard,"נתיב",volume.path ?: "לא נחשף")
            val sd=volume.disk
            addDetail(storageCard,"נפח כולל",sd?.total?.let { gb(it)+" GB" } ?: "לא זמין")
            addDetail(storageCard,"מקום פנוי",sd?.available?.let { gb(it)+" GB" } ?: "לא זמין")
            addDetail(storageCard,"זיהוי", "נשלף=${volume.isRemovable} · ראשי=${volume.isPrimary}")
            if(index<removable.lastIndex) gapIn(storageCard,5)
        }
        content.addView(storageCard,match()); gap(20)
        section("מעבר מהיר","בחר תחום כדי לפתוח מסך ייעודי")
        val r3=row(); r3.addView(shortcut("ביצועים","מעבד, תדרים וטמפרטורות","↗") { open(Page.PERFORMANCE) },weight()); r3.addView(space(9)); r3.addView(shortcut("סוללה","זרם, טעינה וקובצי מערכת","ϟ") { open(Page.BATTERY) },weight()); content.addView(r3,match()); gap(9)
        val r4=row(); r4.addView(shortcut("רכיבי המכשיר","מסך, חיישנים וקודקים","◈") { open(Page.HARDWARE) },weight()); r4.addView(space(9)); r4.addView(shortcut("הגדרות ואבחון","ערכת נושא וייצוא JSON","⚙") { open(Page.SETTINGS) },weight()); content.addView(r4,match()); gap(15)
        noteCard("דיוק ושקיפות","ערכים מחושבים מסומנים כאומדן. נתון שחסום או לא זמין לא יוצג כאילו נמדד. אין לאפליקציה הרשאת אינטרנט או דרישת Root.")
        gap(14); content.addView(txt("Device Lab 2.0.1 · האבחון נשאר מקומי",11f,colors.muted).apply { gravity=Gravity.CENTER })
    }

    private fun performancePage() {
        section("שימוש במעבד","אומדן מהפרש בין שתי קריאות /proc/stat בהפרש של כשנייה")
        val top=row()
        top.addView(metric("שימוש כולל",cpu.percent?.let { fmt.format(it)+"%" } ?: if(cpuLoading) "דוגם…" else "לא זמין",cpu.intervalMs?.let { "חלון של "+it+" ms" } ?: "אומדן",colors.good,cpu.percent?.toInt()),weight())
        top.addView(space(9)); top.addView(metric("ליבות מזוהות",cpu.coreCount.toString(),"מתוך /proc/stat",colors.accent,null),weight()); content.addView(top,match()); gap(9)
        if(cpu.cores.isNotEmpty()) { val c=card("שימוש לפי ליבה","אומדן לכל ליבה בחלון הדגימה"); cpu.cores.forEach { (k,v) -> progressRow(c,k.uppercase(Locale.US),fmt.format(v)+"%",v.toInt()) }; content.addView(c,match()); gap(9) }
        val freq=card("תדרי מעבד","קריאות sysfs גולמיות — בדרך כלל kHz")
        if(cpu.frequencies.isEmpty()) freq.addView(txt("לא נמצאו קובצי תדר קריאים.",12f,colors.muted))
        cpu.frequencies.take(16).forEach { addDetail(freq,it.first,it.second) }
        if(cpu.frequencies.size>16) addDetail(freq,"רשומות נוספות",(cpu.frequencies.size-16).toString())
        content.addView(freq,match()); gap(9)
        val therm=card("טמפרטורות מערכת","ערכים שליליים נשמרים כנתון גולמי אך מסומנים כלא תקפים")
        if(cpu.thermals.isEmpty()) therm.addView(txt("לא נמצאו אזורי טמפרטורה קריאים.",12f,colors.muted))
        cpu.thermals.forEach { t ->
            val v=if(t.invalid) "לא תקף · "+t.raw else if(t.celsius!=null) fmt.format(t.celsius)+"°C · גולמי "+t.raw else t.raw
            addDetail(therm,t.type.ifBlank { t.name },v,if(t.celsius!=null) "המרה מוערכת לפי יחידות m°C נפוצות ב־thermal_zone" else null)
        }
        content.addView(therm,match()); gap(9)
        val load=card("עומס מערכת","מקורות גולמיים — לא אחוז שימוש כשלעצמם")
        addDetail(load,"Load average",cpu.load?.trim()?.ifBlank { "ריק" } ?: "לא זמין")
        addDetail(load,"זמן CPU של האפליקציה",Process.getElapsedCpuTime().toString()+" ms","זמן מצטבר של Device Lab, לא המעבד כולו")
        addDetail(load,"זמן דגימה",cpu.sampledAt ?: if(cpuLoading) "דוגם…" else "טרם נדגם")
        cpu.error?.let { addDetail(load,"הערה",it) }
        content.addView(load,match()); gap(10)
        content.addView(button("דגום מעבד מחדש",true) { refreshCpu() },match()); gap(12)
        noteCard("מה משמעות המספר?","זהו אומדן מחושב מהפרשי מוני CPU, לא מדידה של יצרן השבב. הבדיקה עצמה עשויה להוסיף עומס.")
    }

    private fun batteryPage() {
        val b=battery(); val m=memory()
        val saver=runCatching { (getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }.getOrDefault(false)
        val main=card("מצב הסוללה",b.statusText)
        main.addView(txt(b.level?.let { "$it%" } ?: "לא זמין",36f,colors.text,true)); gapIn(main,8)
        main.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=b.level ?: 0; progressTintList=android.content.res.ColorStateList.valueOf(colors.good); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(colors.line) },LinearLayout.LayoutParams(-1,dp(8)))
        addDetail(main,"מקור חשמל",b.pluggedText); addDetail(main,"טמפרטורת סוללה",b.tempC?.let { fmt.format(it)+"°C" } ?: "לא דווח")
        addDetail(main,"מתח סוללה",b.voltageV?.let { fmt.format(it)+" V" } ?: "לא דווח"); addDetail(main,"טכנולוגיה",b.tech ?: "לא דווח")
        addDetail(main,"בריאות לפי Android",b.healthRaw?.toString() ?: "לא דווח")
        content.addView(main,match()); gap(9)
        val api=card("מדדי BatteryManager","ערכים כפי שה־API דיווח, ללא המרה")
        addDetail(api,"CURRENT_NOW",b.currentNow?.let { "$it µA" } ?: "לא דווח")
        addDetail(api,"CURRENT_AVERAGE",b.currentAvg?.let { "$it µA" } ?: "לא דווח")
        addDetail(api,"CHARGE_COUNTER",b.chargeCounter?.let { "$it µAh" } ?: "לא דווח")
        addDetail(api,"ENERGY_COUNTER",b.energyCounter?.let { "$it nWh" } ?: "לא דווח")
        addDetail(api,"חיסכון בסוללה",if(saver) "פעיל" else "כבוי"); addDetail(api,"BatteryManager.isCharging()",b.isCharging?.toString() ?: "לא זמין")
        if(b.status==BatteryManager.BATTERY_STATUS_CHARGING && b.isCharging==false) api.addView(txt("אי־התאמה: status מציין טעינה אך isCharging() החזיר false. כרגע עדיף להסתמך על status ו־plugged.",12f,colors.warn))
        content.addView(api,match()); gap(9)
        val sys=card("קובצי ספק כוח ישירים","ללא סריקה רקורסיבית של תיקיות פנימיות")
        val readings=supplyFiles()
        if(readings.isEmpty()) sys.addView(txt("לא נמצאו קבצים ישירים נגישים.",12f,colors.muted))
        readings.forEach { addDetail(sys,it.name,it.raw?.trim()?.ifBlank { "(ריק)" } ?: "לא זמין · "+it.status,it.path) }
        content.addView(sys,match()); gap(9)
        val ram=card("מצב הזיכרון","נתונים המדווחים בידי Android")
        addDetail(ram,"RAM זמין",gb(m.available)+" GB"); addDetail(ram,"סף low-memory",mb(m.threshold)+" MB"); addDetail(ram,"המערכת מסמנת lowMemory",if(m.low) "כן" else "לא")
        content.addView(ram,match()); gap(10)
        noteCard("הגבלת המידע","זרם ו־charge counter הם דיווחי מערכת. הם אינם מדידת בריאות סוללה, קיבולת מלאה מאומתת או מספר מחזורי טעינה.")
    }

    private fun hardwarePage() {
        val dm=DisplayMetrics()
        @Suppress("DEPRECATION")
        val display=(getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        runCatching { display.getRealMetrics(dm) }
        val sensors=runCatching { (getSystemService(Context.SENSOR_SERVICE) as SensorManager).getSensorList(Sensor.TYPE_ALL) }.getOrDefault(emptyList())
        val codecs=runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList() }.getOrDefault(emptyList())
        val video=codecs.flatMap { it.supportedTypes.toList() }.filter { it.startsWith("video/") }.distinct().sorted()
        val audio=codecs.flatMap { it.supportedTypes.toList() }.filter { it.startsWith("audio/") }.distinct().sorted()
        val cameras=runCatching { (getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.toList() }.getOrDefault(emptyList())
        val features=runCatching { packageManager.systemAvailableFeatures.mapNotNull { it.name }.distinct().sorted() }.getOrDefault(emptyList())

        val id=card("זהות המכשיר","ערכים כפי שדווחו במערכת")
        addDetail(id,"יצרן",Build.MANUFACTURER); addDetail(id,"דגם",Build.MODEL); addDetail(id,"DEVICE",Build.DEVICE)
        addDetail(id,"BOARD / HARDWARE",Build.BOARD+" / "+Build.HARDWARE)
        addDetail(id,"SoC מדווח",if(Build.VERSION.SDK_INT>=31) Build.SOC_MANUFACTURER+" "+Build.SOC_MODEL else "לא זמין בגרסה זו")
        addDetail(id,"ABI נתמכים",Build.SUPPORTED_ABIS.joinToString(", ")); addDetail(id,"תיקון אבטחה",Build.VERSION.SECURITY_PATCH)
        addDetail(id,"Kernel",System.getProperty("os.version") ?: "לא דווח"); addDetail(id,"Fingerprint",Build.FINGERPRINT)
        content.addView(id,match()); gap(9)
        val scr=card("מסך","מידע שנחשף דרך Display API")
        addDetail(scr,"רזולוציה",dm.widthPixels.toString()+" × "+dm.heightPixels); addDetail(scr,"צפיפות",dm.densityDpi.toString()+" dpi · density "+fmt.format(dm.density))
        addDetail(scr,"קצב רענון",fmt.format(display.refreshRate)+" Hz"); addDetail(scr,"סיבוב גולמי",display.rotation.toString())
        content.addView(scr,match()); gap(9)
        val sum=card("רכיבים","זיהוי אינו בדיקת תפקוד מלאה")
        addDetail(sum,"חיישנים",sensors.size.toString()); addDetail(sum,"קודקים רשומים",codecs.size.toString())
        addDetail(sum,"סוגי וידאו רשומים",video.size.toString()); addDetail(sum,"סוגי אודיו רשומים",audio.size.toString())
        addDetail(sum,"מזהי מצלמה",cameras.joinToString(", ").ifBlank { "לא דווח" }); addDetail(sum,"תכונות מערכת",features.size.toString())
        content.addView(sum,match()); gap(9)
        val sens=card("חיישנים שזוהו","רשימת SensorManager")
        if(sensors.isEmpty()) sens.addView(txt("לא זוהו חיישנים.",12f,colors.muted))
        sensors.forEach { addDetail(sens,it.name,"type="+it.type+" · "+it.vendor+" · wake-up="+it.isWakeUpSensor) }
        content.addView(sens,match()); gap(9)
        val codec=card("קודקי מדיה","סוגי מדיה שהמערכת מפרסמת")
        addDetail(codec,"וידאו",video.joinToString(", ").ifBlank { "לא רשומים" }); addDetail(codec,"אודיו",audio.joinToString(", ").ifBlank { "לא רשומים" })
        content.addView(codec,match()); gap(9)
        val feat=card("תכונות מערכת נוספות","לפי PackageManager")
        feat.addView(txt(features.take(30).joinToString("\n").ifBlank { "לא דווחו תכונות בעלות שם" },12f,colors.muted))
        if(features.size>30) addDetail(feat,"רשומות שלא הוצגו",(features.size-30).toString())
        content.addView(feat,match()); gap(10)
        noteCard("הבחנה חשובה","היעדר קודק וידאו מהרשימה לא מוכיח שנגן עם מפענח תוכנתי אינו מסוגל לנגן וידאו. גם זיהוי מצלמה אינו הוכחה שצילום בפועל עובד.")
    }

    private fun settingsPage() {
        section("מראה האפליקציה","הבחירה נשמרת לפעם הבאה")
        val a=card("ערכת נושא","בחר בהיר, כהה או התאמה להגדרת המכשיר")
        val modes=row()
        ThemeMode.values().forEachIndexed { i,m ->
            modes.addView(Button(this).apply {
                text=m.label; textSize=11.5f; isAllCaps=false; setTextColor(if(themeMode==m) colors.accent else colors.text)
                background=rounded(if(themeMode==m) colors.accentSoft else colors.raised,13)
                setOnClickListener { themeMode=m; getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(KEY_THEME,m.name).apply(); render() }
            },LinearLayout.LayoutParams(0,dp(46),1f))
            if(i<ThemeMode.values().size-1) modes.addView(space(6))
        }
        a.addView(modes); content.addView(a,match()); gap(17)
        section("דוחות ואבחון","שמירת המידע לקובץ מקומי")
        val ex=card("ייצוא דוח JSON","כולל נתונים גולמיים ואומדנים שנאספו")
        ex.addView(txt("פרטי מכשיר, RAM, אחסון, סוללה, קובצי power_supply ישירים, שימוש CPU מחושב, תדרים, טמפרטורות, חיישנים, קודקים ותכונות מערכת.",12f,colors.muted))
        gapIn(ex,11); ex.addView(button("ייצוא JSON",true) { exportReport() })
        content.addView(ex,match()); gap(9)
        val raw=card("פרטים טכניים","לניתוח תקלות")
        addDetail(raw,"Build fingerprint",Build.FINGERPRINT); addDetail(raw,"Build display",Build.DISPLAY)
        addDetail(raw,"SELinux enforce גולמי",safeRead("/sys/fs/selinux/enforce")?.trim() ?: "לא ניתן לקרוא")
        addDetail(raw,"Load average",cpu.load?.trim() ?: "לא זמין")
        addDetail(raw,"CPU usage",cpu.percent?.let { fmt.format(it)+"% · אומדן" } ?: "לא זמין")
        addDetail(raw,"חיסכון בסוללה",if(runCatching { (getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }.getOrDefault(false)) "פעיל" else "כבוי")
        addDetail(raw,"זמן פעילות",uptime()); addDetail(raw,"CPU time של האפליקציה",Process.getElapsedCpuTime().toString()+" ms")
        content.addView(raw,match()); gap(9)
        val about=card("אודות","Device Lab 2.0.1 — מהדורת בדיקה")
        addDetail(about,"Application ID",packageName); addDetail(about,"Android",Build.VERSION.RELEASE+" · API "+Build.VERSION.SDK_INT)
        addDetail(about,"גישה לאינטרנט","אין הרשאת INTERNET"); addDetail(about,"Root / הרשאות אחסון רחבות","לא נדרשים")
        content.addView(about,match()); gap(14)
    }

    private fun navBar(): View {
        val bar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; layoutDirection=View.LAYOUT_DIRECTION_RTL; setPadding(dp(7),dp(8),dp(7),dp(8)); setBackgroundColor(colors.surface); elevation=dp(8).toFloat() }
        val items=listOf(Triple(Page.HOME,"⌂","בית"),Triple(Page.PERFORMANCE,"⌁","ביצועים"),Triple(Page.BATTERY,"ϟ","סוללה"),Triple(Page.HARDWARE,"◈","מערכת"),Triple(Page.SETTINGS,"⚙","עוד"))
        items.forEachIndexed { i,v ->
            val sel=page==v.first
            bar.addView(TextView(this).apply {
                text=v.second+"\n"+v.third; textSize=10.5f; setTextColor(if(sel) colors.accent else colors.muted)
                typeface=if(sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; gravity=Gravity.CENTER; setPadding(dp(2),dp(6),dp(2),dp(6))
                background=rounded(if(sel) colors.accentSoft else Color.TRANSPARENT,14); setOnClickListener { open(v.first) }
            },LinearLayout.LayoutParams(0,dp(51),1f))
            if(i<items.size-1) bar.addView(View(this),LinearLayout.LayoutParams(dp(2),1))
        }
        return bar
    }

    private fun open(target:Page) { page=target; render(); if(target==Page.PERFORMANCE && cpu.sampledAt==null && !cpuLoading) refreshCpu() }
    private fun refreshAll() { render(); refreshCpu() }
    private fun refreshCpu() {
        if(cpuLoading) return
        cpuLoading=true; render()
        worker.execute {
            val result=try { collectCpu() } catch(t:Throwable) { Cpu(error=t.javaClass.simpleName+": "+(t.message ?: "שגיאה לא מפורטת")) }
            runOnUiThread { cpu=result; cpuLoading=false; render() }
        }
    }
    private fun collectCpu():Cpu {
        val start=SystemClock.elapsedRealtime()
        val first=safeRead("/proc/stat") ?: return Cpu(error="לא ניתן לקרוא /proc/stat")
        Thread.sleep(1000L)
        val end=SystemClock.elapsedRealtime()
        val second=safeRead("/proc/stat") ?: return Cpu(stat1=first,error="הקריאה השנייה של /proc/stat נכשלה")
        val a=parseStat(first); val b=parseStat(second); val cores=linkedMapOf<String,Double>(); var overall:Double?=null
        (a.keys intersect b.keys).sortedWith(compareBy<String>({if(it=="cpu") -1 else 0},{it.removePrefix("cpu").toIntOrNull() ?: -1})).forEach { name ->
            val old=a[name] ?: return@forEach; val now=b[name] ?: return@forEach
            val dt=now.sum()-old.sum()
            val di=(now.getOrElse(3){0L}+now.getOrElse(4){0L})-(old.getOrElse(3){0L}+old.getOrElse(4){0L})
            if(dt<=0 || di<0) return@forEach
            val pct=((dt-di).toDouble()/dt*100.0).coerceIn(0.0,100.0)
            if(name=="cpu") overall=pct else cores[name]=pct
        }
        return Cpu(overall,cores,safeRead("/proc/loadavg"),safeRead("/proc/cpuinfo"),first,second,
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US).format(Date()),end-start,cpuFrequencies(),thermalZones(),
            b.keys.count { it.matches(Regex("cpu[0-9]+")) },if(overall==null) "לא ניתן לחשב אחוז שימוש ממוני CPU" else null)
    }
    private fun parseStat(raw:String):Map<String,List<Long>> {
        val out=linkedMapOf<String,List<Long>>()
        raw.lineSequence().forEach { line ->
            val p=line.trim().split(Regex("\\s+")); val key=p.firstOrNull() ?: return@forEach
            if(key!="cpu" && !key.matches(Regex("cpu[0-9]+"))) return@forEach
            val v=p.drop(1).take(8).mapNotNull { it.toLongOrNull() }
            if(v.size>=4) out[key]=v
        }
        return out
    }
    private fun cpuFrequencies():List<Pair<String,String>> {
        val out=mutableListOf<Pair<String,String>>()
        val cpus=runCatching { File("/sys/devices/system/cpu").listFiles()?.filter { it.name.matches(Regex("cpu[0-9]+")) }?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: 999 } }.getOrNull().orEmpty()
        cpus.forEach { c ->
            val values=listOf("scaling_cur_freq","cpuinfo_min_freq","cpuinfo_max_freq").mapNotNull { f ->
                safeRead(c.path+"/cpufreq/"+f)?.trim()?.takeIf { it.isNotBlank() }?.let { f+"="+it }
            }
            if(values.isNotEmpty()) out.add(c.name to values.joinToString(" · "))
        }
        if(out.isEmpty()) runCatching { File("/sys/devices/system/cpu/cpufreq").listFiles()?.filter { it.name.startsWith("policy") } }.getOrNull().orEmpty().forEach { p ->
            val v=listOf("scaling_cur_freq","cpuinfo_min_freq","cpuinfo_max_freq").mapNotNull { f -> safeRead(p.path+"/"+f)?.trim()?.takeIf { it.isNotBlank() }?.let { f+"="+it } }
            if(v.isNotEmpty()) out.add(p.name to v.joinToString(" · "))
        }
        return out
    }
    private fun thermalZones():List<Thermal> {
        val zones=runCatching { File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }?.sortedBy { it.name } }.getOrNull().orEmpty()
        return zones.mapNotNull { z ->
            val raw=safeRead(z.path+"/temp")?.trim() ?: return@mapNotNull null
            val n=raw.toLongOrNull(); val invalid=n != null && n < 0L
            val c=if(!invalid && n!=null && n in 1000L..200000L) n/1000.0 else null
            Thermal(z.name,safeRead(z.path+"/type")?.trim().orEmpty(),raw,c,invalid)
        }
    }
    private fun supplyFiles():List<Sysfs> {
        val list=mutableListOf<Sysfs>()
        val names=setOf("status","present","capacity","cycle_count","charge_full","charge_full_design","current_now","current_avg","voltage_now","voltage_max","temp","health","technology","online")
        runCatching { File("/sys/class/power_supply").listFiles()?.sortedBy { it.name } }.getOrNull().orEmpty().forEach { supply ->
            names.forEach { field ->
                val file=File(supply,field)
                if(!file.exists() || file.isDirectory) return@forEach
                val value=safeRead(file.path)
                list.add(Sysfs(supply.name+"/"+field,file.path,value?.take(500),if(value==null) "unreadable" else "available"))
            }
        }
        return list.distinctBy { it.path }
    }

    private fun memory():Memory {
        val v=ActivityManager.MemoryInfo(); (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(v)
        return Memory(v.totalMem,v.availMem,v.threshold,v.lowMemory)
    }
    private fun disk(file:File):Disk=try { val v=StatFs(file.absolutePath); Disk(file.absolutePath,v.totalBytes,v.availableBytes,v.freeBytes) } catch(_:Throwable) { Disk(file.absolutePath,null,null,null) }
    private fun storageVolumesInfo():List<VolumeInfo> = runCatching {
        val manager=getSystemService(Context.STORAGE_SERVICE) as StorageManager
        manager.storageVolumes.filter { it.isRemovable && !it.isPrimary }.map { volume ->
            val dir=runCatching { volume.directory }.getOrNull()
            VolumeInfo(runCatching { volume.getDescription(this) }.getOrDefault("אחסון נשלף"),dir?.absolutePath,volume.state,volume.isPrimary,volume.isRemovable,dir?.let { disk(it) })
        }
    }.getOrDefault(emptyList())
    private fun removableVolumes():List<VolumeInfo> = storageVolumesInfo().ifEmpty { emptyList() }
    private fun battery():Battery {
        val i=runCatching { registerReceiver(null,android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val lev=i?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1; val scale=i?.getIntExtra(BatteryManager.EXTRA_SCALE,-1) ?: -1
        val pct=if(lev>=0 && scale>0) (lev*100.0/scale).toInt().coerceIn(0,100) else null
        val bm=getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        fun lp(id:Int):Long?=runCatching { bm.getLongProperty(id) }.getOrNull()?.takeIf { it!=Long.MIN_VALUE }
        val extras=linkedMapOf<String,String>()
        i?.extras?.keySet()?.sorted()?.forEach { k -> runCatching { i.extras?.get(k)?.toString() }.getOrNull()?.let { extras[k]=it } }
        return Battery(pct,i?.getIntExtra(BatteryManager.EXTRA_STATUS,-1) ?: -1,i?.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1) ?: -1,
            i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1)?.takeIf { it>=0 },i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1)?.takeIf { it>=0 },
            i?.getIntExtra(BatteryManager.EXTRA_HEALTH,-1)?.takeIf { it>=0 },i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),runCatching { bm.isCharging }.getOrNull(),
            lp(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),lp(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),lp(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),lp(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),extras)
    }

    private fun exportReport() {
        val m=memory(); val d=disk(Environment.getDataDirectory()); val emulated=disk(Environment.getExternalStorageDirectory()); val volumes=storageVolumesInfo(); val b=battery()
        val sensors=runCatching { (getSystemService(Context.SENSOR_SERVICE) as SensorManager).getSensorList(Sensor.TYPE_ALL) }.getOrDefault(emptyList())
        val codecs=runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList() }.getOrDefault(emptyList())
        val report=JSONObject().apply {
            put("app","Device Lab"); put("version","2.0.1"); put("createdAt",SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US).format(Date()))
            put("themeMode",themeMode.name); put("internetPermissionDeclared",false)
            put("device",JSONObject().apply {
                put("manufacturer",Build.MANUFACTURER); put("model",Build.MODEL); put("device",Build.DEVICE); put("board",Build.BOARD); put("hardware",Build.HARDWARE)
                put("socModel",if(Build.VERSION.SDK_INT>=31) Build.SOC_MODEL else JSONObject.NULL); put("socManufacturer",if(Build.VERSION.SDK_INT>=31) Build.SOC_MANUFACTURER else JSONObject.NULL)
                put("supportedAbis",JSONArray(Build.SUPPORTED_ABIS.toList())); put("androidVersion",Build.VERSION.RELEASE); put("apiLevel",Build.VERSION.SDK_INT)
                put("securityPatch",Build.VERSION.SECURITY_PATCH); put("fingerprint",Build.FINGERPRINT); put("buildDisplay",Build.DISPLAY)
                put("kernelVersion",System.getProperty("os.version") ?: JSONObject.NULL); put("selinuxEnforceRaw",safeRead("/sys/fs/selinux/enforce")?.trim() ?: JSONObject.NULL)
            })
            put("memory",JSONObject().apply { put("totalBytes",m.total); put("availableBytes",m.available); put("usedBytesEstimated",m.used); put("usedPercentEstimated",m.percent); put("thresholdBytes",m.threshold); put("lowMemory",m.low) })
            put("storage",JSONObject().apply {
                put("dataPath",d.path); put("dataTotalBytes",d.total ?: JSONObject.NULL); put("dataAvailableBytes",d.available ?: JSONObject.NULL); put("dataFreeBytes",d.free ?: JSONObject.NULL); put("dataUsedPercentEstimated",d.usedPercent ?: JSONObject.NULL)
                put("emulatedUserStoragePath",emulated.path); put("emulatedUserStorageTotalBytes",emulated.total ?: JSONObject.NULL); put("emulatedUserStorageAvailableBytes",emulated.available ?: JSONObject.NULL); put("emulatedUserStorageUsedPercentEstimated",emulated.usedPercent ?: JSONObject.NULL)
                put("removableVolumes",JSONArray().apply { volumes.forEach { v -> put(JSONObject().apply { put("description",v.description); put("directoryPath",v.path ?: JSONObject.NULL); put("state",v.state); put("isPrimary",v.isPrimary); put("isRemovable",v.isRemovable); put("totalBytes",v.disk?.total ?: JSONObject.NULL); put("availableBytes",v.disk?.available ?: JSONObject.NULL); put("freeBytes",v.disk?.free ?: JSONObject.NULL); put("usedPercentEstimated",v.disk?.usedPercent ?: JSONObject.NULL) }) } })
            })
            put("battery",JSONObject().apply {
                put("levelPercent",b.level ?: JSONObject.NULL); put("statusRaw",b.status); put("statusText",b.statusText); put("pluggedRaw",b.plugged); put("pluggedText",b.pluggedText)
                put("temperatureRawDeciC",b.tempRaw ?: JSONObject.NULL); put("temperatureC",b.tempC ?: JSONObject.NULL); put("voltageRawMillivolts",b.voltageRaw ?: JSONObject.NULL); put("voltageV",b.voltageV ?: JSONObject.NULL)
                put("healthRaw",b.healthRaw ?: JSONObject.NULL); put("technology",b.tech ?: JSONObject.NULL); put("isChargingApi",b.isCharging ?: JSONObject.NULL)
                put("currentNowMicroamps",b.currentNow ?: JSONObject.NULL); put("currentAverageMicroamps",b.currentAvg ?: JSONObject.NULL); put("chargeCounterMicroampHours",b.chargeCounter ?: JSONObject.NULL); put("energyCounterNanoWattHours",b.energyCounter ?: JSONObject.NULL)
                put("actionBatteryChangedExtras",JSONObject().apply { b.extras.forEach { (k,v) -> put(k,v) } })
                put("directPowerSupplyFiles",JSONArray().apply { supplyFiles().forEach { v -> put(JSONObject().apply { put("name",v.name); put("path",v.path); put("rawValue",v.raw ?: JSONObject.NULL); put("status",v.status) }) } })
            })
            put("cpu",JSONObject().apply {
                put("wholeDeviceUsagePercentEstimated",cpu.percent ?: JSONObject.NULL); put("perCoreUsagePercentEstimated",JSONObject().apply { cpu.cores.forEach { (k,v) -> put(k,v) } })
                put("sampleIntervalMs",cpu.intervalMs ?: JSONObject.NULL); put("sampledAt",cpu.sampledAt ?: JSONObject.NULL); put("loadAverageRaw",cpu.load ?: JSONObject.NULL)
                put("procStatSample1Raw",cpu.stat1 ?: JSONObject.NULL); put("procStatSample2Raw",cpu.stat2 ?: JSONObject.NULL); put("cpuInfoRaw",cpu.cpuInfo ?: JSONObject.NULL)
                put("frequencyRecords",JSONArray().apply { cpu.frequencies.forEach { (k,v) -> put(JSONObject().put("name",k).put("rawValue",v)) } })
                put("thermalZones",JSONArray().apply { cpu.thermals.forEach { t -> put(JSONObject().apply { put("name",t.name); put("typeRaw",t.type); put("temperatureRaw",t.raw); put("temperatureCelsiusEstimate",t.celsius ?: JSONObject.NULL); put("sentinelInvalid",t.invalid) }) } })
                put("processElapsedCpuTimeMs",Process.getElapsedCpuTime()); put("method","Estimated from two /proc/stat readings"); put("error",cpu.error ?: JSONObject.NULL)
            })
            put("sensors",JSONArray().apply { sensors.forEach { s -> put(JSONObject().apply { put("name",s.name); put("type",s.type); put("vendor",s.vendor); put("version",s.version); put("maximumRange",s.maximumRange); put("resolution",s.resolution); put("power",s.power); put("minDelayUs",s.minDelay); put("maxDelayUs",s.maxDelay); put("wakeUp",s.isWakeUpSensor); put("stringType",s.stringType) }) } })
            put("mediaCodecs",JSONArray().apply { codecs.forEach { c -> put(JSONObject().apply { put("name",c.name); put("isEncoder",c.isEncoder); put("supportedTypes",JSONArray(c.supportedTypes.toList())); put("isHardwareAccelerated",if(Build.VERSION.SDK_INT>=29) c.isHardwareAccelerated else JSONObject.NULL); put("isSoftwareOnly",if(Build.VERSION.SDK_INT>=29) c.isSoftwareOnly else JSONObject.NULL) }) } })
            put("cameraIds",JSONArray(runCatching { (getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.toList() }.getOrDefault(emptyList())))
            put("systemFeatures",JSONArray(runCatching { packageManager.systemAvailableFeatures.mapNotNull { it.name }.distinct().sorted() }.getOrDefault(emptyList())))
            put("settings",JSONObject().apply { put("powerSaveMode",runCatching { (getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }.getOrDefault(false)); put("deviceIdleMode",runCatching { (getSystemService(Context.POWER_SERVICE) as PowerManager).isDeviceIdleMode }.getOrDefault(false)); put("uptimeMs",SystemClock.elapsedRealtime()) })
            put("notes",JSONArray(listOf("CPU percentages are estimates derived from two /proc/stat samples.","Thermal conversions from positive millidegree values are estimates; negative raw values are retained and flagged invalid.","Reported values are not necessarily independently verified hardware measurements.","No Internet permission is declared.")))
        }
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="application/json"; putExtra(Intent.EXTRA_TITLE,"device-lab-v2-"+System.currentTimeMillis()+".json") }
        pendingJson=report.toString(2)
        try { startActivityForResult(intent,EXPORT_REQUEST) } catch(t:Throwable) { Toast.makeText(this,"לא ניתן לפתוח שמירת קובץ: "+t.localizedMessage,Toast.LENGTH_LONG).show() }
    }
    @Deprecated("Retained for Android API 33 compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==EXPORT_REQUEST && resultCode==RESULT_OK) {
            val uri=data?.data ?: return
            try { contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(pendingJson) }; Toast.makeText(this,"דוח JSON נשמר בהצלחה",Toast.LENGTH_LONG).show() }
            catch(t:Throwable) { Toast.makeText(this,"שמירת הדוח נכשלה: "+t.localizedMessage,Toast.LENGTH_LONG).show() }
        }
    }

    private fun deviceName()=listOf(Build.MANUFACTURER,Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "המכשיר שלי" }
    private fun uptime():String { val s=SystemClock.elapsedRealtime()/1000; return (s/86400).toString()+" ימים · "+((s%86400)/3600).toString()+" שעות · "+((s%3600)/60).toString()+" דקות" }
    private fun gb(v:Long)=fmt.format(v.toDouble()/1_000_000_000.0)
    private fun mb(v:Long)=fmt.format(v.toDouble()/1_000_000.0)
    private fun safeRead(path:String):String?=try { val f=File(path); if(!f.exists()||f.isDirectory) null else f.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() } } catch(_:Throwable) { null }

    private fun section(title:String,subtitle:String?=null) { content.addView(txt(title,17f,colors.text,true)); if(!subtitle.isNullOrBlank()) { gap(4); content.addView(txt(subtitle,12f,colors.muted)) }; gap(11) }
    private fun card(title:String,subtitle:String?=null):LinearLayout {
        val c=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutDirection=View.LAYOUT_DIRECTION_RTL; setPadding(dp(16),dp(14),dp(16),dp(14)); background=bordered() }
        c.addView(txt(title,15f,colors.text,true)); if(!subtitle.isNullOrBlank()) { gapIn(c,5); c.addView(txt(subtitle,11.5f,colors.muted)) }; gapIn(c,8); return c
    }
    private fun noteCard(title:String,message:String) {
        val c=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutDirection=View.LAYOUT_DIRECTION_RTL; setPadding(dp(15),dp(14),dp(15),dp(14)); background=rounded(colors.accentSoft,16) }
        c.addView(txt(title,13f,colors.accent,true)); gapIn(c,5); c.addView(txt(message,12f,colors.text)); content.addView(c,match())
    }
    private fun metric(title:String,value:String,detail:String,accent:Int,percent:Int?):View {
        val c=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutDirection=View.LAYOUT_DIRECTION_RTL; setPadding(dp(13),dp(13),dp(13),dp(13)); background=bordered() }
        c.addView(View(this).apply { background=rounded(accent,30) },LinearLayout.LayoutParams(dp(9),dp(9)))
        gapIn(c,10); c.addView(txt(title,12f,colors.muted,true)); gapIn(c,5); c.addView(txt(value,21f,colors.text,true)); gapIn(c,4); c.addView(txt(detail,10.5f,colors.muted))
        if(percent!=null) { gapIn(c,9); c.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=percent.coerceIn(0,100); progressTintList=android.content.res.ColorStateList.valueOf(accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(colors.line) },LinearLayout.LayoutParams(-1,dp(5))) }
        return c
    }
    private fun shortcut(title:String,detail:String,symbol:String,action:()->Unit):View {
        val c=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutDirection=View.LAYOUT_DIRECTION_RTL; setPadding(dp(13),dp(13),dp(13),dp(13)); background=bordered(); isClickable=true; isFocusable=true; setOnClickListener { action() } }
        val r=row(); r.addView(txt(title,14f,colors.text,true),LinearLayout.LayoutParams(0,-2,1f)); r.addView(txt(symbol,18f,colors.accent,true))
        c.addView(r); gapIn(c,6); c.addView(txt(detail,11f,colors.muted)); return c
    }
    private fun addDetail(parent:LinearLayout,title:String,value:String,note:String?=null) {
        val line=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutDirection=View.LAYOUT_DIRECTION_RTL; setPadding(0,dp(8),0,dp(8)) }
        val r=row(); r.addView(txt(title,12f,colors.muted),LinearLayout.LayoutParams(0,-2,0.85f)); r.addView(txt(value.ifBlank { "לא זמין" },12f,colors.text,true).apply { gravity=Gravity.LEFT },LinearLayout.LayoutParams(0,-2,1.15f))
        line.addView(r); if(!note.isNullOrBlank()) { gapIn(line,3); line.addView(txt(note,10f,colors.muted).apply { setTextIsSelectable(true) }) }
        parent.addView(line,match()); parent.addView(View(this).apply { setBackgroundColor(colors.line) },LinearLayout.LayoutParams(-1,dp(1)))
    }
    private fun progressRow(parent:LinearLayout,title:String,value:String,percent:Int) {
        val r=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(8),0,dp(8)) }
        val h=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; layoutDirection=View.LAYOUT_DIRECTION_RTL }
        h.addView(txt(title,12f,colors.text,true),LinearLayout.LayoutParams(0,-2,1f)); h.addView(txt(value,12f,colors.muted,true)); r.addView(h); gapIn(r,6)
        r.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=percent.coerceIn(0,100); progressTintList=android.content.res.ColorStateList.valueOf(colors.accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(colors.line) },LinearLayout.LayoutParams(-1,dp(6)))
        parent.addView(r)
    }
    private fun button(title:String,primary:Boolean,action:()->Unit)=Button(this).apply { text=title; isAllCaps=false; textSize=13f; setTextColor(if(primary) Color.WHITE else colors.accent); background=rounded(if(primary) colors.accent else colors.accentSoft,14); setOnClickListener { action() } }
    private fun txt(value:String,size:Float,color:Int,bold:Boolean=false)=TextView(this).apply { text=value; textSize=size; setTextColor(color); if(bold) setTypeface(typeface,Typeface.BOLD); gravity=Gravity.RIGHT; textDirection=View.TEXT_DIRECTION_FIRST_STRONG; layoutDirection=View.LAYOUT_DIRECTION_RTL; setLineSpacing(dp(2).toFloat(),1f) }
    private fun rounded(color:Int,radius:Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
    private fun bordered()=GradientDrawable().apply { setColor(this@MainActivity.colors.surface); cornerRadius=dp(19).toFloat(); setStroke(dp(1),this@MainActivity.colors.line) }
    private fun gradient(a:Int,b:Int,radius:Int)=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(a,b)).apply { cornerRadius=dp(radius).toFloat() }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; layoutDirection=View.LAYOUT_DIRECTION_RTL; gravity=Gravity.TOP }
    private fun gap(h:Int) { content.addView(View(this),LinearLayout.LayoutParams(1,dp(h))) }
    private fun gapIn(p:LinearLayout,h:Int) { p.addView(View(this),LinearLayout.LayoutParams(1,dp(h))) }
    private fun space(w:Int)=View(this).apply { layoutParams=LinearLayout.LayoutParams(dp(w),1) }
    private fun match()=LinearLayout.LayoutParams(-1,-2)
    private fun weight()=LinearLayout.LayoutParams(0,-2,1f)
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    companion object { private const val PREFS="device_lab_preferences"; private const val KEY_THEME="theme_mode"; private const val EXPORT_REQUEST=2410 }
}
