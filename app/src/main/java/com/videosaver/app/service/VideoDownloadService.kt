package com.videosaver.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.videosaver.app.MainActivity
import com.videosaver.app.R
import com.videosaver.app.VideoSaverApp
import com.videosaver.app.data.db.AppDatabase
import com.videosaver.app.data.model.DownloadItem
import com.videosaver.app.data.model.DownloadStatus
import com.videosaver.app.data.model.Platform
import com.videosaver.app.data.repository.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class VideoDownloadService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var repository: DownloadRepository

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_PLATFORM = "platform"
        private var notificationId = 1000
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            repository = DownloadRepository(db.downloadDao())
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloadId = intent?.getLongExtra(EXTRA_DOWNLOAD_ID, -1) ?: -1
        val videoUrl = intent?.getStringExtra(EXTRA_VIDEO_URL) ?: ""
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME) ?: ""
        val platform = intent?.getStringExtra(EXTRA_PLATFORM) ?: ""

        if (downloadId == -1L || videoUrl.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val currentNotifId = ++notificationId

        try {
            startForeground(currentNotifId, createNotification("جاري التحميل...", 0))
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                if (!::repository.isInitialized) {
                    val db = AppDatabase.getDatabase(applicationContext)
                    repository = DownloadRepository(db.downloadDao())
                }

                repository.updateStatus(downloadId, DownloadStatus.DOWNLOADING, 0)

                val requestBuilder = Request.Builder()
                    .url(videoUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")

                // Add platform-specific headers
                val isCobaltStream = videoUrl.contains("stuff.solutions/api/stream")
                val isTikWmUrl = videoUrl.contains("tikwm.com")
                val isTikWmCdn = (videoUrl.contains("tiktokcdn") || isTikWmUrl) && !isCobaltStream
                when (platform) {
                    Platform.INSTAGRAM -> {
                        requestBuilder.header("Referer", "https://www.instagram.com/")
                    }
                    Platform.TIKTOK -> {
                        if (isCobaltStream) {
                            requestBuilder.header("Accept", "*/*")
                        } else if (isTikWmUrl) {
                            requestBuilder.header("Accept", "*/*")
                        } else if (isTikWmCdn) {
                            requestBuilder.header("Accept", "*/*")
                            requestBuilder.header("Accept-Encoding", "identity")
                        } else {
                            requestBuilder.header("Referer", "https://www.tiktok.com/")
                            requestBuilder.header("Accept", "*/*")
                            requestBuilder.header("Accept-Encoding", "identity")
                        }
                    }
                    else -> {
                        requestBuilder.header("Accept", "*/*")
                    }
                }

                val request = requestBuilder.build()

                // Try download with retry (cobalt stream URLs can return 500)
                var response = try {
                    client.newCall(request).execute()
                } catch (e: Exception) {
                    repository.updateError(downloadId, "فشل الاتصال بالخادم: ${e.message}")
                    stopSelf()
                    return@launch
                }

                // Retry once if server error (500/502/503)
                if (response.code in 500..503) {
                    response.close()
                    try {
                        Thread.sleep(2000) // Wait 2 seconds before retry
                        response = client.newCall(request).execute()
                    } catch (e: Exception) {
                        repository.updateError(downloadId, "فشل الاتصال بالخادم: ${e.message}")
                        stopSelf()
                        return@launch
                    }
                }

                if (!response.isSuccessful && response.code != 206) {
                    repository.updateError(downloadId, "فشل التحميل: ${response.code}")
                    stopSelf()
                    return@launch
                }

                val body = response.body
                if (body == null) {
                    repository.updateError(downloadId, "لم يتم العثور على الملف")
                    stopSelf()
                    return@launch
                }

                val contentType = response.header("Content-Type", "") ?: ""
                // Reject non-video responses (error pages, text errors, JSON errors, audio-only)
                if (contentType.contains("text/") || contentType.contains("application/json") ||
                    contentType.contains("audio/mpeg") || contentType.contains("audio/mp3")) {
                    body.close()
                    repository.updateError(downloadId, "الخادم لم يرجع ملف فيديو صالح")
                    stopSelf()
                    return@launch
                }

                val totalBytes = body.contentLength()

                // Reject suspiciously small responses (likely error pages)
                if (totalBytes in 1..1023) {
                    body.close()
                    repository.updateError(downloadId, "الخادم لم يرجع ملف فيديو صالح")
                    stopSelf()
                    return@launch
                }

                repository.updateStatus(downloadId, DownloadStatus.SAVING, 50)

                // Step 1: Download to a temporary file first (guaranteed writable)
                val tempDir = File(applicationContext.cacheDir, "downloads")
                if (!tempDir.exists()) tempDir.mkdirs()
                val tempFile = File(tempDir, fileName)

                val downloadedBytes = try {
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        val inputStream = body.byteStream()

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                val progress = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                                scope.launch {
                                    try {
                                        repository.updateStatus(downloadId, DownloadStatus.DOWNLOADING, progress)
                                        updateNotification(currentNotifId, "جاري التحميل... $progress%", progress)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        output.flush()
                        output.fd.sync()
                        totalRead
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    repository.updateError(downloadId, "فشل في تحميل الملف: ${e.message}")
                    stopSelf()
                    return@launch
                }

                // Step 2: Verify temp file is a valid video (check size + MP4 magic bytes)
                if (!tempFile.exists() || tempFile.length() < 1024 || downloadedBytes < 1024) {
                    tempFile.delete()
                    repository.updateError(downloadId, "الملف المحمّل فارغ أو تالف ($downloadedBytes bytes)")
                    stopSelf()
                    return@launch
                }

                // Check file header to reject obviously non-video content (HTML error pages, etc.)
                val isLikelyVideo = try {
                    tempFile.inputStream().use { input ->
                        val header = ByteArray(12)
                        val read = input.read(header)
                        if (read < 8) false
                        else {
                            // Standard MP4: bytes 4-7 = "ftyp"
                            (header[4] == 0x66.toByte() && header[5] == 0x74.toByte() &&
                             header[6] == 0x79.toByte() && header[7] == 0x70.toByte()) ||
                            // MP4 moov or mdat atoms
                            (header[4] == 0x6D.toByte() && header[5] == 0x6F.toByte()) ||
                            (header[4] == 0x6D.toByte() && header[5] == 0x64.toByte()) ||
                            // WebM: starts with 0x1A45DFA3
                            (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte()) ||
                            // ID3 metadata header (TikTok wraps MP4 with ID3 tags): "ID3"
                            (header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()) ||
                            // MPEG-TS: starts with 0x47
                            (header[0] == 0x47.toByte()) ||
                            // FLV: starts with "FLV"
                            (header[0] == 0x46.toByte() && header[1] == 0x4C.toByte() && header[2] == 0x56.toByte()) ||
                            // If file is large enough (>100KB), trust it even if header is unknown
                            // (some CDNs prepend custom headers)
                            tempFile.length() > 100 * 1024
                        }
                    }
                } catch (_: Exception) { false }

                // Only reject if it looks like an HTML error page or text response
                if (!isLikelyVideo) {
                    val firstBytes = try {
                        tempFile.inputStream().use { stream ->
                            val bytes = ByteArray(16)
                            stream.read(bytes)
                            bytes.joinToString("") { b -> "%02x".format(b) }
                        }
                    } catch (_: Exception) { "unknown" }
                    // Check if it's HTML/text (starts with '<' or '{')
                    val isTextContent = try {
                        tempFile.inputStream().use { stream ->
                            val first = stream.read()
                            first == '<'.code || first == '{'.code || first == 'H'.code
                        }
                    } catch (_: Exception) { false }

                    if (isTextContent || tempFile.length() < 10 * 1024) {
                        tempFile.delete()
                        repository.updateError(downloadId, "الملف ليس فيديو صالح (magic: $firstBytes)")
                        stopSelf()
                        return@launch
                    }
                    // If not text and file is reasonably large, proceed anyway
                }

                // Step 3: Save a copy in app's external files for reliable sharing (before consuming temp file)
                try {
                    val shareDir = File(applicationContext.getExternalFilesDir(null), "videos")
                    if (!shareDir.exists()) shareDir.mkdirs()
                    // Clean old share files to save space
                    shareDir.listFiles()?.forEach { it.delete() }
                    val shareFile = File(shareDir, fileName)
                    tempFile.copyTo(shareFile, overwrite = true)
                } catch (_: Exception) {
                    // Non-critical - sharing will fall back to MediaStore URI
                }

                // Step 4: Copy verified temp file to gallery (MediaStore or legacy)
                val savedPath = try {
                    tempFile.inputStream().use { stream ->
                        saveToGallery(fileName, stream, tempFile.length()) { _ -> }
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    repository.updateError(downloadId, "فشل في حفظ الملف: ${e.message}")
                    stopSelf()
                    return@launch
                }

                // Clean up temp file
                tempFile.delete()

                if (savedPath != null) {
                    repository.updateCompleted(downloadId, savedPath)
                    try {
                        showCompletionNotification(currentNotifId, fileName)
                    } catch (_: Exception) {
                        // Ignore notification failures
                    }
                } else {
                    repository.updateError(downloadId, "فشل في حفظ الملف في المعرض")
                }

            } catch (e: Exception) {
                try {
                    if (::repository.isInitialized) {
                        repository.updateError(downloadId, "خطأ: ${e.message}")
                    }
                } catch (_: Exception) {
                    // Cannot update error status
                }
            } finally {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun saveToGallery(
        fileName: String,
        inputStream: java.io.InputStream,
        totalBytes: Long,
        onProgress: (Int) -> Unit
    ): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(fileName, inputStream, totalBytes, onProgress)
        } else {
            saveWithLegacy(fileName, inputStream, totalBytes, onProgress)
        }
    }

    private fun saveWithMediaStore(
        fileName: String,
        inputStream: java.io.InputStream,
        totalBytes: Long,
        onProgress: (Int) -> Unit
    ): String? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/${VideoSaverApp.FOLDER_NAME}")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        return try {
            val outputStream = contentResolver.openOutputStream(uri)
            if (outputStream == null) {
                contentResolver.delete(uri, null, null)
                return null
            }
            var totalRead = 0L
            outputStream.use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalBytes > 0) {
                        val progress = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                        onProgress(progress)
                    }
                }
                // Explicit flush to ensure all data is written to disk
                output.flush()
            }

            // Verify file is not empty based on bytes written
            if (totalRead == 0L) {
                contentResolver.delete(uri, null, null)
                return null
            }

            // Mark file as complete (not pending)
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)

            // Post-write verification: re-read the file to confirm data was persisted
            val verifiedSize = try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            } catch (_: Exception) { 0L }

            if (verifiedSize < 1024) {
                // File was not properly saved - delete and fail
                contentResolver.delete(uri, null, null)
                return null
            }

            uri.toString()
        } catch (e: Exception) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
                // Ignore cleanup failure
            }
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun saveWithLegacy(
        fileName: String,
        inputStream: java.io.InputStream,
        totalBytes: Long,
        onProgress: (Int) -> Unit
    ): String? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            VideoSaverApp.FOLDER_NAME
        )
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, fileName)

        return try {
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalBytes > 0) {
                        val progress = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                        onProgress(progress)
                    }
                }
                output.flush()
                output.fd.sync()
            }

            // Verify saved file is valid
            if (!file.exists() || file.length() < 1024) {
                file.delete()
                return null
            }

            // Notify media scanner
            try {
                val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                intent.data = Uri.fromFile(file)
                sendBroadcast(intent)
            } catch (_: Exception) {
                // Ignore scanner notification failure
            }

            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun createNotification(text: String, progress: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, VideoSaverApp.CHANNEL_ID)
            .setContentTitle("Video Saver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(id: Int, text: String, progress: Int) {
        try {
            val notification = createNotification(text, progress)
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(id, notification)
        } catch (_: Exception) {
            // Ignore notification update failures
        }
    }

    private fun showCompletionNotification(id: Int, fileName: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, VideoSaverApp.CHANNEL_ID)
            .setContentTitle("تم التحميل بنجاح")
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(id + 1000, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
