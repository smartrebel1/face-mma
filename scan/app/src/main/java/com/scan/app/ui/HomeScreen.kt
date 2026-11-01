package com.scan.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scan.app.Lang
import com.scan.app.LocalLang
import com.scan.app.ai.AnalysisMode
import com.scan.app.capture.LensService
import com.scan.app.t
import com.scan.app.util.PowerUtils

@Composable
fun HomeScreen(
    onOpenModels: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel()
    val lang: Lang = LocalLang.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val running by vm.lensRunning.collectAsState()

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryOk by remember { mutableStateOf(PowerUtils.isIgnoring(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                batteryOk = PowerUtils.isIgnoring(context)
                vm.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        containerColor = ScanColors.Bg,
        topBar = {
            ScanTopBar(
                title = "SCAN",
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.List, contentDescription = t("السجل", "History"))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = t("الإعدادات", "Settings"))
                    }
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.Filled.Info, contentDescription = t("عن التطبيق", "About"))
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ---- lens status
            CardBox(modifier = Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (running) ScanColors.Ok else ScanColors.TextDim,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (running) t("العدسة تعمل الآن", "Lens is running")
                        else t("العدسة متوقفة", "Lens is off"),
                        color = ScanColors.Text,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    t(
                        "دائرة صغيرة فوق كل التطبيقات: اضغطها لتحديد منطقة، واضغط مطوّلًا للقائمة (السجل، الإعدادات، إغلاق).",
                        "A small bubble over every app: tap to select an area, long-press for the menu (history, settings, close)."
                    ),
                    color = ScanColors.TextDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(14.dp))
                if (running) {
                    SecondaryButton(
                        text = t("إيقاف العدسة", "Stop the lens"),
                        onClick = { LensService.stop(context) }
                    )
                } else {
                    PrimaryButton(
                        text = t("تشغيل العدسة", "Start the lens"),
                        enabled = overlayGranted,
                        onClick = { LensService.start(context) }
                    )
                }
                if (!overlayGranted) {
                    Spacer(Modifier.height(10.dp))
                    ErrorBanner(
                        t(
                            "إذن «الظهور فوق التطبيقات» غير ممنوح، والعدسة لا تعمل بدونه.",
                            "The “display over other apps” permission is missing; the lens needs it."
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(
                        text = t("منح الإذن", "Grant the permission"),
                        onClick = {
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
                }
            }

            // ---- capture settings
            Spacer(Modifier.height(14.dp))
            SectionTitle(t("قبل الالتقاط", "Before capturing"))
            CardBox {
                Text(
                    t("طريقة الإرسال", "How to send"),
                    color = ScanColors.TextDim,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PillChip(
                        text = t("موديل واحد", "One model"),
                        selected = vm.selectionMode == "single",
                        onClick = { vm.setSelectionMode("single") }
                    )
                    PillChip(
                        text = t("مقارنة بين موديلين أو أكثر", "Compare two or more"),
                        selected = vm.selectionMode == "compare",
                        onClick = { vm.setSelectionMode("compare") }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    t("نوع التحليل الافتراضي", "Default analysis mode"),
                    color = ScanColors.TextDim,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnalysisMode.values().forEach { m ->
                        PillChip(
                            text = if (lang == Lang.AR) m.ar else m.en,
                            selected = vm.mode == m,
                            onClick = { vm.setMode(m) }
                        )
                    }
                }
            }

            // ---- models
            Spacer(Modifier.height(14.dp))
            SectionTitle(t("الموديلات المحددة", "Selected models"))
            CardBox {
                if (vm.selectedModels.isEmpty()) {
                    Text(
                        t(
                            "لم تختر أي موديل بعد. أضف مفتاح API من «الموديلات والمفاتيح».",
                            "No model chosen yet. Add an API key in Models & keys."
                        ),
                        color = ScanColors.TextDim,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                } else {
                    vm.selectedModels.forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                m.label,
                                color = ScanColors.Text,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (m.free) Badge(t("مجاني", "Free"), ScanColors.Free)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    text = t("الموديلات والمفاتيح", "Models & keys"),
                    onClick = onOpenModels
                )
            }

            // ---- battery hint
            if (PowerUtils.needsBatteryHint() && !batteryOk) {
                Spacer(Modifier.height(14.dp))
                CardBox(border = androidx.compose.foundation.BorderStroke(1.dp, ScanColors.Warn.copy(alpha = 0.4f))) {
                    Text(
                        t("تحسين البطارية", "Battery optimisation"),
                        color = ScanColors.Warn,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        t(
                            "على ${Build.MANUFACTURER} قد يقتل النظام العدسة. أوقف تحسين البطارية لهذا التطبيق.",
                            "On ${Build.MANUFACTURER} the system may kill the lens. Turn battery optimisation off for this app."
                        ),
                        color = ScanColors.TextDim,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(
                        text = t("فتح إعدادات البطارية", "Open battery settings"),
                        onClick = { PowerUtils.requestIgnoreBattery(context) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            DisclaimerLine()
            Spacer(Modifier.height(28.dp))
        }
    }
}
