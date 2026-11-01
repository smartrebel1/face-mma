package com.scan.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One screenshot the lens sent to the app. It is stored the moment it is captured, inside the
 * folder of its account, before any analysis: the lens only captures, the app owns the archive.
 */
@Serializable
data class CaptureRecord(
    val id: String,
    /** Absolute path of the PNG inside filesDir/library/<account>/. */
    val file: String,
    /** Normalised account key; captures sharing it form one group (and one folder). */
    val source: String = "",
    /** The label exactly as detected or typed ("@ahmed_x"). */
    val label: String = "",
    val createdAt: Long = 0L,
    val analyzed: Boolean = false,
    val analysisId: Long = 0L,
    /** Instagram / X / TikTok ... when the labeler could read it. */
    val platform: String = ""
)

/** One account/person and how many captures the app holds for it. */
data class SourceGroup(
    val key: String,
    val label: String,
    val count: Int,
    val lastAt: Long,
    val thumb: String?,
    /** Folder name on disk, so the user can see the "one folder per account" promise. */
    val folder: String = "",
    val platform: String = ""
)

/**
 * File-based archive of every capture, one folder per account:
 *
 *   filesDir/library/@ahmed_x/shot_....png
 *   filesDir/library/_no_source/shot_....png
 *   filesDir/library/index.json
 *
 * A tiny JSON index (rewritten atomically) keeps this independent of the Room schema, so a
 * future database change can never lose the pictures.
 */
class CaptureStore private constructor(context: Context) {

    private val root = File(context.applicationContext.filesDir, "library").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---------------------------------------------------------------- reads

    fun all(): List<CaptureRecord> = synchronized(lock) {
        load().sortedByDescending { it.createdAt }
    }

    fun count(): Int = synchronized(lock) { load().size }

    fun byId(id: String): CaptureRecord? = synchronized(lock) { load().firstOrNull { it.id == id } }

    fun byFile(path: String): CaptureRecord? = synchronized(lock) {
        load().firstOrNull { it.file == path }
    }

    fun bySource(key: String): List<CaptureRecord> = synchronized(lock) {
        val k = normalize(key)
        load().filter { it.source == k }.sortedByDescending { it.createdAt }
    }

    /** Captures whose account is still unknown. */
    fun unlabeled(): List<CaptureRecord> = synchronized(lock) {
        load().filter { it.source.isBlank() }.sortedByDescending { it.createdAt }
    }

    /** Absolute path of the folder that holds a group, for the UI line. */
    fun folderPathOf(key: String): String = File(root, folderSlug(key)).absolutePath

    fun folderNameOf(key: String): String = folderSlug(key)

    /** Every account group, newest first; captures without a label land in the empty-key group. */
    fun groups(query: String = ""): List<SourceGroup> = synchronized(lock) {
        val q = normalize(query)
        val qRaw = query.trim().lowercase().replace("@", "")
        val rows = load()
        rows.groupBy { it.source }
            .map { (key, items) ->
                val sorted = items.sortedByDescending { it.createdAt }
                val best = sorted.firstOrNull { it.label.isNotBlank() } ?: sorted.firstOrNull()
                val label = best?.label?.takeIf { it.isNotBlank() }
                    ?: sorted.firstOrNull { it.source.isNotBlank() }?.source
                    ?: ""
                SourceGroup(
                    key = key,
                    label = label,
                    count = sorted.size,
                    lastAt = sorted.firstOrNull()?.createdAt ?: 0L,
                    thumb = sorted.firstOrNull()?.file,
                    folder = folderSlug(key),
                    platform = sorted.firstOrNull { it.platform.isNotBlank() }?.platform.orEmpty()
                )
            }
            .filter {
                q.isEmpty() ||
                    it.key.contains(q) ||
                    normalize(it.label).contains(q) ||
                    it.folder.contains(q) ||
                    it.label.lowercase().contains(qRaw)
            }
            .sortedWith(compareByDescending<SourceGroup> { it.key.isNotEmpty() }.thenByDescending { it.lastAt })
    }

    // ---------------------------------------------------------------- writes

    /**
     * Moves a freshly captured PNG into the folder of its account and returns the record.
     * Called the instant the screenshot exists, before the user does anything.
     */
    fun add(sourceFile: File, label: String = "", platform: String = ""): CaptureRecord? = synchronized(lock) {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return null
        val list = load().toMutableList()
        val id = "c" + System.currentTimeMillis() + "_" + (list.size + 1)
        val dir = File(root, folderSlug(label)).apply { mkdirs() }
        val target = File(dir, "$id.png")
        if (!moveFile(sourceFile, target)) return null
        val record = CaptureRecord(
            id = id,
            file = target.absolutePath,
            source = normalize(label),
            label = label.trim(),
            createdAt = System.currentTimeMillis(),
            platform = platform.trim()
        )
        list.add(record)
        save(list)
        record
    }

    /** Re-labels one capture; the picture physically moves when the account changes. */
    fun setLabel(id: String, label: String, platform: String = "") = synchronized(lock) {
        val list = load()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        val old = list[index]
        val newSource = normalize(label)
        val movedPath = if (newSource != old.source) moveToFolder(old, newSource) else old.file
        val next = list.toMutableList()
        next[index] = old.copy(
            source = newSource,
            label = label.trim(),
            file = movedPath,
            platform = platform.ifBlank { old.platform }.trim()
        )
        save(next)
        pruneEmptyFolders()
    }

    /** Renames a whole group and moves every picture into the new folder. */
    fun renameGroup(oldKey: String, newLabel: String) = synchronized(lock) {
        val k = normalize(oldKey)
        val newSource = normalize(newLabel)
        val next = load().map {
            if (it.source != k) {
                it
            } else {
                val moved = if (newSource != it.source) moveToFolder(it, newSource) else it.file
                it.copy(source = newSource, label = newLabel.trim(), file = moved)
            }
        }
        save(next)
        pruneEmptyFolders()
    }

    fun markAnalyzed(ids: List<String>, analysisId: Long) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        val set = ids.toSet()
        val next = load().map {
            if (it.id in set) it.copy(analyzed = true, analysisId = analysisId) else it
        }
        save(next)
    }

    fun remove(id: String) = synchronized(lock) {
        val list = load()
        val hit = list.firstOrNull { it.id == id } ?: return@synchronized
        deleteFileQuietly(hit.file)
        save(list.filterNot { it.id == id })
        pruneEmptyFolders()
    }

    fun removeMany(ids: List<String>) = synchronized(lock) {
        val set = ids.toSet()
        val list = load()
        list.filter { it.id in set }.forEach { deleteFileQuietly(it.file) }
        save(list.filterNot { it.id in set })
        pruneEmptyFolders()
    }

    fun removeGroup(key: String) = synchronized(lock) {
        val k = normalize(key)
        val list = load()
        list.filter { it.source == k }.forEach { deleteFileQuietly(it.file) }
        save(list.filterNot { it.source == k })
        try {
            File(root, folderSlug(key)).deleteRecursively()
        } catch (e: Exception) {
            // ignore
        }
        pruneEmptyFolders()
    }

    fun removeAll() = synchronized(lock) {
        load().forEach { deleteFileQuietly(it.file) }
        try {
            indexFile.delete()
        } catch (e: Exception) {
            // ignore
        }
        root.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                try {
                    f.deleteRecursively()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    /** Honours the "auto-delete screenshots after N days" setting for the archive too. */
    fun purgeOlderThan(days: Int) = synchronized(lock) {
        if (days <= 0) return@synchronized
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        val list = load()
        val keep = list.filter { it.createdAt >= cutoff }
        if (keep.size == list.size) return@synchronized
        list.filter { it.createdAt < cutoff }.forEach { deleteFileQuietly(it.file) }
        save(keep)
        pruneEmptyFolders()
    }

    // ---------------------------------------------------------------- plumbing

    private fun moveToFolder(record: CaptureRecord, newSource: String): String {
        val src = File(record.file)
        val dir = File(root, folderSlug(newSource)).apply { mkdirs() }
        val target = File(dir, src.name)
        if (target.absolutePath == record.file) return record.file
        return if (moveFile(src, target)) target.absolutePath else record.file
    }

    private fun moveFile(src: File, target: File): Boolean {
        if (!src.exists()) return target.exists()
        return try {
            if (src.renameTo(target)) return true
            src.copyTo(target, overwrite = true)
            src.delete()
            true
        } catch (e: Exception) {
            // fall back to a plain copy so the picture is never lost
            try {
                src.copyTo(target, overwrite = true)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /** Keeps the tree clean: an account folder disappears with the last capture in it. */
    private fun pruneEmptyFolders() {
        try {
            root.listFiles()?.forEach { f ->
                if (f.isDirectory && (f.listFiles()?.isEmpty() != false)) f.delete()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun load(): List<CaptureRecord> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val raw = indexFile.readText()
            if (raw.isBlank()) emptyList()
            else json.decodeFromString(ListSerializer(CaptureRecord.serializer()), raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Writes through a temp file so a killed process cannot leave a broken index behind. */
    private fun save(list: List<CaptureRecord>) {
        try {
            val tmp = File(root, "index.tmp")
            tmp.writeText(json.encodeToString(ListSerializer(CaptureRecord.serializer()), list))
            if (indexFile.exists()) indexFile.delete()
            if (!tmp.renameTo(indexFile)) {
                tmp.copyTo(indexFile, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // never crash on bookkeeping
        }
    }

    private fun deleteFileQuietly(path: String) {
        try {
            File(path).delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    companion object {
        @Volatile
        private var instance: CaptureStore? = null

        fun get(context: Context): CaptureStore =
            instance ?: synchronized(this) {
                instance ?: CaptureStore(context.applicationContext).also { instance = it }
            }

        /**
         * Same account written as "@Ahmed_X", "ahmed x" or "أحمد.X" lands in one group:
         * lowercase, no spaces/punctuation, Arabic letters unified.
         */
        fun normalize(label: String): String {
            var s = label.trim().lowercase()
            s = s.replace("@", "").replace(" ", "").replace("_", "").replace(".", "")
                .replace("#", "").replace("ـ", "").replace("-", "")
            s = s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
                .replace('ى', 'ي').replace('ة', 'ه')
            return s
        }

        /** Folder name kept readable but filesystem safe. */
        fun folderSlug(label: String): String {
            val norm = normalize(label)
            if (norm.isEmpty()) return "_no_source"
            val safe = norm.filter { ch ->
                ch.isLetterOrDigit() || ch == '-' || ch == '.' || (ch in '\u0600'..'\u06FF')
            }
            val trimmed = safe.take(40).trim('.', '-')
            return if (trimmed.isEmpty()) "_acc_" + (label.hashCode() and 0xFFFFFF).toString(16) else trimmed
        }
    }
}
