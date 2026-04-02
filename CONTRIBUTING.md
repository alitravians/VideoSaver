# دليل المطور - Video Saver

هذا الملف يشرح كيف تبدأ العمل على التطبيق كمطور جديد.

---

## إعداد بيئة التطوير

### 1. المتطلبات الأساسية
- **Android Studio** Hedgehog (2023.1.1) أو أحدث
- **JDK 17** (يأتي مدمجاً مع Android Studio)
- **Android SDK 34** (يتم تثبيته من SDK Manager)
- **Git** لإدارة الكود

### 2. استنساخ المشروع
```bash
git clone https://github.com/alitravians/VideoSaver.git
cd VideoSaver
```

### 3. فتح المشروع في Android Studio
1. افتح Android Studio
2. اختر **Open** واختر مجلد `VideoSaver`
3. انتظر حتى ينتهي Gradle Sync
4. إذا طلب تثبيت SDK components → وافق

### 4. تشغيل التطبيق
- اختر جهاز أو محاكي من القائمة
- اضغط **Run** (أو `Shift+F10`)

### 5. بناء APK من سطر الأوامر
```bash
# Debug APK
./gradlew assembleDebug
# الملف: app/build/outputs/apk/debug/app-debug.apk

# Release APK (يحتاج signing key)
./gradlew assembleRelease
```

---

## هيكل الكود

### الطبقات الرئيسية

| الطبقة | المسار | المسؤولية |
|--------|--------|-----------|
| **UI** | `ui/screens/` | شاشات Compose |
| **ViewModel** | `viewmodel/` | إدارة الحالة وربط الـ UI بالمنطق |
| **Service** | `service/` | استخراج الروابط وتحميل الفيديو |
| **Data** | `data/` | قاعدة البيانات والنماذج |
| **Util** | `util/` | أدوات مساعدة |

### الملفات الأهم

| الملف | ماذا يفعل |
|-------|-----------|
| `TikTokExtractor.kt` | استخراج فيديوهات TikTok (cobalt → tikwm → scraping) |
| `InstagramExtractor.kt` | استخراج فيديوهات Instagram (cobalt → GraphQL → API v1 → embed → scraping) |
| `VideoDownloadService.kt` | خدمة التحميل (Foreground Service + progress + retry) |
| `UrlValidator.kt` | التحقق من الروابط وتحديد المنصة |
| `MainViewModel.kt` | منطق الشاشة الرئيسية وإدارة التحميل |
| `MainScreen.kt` | واجهة الشاشة الرئيسية |

---

## كيف تضيف منصة جديدة (مثل YouTube, Twitter)

### الخطوة 1: إنشاء Extractor جديد

أنشئ ملف جديد في `service/` (مثلاً `YouTubeExtractor.kt`):

```kotlin
package com.videosaver.app.service

import com.videosaver.app.data.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YouTubeExtractor : VideoExtractor {

    override suspend fun extract(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            // 1. حاول الطريقة الأولى
            var videoInfo = tryMethod1(url)

            // 2. إذا فشلت، حاول الطريقة الثانية
            if (videoInfo == null) {
                videoInfo = tryMethod2(url)
            }

            if (videoInfo != null) {
                Result.success(videoInfo)
            } else {
                Result.failure(Exception("تعذر استخراج الفيديو"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("خطأ في الاتصال: ${e.message}"))
        }
    }
}
```

### الخطوة 2: إضافة المنصة في الثوابت

في `data/model/DownloadItem.kt`:
```kotlin
object Platform {
    const val INSTAGRAM = "Instagram"
    const val TIKTOK = "TikTok"
    const val YOUTUBE = "YouTube"     // أضف هنا
    const val UNKNOWN = "Unknown"
}
```

### الخطوة 3: تسجيل الـ Extractor في Factory

في `service/VideoExtractor.kt`:
```kotlin
object VideoExtractorFactory {
    fun getExtractor(platform: String): VideoExtractor {
        return when (platform) {
            Platform.INSTAGRAM -> InstagramExtractor()
            Platform.TIKTOK -> TikTokExtractor()
            Platform.YOUTUBE -> YouTubeExtractor()  // أضف هنا
            else -> throw IllegalArgumentException("منصة غير مدعومة: $platform")
        }
    }
}
```

### الخطوة 4: إضافة أنماط الروابط

في `service/UrlValidator.kt`:
```kotlin
// أضف أنماط الروابط
private val youtubePattern = Regex("""https?://(www\.)?youtube\.com/watch\?v=[A-Za-z0-9_-]+""")
private val youtubeShortPattern = Regex("""https?://youtu\.be/[A-Za-z0-9_-]+""")

// أضف في دالة validate()
if (youtubePattern.containsMatchIn(trimmedUrl) || youtubeShortPattern.containsMatchIn(trimmedUrl)) {
    return UrlValidationResult(true, Platform.YOUTUBE, ContentType.VIDEO)
}
```

---

## كيف تضيف API بديل جديد

### لـ TikTok
1. افتح `TikTokExtractor.kt`
2. أضف دالة جديدة بنمط `tryNewApi(url: String): VideoInfo?`
3. أضف استدعاءها في `extract()` بالترتيب المناسب
4. ارجع `VideoInfo` عند النجاح أو `null` عند الفشل

### لـ Instagram
1. افتح `InstagramExtractor.kt`
2. نفس النمط: دالة جديدة → إضافتها في `extract()`

### قواعد مهمة
- كل دالة extraction يجب أن ترجع `null` عند الفشل (ليس exception)
- استخدم `try-catch` حول كل العمليات
- أضف timeout مناسب (10-30 ثانية)
- اختبر مع روابط حقيقية

---

## الاختبار

### اختبار على Appetize.io
1. ابنِ APK: `./gradlew assembleDebug`
2. افتح https://appetize.io/upload
3. ارفع ملف APK
4. اختر: **Pixel 7** + **Android 13.0**
5. فعّل **Show Developer Tools** → **Debug Logs** لرؤية الأخطاء
6. اختبر:
   - لصق رابط Instagram Reel
   - لصق رابط TikTok
   - التأكد من التحميل
   - اختبار سجل التحميلات
   - اختبار المشاركة عبر واتساب

### ملاحظات الاختبار
- Appetize.io المجاني يسمح بـ 3 دقائق فقط لكل جلسة
- إذا أردت ملف HAR (Network Log): فعّله من Developer Tools
- إذا التطبيق crash → ستجد Stack Trace في Debug Logs

### روابط اختبار مجربة
```
# Instagram Reel (عام)
https://www.instagram.com/p/DVcMIemCIgf/

# TikTok
أي رابط TikTok عام
```

---

## اصطلاحات الكود

### التسمية
- **Kotlin**: camelCase للمتغيرات والدوال، PascalCase للكلاسات
- **الملفات**: PascalCase (مثل `TikTokExtractor.kt`)
- **الـ Composables**: PascalCase (مثل `MainScreen`, `DeveloperCredits`)

### رسائل الخطأ
- جميع رسائل الخطأ **بالعربي**
- واضحة ومحددة (مثل "هذا المحتوى مقيد بالعمر" بدلاً من "فشل التحميل")

### اصطلاحات Git
```
feat: إضافة ميزة جديدة
fix: إصلاح خطأ
docs: تحديث التوثيق
refactor: إعادة هيكلة الكود
style: تغييرات شكلية (formatting)
```

مثال:
```
feat: إضافة دعم YouTube لتحميل الفيديوهات
fix: إصلاح مشكلة تحميل TikTok عند خطأ 500
```

---

## نصائح مهمة

1. **لا تستخدم HEAD requests مع cobalt** - روابط stream لا تدعمها. استخدم GET مع Range header
2. **اختبر مع روابط حقيقية** - APIs قد تعمل بشكل مختلف مع روابط وهمية
3. **راقب Network HAR** على Appetize.io - يساعد في كشف مشاكل الشبكة
4. **خوادم cobalt تتغير** - إذا توقف خادم، ابحث عن بديل على https://instances.hyper.lol
5. **tikwm.com موثوق** - إذا cobalt فشل مع TikTok، tikwm يعمل دائماً
6. **Instagram API قد يطلب تسجيل دخول** - هذا طبيعي للمحتوى المقيد
