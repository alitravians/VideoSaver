package com.videosaver.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.videosaver.app.R
import com.videosaver.app.ui.theme.AccentPurple
import com.videosaver.app.ui.theme.PrimaryBlueDark
import com.videosaver.app.ui.theme.ErrorRed
import com.videosaver.app.ui.theme.InstagramGradient1
import com.videosaver.app.ui.theme.InstagramGradient2
import com.videosaver.app.ui.theme.InstagramGradient3
import com.videosaver.app.ui.theme.PrimaryBlue
import com.videosaver.app.ui.theme.SuccessGreen
import com.videosaver.app.ui.theme.TikTokCyan
import com.videosaver.app.util.FileManager
import com.videosaver.app.viewmodel.MainViewModel
import com.videosaver.app.viewmodel.StatusType

@Composable
fun MainScreen(
    viewModel: MainViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // App Header
        Text(
            text = "Video Saver",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = PrimaryBlue
        )
        Text(
            text = "حمّل مقاطع الريلز والستوري وتيك توك بسهولة",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )

        // Clipboard Suggestion
        AnimatedVisibility(
            visible = uiState.showClipboardSuggestion,
            enter = slideInVertically() + fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = PrimaryBlue.copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "تم اكتشاف رابط في الحافظة",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.acceptClipboardSuggestion() }) {
                        Text("لصق", color = PrimaryBlue, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { viewModel.dismissClipboardSuggestion() }) {
                        Text("إغلاق", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // URL Input Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = uiState.url,
                    onValueChange = { viewModel.updateUrl(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "الصق رابط Instagram أو TikTok هنا...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = null,
                            tint = PrimaryBlue
                        )
                    },
                    trailingIcon = {
                        if (uiState.url.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateUrl("") }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "مسح",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Paste Button - reads directly from system clipboard
                    OutlinedButton(
                        onClick = {
                            try {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val pastedText = clip.getItemAt(0).text?.toString() ?: ""
                                    if (pastedText.isNotEmpty()) {
                                        val urlPattern = Regex("https?://[^\\s]+")
                                        val match = urlPattern.find(pastedText)
                                        val url = match?.value ?: pastedText
                                        viewModel.updateUrl(url.trim())
                                    }
                                }
                            } catch (_: Exception) {
                                // Silently handle clipboard access failure
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("لصق الرابط")
                    }

                    // Download Button
                    Button(
                        onClick = { viewModel.startDownload() },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.url.isNotEmpty() && !uiState.isLoading,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryBlue
                        )
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (uiState.isLoading) "جاري..." else "تحميل")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Video Preview
        AnimatedVisibility(
            visible = uiState.videoInfo != null,
            enter = slideInVertically() + fadeIn()
        ) {
            uiState.videoInfo?.let { info ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "معاينة",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        // Thumbnail
                        if (info.thumbnailUrl.isNotEmpty()) {
                            AsyncImage(
                                model = info.thumbnailUrl,
                                contentDescription = "صورة مصغرة",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        // Info Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Platform badge
                            Box(
                                modifier = Modifier
                                    .background(
                                        PrimaryBlue.copy(alpha = 0.1f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = FileManager.getPlatformDisplayName(info.platform),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PrimaryBlue,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Content type badge
                            Box(
                                modifier = Modifier
                                    .background(
                                        AccentPurple.copy(alpha = 0.1f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = FileManager.getContentTypeDisplayName(info.contentType),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AccentPurple,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Duration badge
                            if (info.duration.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = info.duration,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Progress and Status
        AnimatedVisibility(
            visible = uiState.statusType != StatusType.IDLE,
            enter = slideInVertically() + fadeIn()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when (uiState.statusType) {
                        StatusType.SUCCESS -> SuccessGreen.copy(alpha = 0.05f)
                        StatusType.ERROR -> ErrorRed.copy(alpha = 0.05f)
                        else -> MaterialTheme.colorScheme.surface
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Status Icon
                    when (uiState.statusType) {
                        StatusType.LOADING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = PrimaryBlue,
                                strokeWidth = 3.dp
                            )
                        }
                        StatusType.SUCCESS -> {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        StatusType.ERROR -> {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = ErrorRed,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        else -> {}
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Status Message
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        color = when (uiState.statusType) {
                            StatusType.SUCCESS -> SuccessGreen
                            StatusType.ERROR -> ErrorRed
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )

                    // Progress Bar
                    if (uiState.isLoading && uiState.progress > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = uiState.progress / 100f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = PrimaryBlue,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${uiState.progress}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = PrimaryBlue,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Action buttons after completion/failure
                    if (!uiState.isLoading && uiState.statusType != StatusType.IDLE) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (uiState.statusType == StatusType.ERROR) {
                                Button(
                                    onClick = { viewModel.retryDownload() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ErrorRed
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("إعادة المحاولة")
                                }
                            }

                            if (uiState.statusType == StatusType.SUCCESS) {
                                // WhatsApp Share Button
                                Button(
                                    onClick = {
                                        shareVideoToWhatsApp(context, uiState.lastDownloadedFilePath)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SuccessGreen
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("واتساب")
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Download Another Button
                                OutlinedButton(
                                    onClick = { viewModel.clearState() },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("تحميل آخر")
                                }

                            }
                        }
                    }
                }
            }
        }

        // Supported Platforms Info
        if (uiState.statusType == StatusType.IDLE && !uiState.isLoading) {
            Spacer(modifier = Modifier.height(32.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "المنصات المدعومة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        PlatformChip("Instagram Reels")
                        PlatformChip("Instagram Stories")
                        PlatformChip("TikTok")
                    }
                }
            }
        }

        // Developer Credits
        Spacer(modifier = Modifier.height(32.dp))
        DeveloperCredits()
        Spacer(modifier = Modifier.height(16.dp))
    }

    // Duplicate Dialog
    if (uiState.showDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDuplicateDialog() },
            title = { Text("ملف موجود مسبقاً", fontWeight = FontWeight.Bold) },
            text = { Text("هذا المقطع تم تحميله مسبقاً. هل تريد تحميله مرة أخرى؟") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissDuplicateDialog()
                        viewModel.startDownload(forceRedownload = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text("تحميل مرة أخرى")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.dismissDuplicateDialog() }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

/**
 * Share video to WhatsApp via FileProvider for maximum compatibility.
 * First checks for local copy saved during download (instant, no ANR risk).
 * Falls back to copying from MediaStore if local copy not found.
 * Shows Toast messages for any errors with diagnostic info.
 */
private fun shareVideoToWhatsApp(context: Context, filePath: String) {
    if (filePath.isEmpty()) {
        Toast.makeText(context, "لم يتم العثور على الملف", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        // Step 1: Try to find the local copy saved during download (in app's external files)
        // This avoids any MediaStore read issues and is instant (no file copy needed)
        val videosDir = java.io.File(context.getExternalFilesDir(null), "videos")
        var localFile: java.io.File? = null

        if (videosDir.exists()) {
            // Try to match by filename from the filePath
            val expectedName = if (filePath.startsWith("content://")) {
                // Query MediaStore for display name
                try {
                    val uri = Uri.parse(filePath)
                    context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Video.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                } catch (_: Exception) { null }
            } else {
                java.io.File(filePath).name
            }

            // First try exact filename match
            if (expectedName != null) {
                val matched = videosDir.listFiles()?.find { it.name == expectedName && it.length() > 1024 }
                if (matched != null) localFile = matched
            }

            // Fallback: most recent file (only if single file exists to avoid wrong match)
            if (localFile == null) {
                val validFiles = videosDir.listFiles()?.filter { it.isFile && it.length() > 1024 }
                if (validFiles?.size == 1) {
                    localFile = validFiles.first()
                }
            }
        }

        // If we have the original filename from a file path, try using it directly
        if (localFile == null && !filePath.startsWith("content://")) {
            val originalFile = java.io.File(filePath)
            if (originalFile.exists() && originalFile.length() > 1024) {
                localFile = originalFile
            }
        }

        val shareFile: java.io.File
        if (localFile != null && localFile.exists() && localFile.length() > 1024) {
            // Use the local copy directly - no MediaStore access needed
            shareFile = localFile
        } else {
            // Fallback: try to copy from MediaStore content URI
            if (!filePath.startsWith("content://")) {
                Toast.makeText(context, "الملف غير موجود - حاول تحميل الفيديو مرة أخرى", Toast.LENGTH_LONG).show()
                return
            }

            val sourceUri = Uri.parse(filePath)
            val cacheDir = java.io.File(context.externalCacheDir ?: context.cacheDir, "share")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            cacheDir.listFiles()?.forEach { it.delete() }

            shareFile = java.io.File(cacheDir, "video_${System.currentTimeMillis()}.mp4")
            val bytesCopied = try {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    java.io.FileOutputStream(shareFile).use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            total += bytesRead
                        }
                        output.flush()
                        output.fd.sync()
                        total
                    }
                } ?: 0L
            } catch (e: Exception) {
                Toast.makeText(context, "فشل نسخ الملف: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }

            if (bytesCopied < 1024) {
                shareFile.delete()
                Toast.makeText(context, "الملف فارغ أو تالف ($bytesCopied bytes) - حاول تحميل الفيديو مرة أخرى", Toast.LENGTH_LONG).show()
                return
            }
        }

        // Step 2: Create FileProvider URI from the local file
        val shareUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            shareFile
        )

        // Step 3: Share to WhatsApp (or general share as fallback)
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                clipData = android.content.ClipData.newRawUri("", shareUri)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(shareIntent)
        } catch (_: Exception) {
            // WhatsApp not installed - try general share
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    clipData = android.content.ClipData.newRawUri("", shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "مشاركة الفيديو"))
            } catch (_: Exception) {
                Toast.makeText(context, "فشل مشاركة الفيديو", Toast.LENGTH_SHORT).show()
            }
        }
    } catch (e: Exception) {
        Toast.makeText(context, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun DeveloperCredits() {
    val context = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "aliAnim")

    val color1 by infiniteTransition.animateColor(
        initialValue = PrimaryBlue,
        targetValue = AccentPurple,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aliColor1"
    )

    val color2 by infiniteTransition.animateColor(
        initialValue = InstagramGradient1,
        targetValue = TikTokCyan,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aliColor2"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            PrimaryBlue.copy(alpha = 0.05f),
                            PrimaryBlueDark.copy(alpha = 0.1f)
                        )
                    )
                )
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Developer photo
            Image(
                painter = painterResource(id = R.drawable.developer_photo),
                contentDescription = "صورة المطور",
                modifier = Modifier
                    .size(90.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Description with animated developer name
            Text(
                text = buildAnnotatedString {
                    append("تم تصميم و برمجة هذا التطبيق بواسطة ")
                    withStyle(
                        SpanStyle(
                            brush = Brush.linearGradient(listOf(color1, color2)),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    ) {
                        append("Ali")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )

        }
    }
}

@Composable
fun PlatformChip(name: String) {
    Box(
        modifier = Modifier
            .background(
                PrimaryBlue.copy(alpha = 0.1f),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = PrimaryBlue,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp
        )
    }
}
