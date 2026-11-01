package com.scan.app.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scan.app.Lang
import com.scan.app.LocalLang
import com.scan.app.ScanApp
import com.scan.app.ai.AnalysisMode
import com.scan.app.capture.LensService
import com.scan.app.t
import com.scan.app.util.PowerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLanguageChange: (Lang) -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    val scan = ScanApp.get(context)
    val store = scan.settings
    val lang = LocalLang.current
    val scope = rememberCoroutineScope()

    var defaultMode by remember { mutableStateOf(AnalysisMode.from(store.defaultMode)) }
    var autoDelete by remember { mutableIntStateOf(store.autoDeleteDays) }
    var fallback by remember { mutableStateOf(store.fallbackEnabled) }
    var confirmWipe by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = ScanColors.Bg,
        topBar = { ScanTopBar(t("الإعدادات", "Settings"), onBack = onBack) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            SectionTitle(t("اللغة", "Language"))
            CardBox {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillChip(
                        text = "العربية",
                        selected = lang == Lang.AR,
                        onClick = { onLanguageChange(Lang.AR) }
                    )
                    PillChip(
                        text = "English",
                        selected = lang == Lang.EN,
                        onClick = { onLanguageChange(Lang.EN) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    t(
                        "الواجهة عربية من اليمين إلى اليسار افتراضيًا.",
                        "The UI is right-to-left Arabic by default."
                    ),
                    color = ScanColors.TextDim,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionTitle(t("نوع التحليل الافتراضي", "Default analysis mode"))
            CardBox {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnalysisMode.values().forEach { m ->
                        PillChip(
                            text = if (lang == Lang.AR) m.ar else m.en,
                            selected = defaultMode == m,
                            onClick = {
                                defaultMode = m
                                store.defaultMode = m.id
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionTitle(t("السجل", "History"))
            CardBox {
                Text(
                    t("حذف الصور القديمة تلقائيًا", "Auto-delete old screenshots"),
                    color = ScanColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    t(
                        "تُحذف الصورة وتبقى بطاقة التحليل النصية.",
                        "The image is deleted; the text record stays."
                    ),
                    color = ScanColors.TextDim,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0, 7, 30, 90).forEach { days ->
                        PillChip(
                            text = when (days) {
                                0 -> t("إيقاف", "Off")
                                else -> t("$days يوم", "$days days")
                            },
                            selected = autoDelete == days,
                            onClick = {
                                autoDelete = days
                                store.autoDeleteDays = days
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionTitle(t("الموديلات", "Models"))
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            t("التتابع التلقائي عند الفشل", "Fallback on failure"),
                            color = ScanColors.Text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            t(
                                "إذا فشل الموديل أو وصل لحد الطلبات (429) يُجرَّب الموديل التالي.",
                                "If a model fails or hits the rate limit (429), the next one is tried."
                            ),
                            color = ScanColors.TextDim,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                    Switch(
                        checked = fallback,
                        onCheckedChange = {
                            fallback = it
                            store.fallbackEnabled = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = ScanColors.Accent)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionTitle(t("العدسة والبطارية", "Lens and battery"))
            CardBox {
                Text(
                    t(
                        "بعض الشركات (vivo / Xiaomi / Oppo وغيرها) توقف الخدمات في الخلفية. أوقف تحسين البطارية لهذا التطبيق.",
                        "Some vendors (vivo / Xiaomi / Oppo and others) stop background services. Turn battery optimisation off for this app."
                    ),
                    color = ScanColors.TextDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(
                    text = t("إعدادات البطارية", "Battery settings"),
                    onClick = { PowerUtils.requestIgnoreBattery(context) }
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionTitle(t("الخصوصية", "Privacy"))
            CardBox {
                Text(
                    t(
                        "لا نسخ احتياطي لبيانات التطبيق، ولا تصفّح تلقائي، ولا قراءة رسائل. الصور لا تُرسل إلا عند ضغط «تحليل».",
                        "No app-data backup, no scraping, no message reading. Images are sent only when you press Analyse."
                    ),
                    color = ScanColors.TextDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    text = t("عن التطبيق والإخلاء", "About and disclaimer"),
                    onClick = onOpenAbout
                )
                Spacer(Modifier.height(10.dp))
                SecondaryButton(
                    text = t("حذف كل البيانات", "Delete all data"),
                    onClick = { confirmWipe = true }
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "SCAN · 1.0.0",
                color = ScanColors.TextDim,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = ScanColors.Surface,
            title = { Text(t("حذف كل البيانات؟", "Delete all data?")) },
            text = {
                Text(
                    t(
                        "سيُحذف السجل والصور وكل المفاتيح المخزّنة، ولا يمكن التراجع.",
                        "History, images and every stored key will be deleted. This cannot be undone."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    wipeEverything(context)
                }) { Text(t("حذف", "Delete"), color = ScanColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text(t("إلغاء", "Cancel")) }
            }
        )
    }
}

private fun wipeEverything(context: Context) {
    val app = ScanApp.get(context)
    LensService.stop(context)
    app.keys.clear()
    app.settings.clear()
    val dao = app.db.history()
    kotlinx.coroutines.MainScope().launch {
        withContext(Dispatchers.IO) {
            val items = dao.olderThan(Long.MAX_VALUE)
            items.forEach { it.imagePath?.let { path -> File(path).delete() } }
            dao.deleteAll()
            File(context.filesDir, "history").deleteRecursively()
            File(context.filesDir, "shots").deleteRecursively()
        }
    }
}
