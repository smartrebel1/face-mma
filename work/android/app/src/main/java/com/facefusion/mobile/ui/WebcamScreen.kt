package com.facefusion.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.facefusion.mobile.R
import com.facefusion.mobile.webcam.*
import kotlin.math.max
import kotlin.math.min

private val StudioBg = Color(0xFF0E1117)
private val StudioCard = Color(0xFF161B24)
private val StudioCardBorder = Color(0xFF262F3F)
private val StudioGreen = Color(0xFF00E676)
private val StudioAmber = Color(0xFFFFB300)
private val StudioRed = Color(0xFFFF5252)
private val StudioCyan = Color(0xFF00B8D4)
private val StudioMuted = Color(0xFF9BA7B8)

/**
 * Main Studio Screen for the Android Virtual Webcam & Live Stream Video Player.
 *
 * Provides:
 * - Prominent ON/OFF Live Output Master Switch with real-time stream status & telemetry
 * - Large Media3 ExoPlayer video preview with Fit / Fill / Original aspect ratio & Mirror mode
 * - Complete transport controls (PLAY, PAUSE, STOP, Prev, Next, Seek, Loop, Volume)
 * - Persistent Video Playlist with thumbnails, duration, add/remove/reorder, and save
 * - Clean Broadcast Stage & Picture-in-Picture (PiP) mode for phone-only TikTok LIVE
 * - Settings & TikTok LIVE Integration Guide modal
 * - Full Arabic RTL interface with English technical labels (plus instant AR/EN toggle)
 */
@Composable
fun WebcamScreen(
    controller: WebcamController,
    onPickVideos: () -> Unit,
    onEnterPip: () -> Unit,
    isExternalCaptureIntent: Boolean = false,
    onDeliverCapturedVideo: ((PlaylistItem) -> Unit)? = null,
) {
    val settings = controller.settings
    val ar = settings.arabicRtl
    val layoutDir = if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE || cfg.screenWidthDp >= 700

    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showGuideDialog by rememberSaveable { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(StudioBg)
        ) {
            if (isLandscape) {
                // Responsive 2-column layout for landscape / tablet screens
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1.08f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StudioTopHeader(
                            ar = ar,
                            onToggleLanguage = {
                                controller.updateSettings(settings.copy(arabicRtl = !ar))
                            },
                            onOpenGuide = { showGuideDialog = true },
                            onOpenSettings = { showSettingsDialog = true },
                        )
                        PreviewStageCard(
                            controller = controller,
                            ar = ar,
                            onEnterPip = onEnterPip,
                        )
                        PlaybackControlsCard(controller = controller, ar = ar)
                    }
                    Column(
                        modifier = Modifier
                            .weight(0.92f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutputMasterCard(
                            controller = controller,
                            ar = ar,
                            onOpenSettings = { showSettingsDialog = true },
                            onEnterPip = onEnterPip,
                        )
                        StatusBanners(controller = controller)
                        if (isExternalCaptureIntent && onDeliverCapturedVideo != null) {
                            ExternalCameraIntentBanner(
                                controller = controller,
                                ar = ar,
                                onDeliver = onDeliverCapturedVideo,
                            )
                        }
                        PlaylistCard(
                            controller = controller,
                            ar = ar,
                            onPickVideos = onPickVideos,
                        )
                    }
                }
            } else {
                // Portrait vertical studio layout
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StudioTopHeader(
                        ar = ar,
                        onToggleLanguage = {
                            controller.updateSettings(settings.copy(arabicRtl = !ar))
                        },
                        onOpenGuide = { showGuideDialog = true },
                        onOpenSettings = { showSettingsDialog = true },
                    )

                    OutputMasterCard(
                        controller = controller,
                        ar = ar,
                        onOpenSettings = { showSettingsDialog = true },
                        onEnterPip = onEnterPip,
                    )

                    StatusBanners(controller = controller)

                    if (isExternalCaptureIntent && onDeliverCapturedVideo != null) {
                        ExternalCameraIntentBanner(
                            controller = controller,
                            ar = ar,
                            onDeliver = onDeliverCapturedVideo,
                        )
                    }

                    PreviewStageCard(
                        controller = controller,
                        ar = ar,
                        onEnterPip = onEnterPip,
                    )

                    PlaybackControlsCard(
                        controller = controller,
                        ar = ar,
                    )

                    PlaylistCard(
                        controller = controller,
                        ar = ar,
                        onPickVideos = onPickVideos,
                    )

                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        // Fullscreen Chromeless Clean Broadcast Stage for TikTok LIVE Screen-Cast
        if (controller.cleanStageFullscreen) {
            CleanBroadcastStageDialog(
                controller = controller,
                ar = ar,
                onExit = { controller.setCleanStageFullscreen(false) },
            )
        }

        if (showSettingsDialog) {
            WebcamSettingsDialog(
                controller = controller,
                onDismiss = { showSettingsDialog = false },
            )
        }

        if (showGuideDialog) {
            TikTokCompatibilityDialog(
                ar = ar,
                onDismiss = { showGuideDialog = false },
            )
        }
    }
}

@Composable
private fun StudioTopHeader(
    ar: Boolean,
    onToggleLanguage: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (ar) "استوديو الكاميرا الافتراضية والبث المباشر" else "Virtual Webcam & Live Stream Studio",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = if (ar) {
                    "تشغيل الفيديوهات المسجلة كمصدر بث مباشر • WebcamMax Workflow"
                } else {
                    "Pre-recorded video source switcher & live stream output"
                },
                style = MaterialTheme.typography.bodySmall,
                color = StudioMuted,
                fontSize = 11.sp,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onToggleLanguage,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Text(
                    text = if (ar) "EN" else "عربي",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            IconButton(
                onClick = onOpenGuide,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "TikTok LIVE Guide",
                    tint = StudioCyan,
                )
            }
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Output Settings",
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * Prominent ON / OFF Output Master Switch Card.
 * Genuinely enables/disables the underlying VideoOutputSink and VirtualCamService.
 */
@Composable
private fun OutputMasterCard(
    controller: WebcamController,
    ar: Boolean,
    onOpenSettings: () -> Unit,
    onEnterPip: () -> Unit,
) {
    val ctx = LocalContext.current
    val enabled = controller.outputEnabled
    val state = controller.outputState
    val borderColor = when (state) {
        OutputState.LIVE -> StudioGreen
        OutputState.STANDBY, OutputState.STARTING -> StudioAmber
        OutputState.ERROR -> StudioRed
        OutputState.OFF -> StudioCardBorder
    }

    Surface(
        color = StudioCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(
                                when (state) {
                                    OutputState.LIVE -> StudioGreen
                                    OutputState.STANDBY, OutputState.STARTING -> StudioAmber
                                    OutputState.ERROR -> StudioRed
                                    OutputState.OFF -> Color.Gray
                                }
                            )
                    )
                    Column {
                        Text(
                            text = if (ar) {
                                "مفتاح البث الرئيسي • LIVE VIDEO OUTPUT"
                            } else {
                                "LIVE VIDEO OUTPUT • MASTER SWITCH"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioMuted,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (ar) state.labelAr else state.labelEn,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = when (state) {
                                OutputState.LIVE -> StudioGreen
                                OutputState.STANDBY, OutputState.STARTING -> StudioAmber
                                OutputState.ERROR -> StudioRed
                                OutputState.OFF -> Color.White
                            },
                        )
                    }
                }

                // Prominent ON / OFF Toggle Button + Switch
                Button(
                    onClick = { controller.toggleOutput() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (enabled) StudioGreen else Color(0xFF283246),
                        contentColor = if (enabled) Color(0xFF041E11) else Color.White,
                    ),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = if (enabled) {
                            if (ar) "🟢 المخرج: نشط (ON)" else "🟢 OUTPUT: ON"
                        } else {
                            if (ar) "⚪ تشغيل المخرج (OFF)" else "⚪ OUTPUT: OFF"
                        },
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                    )
                }
            }

            // Active Sink & Telemetry Row
            val sink = controller.settings.outputSinkType
            Text(
                text = if (ar) "وضع الإخراج: ${sink.labelAr}" else "Mode: ${sink.labelEn}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD5DFEE),
                fontWeight = FontWeight.Medium,
            )

            if (enabled) {
                Surface(
                    color = Color(0xFF0D1118),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "FPS: ${"%.1f".format(java.util.Locale.US, controller.outputFps)} / ${controller.settings.targetFps}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = StudioGreen,
                                )
                                Text(
                                    text = "Frames: ${controller.framesSent} | Clients: ${controller.connectedClients}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = StudioCyan,
                                )
                                Text(
                                    text = "${controller.settings.outputWidth}x${controller.settings.outputHeight}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color.White,
                                )
                            }
                            if (controller.activeEndpointUrl.isNotBlank()) {
                                Text(
                                    text = controller.activeEndpointUrl,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFFB5C4D8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (sink == OutputSinkType.MJPEG_HTTP) {
                                OutlinedButton(
                                    onClick = {
                                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("Stream URL", controller.activeEndpointUrl))
                                        Toast.makeText(
                                            ctx,
                                            if (ar) "تم نسخ رابط البث" else "Stream URL copied",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Text(if (ar) "نسخ رابط MJPEG" else "Copy Stream URL", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        runCatching {
                                            val localUrl = "http://127.0.0.1:${controller.settings.httpPort}/player"
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(localUrl)))
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = StudioCyan, contentColor = Color.Black),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Text(if (ar) "فتح صفحة البث" else "Open Web Receiver", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = { controller.setCleanStageFullscreen(true) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = StudioCyan, contentColor = Color.Black),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = if (ar) "وضع البث النظيف لـ TikTok LIVE" else "Enter TikTok Clean Stage",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                OutlinedButton(
                                    onClick = onEnterPip,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(if (ar) "نافذة PiP" else "PiP Mode", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (ar) "إعدادات طريقة البث (RTMP / HTTP / TikTok)" else "Configure Output Method",
                            fontSize = 11.sp,
                        )
                    }
                    OutlinedButton(
                        onClick = { controller.setCleanStageFullscreen(true) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(if (ar) "شاشة نظيفة" else "Clean Stage", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBanners(controller: WebcamController) {
    val err = controller.errorMessage
    val info = controller.statusBanner
    if (err != null) {
        Surface(
            color = Color(0xFF3A1218),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, StudioRed, RoundedCornerShape(10.dp)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "⚠️ $err",
                    color = Color(0xFFFFB4B4),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { controller.clearMessages() }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White)
                }
            }
        }
    } else if (info != null) {
        Surface(
            color = Color(0xFF122B24),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, StudioGreen.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "✓ $info",
                    color = Color(0xFFB9F6CA),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { controller.clearMessages() }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ExternalCameraIntentBanner(
    controller: WebcamController,
    ar: Boolean,
    onDeliver: (PlaylistItem) -> Unit,
) {
    val activeItem = controller.playlist.getOrNull(controller.activeIndex)
    Surface(
        color = Color(0xFF1A2639),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioCyan, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (ar) "طلب كاميرا خارجي (ACTION_VIDEO_CAPTURE)" else "External App Camera Request",
                    color = StudioCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    text = if (ar) {
                        "اختر فيديو من القائمة واضغط إرسال لتسليمه للتطبيق الطالب."
                    } else {
                        "Select a video in the playlist and tap Deliver to send it to the requesting app."
                    },
                    color = Color.White,
                    fontSize = 11.sp,
                )
            }
            Button(
                onClick = { if (activeItem != null) onDeliver(activeItem) },
                enabled = activeItem != null,
                colors = ButtonDefaults.buttonColors(containerColor = StudioGreen, contentColor = Color.Black),
            ) {
                Text(if (ar) "إرسال الفيديو" else "Deliver Video", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Large Video Preview Stage rendering Android Media3 ExoPlayer via TextureView
 * with real-time Fit / Fill / Original aspect ratio and Mirror transformations.
 */
@Composable
private fun PreviewStageCard(
    controller: WebcamController,
    ar: Boolean,
    onEnterPip: () -> Unit,
) {
    val settings = controller.settings
    val activeItem = controller.playlist.getOrNull(controller.activeIndex)
    val vw = controller.videoWidth.coerceAtLeast(1)
    val vh = controller.videoHeight.coerceAtLeast(1)

    Surface(
        color = StudioCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioCardBorder, RoundedCornerShape(14.dp)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Stage Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        color = when {
                            controller.outputEnabled && controller.isPlaying -> StudioGreen
                            controller.isPlaying -> StudioCyan
                            else -> Color(0xFF2B3548)
                        },
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = when {
                                controller.outputEnabled && controller.isPlaying -> "ON-AIR"
                                controller.isPlaying -> "PLAYING"
                                controller.isStopped -> "STOPPED"
                                else -> "PAUSED"
                            },
                            color = if (controller.isPlaying) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = activeItem?.displayName
                            ?: if (ar) "لم يتم اختيار فيديو بعد" else "No video selected",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onEnterPip,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_videocam),
                            contentDescription = "Picture in Picture",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = { controller.setCleanStageFullscreen(true) },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_fullscreen),
                            contentDescription = "Fullscreen Clean Stage",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // Video Preview Box
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (controller.playlist.isNotEmpty() && !controller.isStopped) {
                    ExoTexturePreview(
                        controller = controller,
                        videoWidth = vw,
                        videoHeight = vh,
                        aspectRatioMode = settings.aspectRatio,
                        mirror = settings.mirrorMode,
                    )
                } else {
                    // Standby / Empty preview state
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = if (controller.playlist.isEmpty()) {
                                if (ar) "📹 أضف فيديوهات من الهاتف لبدء البث" else "📹 Add videos from storage to begin"
                            } else {
                                if (ar) "⏹ متوقف (STOPPED) • اضغط PLAY للتشغيل" else "⏹ Playback Stopped • Press PLAY"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                        Text(
                            text = if (ar) {
                                "يدعم MP4, WebM, MKV, MOV عبر محرك Android Media3 ExoPlayer"
                            } else {
                                "Supports MP4, WebM, MKV, MOV via Android Media3 ExoPlayer"
                            },
                            color = StudioMuted,
                            fontSize = 11.sp,
                        )
                    }
                }

                // Overlay Badges (Aspect Ratio + Mirror + Resolution)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = "${vw}x${vh} • ${settings.aspectRatio.labelEn}" +
                                    (if (settings.mirrorMode) " • MIRROR" else ""),
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders the Media3 ExoPlayer video feed onto a [TextureView] applying [aspectRatioMode]
 * (`Fit`, `Fill`, `Original`) and [mirror] transformations.
 */
@Composable
private fun ExoTexturePreview(
    controller: WebcamController,
    videoWidth: Int,
    videoHeight: Int,
    aspectRatioMode: AspectRatioMode,
    mirror: Boolean,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val vw = videoWidth.toFloat().coerceAtLeast(1f)
        val vh = videoHeight.toFloat().coerceAtLeast(1f)

        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    controller.bindTextureView(this)
                }
            },
            update = { tv ->
                controller.bindTextureView(tv)
                val viewW = tv.width.toFloat().takeIf { it > 0f } ?: boxW
                val viewH = tv.height.toFloat().takeIf { it > 0f } ?: boxH
                val matrix = Matrix()
                val sx: Float
                val sy: Float
                when (aspectRatioMode) {
                    AspectRatioMode.FIT -> {
                        val scale = min(viewW / vw, viewH / vh)
                        sx = (vw * scale) / viewW
                        sy = (vh * scale) / viewH
                    }
                    AspectRatioMode.FILL -> {
                        val scale = max(viewW / vw, viewH / vh)
                        sx = (vw * scale) / viewW
                        sy = (vh * scale) / viewH
                    }
                    AspectRatioMode.ORIGINAL -> {
                        val maxScale = min(1f, min(viewW / vw, viewH / vh))
                        sx = (vw * maxScale) / viewW
                        sy = (vh * maxScale) / viewH
                    }
                }
                val finalSx = if (mirror) -sx else sx
                matrix.setScale(finalSx, sy, viewW / 2f, viewH / 2f)
                tv.setTransform(matrix)
                tv.invalidate()
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Complete Playback & Visual Transform Controls Card:
 * PLAY, PAUSE, STOP, Prev, Next, Timeline Seek, Loop Mode, Volume, Aspect Ratio (Fit/Fill/Original), Mirror.
 */
@Composable
private fun PlaybackControlsCard(
    controller: WebcamController,
    ar: Boolean,
) {
    val settings = controller.settings
    val duration = controller.durationMs.coerceAtLeast(0L)
    val pos = controller.positionMs.coerceIn(0L, duration.coerceAtLeast(1L))

    Surface(
        color = StudioCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioCardBorder, RoundedCornerShape(14.dp)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Seek Bar & Timestamps
            Column {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Slider(
                        value = if (duration > 0L) pos.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { frac ->
                            if (duration > 0L) {
                                controller.seekTo((frac * duration).toLong())
                            }
                        },
                        enabled = duration > 0L,
                        modifier = Modifier.fillMaxWidth().height(26.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatDuration(pos),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = StudioMuted,
                        )
                        Text(
                            text = formatDuration(duration),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = StudioMuted,
                        )
                    }
                }
            }

            // Primary Transport Buttons: PREV | PLAY | PAUSE | STOP | NEXT
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { controller.previousTrack() },
                        enabled = controller.playlist.size > 1,
                        modifier = Modifier.weight(0.85f),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        Text("⏮", fontSize = 14.sp)
                    }

                    // PLAY Button
                    Button(
                        onClick = { controller.play() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (controller.isPlaying) StudioGreen else Color(0xFF1F6FEB),
                            contentColor = if (controller.isPlaying) Color.Black else Color.White,
                        ),
                        modifier = Modifier.weight(1.3f),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        Text(
                            text = if (ar) "▶ تشغيل PLAY" else "▶ PLAY",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }

                    // PAUSE Button
                    OutlinedButton(
                        onClick = { controller.pause() },
                        enabled = controller.isPlaying,
                        modifier = Modifier.weight(1.2f),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        Text(
                            text = if (ar) "⏸ إيقاف PAUSE" else "⏸ PAUSE",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                        )
                    }

                    // STOP Button
                    OutlinedButton(
                        onClick = { controller.stopPlayback() },
                        enabled = !controller.isStopped || controller.isPlaying,
                        modifier = Modifier.weight(1.1f),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        Text(
                            text = if (ar) "⏹ STOP" else "⏹ STOP",
                            color = StudioRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        )
                    }

                    OutlinedButton(
                        onClick = { controller.nextTrack() },
                        enabled = controller.playlist.size > 1,
                        modifier = Modifier.weight(0.85f),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        Text("⏭", fontSize = 14.sp)
                    }
                }
            }

            HorizontalDivider(color = StudioCardBorder)

            // Aspect Ratio Selector: Fit, Fill, Original
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (ar) "نسبة أبعاد الفيديو • Video Aspect Ratio" else "Video Aspect Ratio",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioMuted,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AspectRatioMode.entries.forEach { mode ->
                        val selected = settings.aspectRatio == mode
                        FilterChip(
                            selected = selected,
                            onClick = { controller.setAspectRatio(mode) },
                            label = {
                                Text(
                                    text = if (ar) mode.labelAr else mode.labelEn,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Loop Mode + Mirror Toggle Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { controller.cycleLoopMode() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "🔁 " + (if (ar) settings.loopMode.labelAr else settings.loopMode.labelEn),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                FilterChip(
                    selected = settings.mirrorMode,
                    onClick = { controller.toggleMirror() },
                    label = {
                        Text(
                            text = if (ar) "⇄ عكس الصورة (Mirror)" else "⇄ Mirror Mode",
                            fontSize = 11.sp,
                            fontWeight = if (settings.mirrorMode) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            // Volume Control Slider + Mute Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { controller.toggleMute() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    val volPct = if (settings.muted) 0 else (settings.volume * 100).toInt()
                    Text(
                        text = if (settings.muted || volPct == 0) "🔇 0%" else "🔊 $volPct%",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Slider(
                        value = if (settings.muted) 0f else settings.volume,
                        onValueChange = { controller.setVolume(it) },
                        modifier = Modifier.weight(1f).height(26.dp),
                    )
                }
            }
        }
    }
}

/**
 * Video Source Playlist Manager Card:
 * Select video files via Storage Access Framework, display thumbnails/names/duration,
 * save playlist, add, remove, and reorder videos.
 */
@Composable
private fun PlaylistCard(
    controller: WebcamController,
    ar: Boolean,
    onPickVideos: () -> Unit,
) {
    val items = controller.playlist

    Surface(
        color = StudioCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioCardBorder, RoundedCornerShape(14.dp)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (ar) {
                            "قائمة تشغيل الفيديوهات (${items.size}) • Video Playlist"
                        } else {
                            "Video Source Playlist (${items.size})"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = if (ar) {
                            "اضغط على أي فيديو للتبديل الفوري أثناء البث المباشر"
                        } else {
                            "Tap any video for seamless live source switching"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioMuted,
                        fontSize = 11.sp,
                    )
                }

                Button(
                    onClick = onPickVideos,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (ar) "إضافة فيديو" else "Add Videos",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (items.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { controller.savePlaylistExplicitly() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) {
                        Text(if (ar) "💾 حفظ القائمة (Save Playlist)" else "💾 Save Playlist", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { controller.clearPlaylist() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (ar) "مسح الكل" else "Clear", fontSize = 11.sp)
                    }
                }
            }

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (ar) {
                            "القائمة فارغة. اضغط «إضافة فيديو» لاختيار ملفات MP4 أو WebM من جهازك."
                        } else {
                            "Playlist is empty. Tap 'Add Videos' to select MP4, WebM, or MKV files via Android SAF."
                        },
                        color = StudioMuted,
                        fontSize = 12.sp,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEachIndexed { index, item ->
                        val isCurrent = index == controller.activeIndex
                        PlaylistRowItem(
                            index = index,
                            totalCount = items.size,
                            item = item,
                            isCurrent = isCurrent,
                            isPlaying = isCurrent && controller.isPlaying,
                            onSelect = { controller.selectTrack(index, autoPlay = true) },
                            onMoveUp = { controller.moveVideoUp(index) },
                            onMoveDown = { controller.moveVideoDown(index) },
                            onRemove = { controller.removeVideoAt(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRowItem(
    index: Int,
    totalCount: Int,
    item: PlaylistItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val bgColor = if (isCurrent) Color(0xFF1E293B) else Color(0xFF11151E)
    val borderColor = if (isCurrent) StudioGreen else Color(0xFF232B3A)

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable { onSelect() },
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Video Thumbnail
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 46.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (item.thumbnail != null && !item.thumbnail.isRecycled) {
                    Image(
                        bitmap = item.thumbnail.asImageBitmap(),
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = StudioMuted,
                    )
                }
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(if (isPlaying) StudioGreen else StudioAmber)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (isPlaying) "LIVE" else "CUE",
                            color = Color.Black,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }

            // Title + Duration + Resolution
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${index + 1}. ${item.displayName}",
                    color = Color.White,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = "${item.durationLabel} • ${item.resolutionLabel}",
                        color = StudioMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }

            // Reorder Up / Down & Remove Controls
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move Up",
                        tint = if (index > 0) Color.White else Color.DarkGray,
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < totalCount - 1,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move Down",
                        tint = if (index < totalCount - 1) Color.White else Color.DarkGray,
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove Video",
                        tint = StudioRed,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Fullscreen Chromeless Clean Broadcast Stage Dialog for TikTok LIVE Mobile Gaming / Screen Share.
 * Double-tap anywhere to exit back to the studio controls.
 */
@Composable
private fun CleanBroadcastStageDialog(
    controller: WebcamController,
    ar: Boolean,
    onExit: () -> Unit,
) {
    var showHint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2500L)
        showHint = false
    }

    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onExit() },
                        onTap = { showHint = !showHint },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            ExoTexturePreview(
                controller = controller,
                videoWidth = controller.videoWidth.coerceAtLeast(1),
                videoHeight = controller.videoHeight.coerceAtLeast(1),
                aspectRatioMode = controller.settings.aspectRatio,
                mirror = controller.settings.mirrorMode,
            )

            if (showHint) {
                Surface(
                    color = Color.Black.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 36.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = if (ar) {
                                "وضع البث النظيف لـ TikTok LIVE • انقر مرتين للخروج"
                            } else {
                                "TikTok LIVE Clean Broadcast Stage • Double-tap to exit"
                            },
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                        TextButton(onClick = onExit) {
                            Text(if (ar) "خروج" else "Exit", color = StudioGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Settings Dialog for configuring Output Method (MJPEG HTTP, TikTok Clean Stage, Direct RTMP),
 * Port, FPS, Resolution, RTMP URL/Stream Key, and Language.
 */
@Composable
private fun WebcamSettingsDialog(
    controller: WebcamController,
    onDismiss: () -> Unit,
) {
    val current = controller.settings
    val ar = current.arabicRtl

    var sinkType by remember { mutableStateOf(current.outputSinkType) }
    var portText by remember { mutableStateOf(current.httpPort.toString()) }
    var allowLan by remember { mutableStateOf(current.allowLan) }
    var fps by remember { mutableIntStateOf(current.targetFps) }
    var portraitRes by remember { mutableStateOf(current.outputHeight >= current.outputWidth) }
    var rtmpUrl by remember { mutableStateOf(current.rtmpUrl) }
    var rtmpKey by remember { mutableStateOf(current.rtmpStreamKey) }
    var arabicRtl by remember { mutableStateOf(current.arabicRtl) }
    var continueBg by remember { mutableStateOf(current.continueInBackground) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (ar) "إعدادات البث والكاميرا الافتراضية • Settings" else "Virtual Webcam & Stream Settings",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (ar) "طريقة ربط البث المباشر (Output Integration):" else "Live Stream Integration Mode:",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                OutputSinkType.entries.forEach { mode ->
                    val selected = sinkType == mode
                    Surface(
                        color = if (selected) Color(0xFF1F2D42) else Color(0xFF141821),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (selected) StudioGreen else StudioCardBorder,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { sinkType = mode },
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (ar) mode.labelAr else mode.labelEn,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) StudioGreen else Color.White,
                                fontSize = 12.sp,
                            )
                            Text(
                                text = if (ar) mode.descAr else mode.descEn,
                                color = StudioMuted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }

                if (sinkType == OutputSinkType.MJPEG_HTTP) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit) },
                        label = { Text("HTTP / MJPEG Port (Default: 8080)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (ar) "السماح بالاتصال عبر Wi-Fi LAN (0.0.0.0)" else "Allow Wi-Fi LAN Access (0.0.0.0)",
                            fontSize = 12.sp,
                        )
                        Switch(checked = allowLan, onCheckedChange = { allowLan = it })
                    }
                }

                if (sinkType == OutputSinkType.RTMP_PUSH) {
                    OutlinedTextField(
                        value = rtmpUrl,
                        onValueChange = { rtmpUrl = it },
                        label = { Text("RTMP Server URL (rtmp://...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = rtmpKey,
                        onValueChange = { rtmpKey = it },
                        label = { Text("RTMP Stream Key (مفتاح البث)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Target FPS
                Text(
                    text = if (ar) "معدل الإطارات (Output FPS): $fps FPS" else "Target Output Frame Rate: $fps FPS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 24, 30).forEach { candidate ->
                        FilterChip(
                            selected = fps == candidate,
                            onClick = { fps = candidate },
                            label = { Text("$candidate FPS") },
                        )
                    }
                }

                // Output orientation
                Text(
                    text = if (ar) "أبعاد مخرج البث (Output Canvas):" else "Output Stream Canvas:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = portraitRes,
                        onClick = { portraitRes = true },
                        label = { Text("9:16 Portrait (720x1280)") },
                    )
                    FilterChip(
                        selected = !portraitRes,
                        onClick = { portraitRes = false },
                        label = { Text("16:9 Landscape (1280x720)") },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(if (ar) "واجهة عربية (RTL)" else "Arabic RTL Interface", fontSize = 12.sp)
                    Switch(checked = arabicRtl, onCheckedChange = { arabicRtl = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (ar) "استمرار التشغيل في الخلفية" else "Continue Playback in Background",
                        fontSize = 12.sp,
                    )
                    Switch(checked = continueBg, onCheckedChange = { continueBg = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val outW = if (portraitRes) 720 else 1280
                    val outH = if (portraitRes) 1280 else 720
                    controller.updateSettings(
                        current.copy(
                            outputSinkType = sinkType,
                            httpPort = (portText.toIntOrNull() ?: 8080).coerceIn(1024, 65535),
                            allowLan = allowLan,
                            targetFps = fps,
                            outputWidth = outW,
                            outputHeight = outH,
                            rtmpUrl = rtmpUrl.trim(),
                            rtmpStreamKey = rtmpKey.trim(),
                            arabicRtl = arabicRtl,
                            continueInBackground = continueBg,
                        )
                    )
                    onDismiss()
                }
            ) {
                Text(if (ar) "حفظ الإعدادات" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (ar) "إلغاء" else "Cancel")
            }
        },
    )
}

/**
 * Honest Architecture & TikTok LIVE Compatibility Guide Dialog.
 */
@Composable
private fun TikTokCompatibilityDialog(
    ar: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (ar) {
                    "دليل الربط مع TikTok LIVE وتطبيقات البث"
                } else {
                    "TikTok LIVE & Streaming Compatibility Guide"
                },
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (ar) {
                        "١. البث من الهاتف فقط بدون كمبيوتر وبدون روت (TikTok LIVE Mobile Gaming / Screen Share):\n" +
                            "• افتح تطبيق TikTok وانتقل إلى LIVE ثم اختر «ألعاب الهاتف / مشاركة الشاشة» (Mobile Gaming).\n" +
                            "• في هذا التطبيق، فعّل زر (ON) واختر «وضع البث النظيف لـ TikTok LIVE».\n" +
                            "• يقوم محرك Media3 ExoPlayer بتمرير الصوت الرقمي الداخلي مباشرة إلى TikTok (عبر ALLOW_CAPTURE_BY_ALL) بدون ضوضاء الميكروفون، ويمكنك تبديل الفيديوهات من إشعار النظام أو النافذة العائمة PiP."
                    } else {
                        "1. Phone-Only TikTok LIVE (No PC, No Root — Mobile Gaming / Screen Share Mode):\n" +
                            "• In TikTok LIVE, select 'Mobile Gaming / Cast Screen'.\n" +
                            "• Turn the OUTPUT switch ON in this app and tap 'Enter TikTok Clean Stage'.\n" +
                            "• Media3 ExoPlayer is configured with ALLOW_CAPTURE_BY_ALL so TikTok captures clean digital internal audio while you switch tracks via notification actions or PiP."
                    },
                    fontSize = 12.sp,
                )

                Text(
                    text = if (ar) {
                        "٢. البث المباشر عبر RTMP (من الهاتف مباشرة):\n" +
                            "• إذا كان حسابك يدعم مفتاح البث (RTMP Stream Key) في TikTok أو YouTube أو Twitch، اختر وضع «Direct RTMP Live Push» من الإعدادات وأدخل رابط RTMP ومفتاح البث ثم فعّل زر ON."
                    } else {
                        "2. Direct Phone-Only RTMP Streaming:\n" +
                            "• Select 'Direct RTMP Live Push' in Settings, enter your TikTok LIVE / YouTube / Twitch RTMP URL and Stream Key, and toggle OUTPUT ON."
                    },
                    fontSize = 12.sp,
                )

                Text(
                    text = if (ar) {
                        "٣. الربط مع الكمبيوتر (TikTok LIVE Studio / OBS Virtual Camera):\n" +
                            "• اختر وضع «MJPEG / HTTP Live Stream» وفعّل زر ON.\n" +
                            "• أضف الرابط الظاهر (http://<phone-ip>:8080/stream.mjpeg أو /player) كمصدر Browser Source أو Media Source في OBS أو TikTok LIVE Studio عبر الـ Wi-Fi أو عبر USB (adb forward tcp:8080 tcp:8080)."
                    } else {
                        "3. Desktop OBS / TikTok LIVE Studio Bridge (Wi-Fi or USB):\n" +
                            "• Enable 'MJPEG / HTTP Live Stream' and toggle OUTPUT ON.\n" +
                            "• Add http://<phone-ip>:8080/stream.mjpeg or /player in OBS Studio or TikTok LIVE Studio."
                    },
                    fontSize = 12.sp,
                )

                Text(
                    text = if (ar) {
                        "ملاحظة تقنية هامة حول نظام أندرويد:\n" +
                            "لا يسمح نظام أندرويد القياسي (بدون صلاحيات Root + LSPosed) لأي تطبيق عادي باستبدال كاميرا الهاتف الفيزيائية (Camera2 HAL) داخل التطبيقات الأخرى. لذلك يوفّر هذا التطبيق الطرق الرسمية الموثوقة التي تعمل فعلياً على الأجهزة الحقيقية."
                    } else {
                        "Android OS Security Note:\n" +
                            "Unrooted Android isolates physical Camera2 HALs inside cameraserver. Replacing the physical sensor inside another app's process requires Root + LSPosed (VCam). This app provides the genuine, unrooted streaming architectures that work reliably on real devices."
                    },
                    color = StudioAmber,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(if (ar) "حسناً، فهمت" else "Got it")
            }
        },
    )
}
