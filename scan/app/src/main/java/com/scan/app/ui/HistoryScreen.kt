package com.scan.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scan.app.LocalLang
import com.scan.app.ai.AnalysisMode
import com.scan.app.data.HistoryEntity
import com.scan.app.t
import com.scan.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val vm: HistoryViewModel = viewModel()
    val items by vm.items.collectAsStateWithLifecycle()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val lang = LocalLang.current

    Scaffold(
        containerColor = ScanColors.Bg,
        topBar = {
            ScanTopBar(
                title = t("السجل", "History"),
                onBack = onBack,
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(onClick = { confirmDeleteAll = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = t("حذف الكل", "Delete all"),
                                tint = ScanColors.Danger
                            )
                        }
                    }
                }
            )
        }
    ) { pad ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = t("لا يوجد تحليل بعد", "Nothing here yet"),
                    subtitle = t(
                        "شغّل العدسة، حدّد منطقة، ثم اضغط تحليل. ستظهر النتائج هنا مع الصورة.",
                        "Turn on the lens, pick an area and press Analyse. Results land here with the image."
                    )
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(items, key = { it.id }) { entity ->
                    HistoryRow(
                        entity = entity,
                        modeLabel = if (lang == com.scan.app.Lang.AR) {
                            AnalysisMode.from(entity.mode).ar
                        } else {
                            AnalysisMode.from(entity.mode).en
                        },
                        onClick = { onOpen(entity.id) },
                        onDelete = { vm.delete(entity) }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            containerColor = ScanColors.Surface,
            title = { Text(t("حذف السجل كله؟", "Delete the whole history?")) },
            text = {
                Text(
                    t(
                        "ستُحذف كل الصور وكل التحليلات المحفوظة.",
                        "Every saved image and analysis will be deleted."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    vm.deleteAll()
                }) { Text(t("حذف", "Delete"), color = ScanColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text(t("إلغاء", "Cancel")) }
            }
        )
    }
}

@Composable
private fun HistoryRow(
    entity: HistoryEntity,
    modeLabel: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ScanColors.Surface)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(path = entity.imagePath)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entity.title.ifBlank { modeLabel },
                color = ScanColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2
            )
            Spacer(Modifier.height(3.dp))
            Text(
                modeLabel + " · " + formatDate(entity.createdAt),
                color = ScanColors.TextDim,
                fontSize = 11.sp
            )
            if (entity.models.isNotBlank()) {
                Text(
                    entity.models,
                    color = ScanColors.TextDim.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = t("حذف", "Delete"),
                tint = ScanColors.TextDim,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun Thumb(path: String?) {
    val bitmap: Bitmap? by produceState<Bitmap?>(initialValue = null, path) {
        val loaded = if (path == null) null else withContext(Dispatchers.IO) {
            ImageUtils.loadSampled(path, 220)
        }
        value = loaded
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ScanColors.Surface2),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("—", color = ScanColors.TextDim)
        }
    }
}

fun formatDate(ts: Long): String {
    val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return fmt.format(Date(ts))
}
