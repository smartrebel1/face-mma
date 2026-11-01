package com.scan.app.ui

import android.annotation.SuppressLint

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scan.app.ScanApp
import com.scan.app.ai.AnalysisMode
import com.scan.app.ai.ModelResult
import com.scan.app.t
import com.scan.app.util.ImageUtils
import com.scan.app.util.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        val lang = ScanApp.get(this).settings.lang
        setContent {
            ScanTheme(lang = lang) {
                Surface(color = ScanColors.Bg, modifier = Modifier.fillMaxSize()) {
                    PreviewScreen(path = path, onClose = { finish() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "extra_path"

        fun start(context: Context, path: String) {
            val i = Intent(context, PreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_PATH, path)
            context.startActivity(i)
        }
    }
}

@SuppressLint("ProduceStateDoesNotAssignValue")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(path: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val vm: PreviewViewModel = viewModel()
    val state = vm.state
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val decoded by produceState<Pair<Bitmap, Bitmap>?>(initialValue = null, path) {
        val loaded = withContext(Dispatchers.IO) {
            val bmp = ImageUtils.loadSampled(path, 2400)
            if (bmp == null) null else bmp to ImageUtils.mosaic(bmp, 22)
        }
        value = loaded
    }

    val strokes: SnapshotStateList<BlurStroke> = remember { mutableStateListOf() }
    var painting by remember { mutableStateOf(false) }
    var brushFraction by remember { mutableStateOf(0.06f) }
    val models = remember { vm.selectedModels() }

    fun closeAndDiscard() {
        if (state.savedId == null) {
            val f = File(path)
            if (f.exists()) f.delete()
        }
        onClose()
    }

    androidx.activity.compose.BackHandler { closeAndDiscard() }

    Scaffold(
        containerColor = ScanColors.Bg,
        topBar = {
            ScanTopBar(
                title = t("معاينة اللقطة", "Screenshot preview"),
                onBack = { closeAndDiscard() }
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
            val pair = decoded
            if (pair == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = ScanColors.Accent)
                }
            } else {
                val (bmp, mosaic) = pair
                BlurEditor(
                    original = bmp,
                    mosaic = mosaic,
                    strokes = strokes,
                    brushRadius = bmp.width * brushFraction,
                    painting = painting,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PillChip(
                        text = t("فرشاة الإخفاء", "Hide brush"),
                        selected = painting,
                        onClick = { painting = !painting }
                    )
                    PillChip(
                        text = t("تراجع", "Undo"),
                        selected = false,
                        onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }
                    )
                    PillChip(
                        text = t("مسح الكل", "Clear"),
                        selected = false,
                        onClick = { strokes.clear() }
                    )
                    PillChip(
                        text = t("فرشاة أصغر", "Smaller"),
                        selected = false,
                        onClick = { brushFraction = (brushFraction - 0.02f).coerceAtLeast(0.02f) }
                    )
                    PillChip(
                        text = t("فرشاة أكبر", "Bigger"),
                        selected = false,
                        onClick = { brushFraction = (brushFraction + 0.02f).coerceAtMost(0.2f) }
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    t(
                        "اختر نوع التحليل قبل الإرسال",
                        "Pick the analysis mode before sending"
                    ),
                    color = ScanColors.TextDim,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnalysisMode.values().forEach { m ->
                        PillChip(
                            text = if (com.scan.app.LocalLang.current == com.scan.app.Lang.AR) m.ar else m.en,
                            selected = vm.mode == m,
                            onClick = { vm.chooseMode(m) }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PillChip(
                        text = t("موديل واحد", "One model"),
                        selected = !vm.compare,
                        onClick = { vm.chooseCompare(false) }
                    )
                    PillChip(
                        text = t("مقارنة بين موديلين أو أكثر", "Compare two or more"),
                        selected = vm.compare,
                        onClick = { vm.chooseCompare(true) }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (models.isEmpty()) {
                        t("لا يوجد موديل محدد — أضف مفتاحًا أولًا", "No model selected — add a key first")
                    } else {
                        t("الموديلات: ", "Models: ") + models.joinToString(", ") { it.label }
                    },
                    color = if (models.isEmpty()) ScanColors.Warn else ScanColors.TextDim,
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(16.dp))
                PrimaryButton(
                    text = t("تحليل", "Analyse"),
                    loading = state.busy,
                    enabled = !state.busy,
                    onClick = {
                        scope.launch {
                            val edited = withContext(Dispatchers.Default) {
                                renderWithBlur(bmp, mosaic, strokes.toList())
                            }
                            vm.analyze(edited, path)
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton(
                    text = t("حفظ في السجل بدون تحليل", "Save to history without analysis"),
                    onClick = {
                        scope.launch {
                            val edited = withContext(Dispatchers.Default) {
                                renderWithBlur(bmp, mosaic, strokes.toList())
                            }
                            vm.saveOnly(edited, path)
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
                DisclaimerLine()
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                ) {
                    Text(
                        t(
                            "الصورة تبقى على الجهاز حتى تضغط «تحليل»؛ عندها تُرسل إلى المزوّد المختار فقط.",
                            "The image stays on the device until you tap Analyse; then it goes only to the chosen provider."
                        ),
                        color = ScanColors.TextDim.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }

    if (state.showSheet) {
        ModalBottomSheet(
            onDismissRequest = { vm.dismissSheet() },
            sheetState = sheetState,
            containerColor = ScanColors.Surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    t("نتيجة التحليل", "Analysis result"),
                    color = ScanColors.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                if (state.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = ScanColors.Accent
                        )
                        Text(
                            t("جارٍ التحليل…", "Analysing…"),
                            color = ScanColors.TextDim,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                }
                state.error?.let { err ->
                    ErrorBanner(err)
                    Spacer(Modifier.height(10.dp))
                    if (models.isEmpty()) {
                        SecondaryButton(
                            text = t("افتح الموديلات والمفاتيح", "Open models & keys"),
                            onClick = {
                                context.startActivity(
                                    android.content.Intent(context, com.scan.app.MainActivity::class.java)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        .putExtra(
                                            com.scan.app.MainActivity.EXTRA_SCREEN,
                                            com.scan.app.MainActivity.EXTRA_SCREEN_MODELS
                                        )
                                )
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (state.results.isNotEmpty()) {
                    ResultPane(state.results)
                    Spacer(Modifier.height(14.dp))
                    SecondaryButton(
                        text = t("مشاركة النتيجة كنص", "Share result as text"),
                        onClick = {
                            ShareUtils.shareText(context, shareTextOf(state.results))
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                SecondaryButton(
                    text = t("إغلاق", "Close"),
                    onClick = { vm.dismissSheet() }
                )
            }
        }
    }
}

fun shareTextOf(results: List<ModelResult>): String = buildString {
    append("SCAN · تحليل لقطة شاشة\n\n")
    results.forEach { r ->
        append("— ").append(r.sel.label).append(" —\n")
        if (r.ok && r.analysis != null) {
            append(PreviewViewModel.formatAnalysis(r.analysis))
        } else {
            append("تعذّر: ").append(r.errorKind?.name ?: "?")
        }
        append("\n\n")
    }
    append("التحليل مساعد وليس إثباتًا نهائيًا، وقد يخطئ.")
}
