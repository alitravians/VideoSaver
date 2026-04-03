package com.videosaver.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videosaver.app.ui.theme.AccentPurple
import com.videosaver.app.ui.theme.ErrorRed
import com.videosaver.app.ui.theme.PrimaryBlue
import com.videosaver.app.ui.theme.SuccessGreen
import com.videosaver.app.ui.theme.TikTokCyan
import com.videosaver.app.viewmodel.WatermarkStatusType
import com.videosaver.app.viewmodel.WatermarkViewModel

@Composable
fun WatermarkRemoverScreen(
    viewModel: WatermarkViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Video picker launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onVideoSelected(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header
        Text(
            text = "إزالة العلامة المائية",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = TikTokCyan
        )
        Text(
            text = "اختر فيديو من جهازك وسنزيل علامة TikTok المائية",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )

        // Video Picker Card
        if (uiState.selectedVideoUri == null) {
            // No video selected - show picker
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !uiState.isProcessing) {
                        videoPickerLauncher.launch("video/*")
                    },
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Decorative icon circle
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(
                                TikTokCyan.copy(alpha = 0.1f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = TikTokCyan,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "اضغط لاختيار فيديو",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "اختر فيديو TikTok من الاستديو",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Browse button
                    Button(
                        onClick = { videoPickerLauncher.launch("video/*") },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TikTokCyan
                        ),
                        modifier = Modifier.fillMaxWidth(0.7f)
                    ) {
                        Icon(
                            Icons.Default.Movie,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "استعراض الفيديوهات",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // Video selected - show info
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Video icon
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    TikTokCyan.copy(alpha = 0.1f),
                                    RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.VideoFile,
                                contentDescription = null,
                                tint = TikTokCyan,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Video info
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.videoFileName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (uiState.videoDuration.isNotEmpty()) {
                                    InfoChip(
                                        label = uiState.videoDuration,
                                        icon = Icons.Default.PlayArrow
                                    )
                                }
                                if (uiState.videoResolution.isNotEmpty()) {
                                    InfoChip(label = uiState.videoResolution)
                                }
                            }
                        }

                        // Close button
                        if (!uiState.isProcessing) {
                            IconButton(
                                onClick = { viewModel.clearState() }
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "إزالة",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action buttons
                    if (!uiState.isProcessing && uiState.statusType != WatermarkStatusType.SUCCESS) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Change video button
                            OutlinedButton(
                                onClick = { videoPickerLauncher.launch("video/*") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.VideoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("تغيير")
                            }

                            // Start removal button
                            Button(
                                onClick = { viewModel.startWatermarkRemoval() },
                                modifier = Modifier.weight(2f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TikTokCyan
                                )
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "إزالة العلامة المائية",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Progress and Status
        AnimatedVisibility(
            visible = uiState.statusType != WatermarkStatusType.IDLE,
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
                        WatermarkStatusType.SUCCESS -> SuccessGreen.copy(alpha = 0.05f)
                        WatermarkStatusType.ERROR -> ErrorRed.copy(alpha = 0.05f)
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
                        WatermarkStatusType.PROCESSING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = TikTokCyan,
                                strokeWidth = 3.dp
                            )
                        }
                        WatermarkStatusType.SUCCESS -> {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        WatermarkStatusType.ERROR -> {
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
                            WatermarkStatusType.SUCCESS -> SuccessGreen
                            WatermarkStatusType.ERROR -> ErrorRed
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )

                    // Progress Bar
                    if (uiState.isProcessing && uiState.progress > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = uiState.progress / 100f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = TikTokCyan,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${uiState.progress}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = TikTokCyan,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Cancel button
                        OutlinedButton(
                            onClick = { viewModel.cancelProcessing() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("إلغاء")
                        }
                    }

                    // Action buttons after completion
                    if (!uiState.isProcessing && uiState.statusType != WatermarkStatusType.IDLE) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (uiState.statusType == WatermarkStatusType.ERROR) {
                                Button(
                                    onClick = { viewModel.startWatermarkRemoval() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ErrorRed
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = uiState.selectedVideoUri != null
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

                            if (uiState.statusType == WatermarkStatusType.SUCCESS) {
                                // WhatsApp Share Button
                                Button(
                                    onClick = {
                                        shareProcessedVideo(context, uiState.processedLocalPath, uiState.processedFilePath)
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

                                // Process Another Button
                                OutlinedButton(
                                    onClick = { viewModel.clearState() },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        Icons.Default.VideoLibrary,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("فيديو آخر")
                                }
                            }
                        }
                    }
                }
            }
        }

        // How it works section
        if (uiState.statusType == WatermarkStatusType.IDLE && uiState.selectedVideoUri == null) {
            Spacer(modifier = Modifier.height(32.dp))
            HowItWorksSection()
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun InfoChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun HowItWorksSection() {
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
                .padding(16.dp)
        ) {
            Text(
                text = "كيف تعمل الميزة؟",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            StepItem(
                number = "1",
                title = "اختر الفيديو",
                description = "اضغط على الزر أعلاه لاختيار فيديو TikTok من الاستديو"
            )
            StepItem(
                number = "2",
                title = "المعالجة التلقائية",
                description = "التطبيق يكشف ويزيل علامة TikTok المائية واسم المستخدم"
            )
            StepItem(
                number = "3",
                title = "حفظ ومشاركة",
                description = "الفيديو النظيف يُحفظ في المعرض وتقدر تشاركه بالواتساب"
            )
        }
    }
}

@Composable
private fun StepItem(
    number: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Step number circle
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(TikTokCyan.copy(alpha = 0.15f), CircleShape)
                .border(1.dp, TikTokCyan.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = TikTokCyan
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Share processed video to WhatsApp via FileProvider.
 */
private fun shareProcessedVideo(context: Context, localPath: String, mediaStorePath: String) {
    try {
        // Try local file first
        val shareFile = if (localPath.isNotEmpty()) {
            val file = java.io.File(localPath)
            if (file.exists() && file.length() > 1024) file else null
        } else null

        val shareUri = if (shareFile != null) {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                shareFile
            )
        } else if (mediaStorePath.startsWith("content://")) {
            Uri.parse(mediaStorePath)
        } else {
            Toast.makeText(context, "لم يتم العثور على الملف", Toast.LENGTH_SHORT).show()
            return
        }

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
            // WhatsApp not installed - general share
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
