# Device Lab Probe — שלב 0.2 מורחב

אפליקציית Android נפרדת לאיסוף תוצאות גולמיות ולמיפוי זמינות API/קובצי מערכת. אינה משנה את פרויקט Device Lab הקיים.

## הגדרות
- `applicationId`: `il.devicelab.probe`
- `minSdk`: 33
- `targetSdk`: 33
- `compileSdk`: 35
- ללא הרשאת `INTERNET`, Root, Hidden APIs או עקיפת חסימות.
- ההרשאות המוצהרות ב־Manifest הן `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK` בלבד. אין הרשאת `INTERNET`.
- ייצוא נעשה דרך `ACTION_CREATE_DOCUMENT`; אין הרשאות אחסון.

## שימוש
1. התקן ופתח את Device Lab Probe.
2. בחר תווית ריצה: מחובר לטעינה / מנותק. התווית היא דיווח של המשתמש; הדוח שומר בנפרד את מצב הטעינה בפועל.
3. לחץ **הרץ הכול** והמתן לסיום. סריקת חיישנים ממתינה עד 500ms לכל חיישן שאינו דורש הרשאה.
4. לחץ **ייצוא JSON** ושמור את הקובץ במיקום לבחירתך.
5. בדיקת רקע: התחל ללא Wake Lock, עצור; לאחר מכן התחל בריצת Wake Lock קצר לכל דגימה, ועצור. כל 30 שניות נשמרת דגימת RAM/סוללה לקובץ פנימי. הפסק את הבדיקה אחרי פרק הזמן הרצוי.

## מבנה תוצאות
לכל בדיקה יש `name`, `rawValue`, `unit`, `source`, `status`, `classification`, `rawError`, `timestamp`. לא מבוצעת המרת יחידות. ערכים חסרים/מחרוזות ריקות/סמני `MIN_VALUE` אינם מסומנים כמדידות זמינות. אפס נשמר כערך אפשרי ולא מסווג אוטומטית כריק. קריאות נחסמות/חסרות מתועדות במקום להפיל את האפליקציה.

## מגבלות שחשוב לדעת
- תוצאות תלויות בגרסת Android, בקושחה, בהרשאות וב־SELinux של המכשיר. עצם הכללת בדיקה לא מעידה שהיא הצליחה.
- קריאות `sysfs`/`procfs`, thermal, CPU-wide ו־power-supply עשויות להיות חסומות או לא קיימות.
- `StorageStatsManager` נבדק רק עבור UID האפליקציה; אין ניסיון לקבל נתונים פרטיים של אפליקציות אחרות.
- CameraManager נבדק ללא הוספת הרשאת CAMERA.
- מחזורי סוללה/בריאות דרך API שאינם קיימים ב־API 33 ידווחו כ־unsupported, לא יזויפו.
- שרידות ברקע/אתחול אינה נחשבת מאומתת עד ריצה פיזית על הנגן וסקירת דוח הלוגים.
- מצב Wake Lock מנסה לרכוש `PARTIAL_WAKE_LOCK` קצר לכל דגימה ומתעד `wakeLockAcquired` ואת השגיאה, אם נכשלה הרכישה. ההרשאה `WAKE_LOCK` מוצהרת ומאומתת ב־Merged Manifest; עדיין יש לבדוק במכשיר עצמו שהרכישה מצליחה.
- לא נבדק על M36 Plus כחלק מתהליך יצירת הקוד או הבנייה בענן.

## בנייה
מתיקייה זו: `gradle --no-daemon --console=plain assembleDebug`. ב־GitHub Actions של ענף `probe-stage0` מופק `DeviceLabProbe-debug-apk` ונבדק ה־Merged Manifest. קוד האפליקציה נמצא בתיקייה `device-lab-probe/` אחרי פריסת המקור מהארכיון.
