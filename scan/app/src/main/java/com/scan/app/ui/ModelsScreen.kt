package com.scan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scan.app.Lang
import com.scan.app.LocalLang
import com.scan.app.ai.AiModel
import com.scan.app.ai.KeyDetector
import com.scan.app.ai.ProviderRegistry
import com.scan.app.data.SelectedModel
import com.scan.app.t

@Composable
fun ModelsScreen(onBack: () -> Unit) {
    val vm: ModelsViewModel = viewModel()
    val lang: Lang = LocalLang.current

    var keyText by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var providerOverride by remember { mutableStateOf<String?>(null) }
    var baseUrl by remember { mutableStateOf("") }

    LaunchedEffect(vm.keys.size) { vm.ensureModelsLoaded() }

    val detection = remember(keyText) { KeyDetector.detect(keyText) }
    val effectiveProvider = providerOverride ?: detection.providerId
    val needsBase = effectiveProvider == "custom"

    Scaffold(
        containerColor = ScanColors.Bg,
        topBar = { ScanTopBar(t("الموديلات والمفاتيح", "Models & keys"), onBack = onBack) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            CardBox {
                Text(
                    t("أضف مفتاح API", "Add an API key"),
                    color = ScanColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    t(
                        "الصق المفتاح وسيتعرّف التطبيق على المزوّد تلقائيًا، ثم يتحقق من المفتاح ويعرض الموديلات التي تقبل الصور فقط.",
                        "Paste the key; the app detects the provider, validates the key and lists only models that accept images."
                    ),
                    color = ScanColors.TextDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = keyText,
                    onValueChange = {
                        keyText = it
                        providerOverride = null
                    },
                    label = { Text(t("الصق مفتاح API", "Paste API key")) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillChip(
                        text = if (showKey) t("إخفاء", "Hide") else t("إظهار", "Show"),
                        selected = false,
                        onClick = { showKey = !showKey }
                    )
                    Spacer(Modifier.size(8.dp))
                    if (keyText.isNotBlank()) {
                        Text(
                            text = if (lang == Lang.AR) detection.noteAr else detection.noteEn,
                            color = if (detection.providerId != null) ScanColors.Ok else ScanColors.Warn,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    t("المزوّد", "Provider"),
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
                    ProviderRegistry.all.forEach { p ->
                        PillChip(
                            text = if (lang == Lang.AR) p.nameAr() else p.nameEn(),
                            selected = effectiveProvider == p.id,
                            onClick = { providerOverride = p.id }
                        )
                    }
                }

                if (needsBase) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.example.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    text = t("اختبار وربط", "Test & connect"),
                    loading = vm.busy,
                    enabled = keyText.isNotBlank(),
                    onClick = {
                        vm.connect(keyText, effectiveProvider, baseUrl.ifBlank { null })
                        keyText = ""
                        baseUrl = ""
                        providerOverride = null
                    }
                )
                vm.status?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = ScanColors.Ok, fontSize = 12.sp)
                }
                vm.error?.let {
                    Spacer(Modifier.height(10.dp))
                    ErrorBanner(it)
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle(t("المفاتيح المحفوظة", "Saved keys"))

            if (vm.keys.isEmpty()) {
                CardBox {
                    Text(
                        t("لا يوجد مفتاح محفوظ بعد.", "No saved key yet."),
                        color = ScanColors.TextDim,
                        fontSize = 13.sp
                    )
                }
            }

            vm.keys.forEach { saved ->
                CardBox(modifier = Modifier.padding(bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                vm.providerName(saved.providerId),
                                color = ScanColors.Text,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                saved.masked(),
                                color = ScanColors.TextDim,
                                fontSize = 12.sp
                            )
                            if (!saved.baseUrl.isNullOrBlank()) {
                                Text(
                                    saved.baseUrl ?: "",
                                    color = ScanColors.TextDim,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        IconButton(onClick = { vm.loadModels(saved.id) }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = t("تحديث الموديلات", "Refresh models"),
                                tint = if (vm.loadingModels) ScanColors.Accent else ScanColors.TextDim
                            )
                        }
                        IconButton(onClick = { vm.deleteKey(saved.id) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = t("حذف", "Delete"),
                                tint = ScanColors.Danger
                            )
                        }
                    }

                    val list: List<AiModel> = vm.modelsOf(saved.id)
                    if (list.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (vm.loadingModels) {
                                t("جارٍ جلب الموديلات…", "Loading models…")
                            } else {
                                t(
                                    "اضغط زر التحديث لجلب موديلات هذا المفتاح.",
                                    "Tap refresh to load this key's models."
                                )
                            },
                            color = ScanColors.TextDim,
                            fontSize = 12.sp
                        )
                    } else {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            t(
                                "اختر موديلًا أو أكثر (تُعرض موديلات الصور فقط):",
                                "Tick one or more models (image models only):"
                            ),
                            color = ScanColors.TextDim,
                            fontSize = 12.sp
                        )
                        list.take(40).forEach { model ->
                            val sel = SelectedModel(
                                providerId = saved.providerId,
                                keyId = saved.id,
                                modelId = model.id,
                                label = model.label,
                                free = model.free
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    CheckRow(
                                        text = model.id,
                                        checked = vm.isSelected(saved.id, model.id),
                                        onClick = { vm.toggle(sel) }
                                    )
                                }
                                if (model.free) Badge(t("مجاني", "Free"), ScanColors.Free)
                            }
                        }
                        if (list.size > 40) {
                            Text(
                                t(
                                    "تم عرض أول 40 موديلًا.",
                                    "Showing the first 40 models."
                                ),
                                color = ScanColors.TextDim,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            if (vm.selected.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SectionTitle(t("الموديلات المحددة", "Selected models"))
                CardBox(modifier = Modifier.padding(bottom = 16.dp)) {
                    vm.selected.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                m.label,
                                color = ScanColors.Text,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (m.free) Badge(t("مجاني", "Free"), ScanColors.Free)
                            IconButton(onClick = { vm.toggle(m) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = ScanColors.TextDim,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ScanColors.Surface2.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    t(
                        "المفاتيح مشفّرة على الجهاز وتظهر مقنّعة فقط، ولا تُرسل إلا إلى المزوّد الخاص بها.",
                        "Keys are encrypted on the device, shown masked, and sent only to their own provider."
                    ),
                    color = ScanColors.TextDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
