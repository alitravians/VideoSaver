package com.videosaver.app.service

import com.videosaver.app.data.model.ContentType
import com.videosaver.app.data.model.Platform
import com.videosaver.app.data.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TikTokExtractor : VideoExtractor {
    override val platform = Platform.TIKTOK

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Backup watermarked URL - used only if cobalt and other sources fail
    private var lastWatermarkedBackup: VideoInfo? = null

    // Cobalt v7 instances (no auth required, use /api/json endpoint)
    private val cobaltInstances = listOf(
        "https://downloadapi.stuff.solutions/api/json"
    )

    override suspend fun extract(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            // Clean URL first
            val cleanedUrl = cleanTikTokUrl(url)

            // Reset watermarked backup
            lastWatermarkedBackup = null

            // Step 1: Try tikwm.com API FIRST (most reliable, returns thumbnails + working CDN URLs)
            // If tikwm only has watermarked URL, it saves to lastWatermarkedBackup and returns null
            val (tikwmResult, isSlideshow) = tryTikWmApi(cleanedUrl)
            var videoInfo = tikwmResult

            // Step 2: If tikwm didn't return no-watermark, try cobalt API (always no-watermark)
            if (videoInfo == null) {
                videoInfo = tryCobaltApiWithVerification(cleanedUrl)
            }

            // Step 3: If cobalt failed too, try scraping fallbacks
            if (videoInfo == null) {
                val resolvedUrl = resolveShortUrl(cleanedUrl)
                videoInfo = tryOEmbedMethod(resolvedUrl)
                    ?: tryHtmlScraping(resolvedUrl)
                    ?: tryApiMethod(resolvedUrl)
            }

            // Step 4: Last resort - use tikwm watermarked URL if everything else failed
            if (videoInfo == null && lastWatermarkedBackup != null) {
                videoInfo = lastWatermarkedBackup
            }

            // Step 5: If we have video but no thumbnail, try to fetch thumbnail separately
            if (videoInfo != null && videoInfo.thumbnailUrl.isEmpty()) {
                val thumbnail = tryGetThumbnail(cleanedUrl)
                if (thumbnail.isNotEmpty()) {
                    videoInfo = videoInfo.copy(thumbnailUrl = thumbnail)
                }
            }

            if (videoInfo != null) {
                Result.success(videoInfo)
            } else if (isSlideshow) {
                Result.failure(Exception("هذا المنشور عبارة عن صور وليس فيديو - لا يمكن تحميله كفيديو"))
            } else {
                Result.failure(Exception("تعذر استخراج الفيديو من TikTok. تأكد أن الرابط صحيح."))            }
        } catch (e: Exception) {
            Result.failure(Exception("خطأ في الاتصال: ${e.message}"))
        }
    }

    /**
     * Try cobalt API and verify the returned stream URL actually works.
     * Cobalt sometimes returns 200 with a stream URL, but the stream proxy fails with 500.
     */
    private fun tryCobaltApiWithVerification(url: String): VideoInfo? {
        for (instance in cobaltInstances) {
            try {
                val jsonBody = JSONObject().apply {
                    put("url", url)
                    put("videoQuality", "720")
                    put("tiktokFullAudio", true)
                    put("isNoTTWatermark", true)
                }

                val requestBody = jsonBody.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(instance)
                    .post(requestBody)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "VideoSaver/1.0")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) continue

                val body = response.body?.string() ?: continue
                val json = JSONObject(body)
                val status = json.optString("status", "")

                when (status) {
                    "tunnel", "redirect", "stream" -> {
                        val videoUrl = json.optString("url", "")
                        if (videoUrl.isNotEmpty()) {
                            // For stream URLs, verify with a small GET request
                            // (HEAD doesn't work on cobalt streams)
                            if (status == "stream" && !verifyStreamUrl(videoUrl)) {
                                continue // Stream proxy broken, skip
                            }
                            return VideoInfo(
                                videoUrl = videoUrl,
                                thumbnailUrl = "",
                                platform = Platform.TIKTOK,
                                contentType = ContentType.VIDEO
                            )
                        }
                    }
                    "picker" -> {
                        val picker = json.optJSONArray("picker")
                        if (picker != null && picker.length() > 0) {
                            for (i in 0 until picker.length()) {
                                val item = picker.getJSONObject(i)
                                if (item.optString("type") == "video") {
                                    val videoUrl = item.optString("url", "")
                                    val thumb = item.optString("thumb", "")
                                    if (videoUrl.isNotEmpty()) {
                                        return VideoInfo(
                                            videoUrl = videoUrl,
                                            thumbnailUrl = thumb,
                                            platform = Platform.TIKTOK,
                                            contentType = ContentType.VIDEO
                                        )
                                    }
                                }
                            }
                            val firstItem = picker.getJSONObject(0)
                            val videoUrl = firstItem.optString("url", "")
                            if (videoUrl.isNotEmpty()) {
                                return VideoInfo(
                                    videoUrl = videoUrl,
                                    thumbnailUrl = firstItem.optString("thumb", ""),
                                    platform = Platform.TIKTOK,
                                    contentType = ContentType.VIDEO
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    /**
     * Verify a cobalt stream URL works by doing a small GET request.
     * Returns true if the stream is working, false if it returns 500.
     */
    private fun verifyStreamUrl(url: String): Boolean {
        return try {
            val verifyClient = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", mobileUserAgent)
                .header("Range", "bytes=0-1023")
                .build()

            val response = verifyClient.newCall(request).execute()
            val code = response.code
            response.close()
            code in 200..299 || code == 206
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Verify a video URL returns actual video bytes (not JPEG/image/HTML).
     */
    private fun verifyVideoUrl(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", mobileUserAgent)
                .header("Range", "bytes=0-1023")
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            val contentType = response.header("Content-Type", "") ?: ""
            val headerBytes = response.body?.bytes()?.take(8) ?: emptyList()
            response.close()

            // Fail if HTTP error
            if (code !in 200..299 && code != 206) return false
            // Fail if HTML/text
            if (contentType.contains("text/html") || contentType.contains("text/plain")) return false
            // Fail if audio-only
            if (contentType.contains("audio/mpeg") || contentType.contains("audio/mp3")) return false
            // Fail if image Content-Type
            if (contentType.contains("image/")) return false

            // Fail if actual bytes are JPEG/PNG/GIF
            if (headerBytes.size >= 3) {
                val isJpeg = headerBytes[0] == 0xFF.toByte() && headerBytes[1] == 0xD8.toByte() && headerBytes[2] == 0xFF.toByte()
                val isPng = headerBytes.size >= 4 && headerBytes[0] == 0x89.toByte() && headerBytes[1] == 0x50.toByte() && headerBytes[2] == 0x4E.toByte() && headerBytes[3] == 0x47.toByte()
                val isGif = headerBytes[0] == 0x47.toByte() && headerBytes[1] == 0x49.toByte() && headerBytes[2] == 0x46.toByte()
                if (isJpeg || isPng || isGif) return false
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Try tikwm.com API - reliable free TikTok video extraction.
     * Returns Pair(VideoInfo?, isSlideshow) - isSlideshow true if post is photos not video.
     */
    private fun tryTikWmApi(url: String): Pair<VideoInfo?, Boolean> {
        return try {
            val requestBody = "url=${java.net.URLEncoder.encode(url, "UTF-8")}&hd=1"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())

            val request = Request.Builder()
                .url("https://www.tikwm.com/api/")
                .post(requestBody)
                .header("User-Agent", mobileUserAgent)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return Pair(null, false)

            val body = response.body?.string() ?: return Pair(null, false)
            val json = JSONObject(body)

            if (json.optInt("code", -1) != 0) return Pair(null, false)

            val data = json.optJSONObject("data") ?: return Pair(null, false)

            // Detect photo/slideshow posts (duration=0, size=0, images present)
            // tikwm returns background music audio as "play" URL for these, not video
            val duration = data.optInt("duration", 0)
            val size = data.optInt("size", 0)
            val hdSize = data.optInt("hd_size", 0)
            val images = data.optJSONArray("images")
            val hasImages = images != null && images.length() > 0

            if (duration == 0 && size == 0 && hdSize == 0 && hasImages) {
                // This is a photo/slideshow post, not a video
                return Pair(null, true)
            }

            // Collect no-watermark URLs and watermarked URL separately
            val noWatermarkUrls = mutableListOf<String>()
            data.optString("hdplay", "").let { if (it.isNotEmpty()) noWatermarkUrls.add(it) }
            data.optString("play", "").let { if (it.isNotEmpty()) noWatermarkUrls.add(it) }
            val wmUrl = data.optString("wmplay", "")

            if (noWatermarkUrls.isEmpty() && wmUrl.isEmpty()) return Pair(null, false)

            // Ensure full URLs
            val fullNoWmUrls = noWatermarkUrls.map { u ->
                if (!u.startsWith("http")) "https://www.tikwm.com$u" else u
            }
            val fullWmUrl = if (wmUrl.isNotEmpty() && !wmUrl.startsWith("http")) "https://www.tikwm.com$wmUrl" else wmUrl

            // Try no-watermark URLs first
            var videoUrl = ""
            var isWatermarked = false
            for (candidateUrl in fullNoWmUrls) {
                if (verifyVideoUrl(candidateUrl)) {
                    videoUrl = candidateUrl
                    break
                }
            }

            // If no-watermark URLs all failed (JPEG/image), save wmplay as fallback
            // but return null so the main extract() tries cobalt for watermark-free first
            if (videoUrl.isEmpty()) {
                if (fullWmUrl.isNotEmpty() && verifyVideoUrl(fullWmUrl)) {
                    videoUrl = fullWmUrl
                    isWatermarked = true
                } else {
                    return Pair(null, false)
                }
            }

            val thumbnailUrl = data.optString("cover", "").ifEmpty {
                data.optString("origin_cover", "")
            }
            val title = data.optString("title", "")

            // If watermarked, return with isSlideshow=false but mark as watermarked
            // by using a special third value in the return
            val info = VideoInfo(
                videoUrl = videoUrl,
                thumbnailUrl = thumbnailUrl,
                title = title,
                platform = Platform.TIKTOK,
                contentType = ContentType.VIDEO,
                duration = if (duration > 0) formatDuration(duration) else ""
            )
            // Return isSlideshow=false, but we track watermark status separately
            if (isWatermarked) {
                // Return null so extract() tries cobalt for watermark-free,
                // but save the watermarked URL as backup
                lastWatermarkedBackup = info
                return Pair(null, false)
            }
            Pair(info, false)
        } catch (_: Exception) {
            Pair(null, false)
        }
    }

    /**
     * Try to fetch thumbnail from oEmbed when other methods don't return one.
     */
    private fun tryGetThumbnail(url: String): String {
        return try {
            val oembedUrl = "https://www.tiktok.com/oembed?url=$url"
            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", desktopUserAgent)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return ""

            val body = response.body?.string() ?: return ""
            val json = JSONObject(body)
            json.optString("thumbnail_url", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun cleanTikTokUrl(url: String): String {
        var cleaned = url.trim()
        // Remove tracking parameters
        try {
            val uri = java.net.URI(cleaned)
            val path = uri.path.trimEnd('/')
            val host = uri.host ?: return cleaned
            cleaned = "https://$host$path"
        } catch (_: Exception) {
            val queryIndex = cleaned.indexOf('?')
            if (queryIndex > 0) {
                cleaned = cleaned.substring(0, queryIndex)
            }
        }
        return cleaned
    }

    private fun resolveShortUrl(url: String): String {
        if (!url.contains("vm.tiktok.com") && !url.contains("vt.tiktok.com") && !url.contains("/t/")) {
            return url
        }

        return try {
            val noRedirectClient = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", mobileUserAgent)
                .build()

            val response = noRedirectClient.newCall(request).execute()
            response.header("Location") ?: url
        } catch (_: Exception) {
            url
        }
    }

    private fun tryOEmbedMethod(url: String): VideoInfo? {
        return try {
            val oembedUrl = "https://www.tiktok.com/oembed?url=$url"
            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", desktopUserAgent)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            val title = json.optString("title", "")
            val thumbnailUrl = json.optString("thumbnail_url", "")

            val videoInfo = tryHtmlScraping(url)
            videoInfo?.copy(
                thumbnailUrl = thumbnailUrl.ifEmpty { videoInfo.thumbnailUrl },
                title = title.ifEmpty { videoInfo.title }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun tryHtmlScraping(url: String): VideoInfo? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", desktopUserAgent)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "identity")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val html = response.body?.string() ?: return null

            var videoUrl: String? = null
            var thumbnailUrl = ""
            var duration = ""

            val sigiPattern = Regex("""<script\s+id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val sigiMatch = sigiPattern.find(html)
            if (sigiMatch != null) {
                try {
                    val jsonStr = sigiMatch.groupValues[1]
                    val json = JSONObject(jsonStr)
                    val defaultScope = json.optJSONObject("__DEFAULT_SCOPE__")
                    val videoDetail = defaultScope?.optJSONObject("webapp.video-detail")
                    val itemInfo = videoDetail?.optJSONObject("itemInfo")
                    val itemStruct = itemInfo?.optJSONObject("itemStruct")
                    val video = itemStruct?.optJSONObject("video")

                    if (video != null) {
                        videoUrl = video.optString("downloadAddr", "").ifEmpty {
                            video.optString("playAddr", "")
                        }
                        thumbnailUrl = video.optString("cover", "")
                        val dur = video.optInt("duration", 0)
                        if (dur > 0) duration = formatDuration(dur)
                    }
                } catch (_: Exception) {
                    // Continue
                }
            }

            if (videoUrl.isNullOrEmpty()) {
                val sigiState = Regex("""<script\s+id="SIGI_STATE"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                val stateMatch = sigiState.find(html)
                if (stateMatch != null) {
                    try {
                        val json = JSONObject(stateMatch.groupValues[1])
                        val itemModule = json.optJSONObject("ItemModule")
                        if (itemModule != null) {
                            val keys = itemModule.keys()
                            if (keys.hasNext()) {
                                val firstItem = itemModule.getJSONObject(keys.next())
                                val video = firstItem.optJSONObject("video")
                                if (video != null) {
                                    videoUrl = video.optString("downloadAddr", "").ifEmpty {
                                        video.optString("playAddr", "")
                                    }
                                    thumbnailUrl = video.optString("cover", "")
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Continue
                    }
                }
            }

            if (videoUrl.isNullOrEmpty()) {
                val downloadPattern = Regex(""""downloadAddr"\s*:\s*"([^"]+)"""")
                val downloadMatch = downloadPattern.find(html)
                if (downloadMatch != null) {
                    videoUrl = downloadMatch.groupValues[1]
                        .replace("\\u0026", "&")
                        .replace("\\u002F", "/")
                        .replace("\\/", "/")
                }
            }

            if (videoUrl.isNullOrEmpty()) {
                val playPattern = Regex(""""playAddr"\s*:\s*"([^"]+)"""")
                val playMatch = playPattern.find(html)
                if (playMatch != null) {
                    videoUrl = playMatch.groupValues[1]
                        .replace("\\u0026", "&")
                        .replace("\\u002F", "/")
                        .replace("\\/", "/")
                }
            }

            if (videoUrl.isNullOrEmpty()) {
                val ogPattern = Regex("""<meta\s+property="og:video(?::url)?"\s+content="([^"]+)"""")
                val ogMatch = ogPattern.find(html)
                if (ogMatch != null) {
                    videoUrl = ogMatch.groupValues[1].replace("&amp;", "&")
                }
            }

            if (thumbnailUrl.isEmpty()) {
                val ogImgPattern = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""")
                val imgMatch = ogImgPattern.find(html)
                if (imgMatch != null) {
                    thumbnailUrl = imgMatch.groupValues[1].replace("&amp;", "&")
                }
            }

            if (!videoUrl.isNullOrEmpty()) {
                VideoInfo(
                    videoUrl = videoUrl,
                    thumbnailUrl = thumbnailUrl,
                    platform = Platform.TIKTOK,
                    contentType = ContentType.VIDEO,
                    duration = duration
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun tryApiMethod(url: String): VideoInfo? {
        return try {
            val videoId = extractVideoId(url) ?: return null
            val apiUrl = "https://api16-normal-c-useast1a.tiktokv.com/aweme/v1/feed/?aweme_id=$videoId"

            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", mobileUserAgent)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val awemeList = json.optJSONArray("aweme_list") ?: return null

            if (awemeList.length() == 0) return null

            val aweme = awemeList.getJSONObject(0)
            val video = aweme.optJSONObject("video") ?: return null

            val playAddr = video.optJSONObject("play_addr")
            val videoUrl = playAddr?.optJSONArray("url_list")?.optString(0) ?: return null

            val cover = video.optJSONObject("cover")
            val thumbnailUrl = cover?.optJSONArray("url_list")?.optString(0) ?: ""

            val dur = video.optInt("duration", 0)

            VideoInfo(
                videoUrl = videoUrl,
                thumbnailUrl = thumbnailUrl,
                platform = Platform.TIKTOK,
                contentType = ContentType.VIDEO,
                duration = if (dur > 0) formatDuration(dur / 1000) else ""
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractVideoId(url: String): String? {
        val pattern = Regex("""/video/(\d+)""")
        val match = pattern.find(url)
        return match?.groupValues?.get(1)
    }

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins > 0) "${mins}:${secs.toString().padStart(2, '0')}"
        else "0:${secs.toString().padStart(2, '0')}"
    }
}
