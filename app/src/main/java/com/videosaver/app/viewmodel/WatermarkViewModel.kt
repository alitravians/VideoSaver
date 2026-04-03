package com.videosaver.app.viewmodel

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videosaver.app.VideoSaverApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WatermarkUiState(
    val selectedVideoUri: Uri? = null,
    val videoFileName: String = "",
    val videoDuration: String = "",
    val videoResolution: String = "",
    val isProcessing: Boolean = false,
    val progress: Int = 0,
    val statusMessage: String = "",
    val statusType: WatermarkStatusType = WatermarkStatusType.IDLE,
    val processedFilePath: String = "",
    val processedLocalPath: String = ""
)

enum class WatermarkStatusType {
    IDLE, PROCESSING, SUCCESS, ERROR
}

class WatermarkViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WatermarkUiState())
    val uiState: StateFlow<WatermarkUiState> = _uiState.asStateFlow()

    @Volatile
    private var isCancelled = false

    companion object {
        private const val TAG = "WatermarkVM"
        private const val TIMEOUT_US = 10_000L
    }

    /** Watermark region definition */
    private data class WmRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun onVideoSelected(uri: Uri) {
        val context = getApplication<Application>()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)

                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L

                val width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: 0

                val height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: 0

                retriever.release()

                val durationSec = durationMs / 1000
                val minutes = durationSec / 60
                val seconds = durationSec % 60
                val durationStr = String.format("%d:%02d", minutes, seconds)

                var fileName = "video"
                try {
                    context.contentResolver.query(
                        uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            fileName = cursor.getString(0) ?: "video"
                        }
                    }
                } catch (_: Exception) {}

                _uiState.value = _uiState.value.copy(
                    selectedVideoUri = uri,
                    videoFileName = fileName,
                    videoDuration = durationStr,
                    videoResolution = if (width > 0 && height > 0) "${width}x${height}" else "",
                    statusType = WatermarkStatusType.IDLE,
                    statusMessage = "",
                    processedFilePath = "",
                    processedLocalPath = ""
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "\u0641\u0634\u0644 \u0642\u0631\u0627\u0621\u0629 \u0645\u0639\u0644\u0648\u0645\u0627\u062a \u0627\u0644\u0641\u064a\u062f\u064a\u0648: ${e.message}",
                    statusType = WatermarkStatusType.ERROR
                )
            }
        }
    }

    fun startWatermarkRemoval() {
        val uri = _uiState.value.selectedVideoUri ?: return
        val context = getApplication<Application>()
        isCancelled = false

        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            progress = 0,
            statusMessage = "\u062c\u0627\u0631\u064a \u062a\u062c\u0647\u064a\u0632 \u0627\u0644\u0641\u064a\u062f\u064a\u0648...",
            statusType = WatermarkStatusType.PROCESSING
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(context.cacheDir, "watermark")
                if (!tempDir.exists()) tempDir.mkdirs()
                tempDir.listFiles()?.forEach { it.delete() }

                val inputFile = File(tempDir, "input_source.mp4")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(inputFile).use { output ->
                        input.copyTo(output, 8192)
                        output.flush()
                        output.fd.sync()
                    }
                } ?: throw Exception("\u0644\u0645 \u064a\u062a\u0645 \u0641\u062a\u062d \u0645\u0644\u0641 \u0627\u0644\u0641\u064a\u062f\u064a\u0648")

                _uiState.value = _uiState.value.copy(
                    progress = 5,
                    statusMessage = "\u062c\u0627\u0631\u064a \u062a\u062d\u0644\u064a\u0644 \u0627\u0644\u0641\u064a\u062f\u064a\u0648..."
                )

                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(inputFile.absolutePath)

                val videoWidth = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: 1080

                val videoHeight = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: 1920

                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L

                val rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0

                retriever.release()

                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                val timestamp = dateFormat.format(Date())
                val outputFileName = "NoWM_${timestamp}_${(100..999).random()}.mp4"
                val outputFile = File(tempDir, outputFileName)

                processVideoWithImageApi(
                    inputFile, outputFile,
                    videoWidth, videoHeight, durationMs, rotation
                )

                inputFile.delete()

                if (isCancelled) {
                    outputFile.delete()
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = "\u062a\u0645 \u0625\u0644\u063a\u0627\u0621 \u0627\u0644\u0639\u0645\u0644\u064a\u0629",
                        statusType = WatermarkStatusType.ERROR,
                        progress = 0
                    )
                    return@launch
                }

                if (!outputFile.exists() || outputFile.length() < 1024) {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = "\u0641\u0634\u0644 \u0645\u0639\u0627\u0644\u062c\u0629 \u0627\u0644\u0641\u064a\u062f\u064a\u0648 - \u0627\u0644\u0645\u0644\u0641 \u0627\u0644\u0646\u0627\u062a\u062c \u0641\u0627\u0631\u063a",
                        statusType = WatermarkStatusType.ERROR,
                        progress = 0
                    )
                    return@launch
                }

                handleProcessingSuccess(outputFile, outputFileName)

            } catch (e: Exception) {
                Log.e(TAG, "Watermark removal failed", e)
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = "\u062e\u0637\u0623 \u0641\u064a \u0627\u0644\u0645\u0639\u0627\u0644\u062c\u0629: ${e.message}",
                    statusType = WatermarkStatusType.ERROR,
                    progress = 0
                )
            }
        }
    }

    /**
     * Process video using Image API for proper YUV plane handling.
     * Decodes each frame, converts to Bitmap for watermark painting,
     * then re-encodes. This approach is device-independent since it
     * works with Bitmap (RGB) for modifications.
     */
    private fun processVideoWithImageApi(
        inputFile: File,
        outputFile: File,
        width: Int,
        height: Int,
        durationMs: Long,
        rotation: Int
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        var videoTrackIdx = -1
        var audioTrackIdx = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/") && videoTrackIdx == -1) {
                videoTrackIdx = i
                videoFormat = format
            } else if (mime.startsWith("audio/") && audioTrackIdx == -1) {
                audioTrackIdx = i
                audioFormat = format
            }
        }

        if (videoTrackIdx == -1 || videoFormat == null) {
            throw Exception("\u0644\u0645 \u064a\u062a\u0645 \u0627\u0644\u0639\u062b\u0648\u0631 \u0639\u0644\u0649 \u0645\u0633\u0627\u0631 \u0641\u064a\u062f\u064a\u0648")
        }

        val videoMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"

        val frameRate = try {
            videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
        } catch (_: Exception) { 30 }

        val bitRate = try {
            videoFormat.getInteger(MediaFormat.KEY_BIT_RATE)
        } catch (_: Exception) { 8_000_000 }

        // Calculate watermark regions (relative to 1080x1920 TikTok base)
        val scaleX = width.toFloat() / 1080f
        val scaleY = height.toFloat() / 1920f

        val watermarkRegions = listOf(
            // TikTok logo (bottom-right)
            WmRegion(
                (width - (200 * scaleX)).toInt().coerceAtLeast(0),
                (height - (220 * scaleY)).toInt().coerceAtLeast(0),
                width,
                height
            ),
            // Username text (bottom-left)
            WmRegion(
                0,
                (height - (240 * scaleY)).toInt().coerceAtLeast(0),
                (500 * scaleX).toInt().coerceAtMost(width),
                (height - (140 * scaleY)).toInt().coerceAtMost(height)
            ),
            // Small TikTok watermark text at top-left
            WmRegion(
                0,
                0,
                (160 * scaleX).toInt().coerceAtMost(width),
                (60 * scaleY).toInt().coerceAtMost(height)
            )
        )

        _uiState.value = _uiState.value.copy(
            progress = 10,
            statusMessage = "\u062c\u0627\u0631\u064a \u0625\u0632\u0627\u0644\u0629 \u0627\u0644\u0639\u0644\u0627\u0645\u0629 \u0627\u0644\u0645\u0627\u0626\u064a\u0629..."
        )

        // Setup decoder in byte-buffer mode
        extractor.selectTrack(videoTrackIdx)
        val decoder = MediaCodec.createDecoderByType(videoMime)
        decoder.configure(videoFormat, null, null, 0)
        decoder.start()

        // Setup encoder with COLOR_FormatYUV420Flexible
        val encoderFormat = MediaFormat.createVideoFormat("video/avc", width, height)
        encoderFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        // Setup muxer
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) {
            muxer.setOrientationHint(rotation)
        }

        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()

        val totalFrames = ((durationMs / 1000.0) * frameRate).toLong().coerceAtLeast(1)
        var processedFrames = 0L
        var inputDone = false
        var decoderDone = false

        // Paint for watermark cover
        val coverPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        try {
            while (!decoderDone && !isCancelled) {
                // Feed decoder from extractor
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inIdx, 0, sampleSize,
                                extractor.sampleTime, extractor.sampleFlags
                            )
                            extractor.advance()
                        }
                    }
                }

                // Drain decoder output
                val decOutIdx = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                if (decOutIdx >= 0) {
                    val eos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    if (eos) {
                        // Signal EOS to encoder
                        var eosQueued = false
                        for (attempt in 0..50) {
                            val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encInIdx >= 0) {
                                encoder.queueInputBuffer(
                                    encInIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                eosQueued = true
                                break
                            }
                            // Drain encoder to make room
                            drainEncoderOutput(encoder, encInfo, muxer,
                                muxerVideoTrack, muxerStarted, audioFormat).let {
                                muxerVideoTrack = it.first
                                muxerStarted = it.second
                                muxerAudioTrack = it.third
                            }
                        }
                        decoder.releaseOutputBuffer(decOutIdx, false)
                        decoderDone = true
                        if (!eosQueued) {
                            Log.w(TAG, "Could not queue EOS to encoder")
                        }
                    } else if (decInfo.size > 0) {
                        // Try Image API first (proper plane handling)
                        val decImage = try {
                            decoder.getOutputImage(decOutIdx)
                        } catch (_: Exception) { null }

                        if (decImage != null) {
                            // Convert decoded Image (YUV) → Bitmap (RGB)
                            val bitmap = yuvImageToBitmap(decImage, width, height)
                            decImage.close()
                            decoder.releaseOutputBuffer(decOutIdx, false)

                            if (bitmap != null) {
                                // Paint over watermark regions on Bitmap
                                val mutableBmp = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                bitmap.recycle()
                                paintOverWatermarks(mutableBmp, watermarkRegions, coverPaint)

                                // Convert Bitmap back to NV21 and feed to encoder
                                val nv21 = bitmapToNv21(mutableBmp, width, height)
                                mutableBmp.recycle()

                                feedDataToEncoder(
                                    encoder, nv21, decInfo.presentationTimeUs,
                                    encInfo, muxer, muxerVideoTrack, muxerStarted, audioFormat
                                ).let {
                                    muxerVideoTrack = it.first
                                    muxerStarted = it.second
                                    muxerAudioTrack = it.third
                                }
                            } else {
                                decoder.releaseOutputBuffer(decOutIdx, false)
                            }
                        } else {
                            // Fallback: use raw buffer with format detection
                            val decBuf = decoder.getOutputBuffer(decOutIdx)
                            if (decBuf != null) {
                                val data = ByteArray(decInfo.size)
                                decBuf.position(decInfo.offset)
                                decBuf.get(data)
                                decoder.releaseOutputBuffer(decOutIdx, false)

                                // Get actual output format for stride info
                                val outFmt = decoder.outputFormat
                                val stride = try {
                                    outFmt.getInteger(MediaFormat.KEY_STRIDE)
                                } catch (_: Exception) { width }
                                val sliceHeight = try {
                                    outFmt.getInteger(MediaFormat.KEY_SLICE_HEIGHT)
                                } catch (_: Exception) { height }

                                // Convert YUV buffer to Bitmap using YuvImage
                                val bmp = yuvBufferToBitmap(data, stride, sliceHeight, width, height)
                                if (bmp != null) {
                                    val mutableBmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
                                    bmp.recycle()
                                    paintOverWatermarks(mutableBmp, watermarkRegions, coverPaint)
                                    val nv21 = bitmapToNv21(mutableBmp, width, height)
                                    mutableBmp.recycle()

                                    feedDataToEncoder(
                                        encoder, nv21, decInfo.presentationTimeUs,
                                        encInfo, muxer, muxerVideoTrack, muxerStarted, audioFormat
                                    ).let {
                                        muxerVideoTrack = it.first
                                        muxerStarted = it.second
                                        muxerAudioTrack = it.third
                                    }
                                } else {
                                    // Last resort: pass through unmodified
                                    feedDataToEncoder(
                                        encoder, data, decInfo.presentationTimeUs,
                                        encInfo, muxer, muxerVideoTrack, muxerStarted, audioFormat
                                    ).let {
                                        muxerVideoTrack = it.first
                                        muxerStarted = it.second
                                        muxerAudioTrack = it.third
                                    }
                                }
                            } else {
                                decoder.releaseOutputBuffer(decOutIdx, false)
                            }
                        }

                        processedFrames++
                        val progress = ((processedFrames.toFloat() / totalFrames) * 80 + 10)
                            .toInt().coerceIn(10, 90)
                        _uiState.value = _uiState.value.copy(
                            progress = progress,
                            statusMessage = "\u062c\u0627\u0631\u064a \u0625\u0632\u0627\u0644\u0629 \u0627\u0644\u0639\u0644\u0627\u0645\u0629 \u0627\u0644\u0645\u0627\u0626\u064a\u0629... $progress%"
                        )
                    } else {
                        decoder.releaseOutputBuffer(decOutIdx, false)
                    }
                }

                // Drain encoder
                drainEncoderOutput(encoder, encInfo, muxer,
                    muxerVideoTrack, muxerStarted, audioFormat).let {
                    muxerVideoTrack = it.first
                    muxerStarted = it.second
                    muxerAudioTrack = it.third
                }
            }

            // Final encoder drain
            if (!isCancelled) {
                var encDone = false
                var drainAttempts = 0
                while (!encDone && drainAttempts < 500) {
                    val idx = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                                if (audioFormat != null) {
                                    muxerAudioTrack = muxer.addTrack(audioFormat)
                                }
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        idx >= 0 -> {
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                encoder.releaseOutputBuffer(idx, false)
                                drainAttempts++
                                continue
                            }
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encDone = true
                            }
                            val buf = encoder.getOutputBuffer(idx)
                            if (buf != null && encInfo.size > 0 && muxerStarted) {
                                muxer.writeSampleData(muxerVideoTrack, buf, encInfo)
                            }
                            encoder.releaseOutputBuffer(idx, false)
                        }
                        else -> {
                            drainAttempts++
                            if (drainAttempts > 100) encDone = true
                        }
                    }
                    drainAttempts++
                }
            }

            // Copy audio track separately
            if (!isCancelled && audioTrackIdx != -1 && audioFormat != null && muxerStarted) {
                _uiState.value = _uiState.value.copy(
                    progress = 92,
                    statusMessage = "\u062c\u0627\u0631\u064a \u0646\u0633\u062e \u0627\u0644\u0635\u0648\u062a..."
                )

                if (muxerAudioTrack == -1) {
                    // Audio track wasn't added yet - need to stop and restart muxer
                    // This shouldn't happen, but handle gracefully
                    Log.w(TAG, "Audio track not added to muxer")
                } else {
                    val audioExtractor = MediaExtractor()
                    audioExtractor.setDataSource(inputFile.absolutePath)
                    audioExtractor.selectTrack(audioTrackIdx)

                    val audioBuf = ByteBuffer.allocate(512 * 1024)
                    val audioInfo = MediaCodec.BufferInfo()
                    while (!isCancelled) {
                        val size = audioExtractor.readSampleData(audioBuf, 0)
                        if (size < 0) break
                        audioInfo.offset = 0
                        audioInfo.size = size
                        audioInfo.presentationTimeUs = audioExtractor.sampleTime
                        audioInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo)
                        audioExtractor.advance()
                    }
                    audioExtractor.release()
                }
            }

        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * Convert a decoder output Image (YUV_420_888) to Bitmap.
     * Uses the Image API planes which properly describe the YUV layout.
     */
    private fun yuvImageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap? {
        try {
            val planes = image.planes
            if (planes.size < 3) return null

            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            // Build NV21 byte array from Image planes
            val nv21 = ByteArray(width * height * 3 / 2)

            // Copy Y plane
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }

            // Copy UV planes to NV21 interleaved format (V then U)
            val uvHeight = height / 2
            val uvWidth = width / 2
            val uvOffset = width * height
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * uvRowStride + col * uvPixelStride
                    vBuffer.position(uvIndex)
                    uBuffer.position(uvIndex)
                    nv21[uvOffset + row * width + col * 2] = vBuffer.get()
                    nv21[uvOffset + row * width + col * 2 + 1] = uBuffer.get()
                }
            }

            // Use YuvImage to convert to JPEG then to Bitmap
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 95, out)
            val jpegBytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "yuvImageToBitmap failed", e)
            return null
        }
    }

    /**
     * Convert raw YUV buffer to Bitmap using YuvImage.
     * Handles stride/sliceHeight differences.
     */
    private fun yuvBufferToBitmap(
        data: ByteArray, stride: Int, sliceHeight: Int,
        width: Int, height: Int
    ): Bitmap? {
        try {
            // If stride matches width, data is likely already NV21-compatible
            val nv21: ByteArray
            if (stride == width && sliceHeight == height) {
                nv21 = data
            } else {
                // Extract actual image data removing stride padding
                nv21 = ByteArray(width * height * 3 / 2)
                // Copy Y plane (removing stride padding)
                for (row in 0 until height) {
                    val srcOffset = row * stride
                    val dstOffset = row * width
                    if (srcOffset + width <= data.size && dstOffset + width <= nv21.size) {
                        System.arraycopy(data, srcOffset, nv21, dstOffset, width)
                    }
                }
                // Copy UV plane
                val srcUvOffset = stride * sliceHeight
                val dstUvOffset = width * height
                val uvHeight = height / 2
                for (row in 0 until uvHeight) {
                    val srcOff = srcUvOffset + row * stride
                    val dstOff = dstUvOffset + row * width
                    if (srcOff + width <= data.size && dstOff + width <= nv21.size) {
                        System.arraycopy(data, srcOff, nv21, dstOff, width)
                    }
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 95, out)
            val jpegBytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "yuvBufferToBitmap failed", e)
            return null
        }
    }

    /**
     * Paint over watermark regions on a Bitmap.
     * Samples the surrounding border color and fills with a smooth gradient.
     */
    private fun paintOverWatermarks(bitmap: Bitmap, regions: List<WmRegion>, paint: Paint) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width
        val h = bitmap.height

        for (region in regions) {
            val left = region.left.coerceIn(0, w - 1)
            val top = region.top.coerceIn(0, h - 1)
            val right = region.right.coerceIn(left + 1, w)
            val bottom = region.bottom.coerceIn(top + 1, h)

            if (right - left < 2 || bottom - top < 2) continue

            // Sample average color from the border pixels around the watermark region
            val avgColor = sampleBorderColor(bitmap, left, top, right, bottom)
            paint.color = avgColor

            // Draw filled rectangle over watermark
            canvas.drawRect(
                left.toFloat(), top.toFloat(),
                right.toFloat(), bottom.toFloat(),
                paint
            )

            // Apply simple edge blending for smoother transition
            val blendPaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = avgColor
                alpha = 180
                isAntiAlias = true
            }
            canvas.drawRect(
                (left - 1).coerceAtLeast(0).toFloat(),
                (top - 1).coerceAtLeast(0).toFloat(),
                (right + 1).coerceAtMost(w).toFloat(),
                (bottom + 1).coerceAtMost(h).toFloat(),
                blendPaint
            )
        }
    }

    /**
     * Sample the average color from pixels around the border of a rectangle.
     */
    private fun sampleBorderColor(
        bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int
    ): Int {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0

        val w = bitmap.width
        val h = bitmap.height

        // Sample pixels along the border (1px outside the region)
        // Top edge
        val sampleTop = (top - 2).coerceAtLeast(0)
        if (sampleTop >= 0 && sampleTop < h) {
            for (x in left until right step 2) {
                if (x in 0 until w) {
                    val pixel = bitmap.getPixel(x, sampleTop)
                    rSum += Color.red(pixel)
                    gSum += Color.green(pixel)
                    bSum += Color.blue(pixel)
                    count++
                }
            }
        }

        // Bottom edge
        val sampleBottom = (bottom + 1).coerceAtMost(h - 1)
        if (sampleBottom >= 0 && sampleBottom < h) {
            for (x in left until right step 2) {
                if (x in 0 until w) {
                    val pixel = bitmap.getPixel(x, sampleBottom)
                    rSum += Color.red(pixel)
                    gSum += Color.green(pixel)
                    bSum += Color.blue(pixel)
                    count++
                }
            }
        }

        // Left edge
        val sampleLeft = (left - 2).coerceAtLeast(0)
        if (sampleLeft >= 0 && sampleLeft < w) {
            for (y in top until bottom step 2) {
                if (y in 0 until h) {
                    val pixel = bitmap.getPixel(sampleLeft, y)
                    rSum += Color.red(pixel)
                    gSum += Color.green(pixel)
                    bSum += Color.blue(pixel)
                    count++
                }
            }
        }

        // Right edge
        val sampleRight = (right + 1).coerceAtMost(w - 1)
        if (sampleRight >= 0 && sampleRight < w) {
            for (y in top until bottom step 2) {
                if (y in 0 until h) {
                    val pixel = bitmap.getPixel(sampleRight, y)
                    rSum += Color.red(pixel)
                    gSum += Color.green(pixel)
                    bSum += Color.blue(pixel)
                    count++
                }
            }
        }

        return if (count > 0) {
            Color.rgb(
                (rSum / count).toInt().coerceIn(0, 255),
                (gSum / count).toInt().coerceIn(0, 255),
                (bSum / count).toInt().coerceIn(0, 255)
            )
        } else {
            Color.BLACK
        }
    }

    /**
     * Convert ARGB Bitmap to NV21 byte array for encoder input.
     */
    private fun bitmapToNv21(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val nv21 = ByteArray(width * height * 3 / 2)
        val uvOffset = width * height

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = argb[y * width + x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // RGB to YUV conversion (BT.601)
                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                nv21[y * width + x] = yVal.coerceIn(0, 255).toByte()

                // UV subsampled (every 2x2 block)
                if (y % 2 == 0 && x % 2 == 0) {
                    val uvY = y / 2
                    val uvX = x / 2
                    val vVal = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val uVal = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val uvIdx = uvOffset + uvY * width + uvX * 2
                    if (uvIdx + 1 < nv21.size) {
                        nv21[uvIdx] = vVal.coerceIn(0, 255).toByte()
                        nv21[uvIdx + 1] = uVal.coerceIn(0, 255).toByte()
                    }
                }
            }
        }

        return nv21
    }

    /**
     * Feed YUV data to encoder input, draining output as needed.
     */
    private fun feedDataToEncoder(
        encoder: MediaCodec,
        data: ByteArray,
        pts: Long,
        encInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        videoTrack: Int,
        muxerStarted: Boolean,
        audioFormat: MediaFormat?
    ): Triple<Int, Boolean, Int> {
        var vt = videoTrack
        var ms = muxerStarted
        var at = -1

        var attempts = 0
        while (!isCancelled && attempts < 200) {
            val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (encInIdx >= 0) {
                // Try using Image API for encoder input
                val encImage = try {
                    encoder.getInputImage(encInIdx)
                } catch (_: Exception) { null }

                if (encImage != null) {
                    // Fill encoder Image planes from our NV21 data
                    fillEncoderImage(encImage, data, encImage.width, encImage.height)
                    encImage.close()
                    encoder.queueInputBuffer(encInIdx, 0, 0, pts, 0)
                } else {
                    // Fallback: direct buffer copy
                    val encBuf = encoder.getInputBuffer(encInIdx)!!
                    encBuf.clear()
                    val size = data.size.coerceAtMost(encBuf.capacity())
                    encBuf.put(data, 0, size)
                    encoder.queueInputBuffer(encInIdx, 0, size, pts, 0)
                }
                break
            }
            // Drain encoder to make room
            drainEncoderOutput(encoder, encInfo, muxer, vt, ms, audioFormat).let {
                vt = it.first
                ms = it.second
                at = it.third
            }
            attempts++
        }

        return Triple(vt, ms, at)
    }

    /**
     * Fill encoder input Image planes from NV21 data.
     */
    private fun fillEncoderImage(image: android.media.Image, nv21: ByteArray, width: Int, height: Int) {
        val planes = image.planes
        if (planes.size < 3) return

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        // Fill Y plane
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.put(nv21, row * width, width)
        }

        // Fill UV planes from NV21 interleaved data
        val uvOffset = width * height
        val uvHeight = height / 2
        val uvWidth = width / 2

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val nv21Idx = uvOffset + row * width + col * 2
                val vVal = nv21[nv21Idx]
                val uVal = nv21[nv21Idx + 1]

                val planeIdx = row * uvRowStride + col * uvPixelStride
                vBuffer.position(planeIdx)
                vBuffer.put(vVal)
                uBuffer.position(planeIdx)
                uBuffer.put(uVal)
            }
        }
    }

    /**
     * Drain encoder output buffers and write to muxer.
     */
    private fun drainEncoderOutput(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        currentVideoTrack: Int,
        currentMuxerStarted: Boolean,
        audioFormat: MediaFormat?
    ): Triple<Int, Boolean, Int> {
        var videoTrack = currentVideoTrack
        var muxerStarted = currentMuxerStarted
        var audioTrack = -1

        while (true) {
            val idx = encoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioFormat != null) {
                            audioTrack = muxer.addTrack(audioFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer started: videoTrack=$videoTrack audioTrack=$audioTrack")
                    }
                }
                idx >= 0 -> {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encoder.releaseOutputBuffer(idx, false)
                        continue
                    }
                    val buf = encoder.getOutputBuffer(idx)
                    if (buf != null && bufferInfo.size > 0 && muxerStarted) {
                        muxer.writeSampleData(videoTrack, buf, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                }
                else -> break
            }
        }

        return Triple(videoTrack, muxerStarted, audioTrack)
    }

    private fun handleProcessingSuccess(outputFile: File, outputFileName: String) {
        val context = getApplication<Application>()

        _uiState.value = _uiState.value.copy(
            progress = 95,
            statusMessage = "\u062c\u0627\u0631\u064a \u062d\u0641\u0638 \u0627\u0644\u0641\u064a\u062f\u064a\u0648..."
        )

        try {
            val shareDir = File(context.getExternalFilesDir(null), "videos")
            if (!shareDir.exists()) shareDir.mkdirs()
            shareDir.listFiles()?.forEach { it.delete() }
            val shareFile = File(shareDir, outputFileName)
            outputFile.copyTo(shareFile, overwrite = true)

            val savedPath = saveToGallery(outputFileName, outputFile)
            outputFile.delete()

            if (savedPath != null) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    progress = 100,
                    statusMessage = "\u062a\u0645 \u0625\u0632\u0627\u0644\u0629 \u0627\u0644\u0639\u0644\u0627\u0645\u0629 \u0627\u0644\u0645\u0627\u0626\u064a\u0629 \u0648\u062d\u0641\u0638 \u0627\u0644\u0641\u064a\u062f\u064a\u0648 \u0628\u0646\u062c\u0627\u062d",
                    statusType = WatermarkStatusType.SUCCESS,
                    processedFilePath = savedPath,
                    processedLocalPath = shareFile.absolutePath
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = "\u0641\u0634\u0644 \u062d\u0641\u0638 \u0627\u0644\u0641\u064a\u062f\u064a\u0648 \u0641\u064a \u0627\u0644\u0645\u0639\u0631\u0636",
                    statusType = WatermarkStatusType.ERROR,
                    progress = 0
                )
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                statusMessage = "\u062e\u0637\u0623 \u0641\u064a \u0627\u0644\u062d\u0641\u0638: ${e.message}",
                statusType = WatermarkStatusType.ERROR,
                progress = 0
            )
        }
    }

    private fun saveToGallery(fileName: String, sourceFile: File): String? {
        val context = getApplication<Application>()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/${VideoSaverApp.FOLDER_NAME}")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null

            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "w") ?: run {
                    context.contentResolver.delete(uri, null, null)
                    return null
                }

                pfd.use { descriptor ->
                    FileOutputStream(descriptor.fileDescriptor).use { output ->
                        FileInputStream(sourceFile).use { input ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                            }
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)

                uri.toString()
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                null
            }
        } else {
            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                VideoSaverApp.FOLDER_NAME
            )
            if (!moviesDir.exists()) moviesDir.mkdirs()
            val destFile = File(moviesDir, fileName)
            try {
                sourceFile.copyTo(destFile, overwrite = true)
                destFile.absolutePath
            } catch (_: Exception) {
                null
            }
        }
    }

    fun cancelProcessing() {
        isCancelled = true
    }

    fun clearState() {
        _uiState.value = WatermarkUiState()
    }
}
