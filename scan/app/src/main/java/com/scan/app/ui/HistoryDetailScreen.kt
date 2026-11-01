package com.scan.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scan.app.ScanApp
import com.scan.app.ai.Analysis
import com.scan.app.ai.AnalysisMode
import com.scan.app.ai.AnalysisParser
import com.scan.app.data.HistoryEntity
import com.scan.app.t
import com.scan.app.util.ImageUtils
import com.scan.app.util.PdfExport
import com.scan.app.util.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HistoryDetailScreen(id: Long, onBack: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scan = ScanApp.get(context)
    val dao = scan.db.history()
    val scope = rememberCoroutineScope()
    val entity by dao.observeById(id)
        .collectAsStateWithLifecycle(initialValue = null)

    var notes by remember(entity?.id) { mutableStateOf(entity?.notes ?: "") }
    var sourceUrl by remember(entity?.id) { mutableStateOf(entity?.sourceUrl ?: "") }
    var showRaw by remember { mutableStateOf(false) }
    val disclaimerText = t(
        "التحليل مساعد وليس إثباتًا نهائيًا، وقد يخطئ.",
        "Analysis is an aid, not proof, and it can be wrong."
    )

    Scaffold(
        containerColor = ScanColors.Bg,
        topBar = {
            ScanTopBar(
                title = t("تفاصيل التحليل", "Analysis details"),
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        val e = entity ?: return@IconButton
                        ShareUtils.shareText(context, fullText(e), "SCAN")
                    }) { Icon(Icons.Filled.Share, contentDescription = t("مشاركة", "Share")) }
                    IconButton(onClick = {
                        val e = entity ?: return@IconButton
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                PdfExport.export(
                                    context,
                                    "SCAN — " + formatDate(e.createdAt),
                                    fullText(e),
                                    e.imagePath,
                                    disclaimerText
                                )
                            }
                            if (file != null) ShareUtils.shareFile(context, file, "application/pdf", "PDF")
                        }
                    }) { Icon(Icons.Filled.Share, contentDescription = "PDF") }
                    IconButton(onClick = {
                        val e = entity ?: return@IconButton
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                e.imagePath?.let { p -> File(p).delete() }
                                dao.delete(e.id)
                            }
                            onDeleted()
                        }
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = t("حذف", "Delete"),
                            tint = ScanColors.Danger
                        )
                    }
                }
            )
        }
    ) { pad ->
        val e = entity
        if (e == null) {
            Box(Modifier.fillMaxSize().padding(pad))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    AnalysisMode.from(e.mode).ar + " · " + formatDate(e.createdAt),
                    color = ScanColors.TextDim,
                    fontSize = 12.sp
                )
                if (e.models.isNotBlank()) {
                    Text(e.models, color = ScanColors.TextDim, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))

                Screenshot(path = e.imagePath)

                Spacer(Modifier.height(14.dp))
                val analysis: Analysis = remember(e.id, e.analysisJson) {
                    if (e.analysisJson.isBlank()) Analysis() else AnalysisParser.parse(e.analysisJson)
                }
                if (analysis.hasAny()) {
                    AnalysisCards(analysis)
                } else {
                    CardBox {
                        Text(
                            t("لا يوجد تحليل لهذه اللقطة.", "This capture has no analysis."),
                            color = ScanColors.TextDim,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionTitle(t("ملاحظاتي", "My notes"))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text(t("ملاحظات", "Notes")) }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = sourceUrl,
                    onValueChange = { sourceUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(t("رابط المصدر (اختياري)", "Source link (optional)")) }
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton(
                    text = t("حفظ الملاحظات", "Save notes"),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                dao.update(e.copy(notes = notes, sourceUrl = sourceUrl))
                            }
                        }
                    }
                )

                if (e.rawText.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillChip(
                            text = t("نص إجابات الموديلات", "Raw model answers"),
                            selected = showRaw,
                            onClick = { showRaw = !showRaw }
                        )
                    }
                    if (showRaw) {
                        Spacer(Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ScanColors.Surface, RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                e.rawText,
                                color = ScanColors.TextDim,
                                fontSize = 11.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        text = t("مشاركة نص", "Share text"),
                        modifier = Modifier.weight(1f),
                        onClick = { ShareUtils.shareText(context, fullText(e), "SCAN") }
                    )
                    SecondaryButton(
                        text = t("PDF", "PDF"),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    PdfExport.export(
                                        context,
                                        "SCAN — " + formatDate(e.createdAt),
                                        fullText(e),
                                        e.imagePath,
                                        "التحليل مساعد وليس إثباتًا نهائيًا، وقد يخطئ."
                                    )
                                }
                                if (file != null) {
                                    ShareUtils.shareFile(context, file, "application/pdf", "PDF")
                                }
                            }
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                DisclaimerLine()
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun Screenshot(path: String?) {
    val bitmap: Bitmap? by produceState<Bitmap?>(initialValue = null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            ImageUtils.loadSampled(path, 1400)
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        CardBox {
            Text(
                t("الصورة غير متاحة (ربما حُذفت تلقائيًا).", "The image is gone (maybe auto-deleted)."),
                color = ScanColors.TextDim,
                fontSize = 12.sp
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f))
                .clip(RoundedCornerShape(16.dp))
                .background(ScanColors.Surface2)
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun fullText(e: HistoryEntity): String = buildString {
    append("SCAN — ").append(formatDate(e.createdAt)).append("\n")
    if (e.models.isNotBlank()) append("الموديلات: ").append(e.models).append("\n")
    append("\n")
    val a = if (e.analysisJson.isBlank()) Analysis() else AnalysisParser.parse(e.analysisJson)
    if (a.hasAny()) append(PreviewViewModel.formatAnalysis(a)) else append(e.rawText)
    if (e.notes.isNotBlank()) append("\n\nملاحظاتي:\n").append(e.notes)
    if (e.sourceUrl.isNotBlank()) append("\n\nالمصدر: ").append(e.sourceUrl)
    append("\n\nالتحليل مساعد وليس إثباتًا نهائيًا، وقد يخطئ.")
}
