package com.scan.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One screenshot the lens sent to the app. It is stored the moment it is captured, before any
 * analysis: the lens only captures, the app owns the archive.
 */
@Serializable
data class CaptureRecord(
    val id: String,
    /** Absolute path of the PNG inside filesDir/library. */
    val file: String,
    /** Normalised account key; captures sharing it form one group. */
    val source: String = "",
    /** The label exactly as the user typed it or as the model read it. */
    val label: String = "",
    val createdAt: Long = 0L,
    val analyzed: Boolean = false,
    val analysisId: Long = 0L
)

/** One account/person and how many captures the app holds for it. */
data class SourceGroup(
    val key: String,
    val label: String,
    val count: Int,
    val lastAt: Long,
    val thumb: String?
)

/**
 * File-based archive of every capture. A tiny JSON index (rewritten atomically) keeps this
 * independent of the Room schema, so a future database change can never lose the pictures.
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

    /** Every account group, newest first; captures without a label land in the empty-key group. */
    fun groups(query: String = ""): List<SourceGroup> = synchronized(lock) {
        val q = query.trim().lowercase().replace("@", "")
        val rows = load()
        rows.groupBy { it.source }
            .map { (key, items) ->
                val sorted = items.sortedByDescending { it.createdAt }
                val label = sorted.firstOrNull { it.label.isNotBlank() }?.label
                    ?: sorted.firstOrNull { it.source.isNotBlank() }?.source
                    ?: ""
                SourceGroup(
                    key = key,
                    label = label,
                    count = sorted.size,
                    lastAt = sorted.firstOrNull()?.createdAt ?: 0L,
                    thumb = sorted.firstOrNull()?.file
                )
            }
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.key.contains(q) }
            .sortedWith(compareByDescending<SourceGroup> { it.key.isNotEmpty() }.thenByDescending { it.lastAt })
    }

    // ---------------------------------------------------------------- writes

    /** Moves a freshly captured PNG into the archive and returns its record. */
    fun add(sourceFile: File, label: String = ""): CaptureRecord? = synchronized(lock) {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return null
        val list = load().toMutableList()
        val id = "c" + System.currentTimeMillis() + "_" + (list.size + 1)
        val target = File(root, "$id.png")
        val moved = try {
            sourceFile.renameTo(target)
        } catch (e: Exception) {
            false
        }
        if (!moved) {
            val copied = try {
                sourceFile.copyTo(target, overwrite = true)
                true
            } catch (e: Exception) {
                false
            }
            if (!copied) return null
            try {
                sourceFile.delete()
            } catch (e: Exception) {
                // the archive copy is what matters
            }
        }
        val record = CaptureRecord(
            id = id,
            file = target.absolutePath,
            source = normalize(label),
            label = label.trim(),
            createdAt = System.currentTimeMillis()
        )
        list.add(record)
        save(list)
        record
    }

    fun setLabel(id: String, label: String) = synchronized(lock) {
        val list = load()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        val next = list.toMutableList()
        next[index] = next[index].copy(source = normalize(label), label = label.trim())
        save(next)
    }

    /** Renames a whole group, so every capture of the same person shares the new label. */
    fun renameGroup(oldKey: String, newLabel: String) = synchronized(lock) {
        val k = normalize(oldKey)
        val next = load().map {
            if (it.source == k) it.copy(source = normalize(newLabel), label = newLabel.trim()) else it
        }
        save(next)
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
    }

    fun removeMany(ids: List<String>) = synchronized(lock) {
        val set = ids.toSet()
        val list = load()
        list.filter { it.id in set }.forEach { deleteFileQuietly(it.file) }
        save(list.filterNot { it.id in set })
    }

    fun removeGroup(key: String) = synchronized(lock) {
        val k = normalize(key)
        val list = load()
        list.filter { it.source == k }.forEach { deleteFileQuietly(it.file) }
        save(list.filterNot { it.source == k })
    }

    fun removeAll() = synchronized(lock) {
        load().forEach { deleteFileQuietly(it.file) }
        try {
            indexFile.delete()
        } catch (e: Exception) {
            // ignore
        }
        root.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".png")) deleteFileQuietly(f.absolutePath)
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
    }

    // ---------------------------------------------------------------- plumbing

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

        /** Same account written as "@Ahmed_X", "ahmed x" or "AhmedX" lands in one group. */
        fun normalize(label: String): String = label.trim().lowercase()
            .replace("@", "")
            .replace(" ", "")
            .replace("_", "")
            .replace(".", "")
            .replace("#", "")
            .replace("ـ", "")
    }
}
