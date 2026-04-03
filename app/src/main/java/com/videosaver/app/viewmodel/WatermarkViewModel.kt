package com.videosaver.app.viewmodel

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
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
import java.nio.ByteOrder
import java.nio.FloatBuffer
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

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private val QUAD_COORDS = floatArrayOf(
            -1f, -1f,  0f, 0f,
             1f, -1f,  1f, 0f,
            -1f,  1f,  0f, 1f,
             1f,  1f,  1f, 1f
        )
    }

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

                processVideoWithSurface(
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
     * Process video using Surface + EGL for encoder input.
     * Decoder outputs frames -> Image API -> Bitmap -> paint watermarks ->
     * upload to OpenGL texture -> render to encoder's input Surface.
     * Audio track is copied separately.
     */
    private fun processVideoWithSurface(
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

        val scaleX = width.toFloat() / 1080f
        val scaleY = height.toFloat() / 1920f

        val watermarkRegions = listOf(
            WmRegion(
                (width - (200 * scaleX)).toInt().coerceAtLeast(0),
                (height - (220 * scaleY)).toInt().coerceAtLeast(0),
                width,
                height
            ),
            WmRegion(
                0,
                (height - (240 * scaleY)).toInt().coerceAtLeast(0),
                (500 * scaleX).toInt().coerceAtMost(width),
                (height - (140 * scaleY)).toInt().coerceAtMost(height)
            ),
            WmRegion(
                0, 0,
                (160 * scaleX).toInt().coerceAtMost(width),
                (60 * scaleY).toInt().coerceAtMost(height)
            )
        )

        _uiState.value = _uiState.value.copy(
            progress = 10,
            statusMessage = "\u062c\u0627\u0631\u064a \u0625\u0632\u0627\u0644\u0629 \u0627\u0644\u0639\u0644\u0627\u0645\u0629 \u0627\u0644\u0645\u0627\u0626\u064a\u0629..."
        )

        // Setup decoder (byte-buffer mode, no Surface output)
        extractor.selectTrack(videoTrackIdx)
        val decoder = MediaCodec.createDecoderByType(videoMime)
        decoder.configure(videoFormat, null, null, 0)
        decoder.start()

        // Setup encoder with Surface input
        val encoderFormat = MediaFormat.createVideoFormat("video/avc", width, height)
        encoderFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        // Setup EGL + OpenGL
        val eglHelper = EglHelper(inputSurface)
        val glProgram = createGlProgram()
        val texId = createTexture()
        val vertexBuffer = createVertexBuffer()

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
                        decoder.releaseOutputBuffer(decOutIdx, false)
                        decoderDone = true
                        encoder.signalEndOfInputStream()
                        Log.d(TAG, "Signaled EOS to encoder")
                    } else if (decInfo.size > 0) {
                        val pts = decInfo.presentationTimeUs

                        val bitmap = getDecodedBitmap(decoder, decOutIdx, width, height)
                        decoder.releaseOutputBuffer(decOutIdx, false)

                        if (bitmap != null) {
                            val mutableBmp = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                            bitmap.recycle()
                            paintOverWatermarks(mutableBmp, watermarkRegions, coverPaint)

                            renderBitmapToSurface(mutableBmp, glProgram, texId, vertexBuffer, width, height)
                            mutableBmp.recycle()

                            eglHelper.setPresentationTime(pts * 1000)
                            eglHelper.swapBuffers()
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

                // Drain encoder (non-blocking)
                drainEncoder(
                    encoder, encInfo, muxer,
                    muxerVideoTrack, muxerStarted, muxerAudioTrack, audioFormat
                ).let {
                    muxerVideoTrack = it.videoTrack
                    muxerStarted = it.muxerStarted
                    muxerAudioTrack = it.audioTrack
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
                                Log.d(TAG, "Muxer started in final drain: vt=$muxerVideoTrack at=$muxerAudioTrack")
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
                            if (drainAttempts > 200) encDone = true
                        }
                    }
                    drainAttempts++
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
            try { GLES20.glDeleteTextures(1, intArrayOf(texId), 0) } catch (_: Exception) {}
            try { GLES20.glDeleteProgram(glProgram) } catch (_: Exception) {}
            try { eglHelper.release() } catch (_: Exception) {}
            try { inputSurface.release() } catch (_: Exception) {}
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    // ==================== Decode helpers ====================

    private fun getDecodedBitmap(decoder: MediaCodec, outputIndex: Int, width: Int, height: Int): Bitmap? {
        val image = try {
            decoder.getOutputImage(outputIndex)
        } catch (_: Exception) { null }

        if (image != null) {
            val bmp = yuvImageToBitmap(image, width, height)
            image.close()
            return bmp
        }

        val buf = decoder.getOutputBuffer(outputIndex) ?: return null
        val outFmt = decoder.outputFormat
        val stride = try { outFmt.getInteger(MediaFormat.KEY_STRIDE) } catch (_: Exception) { width }
        val sliceHeight = try { outFmt.getInteger(MediaFormat.KEY_SLICE_HEIGHT) } catch (_: Exception) { height }

        val data = ByteArray(buf.remaining())
        buf.get(data)
        return yuvBufferToBitmap(data, stride, sliceHeight, width, height)
    }

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

            val nv21 = ByteArray(width * height * 3 / 2)

            for (row in 0 until height) {
                val srcPos = row * yRowStride
                if (srcPos + width <= yBuffer.capacity()) {
                    yBuffer.position(srcPos)
                    yBuffer.get(nv21, row * width, width)
                }
            }

            val uvHeight = height / 2
            val uvWidth = width / 2
            val uvOffset = width * height
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * uvRowStride + col * uvPixelStride
                    if (uvIndex < vBuffer.capacity() && uvIndex < uBuffer.capacity()) {
                        nv21[uvOffset + row * width + col * 2] = vBuffer.get(uvIndex)
                        nv21[uvOffset + row * width + col * 2 + 1] = uBuffer.get(uvIndex)
                    }
                }
            }

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

    private fun yuvBufferToBitmap(
        data: ByteArray, stride: Int, sliceHeight: Int,
        width: Int, height: Int
    ): Bitmap? {
        try {
            val nv21: ByteArray
            if (stride == width && sliceHeight == height) {
                nv21 = data
            } else {
                nv21 = ByteArray(width * height * 3 / 2)
                for (row in 0 until height) {
                    val srcOffset = row * stride
                    val dstOffset = row * width
                    if (srcOffset + width <= data.size && dstOffset + width <= nv21.size) {
                        System.arraycopy(data, srcOffset, nv21, dstOffset, width)
                    }
                }
                val srcUvOffset = stride * sliceHeight
                val dstUvOffset = width * height
                for (row in 0 until height / 2) {
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

    // ==================== Watermark painting ====================

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

            val avgColor = sampleBorderColor(bitmap, left, top, right, bottom)
            paint.color = avgColor

            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)

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

    private fun sampleBorderColor(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
        val w = bitmap.width; val h = bitmap.height

        val sampleTop = (top - 2).coerceAtLeast(0)
        if (sampleTop in 0 until h) {
            for (x in left until right step 2) {
                if (x in 0 until w) {
                    val p = bitmap.getPixel(x, sampleTop)
                    rSum += Color.red(p); gSum += Color.green(p); bSum += Color.blue(p); count++
                }
            }
        }

        val sampleBottom = (bottom + 1).coerceAtMost(h - 1)
        if (sampleBottom in 0 until h) {
            for (x in left until right step 2) {
                if (x in 0 until w) {
                    val p = bitmap.getPixel(x, sampleBottom)
                    rSum += Color.red(p); gSum += Color.green(p); bSum += Color.blue(p); count++
                }
            }
        }

        val sampleLeft = (left - 2).coerceAtLeast(0)
        if (sampleLeft in 0 until w) {
            for (y in top until bottom step 2) {
                if (y in 0 until h) {
                    val p = bitmap.getPixel(sampleLeft, y)
                    rSum += Color.red(p); gSum += Color.green(p); bSum += Color.blue(p); count++
                }
            }
        }

        val sampleRight = (right + 1).coerceAtMost(w - 1)
        if (sampleRight in 0 until w) {
            for (y in top until bottom step 2) {
                if (y in 0 until h) {
                    val p = bitmap.getPixel(sampleRight, y)
                    rSum += Color.red(p); gSum += Color.green(p); bSum += Color.blue(p); count++
                }
            }
        }

        return if (count > 0) {
            Color.rgb(
                (rSum / count).toInt().coerceIn(0, 255),
                (gSum / count).toInt().coerceIn(0, 255),
                (bSum / count).toInt().coerceIn(0, 255)
            )
        } else Color.BLACK
    }

    // ==================== EGL + OpenGL ====================

    private data class MuxerState(val videoTrack: Int, val muxerStarted: Boolean, val audioTrack: Int)

    private inner class EglHelper(surface: Surface) {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        init {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1))
                throw RuntimeException("eglInitialize failed")

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0))
                throw RuntimeException("eglChooseConfig failed")
            val eglConfig = configs[0] ?: throw RuntimeException("No EGL config found")

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
                throw RuntimeException("eglMakeCurrent failed")

            Log.d(TAG, "EGL context created successfully")
        }

        fun setPresentationTime(nsecs: Long) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
        }

        fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        fun release() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
                eglContext = EGL14.EGL_NO_CONTEXT
                eglSurface = EGL14.EGL_NO_SURFACE
            }
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    private fun createGlProgram(): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun createTexture(): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return texIds[0]
    }

    private fun createVertexBuffer(): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
        bb.order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(QUAD_COORDS)
        fb.position(0)
        return fb
    }

    private fun renderBitmapToSurface(
        bitmap: Bitmap, program: Int, texId: Int,
        vertexBuffer: FloatBuffer, width: Int, height: Int
    ) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        val texLoc = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glUniform1i(texLoc, 0)

        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(posLoc)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)

        val texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoordLoc)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texCoordLoc)
        GLES20.glFinish()
    }

    // ==================== Encoder drain ====================

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        currentVideoTrack: Int,
        currentMuxerStarted: Boolean,
        currentAudioTrack: Int,
        audioFormat: MediaFormat?
    ): MuxerState {
        var videoTrack = currentVideoTrack
        var muxerStarted = currentMuxerStarted
        var audioTrack = currentAudioTrack

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

        return MuxerState(videoTrack, muxerStarted, audioTrack)
    }

    // ==================== Save + Gallery ====================

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
