package com.scan.app.ui

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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scan.app.data.SourceGroup
import com.scan.app.t

/**
 * Every capture the lens ever sent, grouped by account. Search finds a name even when the archive
 * holds thirty or more shots, and tapping a group opens its full set for analysis.
 */
@Composable
fun LibraryScreen(onBack: () -> Unit, onOpenSource: (String) -> Unit) {
    val vm: LibraryViewModel = viewModel()
    var confirmKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        containerColor = ScanColors.Bg,
        topBar = { ScanTopBar(title = t("المكتبة", "Library"), onBack = onBack) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                t(
                    "كل لقطة يُرسلها الزر العائم تُحفظ هنا تلقائيًا، قبل أي تحليل. ابحث باسم الحساب ليجمع التطبيق كل لقطاته ثم يحلّلها معًا.",
                    "Every shot the floating button sends is archived here before any analysis. Search the account name and the app gathers all of its captures, then analyses them together."
                ),
                color = ScanColors.TextDim,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.updateQuery(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(t("ابحث باسم الحساب أو المصدر", "Search account or source")) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
            )
            Spacer(Modifier.height(10.dp))

            Text(
                t(
                    "إجمالي اللقطات المحفوظة: ${vm.total} · المجموعات: ${vm.groups.size}",
                    "Archived captures: ${vm.total} · groups: ${vm.groups.size}"
                ),
                color = ScanColors.TextDim,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(6.dp))

            if (vm.groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = t("المكتبة فارغة", "The library is empty"),
                        subtitle = t(
                            "شغّل العدسة، اضغط الزر، وستصل اللقطة إلى هنا مباشرة حتى لو أغلقت شاشة المعاينة.",
                            "Turn the lens on, tap the button, and the shot lands here even if you close the preview."
                        )
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.groups, key = { group -> group.key.ifEmpty { "__none__" } }) { group ->
                        GroupRow(
                            group = group,
                            onClick = { onOpenSource(group.key) },
                            onDelete = { confirmKey = group.key }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    val key = confirmKey
    if (key != null) {
        AlertDialog(
            onDismissRequest = { confirmKey = null },
            title = { Text(t("حذف كل لقطات هذه المجموعة؟", "Delete every capture in this group?")) },
            text = {
                Text(
                    t(
                        "ستُحذف الصور من الجهاز، ولا يمكن التراجع.",
                        "The images are removed from the device and this cannot be undone."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteGroup(key)
                    confirmKey = null
                }) { Text(t("حذف", "Delete"), color = ScanColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmKey = null }) { Text(t("إلغاء", "Cancel")) }
            },
            containerColor = ScanColors.Surface
        )
    }
}

@Composable
private fun GroupRow(group: SourceGroup, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ScanColors.Surface)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(path = group.thumb)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (group.key.isBlank()) t("بدون مصدر محدد", "No source yet") else group.label,
                color = ScanColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(Modifier.height(3.dp))
            Text(
                t(
                    "${group.count} لقطة · آخر لقطة ${formatDate(group.lastAt)}",
                    "${group.count} captures · last ${formatDate(group.lastAt)}"
                ),
                color = ScanColors.TextDim,
                fontSize = 11.sp
            )
            if (group.count >= 3) {
                Spacer(Modifier.height(4.dp))
                Badge(t("جاهز لتحليل نمط", "Ready for a pattern run"), ScanColors.Ok)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = t("حذف المجموعة", "Delete group"),
                tint = ScanColors.TextDim,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
