package com.scan.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scan.app.Lang
import com.scan.app.ScanApp
import com.scan.app.ai.AiErrorKind
import com.scan.app.ai.AiException
import com.scan.app.ai.AiModel
import com.scan.app.ai.Analysis
import com.scan.app.ai.AnalysisEngine
import com.scan.app.ai.AnalysisMode
import com.scan.app.ai.AnalysisParser
import com.scan.app.ai.KeyDetector
import com.scan.app.ai.ModelResult
import com.scan.app.ai.Prompts
import com.scan.app.ai.ProviderRegistry
import com.scan.app.ai.ar
import com.scan.app.ai.en
import com.scan.app.capture.LensService
import com.scan.app.data.HistoryEntity
import com.scan.app.data.SavedKey
import com.scan.app.data.SelectedModel
import com.scan.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val scan = ScanApp.get(app)
    private val store = scan.settings
    private val vault = scan.keys

    var selectedModels by mutableStateOf(store.selectedModels)
        private set
    var mode by mutableStateOf(AnalysisMode.from(store.defaultMode))
        private set
    var selectionMode by mutableStateOf(store.selectionMode)
        private set
    var keyCount by mutableStateOf(vault.all().size)
        private set

    val lensRunning: StateFlow<Boolean> = LensService.running

    fun refresh() {
        selectedModels = store.selectedModels
        mode = AnalysisMode.from(store.defaultMode)
        selectionMode = store.selectionMode
        keyCount = vault.all().size
    }

    fun setMode(m: AnalysisMode) {
        mode = m
        store.defaultMode = m.id
    }

    fun setSelectionMode(id: String) {
        selectionMode = id
        store.selectionMode = id
    }

    fun langOf(): Lang = store.lang
}

class ModelsViewModel(app: Application) : AndroidViewModel(app) {

    private val scan = ScanApp.get(app)
    private val store = scan.settings
    private val vault = scan.keys

    var keys by mutableStateOf(vault.all())
        private set
    var selected by mutableStateOf(store.selectedModels)
        private set
    var models by mutableStateOf<Map<String, List<AiModel>>>(emptyMap())
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var lastModelsKeyId by mutableStateOf<String?>(null)
        private set

    fun clearMessages() {
        status = null
        error = null
    }

    /**
     * Detect the provider from the key, validate it against the provider's model list, save the
     * key and expose the image-capable models.
     */
    fun connect(rawKey: String, chosenProvider: String?, baseUrl: String?) {
        val key = rawKey.trim()
        if (key.isEmpty()) {
            error = "الصق المفتاح أولًا"
            return
        }
        val detection = KeyDetector.detect(key)
        val providerId = chosenProvider ?: detection.providerId
        if (providerId == null) {
            error = if (store.lang == Lang.AR) detection.noteAr else detection.noteEn
            return
        }
        val provider = ProviderRegistry.byId(providerId)
        val base = baseUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (provider.needsBaseUrl && base.isNullOrEmpty()) {
            error = if (store.lang == Lang.AR) "أدخل Base URL للمزوّد المتوافق مع OpenAI"
            else "Enter the base URL for the OpenAI-compatible provider"
            return
        }

        busy = true
        error = null
        status = null
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { provider.listModels(key, base) }
                val saved = vault.add(
                    SavedKey(
                        providerId = providerId,
                        key = key,
                        baseUrl = base,
                        label = "",
                        modelCount = list.size
                    )
                )
                keys = vault.all()
                models = models + (saved.id to list)
                lastModelsKeyId = saved.id
                status = if (store.lang == Lang.AR) {
                    "تم الربط ✓ اختر موديلًا أو أكثر من القائمة"
                } else {
                    "Connected ✓ pick one or more models below"
                }
            } catch (e: AiException) {
                error = if (store.lang == Lang.AR) e.kind.ar() else e.kind.en()
                if (e.kind == AiErrorKind.NO_NET || e.kind == AiErrorKind.SERVER) {
                    val saved = vault.add(
                        SavedKey(providerId = providerId, key = key, baseUrl = base)
                    )
                    keys = vault.all()
                    lastModelsKeyId = saved.id
                }
            } catch (e: Exception) {
                error = e.message ?: "error"
            } finally {
                busy = false
            }
        }
    }

    fun loadModels(keyId: String) {
        val saved = vault.byId(keyId) ?: return
        busy = true
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    ProviderRegistry.byId(saved.providerId).listModels(saved.key, saved.baseUrl)
                }
                models = models + (keyId to list)
                lastModelsKeyId = keyId
            } catch (e: AiException) {
                error = if (store.lang == Lang.AR) e.kind.ar() else e.kind.en()
            } finally {
                busy = false
            }
        }
    }

    fun deleteKey(id: String) {
        vault.remove(id)
        keys = vault.all()
        models = models - id
        selected = selected.filterNot { it.keyId == id }
        store.selectedModels = selected
        if (lastModelsKeyId == id) lastModelsKeyId = null
    }

    fun toggle(sel: SelectedModel) {
        val list = store.selectedModels.toMutableList()
        val existing = list.indexOfFirst { it.keyId == sel.keyId && it.modelId == sel.modelId }
        if (existing >= 0) list.removeAt(existing) else list.add(sel)
        store.selectedModels = list
        selected = list
    }

    fun isSelected(keyId: String, modelId: String): Boolean =
        selected.any { it.keyId == keyId && it.modelId == modelId }

    fun modelsOf(keyId: String): List<AiModel> = models[keyId] ?: emptyList()

    fun providerName(providerId: String): String = ProviderRegistry.name(providerId, store.lang)

    fun lang(): Lang = store.lang
}

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val scan = ScanApp.get(app)

    val items: StateFlow<List<HistoryEntity>> = scan.db.history().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(entity: HistoryEntity) {
        viewModelScope.launch {
            scan.db.history().delete(entity.id)
            entity.imagePath?.let { path ->
                withContext(Dispatchers.IO) { File(path).delete() }
            }
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            val dao = scan.db.history()
            val list = items.value
            withContext(Dispatchers.IO) {
                list.forEach { it.imagePath?.let { path -> File(path).delete() } }
                dao.deleteAll()
            }
            File(getApplication<Application>().filesDir, "history").deleteRecursively()
        }
    }
}

data class PreviewState(
    val busy: Boolean = false,
    val progress: String = "",
    val results: List<ModelResult> = emptyList(),
    val error: String? = null,
    val savedId: Long? = null,
    val showSheet: Boolean = false
)

class PreviewViewModel(app: Application) : AndroidViewModel(app) {

    private val scan = ScanApp.get(app)
    private val store = scan.settings
    private val engine = AnalysisEngine(scan.keys)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var state by mutableStateOf(PreviewState())
        private set

    var mode by mutableStateOf(AnalysisMode.from(store.lastModeUsed))
        private set
    var compare by mutableStateOf(store.selectionMode == "compare")
        private set
    var customInstruction by mutableStateOf(store.customInstruction)
        private set

    fun setMode(m: AnalysisMode) {
        mode = m
        store.lastModeUsed = m.id
    }

    fun setCompare(v: Boolean) {
        compare = v
        store.selectionMode = if (v) "compare" else "single"
    }

    fun setCustom(text: String) {
        customInstruction = text
        store.customInstruction = text
    }

    fun dismissSheet() {
        state = state.copy(showSheet = false)
    }

    fun selectedModels(): List<SelectedModel> = store.selectedModels

    fun clearError() {
        state = state.copy(error = null)
    }

    fun analyze(edited: Bitmap?, sourcePath: String) {
        val models = store.selectedModels
        if (models.isEmpty()) {
            state = state.copy(
                error = "لم تختر أي موديل. أضف مفتاحًا واختر موديلًا من «الموديلات والمفاتيح».",
                showSheet = true
            )
            return
        }
        state = state.copy(busy = true, error = null, progress = "", showSheet = true)
        viewModelScope.launch {
            try {
                val b64 = withContext(Dispatchers.IO) {
                    val bitmap = edited ?: ImageUtils.loadSampled(sourcePath, 2000)
                    if (bitmap == null) throw IllegalStateException("image")
                    val data = ImageUtils.toJpegBase64(bitmap, 1600, 85)
                    if (bitmap !== edited) bitmap.recycle()
                    data
                }
                val lang = store.lang
                val system = Prompts.system(lang)
                val user = Prompts.user(mode, lang, customInstruction)
                val fallback = store.fallbackEnabled

                val results = if (compare) {
                    engine.compare(models, b64, system, user)
                } else {
                    engine.singleWithFallback(models, b64, system, user, fallback)
                }

                val anyOk = results.any { it.ok }
                if (!anyOk) {
                    val first = results.firstOrNull()
                    state = state.copy(
                        busy = false,
                        results = results,
                        error = first?.errorKind?.let { kind ->
                            if (lang == Lang.AR) kind.ar() else kind.en()
                        } ?: "no result"
                    )
                    return@launch
                }

                val savedId = withContext(Dispatchers.IO) {
                    saveHistory(results, edited, sourcePath)
                }
                state = state.copy(busy = false, results = results, savedId = savedId)
            } catch (e: Exception) {
                state = state.copy(busy = false, error = e.message ?: "error")
            }
        }
    }

    /** Saves the (possibly blurred) image plus the analysis. */
    private suspend fun saveHistory(results: List<ModelResult>, edited: Bitmap?, sourcePath: String): Long {
        val dir = File(scan.filesDir, "history").apply { mkdirs() }
        val target = File(dir, "hist_${System.currentTimeMillis()}.png")
        val ok = if (edited != null) ImageUtils.savePng(edited, target) else {
            val src = File(sourcePath)
            if (src.exists()) src.copyTo(target, overwrite = true).let { true } else false
        }
        if (!ok) return 0L
        File(sourcePath).takeIf { it.exists() && it.absolutePath != target.absolutePath }?.delete()

        val best = results.firstOrNull { it.ok }?.analysis ?: Analysis()
        val dump = results.joinToString("\n\n") { r ->
            val head = "${r.sel.label} (${ProviderRegistry.name(r.sel.providerId, store.lang)})"
            if (r.ok) "$head\n${formatAnalysis(r.analysis ?: Analysis())}"
            else "$head\n⚠ ${r.errorKind?.let { k -> if (store.lang == Lang.AR) k.ar() else k.en() } ?: ""}"
        }
        val models = results.joinToString(", ") { it.sel.label }
        val entity = HistoryEntity(
            createdAt = System.currentTimeMillis(),
            imagePath = target.absolutePath,
            mode = mode.id,
            models = models,
            analysisJson = json.encodeToString(Analysis.serializer(), best),
            rawText = dump,
            title = (best.observed.take(60).ifBlank { mode.ar })
        )
        return scan.db.history().insert(entity)
    }

    fun saveOnly(edited: Bitmap?, sourcePath: String) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { saveHistory(emptyList(), edited, sourcePath) }
            state = state.copy(savedId = id)
        }
    }

    fun clearEdits() = Unit

    companion object {
        fun formatAnalysis(a: Analysis): String = buildString {
            if (a.observed.isNotBlank()) append("المُلاحَظ: ").append(a.observed).append("\n")
            if (a.classification.isNotBlank()) append("التصنيف: ").append(a.classification).append("\n")
            if (a.pattern.isNotBlank()) append("النمط: ").append(a.pattern).append("\n")
            if (a.interpretation.isNotBlank()) append("القراءة المحتملة: ").append(a.interpretation).append("\n")
            if (a.confidence.isNotBlank()) append("الثقة: ").append(a.confidence).append("\n")
            if (a.limits.isNotBlank()) append("الحدود: ").append(a.limits).append("\n")
            a.notes.forEach { append("• ").append(it).append("\n") }
        }.trim()
    }
}

/** Shared helpers used by more than one screen. */
object UiUtil {
    fun parseAnalysis(text: String): Analysis = AnalysisParser.parse(text)

    fun modelsLine(models: List<SelectedModel>): String =
        models.joinToString(" · ") { it.label }

    fun serialiseSelection(models: List<SelectedModel>): String = Json.encodeToString(
        ListSerializer(SelectedModel.serializer()), models
    )
}
