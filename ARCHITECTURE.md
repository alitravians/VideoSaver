# البنية التقنية - Video Saver

هذا الملف يشرح البنية التقنية للتطبيق بالتفصيل لأي مبرمج جديد يريد فهم أو تطوير التطبيق.

---

## التقنيات المستخدمة

| التقنية | الاستخدام | الإصدار |
|---------|-----------|---------|
| **Kotlin** | لغة البرمجة الأساسية | 1.9.22 |
| **Jetpack Compose** | واجهة المستخدم (UI) | BOM 2023.10.01 |
| **Material 3** | نظام التصميم | ضمن Compose BOM |
| **MVVM Architecture** | نمط البنية | - |
| **Room Database** | قاعدة بيانات محلية لسجل التحميلات | 2.6.1 |
| **OkHttp** | مكتبة الشبكات والتحميل | 4.12.0 |
| **Jsoup** | تحليل HTML | 1.17.2 |
| **Coil** | تحميل الصور والمصغرات | 2.5.0 |
| **DataStore** | حفظ إعدادات المستخدم | 1.0.0 |
| **Coroutines** | البرمجة غير المتزامنة | 1.7.3 |
| **Navigation Compose** | التنقل بين الشاشات | 2.7.6 |
| **KSP** | معالج الترميز لـ Room | - |

### إعدادات البناء

| الإعداد | القيمة |
|---------|--------|
| Min SDK | 26 (Android 8.0 Oreo) |
| Target SDK | 34 (Android 14) |
| Compile SDK | 34 |
| Kotlin Compiler Extension | 1.5.8 |
| Java Version | 17 |
| Gradle Version | 8.5 |

---

## هيكل المشروع

```
app/src/main/java/com/videosaver/app/
│
├── MainActivity.kt                          # النشاط الرئيسي - نقطة الدخول
├── VideoSaverApp.kt                         # Application class - إعداد القنوات والثوابت
│
├── data/                                    # طبقة البيانات
│   ├── db/
│   │   ├── AppDatabase.kt                   # قاعدة بيانات Room (Singleton)
│   │   └── DownloadDao.kt                   # Data Access Object - عمليات CRUD
│   ├── model/
│   │   ├── DownloadItem.kt                  # نموذج التحميل + DownloadStatus + Platform + ContentType
│   │   └── VideoInfo.kt                     # معلومات الفيديو المستخرج
│   └── repository/
│       └── DownloadRepository.kt            # Repository pattern - واجهة موحدة للبيانات
│
├── service/                                 # طبقة الخدمات (منطق الأعمال)
│   ├── VideoExtractor.kt                    # الواجهة الأساسية + Factory pattern
│   ├── InstagramExtractor.kt               # استخراج فيديوهات Instagram
│   ├── TikTokExtractor.kt                  # استخراج فيديوهات TikTok
│   ├── UrlValidator.kt                      # التحقق من صحة الروابط وتصنيفها
│   └── VideoDownloadService.kt              # خدمة التحميل (Foreground Service)
│
├── viewmodel/                               # طبقة ViewModel
│   ├── MainViewModel.kt                     # ViewModel الشاشة الرئيسية + MainUiState
│   ├── HistoryViewModel.kt                  # ViewModel سجل التحميلات
│   └── SettingsViewModel.kt                 # ViewModel الإعدادات
│
├── ui/                                      # طبقة العرض
│   ├── screens/
│   │   ├── MainScreen.kt                    # الشاشة الرئيسية (لصق + تحميل + مشاركة)
│   │   ├── HistoryScreen.kt                 # شاشة سجل التحميلات
│   │   ├── SettingsScreen.kt                # شاشة الإعدادات
│   │   └── SplashScreen.kt                  # شاشة اللودينغ (0-100%)
│   ├── navigation/
│   │   └── AppNavigation.kt                 # نظام التنقل بين الشاشات
│   └── theme/
│       ├── Color.kt                         # ألوان التطبيق
│       ├── Theme.kt                         # سمة التطبيق
│       └── Type.kt                          # خطوط التطبيق
│
└── util/                                    # أدوات مساعدة
    ├── ClipboardHelper.kt                   # قراءة الحافظة
    ├── FileManager.kt                       # إدارة الملفات وتسمية الفيديوهات
    └── SettingsManager.kt                   # إدارة إعدادات المستخدم (SharedPreferences)
```

---

## نمط البنية (MVVM)

```
┌─────────────┐     ┌──────────────┐     ┌──────────────┐     ┌─────────────┐
│   UI Layer  │────▶│  ViewModel   │────▶│  Repository  │────▶│  Database   │
│  (Compose)  │◀────│  (StateFlow) │◀────│   (DAO)      │◀────│   (Room)    │
└─────────────┘     └──────────────┘     └──────────────┘     └─────────────┘
                           │
                           ▼
                    ┌──────────────┐
                    │   Services   │
                    │ (Extractors  │
                    │  + Download) │
                    └──────────────┘
```

### تدفق البيانات

1. **المستخدم** يلصق رابط في `MainScreen`
2. **MainScreen** يرسل الرابط إلى `MainViewModel.startDownload()`
3. **MainViewModel** يتحقق من الرابط عبر `UrlValidator`
4. **UrlValidator** يحدد المنصة (Instagram/TikTok) ونوع المحتوى
5. **VideoExtractorFactory** ينشئ الـ Extractor المناسب
6. **Extractor** (Instagram أو TikTok) يستخرج رابط الفيديو المباشر
7. **MainViewModel** يسجل التحميل في `DownloadRepository`
8. **VideoDownloadService** (Foreground Service) يحمّل الفيديو
9. **MainViewModel** يراقب التقدم عبر `monitorDownload()`
10. عند الاكتمال، يتم عرض خيارات المشاركة

---

## آلية استخراج الفيديو

### Instagram - ترتيب المحاولات

```
1. checkContentAvailability()     ← فحص oEmbed API لكشف المحتوى المقيد
   │
   ├── إذا مقيد بالعمر → رسالة خطأ واضحة
   │
   ▼
2. tryCobaltApi()                 ← Cobalt API v7 (أساسي، بدون مصادقة)
   │                                 يدعم: tunnel, redirect, stream, picker
   │
   ▼
3. tryGraphQLMethod()             ← Instagram GraphQL API
   │                                 يحتاج: X-IG-App-ID header
   │
   ▼
4. tryInstagramApiV1()            ← Instagram API v1
   │                                 يحتاج: Mobile User-Agent + X-IG headers
   │
   ▼
5. tryEmbedMethod()               ← Instagram Embed page scraping
   │                                 يستخرج video_url من HTML
   │
   ▼
6. tryHtmlScraping()              ← HTML scraping مباشر
                                     يبحث عن og:video و video_url
```

### TikTok - ترتيب المحاولات

```
1. tryCobaltApiWithVerification() ← Cobalt API + تحقق من صلاحية الرابط
   │                                 يتحقق بـ GET request (ليس HEAD!)
   │                                 إذا الرابط 500 → ينتقل للبديل
   │
   ▼
2. tryTikWmApi()                  ← tikwm.com API (بديل أساسي موثوق)
   │                                 يرجع روابط CDN مباشرة تشتغل
   │                                 يدعم: HD, عادي, بعلامة مائية
   │
   ▼
3. tryOEmbedMethod()              ← TikTok oEmbed + HTML scraping
   │
   ▼
4. tryHtmlScraping()              ← HTML scraping مباشر
   │                                 يبحث في: UNIVERSAL_DATA, SIGI_STATE
   │                                 يستخرج: downloadAddr, playAddr, og:video
   │
   ▼
5. tryApiMethod()                 ← TikTok Internal API
                                     يحتاج: Video ID
```

---

## خدمة التحميل (VideoDownloadService)

### كيف تعمل

- تعمل كـ **Foreground Service** مع إشعار تقدم
- تستخدم **OkHttp** للتحميل مع timeout مناسب (60s connect, 120s read)
- **Headers ذكية** حسب نوع الرابط:
  - Cobalt Stream → headers بسيطة
  - TikTok CDN (من tikwm) → بدون Referer
  - روابط TikTok أخرى → مع Referer
  - Instagram → مع Referer
- **إعادة محاولة** عند خطأ 500-503 (مرة واحدة بعد 2 ثواني)
- **حفظ ذكي**:
  - Android 10+ (API 29+) → MediaStore API
  - Android 8-9 (API 26-28) → حفظ مباشر + Media Scanner

### تسمية الملفات

```
{Platform}_{Date}_{SequentialNumber}.mp4
مثال: Instagram_20260402_001.mp4
مثال: TikTok_20260402_002.mp4
```

---

## قاعدة البيانات

### جدول downloads

| العمود | النوع | الوصف |
|--------|-------|-------|
| id | Long (PK, auto) | معرف فريد |
| url | String | رابط المصدر |
| platform | String | المنصة (Instagram/TikTok) |
| contentType | String | نوع المحتوى (Reel/Story/Video) |
| fileName | String | اسم الملف |
| filePath | String | مسار الحفظ |
| thumbnailUrl | String | رابط الصورة المصغرة |
| duration | String | مدة الفيديو |
| status | String | حالة التحميل |
| progress | Int | نسبة التقدم (0-100) |
| fileSize | Long | حجم الملف |
| downloadedAt | Long | وقت التحميل (timestamp) |
| errorMessage | String | رسالة الخطأ |

### حالات التحميل (DownloadStatus)

```
pending → validating → extracting → downloading → saving → completed
                                                           ↓
                                                         failed
```

---

## الشاشات

### 1. SplashScreen (شاشة اللودينغ)
- عرض: 3 ثواني تقريباً
- أنيميشن: دائرة تقدم من 0% إلى 100%
- ألوان متدرجة: أزرق → بنفسجي
- رسائل حالة متغيرة أثناء التحميل

### 2. MainScreen (الشاشة الرئيسية)
- حقل إدخال الرابط مع زر لصق
- زر تحميل
- عرض التقدم أثناء التحميل
- شرائح المنصات المدعومة
- زر مشاركة واتساب بعد التحميل
- معلومات المطور (Ali) مع أنيميشن

### 3. HistoryScreen (سجل التحميلات)
- قائمة بجميع التحميلات مع معلوماتها
- إمكانية حذف تحميل أو مسح الكل
- عرض حالة كل تحميل بألوان مختلفة

### 4. SettingsScreen (الإعدادات)
- إعدادات التطبيق العامة
- تفضيلات المستخدم محفوظة بـ SharedPreferences

---

## الأمان والصلاحيات

### الصلاحيات المطلوبة (AndroidManifest.xml)

```xml
INTERNET                    ← الوصول للإنترنت
FOREGROUND_SERVICE          ← خدمة التحميل في الخلفية
POST_NOTIFICATIONS          ← إشعارات التحميل (Android 13+)
WRITE_EXTERNAL_STORAGE      ← حفظ الفيديو (Android 8-9 فقط)
READ_EXTERNAL_STORAGE       ← قراءة الملفات (Android 8-9 فقط)
FOREGROUND_SERVICE_DATA_SYNC ← نوع خدمة التحميل
```

### إعدادات الشبكة

```xml
<!-- network_security_config.xml -->
- السماح بـ cleartext traffic للتطوير
- في الإنتاج: يفضل تقييده لنطاقات محددة
```

---

## ملاحظات مهمة للمطور الجديد

### 1. خوادم Cobalt API
- نستخدم حالياً: `downloadapi.stuff.solutions/api/json` (v7)
- الخوادم القديمة ماتت (cobalt-api.ayo.tf, cobalt-api.kwiatekmiki.com)
- خوادم v10 تحتاج JWT ومعظمها متوقف
- إذا توقف الخادم الحالي: ابحث عن خادم v7 جديد في https://instances.hyper.lol

### 2. tikwm.com API
- بديل أساسي لـ TikTok يرجع روابط CDN مباشرة
- مجاني وبدون مصادقة
- يرجع: hdplay (HD), play (عادي), wmplay (بعلامة مائية)

### 3. محتوى Instagram المقيد
- بعض المحتوى مقيد بالعمر (15+ سنة)
- oEmbed API يرجع `geoblock_required` لهذا المحتوى
- لا يوجد حل بدون تسجيل دخول Instagram

### 4. Cobalt Stream URLs
- HEAD requests تكسر روابط cobalt stream (لا تستخدمها!)
- استخدم GET مع Range header للتحقق
- الخادم أحياناً يرجع 500 - لذلك نتحقق قبل التحميل

### 5. TikTok CDN Headers
- روابط CDN المباشرة تحتاج Referer header
- روابط من tikwm لا تحتاج Referer
- روابط cobalt stream تحتاج headers بسيطة فقط
