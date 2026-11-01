package com.scan.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scan.app.Lang
import com.scan.app.LocalLang
import com.scan.app.ai.AnalysisMode
import com.scan.app.data.CaptureRecord
import com.scan.app.t
import com.scan.app.util.ImageUtils
import com.scan.app.util.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One account: the whole set of its captures, which can be trimmed, renamed, deleted, and above
 * all analysed together -- 30 captures become one grounded result instead of 30 separate guesses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(sourceKey: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: SourceViewModel = viewModel()
    val lang = LocalLang.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var nameDraft by remember { mutableStateOf("") }
    var renamedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(sourceKey) {
        vm.load(sourceKey)
        nameDraft = vm.label
        renamedOnce = false
    }

    Scaffold(
        containerColor = ScanColors.Bg,
        topBar = {
            ScanTopBar(
                title = vm.label.ifBlank { t("بدون مصدر", "No source") },
                onBack = onBack,
                actions = {
                    if (vm.selected.isNotEmpty()) {
                        IconButton(onClick = { vm.deleteSelected() }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = t("حذف المحدد", "Delete selected"),
                                tint = ScanColors.Danger
                            )
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                t(
                    "${vm.captures.size} لقطة محفوظة لهذا المصدر · ${vm.selected.size} مختارة",
                    "${vm.captures.size} captures for this source · ${vm.selected.size} selected"
                ),
                color = ScanColors.TextDim,
                fontSize = 12.sp
            )
            Text(
                t(
                    "فولدر هذا الحساب: library/${vm.folder}",
                    "This account's folder: library/${vm.folder}"
                ),
                color = ScanColors.TextDim.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(t("اسم المصدر / الحساب", "Source / account name")) }
            )
            if (vm.sourceKey.isBlank()) {
                Text(
                    t(
                        "المجموعة دي بدون اسم. اكتب الاسم فوق واضغط «تسمية كل لقطات المجموعة» مرة واحدة، أو شغّل «تسمية تلقائية» من المكتبة ليقرأ التطبيق الأسماء من الصور.",
                        "This group has no name. Type it above and press “Name every capture in this group” once, or run the automatic naming in the library."
                    ),
                    color = ScanColors.Warn,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    vm.renameGroup(nameDraft)
                    renamedOnce = true
                }) {
                    Text(t("تسمية كل لقطات المجموعة", "Name every capture in this group"))
                }
                if (renamedOnce) {
                    Text(t("تم ✓", "Saved ✓"), color = ScanColors.Ok, fontSize = 11.sp)
                }
            }
            Text(
                t(
                    "اسم مختلف = مجموعة مختلفة. اكتب نفس الاسم لكل لقطات الشخص نفسه.",
                    "A different name means a different group — use one name per person."
                ),
                color = ScanColors.TextDim.copy(alpha = 0.8f),
                fontSize = 10.sp
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
                        text = if (lang == Lang.AR) m.ar else m.en,
                        selected = vm.mode == m,
                        onClick = { vm.chooseMode(m) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PillChip(
                    text = t("موديل واحد", "One model"),
                    selected = !vm.compare,
                    onClick = { vm.chooseCompare(false) }
                )
                PillChip(
                    text = t("مقارنة بين موديلين", "Compare two"),
                    selected = vm.compare,
                    onClick = { vm.chooseCompare(true) }
                )
                PillChip(
                    text = t("تحديد الكل", "Select all"),
                    selected = false,
                    onClick = { vm.selectAll() }
                )
                PillChip(
                    text = t("مسح التحديد", "Clear"),
                    selected = false,
                    onClick = { vm.clearSelection() }
                )
            }

            Spacer(Modifier.height(10.dp))
            if (vm.captures.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = t("لا توجد لقطات", "No captures"),
                        subtitle = t("التقط من الزر العائم أولًا.", "Capture something from the floating button first.")
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(vm.captures, key = { it.id }) { rec ->
                        CaptureTile(
                            record = rec,
                            selected = rec.id in vm.selected,
                            onClick = { vm.toggle(rec.id) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = if (vm.selected.isEmpty()) {
                    t("اختر لقطة واحدة على الأقل", "Pick at least one capture")
                } else {
                    t(
                        "تحليل ${vm.selected.size} لقطة معًا",
                        "Analyse ${vm.selected.size} captures together"
                    )
                },
                enabled = vm.selected.isNotEmpty() && !vm.busy,
                loading = vm.busy,
                onClick = { vm.analyze() }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                t(
                    "تُرسل الصور إلى المزوّد المختار فقط عند الضغط على التحليل، وتُحلَّل على دفعات ثم تُجمع في نتيجة واحدة.",
                    "Images go to the chosen provider only when you press Analyse; they are read in batches and merged into one result."
                ),
                color = ScanColors.TextDim.copy(alpha = 0.8f),
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(14.dp))
        }
    }

    if (vm.showSheet) {
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
                    t("نتيجة تحليل السلسلة", "Series result"),
                    color = ScanColors.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    t("${vm.captures.size} لقطة في هذه المجموعة", "${vm.captures.size} captures in this group"),
                    color = ScanColors.TextDim,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))

                if (vm.busy || vm.info.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (vm.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = ScanColors.Accent
                            )
                        }
                        Text(
                            vm.info.ifBlank { t("جارٍ التحليل…", "Analysing…") },
                            color = ScanColors.TextDim,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                vm.error?.let { err ->
                    ErrorBanner(err)
                    Spacer(Modifier.height(10.dp))
                }

                if (vm.results.isNotEmpty()) {
                    ResultPane(vm.results)
                    Spacer(Modifier.height(14.dp))
                    SecondaryButton(
                        text = t("مشاركة النتيجة كنص", "Share result as text"),
                        onClick = { ShareUtils.shareText(context, shareTextOf(vm.results)) }
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

@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun CaptureTile(record: CaptureRecord, selected: Boolean, onClick: () -> Unit) {
    val bitmap: Bitmap? by produceState<Bitmap?>(initialValue = null, record.file) {
        value = withContext(Dispatchers.IO) { ImageUtils.loadSampled(record.file, 420) }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(ScanColors.Surface2)
            .clickable { onClick() }
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (selected) ScanColors.Accent.copy(alpha = 0.18f) else Color.Transparent)
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ScanColors.Accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color(0xFF04202A),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        if (record.analyzed) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(ScanColors.Surface.copy(alpha = 0.85f), RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(t("تم تحليلها", "Analysed"), color = ScanColors.Ok, fontSize = 8.sp)
            }
        }
    }
}
