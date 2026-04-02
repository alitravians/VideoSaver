# مرجع الـ APIs - Video Saver

هذا الملف يوثق جميع الـ APIs الخارجية المستخدمة في التطبيق مع أمثلة عملية.

---

## 1. Cobalt API (v7)

### الوصف
خدمة مجانية ومفتوحة المصدر لاستخراج روابط الفيديو من منصات التواصل الاجتماعي.

### الخادم الحالي
```
https://downloadapi.stuff.solutions/api/json
```

### الطلب
```http
POST /api/json
Content-Type: application/json
Accept: application/json
User-Agent: VideoSaver/1.0

{
    "url": "https://www.instagram.com/reel/ABC123/",
    "videoQuality": "720",
    "tiktokFullAudio": true,        // TikTok فقط
    "isNoTTWatermark": true          // TikTok فقط - بدون علامة مائية
}
```

### الاستجابات المحتملة

#### نجاح - tunnel/redirect/stream
```json
{
    "status": "stream",
    "url": "https://downloadapi.stuff.solutions/api/stream?..."
}
```
- `tunnel`: رابط مباشر للتحميل
- `redirect`: رابط يحول للفيديو
- `stream`: رابط proxy عبر خادم cobalt (الأكثر شيوعاً)

#### نجاح - picker (عدة فيديوهات)
```json
{
    "status": "picker",
    "picker": [
        {
            "type": "video",
            "url": "https://...",
            "thumb": "https://..."
        }
    ]
}
```

#### خطأ
```json
{
    "status": "error",
    "text": "رسالة الخطأ"
}
```

### ملاحظات مهمة
- **لا يحتاج مصادقة** (خوادم v7 فقط)
- **Stream URLs**: لا تدعم HEAD requests! استخدم GET مع `Range: bytes=0-1023` للتحقق
- **خوادم v10** تحتاج JWT token ومعظمها متوقف حالياً
- **إيجاد خوادم جديدة**: https://instances.hyper.lol

### الملف المسؤول
- Instagram: `InstagramExtractor.kt` → `tryCobaltApi()`
- TikTok: `TikTokExtractor.kt` → `tryCobaltApiWithVerification()`

---

## 2. tikwm.com API

### الوصف
خدمة مجانية لاستخراج فيديوهات TikTok. ترجع روابط CDN مباشرة تعمل بدون مشاكل.

### الطلب
```http
POST https://www.tikwm.com/api/
Content-Type: application/x-www-form-urlencoded
User-Agent: Mozilla/5.0 (Linux; Android 14; Pixel 8) ...

url=https://www.tiktok.com/@user/video/1234567890&hd=1
```

### الاستجابة
```json
{
    "code": 0,
    "msg": "success",
    "data": {
        "id": "1234567890",
        "title": "عنوان الفيديو",
        "duration": 15,
        "cover": "https://p16-sign-sg.tiktokcdn.com/...",
        "hdplay": "https://v16m-default.akamaized.net/...",
        "play": "https://v16m-default.akamaized.net/...",
        "wmplay": "https://v16m-default.akamaized.net/...",
        "size": 1234567,
        "hd_size": 2345678
    }
}
```

### حقول الفيديو (بترتيب الأفضلية)
1. `hdplay` - جودة عالية بدون علامة مائية
2. `play` - جودة عادية بدون علامة مائية
3. `wmplay` - مع علامة مائية TikTok

### ملاحظات
- `code: 0` = نجاح، أي قيمة أخرى = فشل
- روابط CDN المرجعة لا تحتاج Referer header
- مجاني بدون حد للطلبات (حتى الآن)

### الملف المسؤول
`TikTokExtractor.kt` → `tryTikWmApi()`

---

## 3. Instagram oEmbed API

### الوصف
API رسمي من Instagram لجلب معلومات المحتوى. نستخدمه أساساً لكشف المحتوى المقيد.

### الطلب
```http
GET https://www.instagram.com/api/v1/oembed/?url=https://www.instagram.com/reel/ABC123/
User-Agent: Mozilla/5.0 ...
Accept: application/json
```

### استجابة - محتوى عادي
```json
{
    "version": "1.0",
    "title": "عنوان المنشور",
    "author_name": "اسم الحساب",
    "author_url": "https://www.instagram.com/username/",
    "thumbnail_url": "https://...",
    "html": "<blockquote>...</blockquote>"
}
```

### استجابة - محتوى مقيد بالعمر
```json
{
    "status": "fail",
    "message": "geoblock_required",
    "description": "You must be 15 years old or over to see this profile"
}
```

### استجابة - محتوى يحتاج تسجيل دخول
```json
{
    "status": "fail",
    "message": "login_required"
}
```

### الملف المسؤول
`InstagramExtractor.kt` → `checkContentAvailability()`

---

## 4. Instagram GraphQL API

### الوصف
API داخلي من Instagram لجلب تفاصيل المنشورات.

### الطلب
```http
GET https://www.instagram.com/p/{shortcode}/?__a=1&__d=dis
User-Agent: Mozilla/5.0 ...
Accept: application/json
X-IG-App-ID: 936619743392459
X-Requested-With: XMLHttpRequest
```

### الاستجابة
```json
{
    "items": [
        {
            "video_versions": [
                {
                    "url": "https://scontent-iad3-2.cdninstagram.com/...",
                    "width": 720,
                    "height": 1280,
                    "type": 101
                }
            ],
            "image_versions2": {
                "candidates": [
                    {
                        "url": "https://scontent-iad3-2.cdninstagram.com/...",
                        "width": 1080,
                        "height": 1920
                    }
                ]
            },
            "video_duration": 15.5
        }
    ]
}
```

### ملاحظات
- قد يحتاج تسجيل دخول للمحتوى المقيد
- `X-IG-App-ID: 936619743392459` ثابت (Facebook App ID)

### الملف المسؤول
`InstagramExtractor.kt` → `tryGraphQLMethod()`

---

## 5. Instagram API v1 (Mobile)

### الوصف
API الهاتف الداخلي من Instagram. يحتاج تحويل Shortcode إلى Media ID.

### تحويل Shortcode إلى Media ID
```
Shortcode: "DVcMIemCIgf"
خوارزمية: Base-64 alphabet decode
Alphabet: ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_
النتيجة: Media ID (رقم)
```

### الطلب
```http
GET https://i.instagram.com/api/v1/media/{media_id}/info/
User-Agent: Instagram 275.0.0.27.98 Android (33/13; 420dpi; 1080x2400; Google/google; Pixel 7; panther; panther; en_US; 458229258)
X-IG-App-ID: 936619743392459
X-IG-Capabilities: 3brTvw8=
X-IG-Connection-Type: WIFI
Accept-Language: en-US
Accept: */*
```

### الاستجابة
نفس بنية GraphQL API (items → video_versions)

### الملف المسؤول
`InstagramExtractor.kt` → `tryInstagramApiV1()` + `shortcodeToMediaId()`

---

## 6. TikTok oEmbed API

### الوصف
API رسمي من TikTok لجلب معلومات الفيديو (عنوان + صورة مصغرة).

### الطلب
```http
GET https://www.tiktok.com/oembed?url=https://www.tiktok.com/@user/video/123
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) ...
```

### الاستجابة
```json
{
    "title": "عنوان الفيديو",
    "thumbnail_url": "https://p16-sign-sg.tiktokcdn.com/...",
    "author_name": "اسم المستخدم",
    "html": "<blockquote>...</blockquote>"
}
```

### الملف المسؤول
`TikTokExtractor.kt` → `tryOEmbedMethod()`

---

## 7. TikTok Internal API

### الوصف
API داخلي من TikTok لجلب تفاصيل الفيديو مباشرة.

### الطلب
```http
GET https://api16-normal-c-useast1a.tiktokv.com/aweme/v1/feed/?aweme_id={video_id}
User-Agent: Mozilla/5.0 (Linux; Android 14; Pixel 8) ...
```

### الاستجابة
```json
{
    "aweme_list": [
        {
            "video": {
                "play_addr": {
                    "url_list": ["https://v16m-default.akamaized.net/..."]
                },
                "cover": {
                    "url_list": ["https://p16-sign-sg.tiktokcdn.com/..."]
                },
                "duration": 15000
            }
        }
    ]
}
```

### ملاحظات
- `duration` بالمللي ثانية (يجب القسمة على 1000)
- قد لا يعمل في بعض المناطق

### الملف المسؤول
`TikTokExtractor.kt` → `tryApiMethod()`

---

## ملخص الموثوقية

| API | الموثوقية | ملاحظات |
|-----|-----------|---------|
| Cobalt API v7 | ⭐⭐⭐ | قد يتوقف الخادم أحياناً |
| tikwm.com | ⭐⭐⭐⭐ | موثوق جداً لـ TikTok |
| Instagram oEmbed | ⭐⭐⭐⭐⭐ | رسمي ومستقر |
| Instagram GraphQL | ⭐⭐ | قد يحتاج تسجيل دخول |
| Instagram API v1 | ⭐⭐ | قد يحتاج تسجيل دخول |
| TikTok oEmbed | ⭐⭐⭐⭐ | رسمي، لكن لا يرجع رابط فيديو مباشر |
| TikTok Internal API | ⭐⭐ | غير مستقر |
| HTML Scraping | ⭐ | آخر خيار، يعتمد على بنية الصفحة |
