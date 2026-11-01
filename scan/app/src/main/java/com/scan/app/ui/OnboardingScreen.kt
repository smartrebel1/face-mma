package com.scan.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.scan.app.ScanApp
import com.scan.app.t
import com.scan.app.util.PowerUtils

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notifGranted by remember { mutableStateOf(notifAllowed(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                notifGranted = notifAllowed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    Scaffold(containerColor = ScanColors.Bg) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp)
        ) {
            Spacer(Modifier.height(18.dp))
            Text(
                "SCAN",
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                color = ScanColors.Accent,
                letterSpacing = 6.sp
            )
            Text(
                t("سكان — عدسة تحليل لقطات الشاشة", "SCAN — screenshot analysis lens"),
                color = ScanColors.TextDim,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(22.dp))

            StepCard(
                number = "1",
                title = t("الظهور فوق التطبيقات", "Display over other apps"),
                body = t(
                    "تحتاج العدسة العائمة إلى هذا الإذن لتظهر فوق كل التطبيقات. التطبيق لا يقرأ محتوى أي تطبيق آخر؛ أنت من يحدد المنطقة ويضغط «التقاط».",
                    "The floating lens needs this to sit above other apps. The app does not read other apps; you pick the area and press Capture."
                ),
                done = overlayGranted,
                actionLabel = t("فتح الإعدادات", "Open settings"),
                onAction = {
                    val i = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.packageName)
                    )
                    try {
                        context.startActivity(i)
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }
                }
            )

            Spacer(Modifier.height(14.dp))

            StepCard(
                number = "2",
                title = t("الإشعارات", "Notifications"),
                body = t(
                    "إشعار دائم بسيط يخبرك أن العدسة تعمل، ومع زر «إيقاف».",
                    "A small persistent notification tells you the lens is on, with a Stop button."
                ),
                done = notifGranted,
                actionLabel = t("السماح", "Allow"),
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        notifGranted = true
                    }
                }
            )

            Spacer(Modifier.height(14.dp))

            CardBox {
                Text(
                    t("الخصوصية أولًا", "Privacy first"),
                    color = ScanColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    t(
                        "الصور تبقى على جهازك حتى تضغط «تحليل»؛ عندها فقط تُرسل الصورة إلى المزوّد الذي اخترته. لا تصفّح تلقائي، ولا قراءة رسائل خاصة، ولا خدمات إتاحة.",
                        "Images stay on your device until you press Analyse; only then is the image sent to the provider you chose. No scraping, no reading private messages, no accessibility tricks."
                    ),
                    color = ScanColors.TextDim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }

            if (PowerUtils.needsBatteryHint()) {
                Spacer(Modifier.height(14.dp))
                CardBox {
                    Text(
                        t("نصيحة لأجهزة vivo / Xiaomi / Oppo", "Hint for vivo / Xiaomi / Oppo"),
                        color = ScanColors.Warn,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        t(
                            "أوقف تحسين البطارية لهذا التطبيق وإلا قد يقتل النظام العدسة أثناء العمل.",
                            "Disable battery optimisation for this app or the system may kill the lens."
                        ),
                        color = ScanColors.TextDim,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(
                        text = t("فتح إعدادات البطارية", "Open battery settings"),
                        onClick = { PowerUtils.requestIgnoreBattery(context) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = t("ابدأ", "Start"),
                enabled = overlayGranted,
                onClick = {
                    ScanApp.get(context).settings.onboardingDone = true
                    onDone()
                }
            )
            if (!overlayGranted) {
                Spacer(Modifier.height(8.dp))
                Text(
                    t(
                        "لا يمكن تشغيل العدسة قبل منح إذن الظهور فوق التطبيقات.",
                        "The lens cannot start before the overlay permission is granted."
                    ),
                    color = ScanColors.Warn,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun StepCard(
    number: String,
    title: String,
    body: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        if (done) ScanColors.Ok.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = ScanColors.Ok,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(number, color = ScanColors.Text, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = ScanColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    if (done) t("تم", "Done") else t("مطلوب", "Required"),
                    color = if (done) ScanColors.Ok else ScanColors.Warn,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(body, color = ScanColors.TextDim, fontSize = 13.sp, lineHeight = 19.sp)
        if (!done) {
            Spacer(Modifier.height(12.dp))
            SecondaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

fun notifAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}
