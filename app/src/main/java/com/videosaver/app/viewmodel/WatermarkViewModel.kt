package com.videosaver.app.viewmodel

import android.app.Application
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaCodecInfo
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
                // Step 1: Copy source video to a local temp file for reliable access
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

                // Step 2: Get video info
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

                // Step 3: Process video
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                val timestamp = dateFormat.format(Date())
                val outputFileName = "NoWM_${timestamp}_${(100..999).random()}.mp4"
                val outputFile = File(tempDir, outputFileName)

                processVideoWithCodec(
                    inputFile, outputFile,
                    videoWidth, videoHeight, durationMs, rotation
                )

                // Clean up input temp
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

                // Step 4: Save processed video
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
     * Process video: decode each frame, paint over watermark regions, re-encode.
     * Uses native MediaCodec decode/encode pipeline.
     */
    private fun processVideoWithCodec(
        inputFile: File,
        outputFile: File,
        width: Int,
        height: Int,
        durationMs: Long,
        rotation: Int
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        // Find video and audio tracks
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
        val scaleX = width / 1080.0
        val scaleY = height / 1920.0

        val watermarkRegions = listOf(
            // TikTok logo (bottom-right)
            WmRegion(
                (width - (180 * scaleX)).toInt().coerceAtLeast(0),
                (height - (200 * scaleY)).toInt().coerceAtLeast(0),
                (160 * scaleX).toInt().coerceAtLeast(50),
                (160 * scaleY).toInt().coerceAtLeast(50)
            ),
            // Username text (bottom-left)
            WmRegion(
                (20 * scaleX).toInt(),
                (height - (220 * scaleY)).toInt().coerceAtLeast(0),
                (450 * scaleX).toInt().coerceAtLeast(100),
                (80 * scaleY).toInt().coerceAtLeast(30)
            ),
            // Small TikTok watermark text at top
            WmRegion(
                (15 * scaleX).toInt(),
                (15 * scaleY).toInt(),
                (140 * scaleX).toInt().coerceAtLeast(40),
                (50 * scaleY).toInt().coerceAtLeast(20)
            )
        )

        _uiState.value = _uiState.value.copy(
            progress = 10,
            statusMessage = "\u062c\u0627\u0631\u064a \u0625\u0632\u0627\u0644\u0629 \u0627\u0644\u0639\u0644\u0627\u0645\u0629 \u0627\u0644\u0645\u0627\u0626\u064a\u0629..."
        )

        // Setup decoder
        extractor.selectTrack(videoTrackIdx)
        val decoder = MediaCodec.createDecoderByType(videoMime)
        decoder.configure(videoFormat, null, null, 0)
        decoder.start()

        // Setup encoder
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

        try {
            while (!decoderDone && !isCancelled) {
                // Feed decoder
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize,
                                extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                // Drain decoder
                val decOutIdx = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                if (decOutIdx >= 0) {
                    val eos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    if (eos) {
                        // Signal EOS to encoder
                        val encInIdx = encoder.dequeueInputBuffer(50_000L)
                        if (encInIdx >= 0) {
                            encoder.queueInputBuffer(encInIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                        decoder.releaseOutputBuffer(decOutIdx, false)
                        decoderDone = true
                    } else if (decInfo.size > 0) {
                        val decBuf = decoder.getOutputBuffer(decOutIdx)
                        if (decBuf != null) {
                            val data = ByteArray(decInfo.size)
                            decBuf.position(decInfo.offset)
                            decBuf.get(data)

                            // Apply watermark removal on YUV data
                            blurYuvRegions(data, width, height, watermarkRegions)

                            // Feed to encoder
                            feedEncoder(encoder, data, decInfo.presentationTimeUs, encInfo,
                                muxer, muxerVideoTrack, muxerStarted).let {
                                muxerVideoTrack = it.first
                                muxerStarted = it.second
                            }

                            processedFrames++
                            val progress = ((processedFrames.toFloat() / totalFrames) * 80 + 10)
                                .toInt().coerceIn(10, 90)
                            _uiState.value = _uiState.value.copy(
                                progress = progress,
                                statusMessage = "\u062c\u0627\u0631\u064a \u0625\u0632\u0627\u0644\u0629 \u0627\u0644\u0639\u0644\u0627\u0645\u0629 \u0627\u0644\u0645\u0627\u0626\u064a\u0629... $progress%"
                            )
                        }
                        decoder.releaseOutputBuffer(decOutIdx, false)
                    } else {
                        decoder.releaseOutputBuffer(decOutIdx, false)
                    }
                }

                // Drain encoder output
                drainEncoder(encoder, encInfo, muxer, muxerVideoTrack, muxerStarted).let {
                    muxerVideoTrack = it.first
                    muxerStarted = it.second
                }
            }

            // Final encoder drain (including EOS)
            if (!isCancelled) {
                var encDone = false
                while (!encDone) {
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
                        else -> encDone = true
                    }
                }
            }

            // Copy audio track
            if (!isCancelled && audioTrackIdx != -1 && audioFormat != null && muxerStarted && muxerAudioTrack != -1) {
                _uiState.value = _uiState.value.copy(
                    progress = 92,
                    statusMessage = "\u062c\u0627\u0631\u064a \u0646\u0633\u062e \u0627\u0644\u0635\u0648\u062a..."
                )

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

    /** Watermark region definition */
    private data class WmRegion(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * Feed a processed frame to the encoder, draining output as needed.
     */
    private fun feedEncoder(
        encoder: MediaCodec,
        data: ByteArray,
        pts: Long,
        encInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        videoTrack: Int,
        muxerStarted: Boolean
    ): Pair<Int, Boolean> {
        var vt = videoTrack
        var ms = muxerStarted

        var attempts = 0
        while (!isCancelled && attempts < 100) {
            val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (encInIdx >= 0) {
                val encBuf = encoder.getInputBuffer(encInIdx)!!
                encBuf.clear()
                val size = data.size.coerceAtMost(encBuf.capacity())
                encBuf.put(data, 0, size)
                encoder.queueInputBuffer(encInIdx, 0, size, pts, 0)
                break
            }
            drainEncoder(encoder, encInfo, muxer, vt, ms).let {
                vt = it.first
                ms = it.second
            }
            attempts++
        }

        return Pair(vt, ms)
    }

    /**
     * Drain encoder output buffers and write to muxer.
     */
    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        currentVideoTrack: Int,
        currentMuxerStarted: Boolean
    ): Pair<Int, Boolean> {
        var videoTrack = currentVideoTrack
        var muxerStarted = currentMuxerStarted

        while (true) {
            val idx = encoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
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

        return Pair(videoTrack, muxerStarted)
    }

    /**
     * Blur/smooth watermark regions in YUV420 frame data.
     * Replaces the watermark area with a smooth gradient from surrounding pixels.
     */
    private fun blurYuvRegions(
        frameData: ByteArray,
        width: Int,
        height: Int,
        regions: List<WmRegion>
    ) {
        val yPlaneSize = width * height

        for (region in regions) {
            val rx = region.x.coerceIn(0, width - 1)
            val ry = region.y.coerceIn(0, height - 1)
            val rw = region.w.coerceAtMost(width - rx)
            val rh = region.h.coerceAtMost(height - ry)
            if (rw <= 0 || rh <= 0) continue

            // Sample border pixels for smooth fill
            val topAvg = sampleRowAvgY(frameData, width, height, (ry - 1).coerceAtLeast(0), rx, rw)
            val botAvg = sampleRowAvgY(frameData, width, height, (ry + rh).coerceAtMost(height - 1), rx, rw)
            val leftAvg = sampleColAvgY(frameData, width, height, (rx - 1).coerceAtLeast(0), ry, rh)
            val rightAvg = sampleColAvgY(frameData, width, height, (rx + rw).coerceAtMost(width - 1), ry, rh)

            // Fill Y plane with bilinear interpolation from borders
            for (row in 0 until rh) {
                val ty = row.toFloat() / rh.coerceAtLeast(1)
                for (col in 0 until rw) {
                    val tx = col.toFloat() / rw.coerceAtLeast(1)
                    val topBlend = topAvg * (1 - ty) + botAvg * ty
                    val leftBlend = leftAvg * (1 - tx) + rightAvg * tx
                    val blended = ((topBlend + leftBlend) / 2).toInt().coerceIn(0, 255)
                    val idx = (ry + row) * width + (rx + col)
                    if (idx < yPlaneSize) {
                        frameData[idx] = blended.toByte()
                    }
                }
            }

            // Fill UV plane (NV12/NV21 - interleaved U/V at half resolution)
            val uvBaseY = ry / 2
            val uvH = rh / 2
            val uvBaseX = rx / 2
            val uvW = rw / 2

            for (row in 0 until uvH) {
                for (col in 0 until uvW) {
                    val uvIdx = yPlaneSize + (uvBaseY + row) * width + (uvBaseX + col) * 2
                    if (uvIdx + 1 < frameData.size) {
                        frameData[uvIdx] = 128.toByte()
                        frameData[uvIdx + 1] = 128.toByte()
                    }
                }
            }
        }
    }

    private fun sampleRowAvgY(data: ByteArray, w: Int, h: Int, row: Int, startX: Int, len: Int): Float {
        if (row < 0 || row >= h) return 128f
        var sum = 0L
        var count = 0
        for (col in startX until (startX + len).coerceAtMost(w)) {
            val idx = row * w + col
            if (idx < w * h) {
                sum += (data[idx].toInt() and 0xFF)
                count++
            }
        }
        return if (count > 0) sum.toFloat() / count else 128f
    }

    private fun sampleColAvgY(data: ByteArray, w: Int, h: Int, col: Int, startY: Int, len: Int): Float {
        if (col < 0 || col >= w) return 128f
        var sum = 0L
        var count = 0
        for (row in startY until (startY + len).coerceAtMost(h)) {
            val idx = row * w + col
            if (idx < w * h) {
                sum += (data[idx].toInt() and 0xFF)
                count++
            }
        }
        return if (count > 0) sum.toFloat() / count else 128f
    }

    private fun handleProcessingSuccess(outputFile: File, outputFileName: String) {
        val context = getApplication<Application>()

        _uiState.value = _uiState.value.copy(
            progress = 95,
            statusMessage = "\u062c\u0627\u0631\u064a \u062d\u0641\u0638 \u0627\u0644\u0641\u064a\u062f\u064a\u0648..."
        )

        try {
            // Save local copy for sharing
            val shareDir = File(context.getExternalFilesDir(null), "videos")
            if (!shareDir.exists()) shareDir.mkdirs()
            shareDir.listFiles()?.forEach { it.delete() }
            val shareFile = File(shareDir, outputFileName)
            outputFile.copyTo(shareFile, overwrite = true)

            // Save to gallery
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
