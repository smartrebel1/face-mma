package com.scan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scan.app.Lang
import com.scan.app.LocalLang
import com.scan.app.ai.Analysis
import com.scan.app.ai.AiErrorKind
import com.scan.app.ai.ModelResult
import com.scan.app.ai.ar
import com.scan.app.ai.en
import com.scan.app.t

/** The four layers, rendered as cards in the order the model was told to answer. */
@Composable
fun AnalysisCards(analysis: Analysis, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (analysis.observed.isNotBlank()) {
            LayerCard(
                title = t("المُلاحَظ", "Observed"),
                subtitle = t("ما هو مكتوب أو ظاهر حرفيًا", "What is literally written or shown"),
                body = analysis.observed,
                accent = ScanColors.Text
            )
        }
        if (analysis.classification.isNotBlank()) {
            LayerCard(
                title = t("التصنيف الموضوعي", "Classification"),
                subtitle = t("موضوع المحتوى", "Topic of the content"),
                body = analysis.classification,
                accent = ScanColors.Accent
            )
        }
        if (analysis.pattern.isNotBlank()) {
            LayerCard(
                title = t("النمط", "Pattern"),
                subtitle = t(
                    "يظهر فقط عند وجود عدة عناصر من نفس المصدر",
                    "Only meaningful with several items from the same source"
                ),
                body = analysis.pattern,
                accent = ScanColors.Warn
            )
        }
        if (analysis.interpretation.isNotBlank() || analysis.confidence.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ScanColors.Surface, CardShape)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t("قراءة محتملة", "Possible reading"),
                        color = ScanColors.Text,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    if (analysis.confidence.isNotBlank()) {
                        Badge(
                            text = t("الثقة: ", "Confidence: ") + analysis.confidence,
                            color = ScanColors.Accent
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(analysis.interpretation, color = ScanColors.Text, fontSize = 13.sp, lineHeight = 20.sp)
                if (analysis.limits.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        t("حدود القراءة", "Limits"),
                        color = ScanColors.TextDim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(analysis.limits, color = ScanColors.TextDim, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
        if (analysis.notes.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ScanColors.Surface, CardShape)
                    .padding(16.dp)
            ) {
                Text(
                    t("ملاحظات وتحفظات", "Notes and caveats"),
                    color = ScanColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(6.dp))
                analysis.notes.forEach { note ->
                    Text("• $note", color = ScanColors.TextDim, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ScanColors.Surface2.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(
                t(
                    "اسم الحساب أو صورته لا يُثبتان هوية صاحبه. والصورة قد تكون مقتبسة أو ساخرة.",
                    "An account name or photo does not prove identity, and the post may be a quote or sarcasm."
                ),
                color = ScanColors.TextDim,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun LayerCard(title: String, subtitle: String, body: String, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ScanColors.Surface, CardShape)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Text(subtitle, color = ScanColors.TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Text(body, color = ScanColors.Text, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** Results of one or more models: tabs when comparing, one set of cards otherwise. */
@Composable
fun ResultPane(results: List<ModelResult>, modifier: Modifier = Modifier) {
    val lang = LocalLang.current
    if (results.isEmpty()) return
    var index by remember(results) { mutableStateOf(results.indexOfFirst { it.ok }.coerceAtLeast(0)) }

    Column(modifier = modifier) {
        if (results.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                results.forEachIndexed { i, r ->
                    PillChip(
                        text = r.sel.label,
                        selected = i == index,
                        badge = if (r.ok) null else "!",
                        onClick = { index = i }
                    )
                }
            }
        }
        val result = results.getOrNull(index) ?: return@Column
        if (result.ok && result.analysis != null) {
            AnalysisCards(result.analysis)
            Spacer(Modifier.height(10.dp))
            DisclaimerLine()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ScanColors.Surface, CardShape)
                    .padding(16.dp)
            ) {
                Text(
                    t("لم ينجح هذا الموديل", "This model failed"),
                    color = ScanColors.Danger,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    result.errorKind?.let { if (lang == Lang.AR) it.ar() else it.en() }
                        ?: AiErrorKind.UNKNOWN.let { if (lang == Lang.AR) it.ar() else it.en() },
                    color = ScanColors.TextDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                if (result.errorDetail.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        result.errorDetail.take(300),
                        color = ScanColors.TextDim.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ModelChipRow(
    labels: List<String>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEach { label ->
            Text(
                label,
                color = ScanColors.Text,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
        Spacer(Modifier.size(2.dp))
    }
}
