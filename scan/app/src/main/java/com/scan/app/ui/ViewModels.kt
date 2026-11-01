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
import com.scan.app.ai.SeriesEngine
import com.scan.app.ai.SeriesProgress
import com.scan.app.capture.LensService
import com.scan.app.data.CaptureRecord
import com.scan.app.data.CaptureStore
import com.scan.app.data.HistoryEntity
import com.scan.app.data.SourceGroup
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

    fun chooseMode(m: AnalysisMode) {
        mode = m
        store.defaultMode = m.id
    }

    fun chooseSelectionMode(id: String) {
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
    var loadingModels by mutableStateOf(false)
        private set
    private val queried = mutableSetOf<String>()

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

    /** Loads model lists for every saved key once, so a saved key still shows its models. */
    fun ensureModelsLoaded() {
        keys.forEach { k ->
            if (k.id !in queried && models[k.id].isNullOrEmpty()) {
                queried.add(k.id)
                loadModels(k.id)
            }
        }
    }

    fun loadModels(keyId: String) {
        val saved = vault.byId(keyId) ?: return
        queried.add(keyId)
        loadingModels = true
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
                loadingModels = false
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
    private val library = CaptureStore.get(app)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The archived capture this preview belongs to, so labels land in the library. */
    var captureId by mutableStateOf<String?>(null)
        private set

    var state by mutableStateOf(PreviewState())
        private set

    var mode by mutableStateOf(AnalysisMode.from(store.lastModeUsed))
        private set
    var compare by mutableStateOf(store.selectionMode == "compare")
        private set
    var customInstruction by mutableStateOf(store.customInstruction)
        private set

    /** Account/source label that groups captures of the same person. */
    var sourceLabel by mutableStateOf("")
        private set
    var sourceCount by mutableStateOf(0)
        private set

    fun initCapture(id: String?) {
        captureId = id
        val record = id?.let { library.byId(it) }
        if (sourceLabel.isBlank()) {
            sourceLabel = record?.label?.takeIf { it.isNotBlank() } ?: store.lastSource
        }
        refreshSourceCount()
    }

    fun initSource() {
        if (sourceLabel.isNotBlank()) return
        sourceLabel = store.lastSource
        refreshSourceCount()
    }

    /** Closing the preview without analysing throws the shot away, archive included. */
    fun discardCapture() {
        if (state.savedId != null) return
        val id = captureId ?: return
        library.remove(id)
    }

    fun setSource(text: String) {
        sourceLabel = text
        refreshSourceCount()
    }

    private fun normalizeSource(s: String): String = CaptureStore.normalize(s)

    private fun refreshSourceCount() {
        val key = normalizeSource(sourceLabel)
        if (key.isEmpty()) {
            sourceCount = 0
            return
        }
        viewModelScope.launch {
            sourceCount = withContext(Dispatchers.IO) {
                try {
                    library.bySource(key).size
                } catch (e: Exception) {
                    0
                }
            }
        }
    }

    /** Text handed to the model so the pattern layer is grounded in real earlier captures. */
    private fun historyContext(items: List<HistoryEntity>): String {
        if (items.isEmpty()) return ""
        return buildString {
            append("عدد اللقطات لنفس المصدر بعد إضافة الحالية: ").append(items.size + 1).append("\n")
            items.forEachIndexed { index, item ->
                append("\n[").append(index + 1).append("] ")
                val a = try {
                    json.decodeFromString(Analysis.serializer(), item.analysisJson)
                } catch (e: Exception) {
                    null
                }
                if (a == null) {
                    append("لقطة بدون تحليل")
                } else {
                    if (a.observed.isNotBlank()) append("الملاحظ: ").append(a.observed.take(400)).append("\n")
                    if (a.classification.isNotBlank()) append("   التصنيف: ").append(a.classification.take(200)).append("\n")
                    if (a.interpretation.isNotBlank()) append("   قراءة سابقة: ").append(a.interpretation.take(300))
                }
            }
        }
    }

    fun chooseMode(m: AnalysisMode) {
        mode = m
        store.lastModeUsed = m.id
    }

    fun chooseCompare(v: Boolean) {
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
                val key = normalizeSource(sourceLabel)
                val earlier = withContext(Dispatchers.IO) {
                    if (key.isEmpty()) {
                        emptyList()
                    } else {
                        try {
                            scan.db.history().bySource(key, 5).filter { it.analysisJson.isNotBlank() }
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }
                val user = Prompts.user(mode, lang, customInstruction, historyContext(earlier))
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

                // Learn the account from the answer so the next capture is grouped automatically.
                val account = results.firstOrNull { it.ok }?.analysis?.account?.trim().orEmpty()
                val label = sourceLabel.ifBlank { account }
                if (sourceLabel.isBlank() && account.isNotBlank()) sourceLabel = account
                if (label.isNotBlank()) store.lastSource = label
                val savedId = withContext(Dispatchers.IO) {
                    saveHistory(results, edited, sourcePath, normalizeSource(label))
                }
                withContext(Dispatchers.IO) {
                    val id = captureId
                    if (id != null) {
                        library.setLabel(id, label)
                        library.markAnalyzed(listOf(id), savedId)
                    }
                }
                state = state.copy(busy = false, results = results, savedId = savedId)
                refreshSourceCount()
            } catch (e: Exception) {
                state = state.copy(busy = false, error = e.message ?: "error")
            }
        }
    }

    /** Saves the (possibly blurred) image plus the analysis. */
    private suspend fun saveHistory(
        results: List<ModelResult>,
        edited: Bitmap?,
        sourcePath: String,
        sourceKey: String
    ): Long {
        val dir = File(scan.filesDir, "history").apply { mkdirs() }
        val target = File(dir, "hist_${System.currentTimeMillis()}.png")
        val ok = if (edited != null) ImageUtils.savePng(edited, target) else {
            val src = File(sourcePath)
            if (src.exists()) src.copyTo(target, overwrite = true).let { true } else false
        }
        if (!ok) return 0L
        val libraryRecord = try {
            library.byFile(sourcePath)
        } catch (e: Exception) {
            null
        }
        // Hidden areas must not survive in the archive either: the blurred copy wins there too.
        if (libraryRecord != null && edited != null) {
            ImageUtils.savePng(edited, File(libraryRecord.file))
        }
        if (libraryRecord == null) {
            File(sourcePath).takeIf { it.exists() && it.absolutePath != target.absolutePath }?.delete()
        }

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
            title = (best.observed.take(60).ifBlank { mode.ar }),
            sourceKey = sourceKey
        )
        return scan.db.history().insert(entity)
    }

    fun saveOnly(edited: Bitmap?, sourcePath: String) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                saveHistory(emptyList(), edited, sourcePath, normalizeSource(sourceLabel))
            }
            withContext(Dispatchers.IO) {
                val cid = captureId
                if (cid != null) library.setLabel(cid, sourceLabel)
            }
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

/**
 * The library: every capture the lens ever sent, grouped by the account it belongs to, with a
 * search box so a name can be found even after thirty shots.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val library = CaptureStore.get(app)

    var query by mutableStateOf("")
        private set
    var groups by mutableStateOf<List<SourceGroup>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set

    init {
        refresh()
    }

    fun refresh() {
        groups = library.groups(query)
        total = library.count()
    }

    fun setQuery(q: String) {
        query = q
        groups = library.groups(q)
    }

    fun deleteGroup(key: String) {
        library.removeGroup(key)
        refresh()
    }
}

/**
 * One account: all of its captures, selectable, analysed together in two passes. This is the
 * screen the user reaches after searching for a name in the library.
 */
class SourceViewModel(app: Application) : AndroidViewModel(app) {

    private val scan = ScanApp.get(app)
    private val store = scan.settings
    private val library = CaptureStore.get(app)
    private val engine = SeriesEngine(scan.keys)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var sourceKey by mutableStateOf("")
        private set
    var label by mutableStateOf("")
        private set
    var captures by mutableStateOf<List<CaptureRecord>>(emptyList())
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    var mode by mutableStateOf(AnalysisMode.from(store.lastModeUsed))
        private set
    var compare by mutableStateOf(store.selectionMode == "compare")
        private set

    var busy by mutableStateOf(false)
        private set
    var info by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var results by mutableStateOf<List<ModelResult>>(emptyList())
        private set
    var showSheet by mutableStateOf(false)
        private set
    var savedId by mutableStateOf<Long?>(null)
        private set

    fun load(key: String) {
        if (key == sourceKey && captures.isNotEmpty()) return
        sourceKey = key
        refresh()
    }

    fun refresh() {
        val list = library.bySource(sourceKey)
        captures = list
        label = list.firstOrNull { it.label.isNotBlank() }?.label
            ?: if (sourceKey.isBlank()) "" else sourceKey
        selected = list.map { it.id }.toSet()
    }

    fun toggle(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun selectAll() {
        selected = captures.map { it.id }.toSet()
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun chooseMode(m: AnalysisMode) {
        mode = m
        store.lastModeUsed = m.id
    }

    fun chooseCompare(v: Boolean) {
        compare = v
        store.selectionMode = if (v) "compare" else "single"
    }

    fun dismissSheet() {
        showSheet = false
    }

    fun clearError() {
        error = null
    }

    fun renameGroup(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        library.renameGroup(sourceKey, clean)
        label = clean
        store.lastSource = clean
        sourceKey = CaptureStore.normalize(clean)
        refresh()
    }

    fun deleteSelected() {
        val ids = selected.toList()
        if (ids.isEmpty()) return
        library.removeMany(ids)
        refresh()
    }

    /** Reads every selected capture off the device and runs the two-pass series analysis. */
    fun analyze() {
        val chosen = captures.filter { it.id in selected }
        val models = store.selectedModels
        if (chosen.isEmpty()) {
            error = if (store.lang == Lang.AR) "اختر لقطة واحدة على الأقل." else "Pick at least one capture."
            return
        }
        if (models.isEmpty()) {
            error = if (store.lang == Lang.AR) {
                "لم تختر أي موديل. أضف مفتاحًا واختر موديلًا من «الموديلات والمفاتيح»."
            } else {
                "No model selected. Add a key and pick a model in Models & Keys."
            }
            showSheet = true
            return
        }
        if (busy) return
        busy = true
        error = null
        results = emptyList()
        info = if (store.lang == Lang.AR) "جارٍ تجهيز اللقطات…" else "Preparing captures…"
        showSheet = true

        viewModelScope.launch {
            try {
                val images = withContext(Dispatchers.IO) {
                    chosen.mapNotNull { rec ->
                        val bmp = ImageUtils.loadSampled(rec.file, 1400) ?: return@mapNotNull null
                        val b64 = ImageUtils.toJpegBase64(bmp, 1100, 80)
                        bmp.recycle()
                        b64
                    }
                }
                if (images.isEmpty()) {
                    busy = false
                    info = ""
                    error = if (store.lang == Lang.AR) {
                        "تعذّر قراءة صور اللقطات المختارة."
                    } else {
                        "The selected captures could not be read."
                    }
                    return@launch
                }

                val lang = store.lang
                val outs = engine.analyzeSeries(
                    models = models,
                    imagesJpegBase64 = images,
                    lang = lang,
                    mode = mode,
                    custom = store.customInstruction,
                    label = label,
                    compare = compare,
                    fallback = store.fallbackEnabled
                ) { p: SeriesProgress -> publishProgress(p, images.size) }

                val anyOk = outs.any { it.ok }
                if (!anyOk) {
                    val first = outs.firstOrNull()
                    busy = false
                    info = ""
                    results = outs
                    error = first?.errorKind?.let { k -> if (lang == Lang.AR) k.ar() else k.en() }
                        ?: if (lang == Lang.AR) "لم تنجح أي محاولة." else "Every attempt failed."
                    return@launch
                }

                val id = withContext(Dispatchers.IO) {
                    val saved = saveRun(chosen, outs)
                    library.markAnalyzed(chosen.map { it.id }, saved)
                    saved
                }
                if (label.isNotBlank()) store.lastSource = label
                savedId = id
                results = outs
                busy = false
                info = ""
            } catch (e: Exception) {
                busy = false
                info = ""
                error = e.message ?: "error"
            }
        }
    }

    private fun publishProgress(p: SeriesProgress, images: Int) {
        val text = if (store.lang == Lang.AR) {
            if (p.note == "final") {
                "تجميع النتيجة النهائية من $images لقطة…"
            } else {
                "قراءة اللقطات: دفعة ${p.done} من ${p.total - 1}…"
            }
        } else {
            if (p.note == "final") {
                "Building the final result from $images captures…"
            } else {
                "Reading captures: batch ${p.done} of ${p.total - 1}…"
            }
        }
        viewModelScope.launch(Dispatchers.Main) { info = text }
    }

    /** Keeps a copy of one capture next to the analysis so history deletion cannot eat the archive. */
    private suspend fun saveRun(chosen: List<CaptureRecord>, results: List<ModelResult>): Long {
        val dir = File(scan.filesDir, "history").apply { mkdirs() }
        val target = File(dir, "series_${System.currentTimeMillis()}.png")
        val imagePath: String? = try {
            File(chosen.first().file).copyTo(target, overwrite = true)
            target.absolutePath
        } catch (e: Exception) {
            null
        }
        val best = results.firstOrNull { it.ok }?.analysis ?: Analysis()
        val dump = results.joinToString("\n\n") { r ->
            val head = "${r.sel.label} (${ProviderRegistry.name(r.sel.providerId, store.lang)})"
            if (r.ok) "$head\n${PreviewViewModel.formatAnalysis(r.analysis ?: Analysis())}"
            else "$head\n⚠ ${r.errorKind?.let { k -> if (store.lang == Lang.AR) k.ar() else k.en() } ?: ""}"
        }
        val shownName = label.ifBlank { if (store.lang == Lang.AR) "بدون مصدر" else "no source" }
        val titleText = if (store.lang == Lang.AR) {
            "تحليل " + chosen.size + " لقطة — " + shownName
        } else {
            chosen.size.toString() + " captures — " + shownName
        }
        val entity = HistoryEntity(
            createdAt = System.currentTimeMillis(),
            imagePath = imagePath,
            mode = mode.id,
            models = results.joinToString(", ") { it.sel.label },
            analysisJson = json.encodeToString(Analysis.serializer(), best),
            rawText = dump,
            title = titleText,
            sourceKey = sourceKey
        )
        return scan.db.history().insert(entity)
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
