package com.facefusion.mobile.webcam

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Video aspect ratio scaling modes for both the studio preview and outbound live stream frames.
 */
enum class AspectRatioMode(
    val code: String,
    val labelEn: String,
    val labelAr: String,
) {
    FIT("fit", "Fit", "احتواء (Fit)"),
    FILL("fill", "Fill", "ملء الشاشة (Fill)"),
    ORIGINAL("original", "Original", "الأصلي (Original)");

    companion object {
        fun fromCode(code: String?): AspectRatioMode =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: FIT
    }
}

/**
 * Playlist and track loop modes mapped to Android Media3 ExoPlayer repeat modes.
 */
enum class LoopMode(
    val code: String,
    val labelEn: String,
    val labelAr: String,
) {
    OFF("off", "Loop Off", "بدون تكرار (Off)"),
    ONE("one", "Loop Video", "تكرار الفيديو (One)"),
    ALL("all", "Loop Playlist", "تكرار القائمة (All)");

    fun next(): LoopMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }

    companion object {
        fun fromCode(code: String?): LoopMode =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ALL
    }
}

/**
 * Supported modular live streaming output integration sinks.
 */
enum class OutputSinkType(
    val code: String,
    val labelEn: String,
    val labelAr: String,
    val descEn: String,
    val descAr: String,
) {
    MJPEG_HTTP(
        code = "mjpeg_http",
        labelEn = "MJPEG / HTTP Live Stream (Wi-Fi / USB / OBS)",
        labelAr = "بث مباشر HTTP / MJPEG (واي فاي / USB / OBS)",
        descEn = "Streams live composited video over HTTP (/stream.mjpeg & /player) for OBS, TikTok LIVE Studio, VLC, or browser receivers.",
        descAr = "يبث الفيديو المباشر عبر HTTP (/stream.mjpeg و /player) لبرامج OBS و TikTok LIVE Studio والمتصفح أو عبر كابل USB."
    ),
    TIKTOK_CLEAN_STAGE(
        code = "tiktok_stage",
        labelEn = "TikTok LIVE Clean Stage + PiP (Phone-Only)",
        labelAr = "وضع البث النظيف لـ TikTok LIVE + نافذة PiP (من الهاتف مباشرة)",
        descEn = "Phone-only integration for TikTok LIVE Mobile Gaming / Screen Cast mode with clean fullscreen stage, PiP window, and digital internal audio (ALLOW_CAPTURE_BY_ALL).",
        descAr = "بث مباشر من الهاتف فقط بدون كمبيوتر عبر ميزة مشاركة الشاشة (ألعاب الهاتف) في TikTok LIVE مع صوت رقمي نقي ونافذة عائمة PiP."
    ),
    RTMP_PUSH(
        code = "rtmp_push",
        labelEn = "Direct RTMP Live Push (rtmp://)",
        labelAr = "بث مباشر عبر بروتوكول RTMP (بدون كمبيوتر)",
        descEn = "Encodes live H.264/AVC video via hardware MediaCodec and pushes directly to an RTMP server (TikTok RTMP, YouTube, Twitch, Custom).",
        descAr = "ترميز مباشر H.264 وإرسال البث مباشرة إلى خادم RTMP (مثل مفتاح بث TikTok LIVE أو YouTube أو Twitch)."
    );

    companion object {
        fun fromCode(code: String?): OutputSinkType =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: MJPEG_HTTP
    }
}

/**
 * Operational state of the virtual webcam / live stream output engine.
 */
enum class OutputState(
    val labelEn: String,
    val labelAr: String,
) {
    OFF("OUTPUT OFF", "المخرج متوقف (OFF)"),
    STARTING("STARTING...", "جارٍ تشغيل البث..."),
    LIVE("LIVE ON-AIR", "بث مباشر نشط (LIVE)"),
    STANDBY("STANDBY (ON)", "في وضع الاستعداد (STANDBY)"),
    ERROR("OUTPUT ERROR", "خطأ في البث (ERROR)")
}

/**
 * Represents one video item inside the user's saved playlist.
 */
data class PlaylistItem(
    val id: String = UUID.randomUUID().toString(),
    val uriString: String,
    val displayName: String,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "video/mp4",
    val addedAt: Long = System.currentTimeMillis(),
    val thumbnail: Bitmap? = null,
) {
    val resolutionLabel: String
        get() = if (width > 0 && height > 0) "${width}x${height}" else "Video"

    val durationLabel: String
        get() = formatDuration(durationMs)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("uriString", uriString)
        put("displayName", displayName)
        put("durationMs", durationMs)
        put("width", width)
        put("height", height)
        put("mimeType", mimeType)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): PlaylistItem = PlaylistItem(
            id = obj.optString("id", UUID.randomUUID().toString()),
            uriString = obj.optString("uriString", ""),
            displayName = obj.optString("displayName", "video.mp4"),
            durationMs = obj.optLong("durationMs", 0L),
            width = obj.optInt("width", 0),
            height = obj.optInt("height", 0),
            mimeType = obj.optString("mimeType", "video/mp4"),
            addedAt = obj.optLong("addedAt", 0L),
            thumbnail = null,
        )
    }
}

/**
 * Persistent configuration for the Virtual Webcam & Live Stream Studio.
 */
data class WebcamSettings(
    val outputSinkType: OutputSinkType = OutputSinkType.MJPEG_HTTP,
    val httpPort: Int = 8080,
    val allowLan: Boolean = true,
    val targetFps: Int = 24,
    val jpegQuality: Int = 80,
    val outputWidth: Int = 720,
    val outputHeight: Int = 1280,
    val rtmpUrl: String = "rtmp://127.0.0.1:1935/live",
    val rtmpStreamKey: String = "stream",
    val arabicRtl: Boolean = true,
    val keepScreenOn: Boolean = true,
    val continueInBackground: Boolean = true,
    val aspectRatio: AspectRatioMode = AspectRatioMode.FIT,
    val mirrorMode: Boolean = false,
    val loopMode: LoopMode = LoopMode.ALL,
    val volume: Float = 1.0f,
    val muted: Boolean = false,
) {
    companion object {
        private const val PREFS_NAME = "webcam_studio_prefs"

        fun load(ctx: Context): WebcamSettings {
            val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return WebcamSettings(
                outputSinkType = OutputSinkType.fromCode(sp.getString("sink_type", OutputSinkType.MJPEG_HTTP.code)),
                httpPort = sp.getInt("http_port", 8080).coerceIn(1024, 65535),
                allowLan = sp.getBoolean("allow_lan", true),
                targetFps = sp.getInt("target_fps", 24).coerceIn(5, 60),
                jpegQuality = sp.getInt("jpeg_quality", 80).coerceIn(30, 95),
                outputWidth = sp.getInt("output_width", 720).coerceIn(240, 1920),
                outputHeight = sp.getInt("output_height", 1280).coerceIn(240, 1920),
                rtmpUrl = sp.getString("rtmp_url", "rtmp://127.0.0.1:1935/live") ?: "rtmp://127.0.0.1:1935/live",
                rtmpStreamKey = sp.getString("rtmp_key", "stream") ?: "stream",
                arabicRtl = sp.getBoolean("arabic_rtl", true),
                keepScreenOn = sp.getBoolean("keep_screen_on", true),
                continueInBackground = sp.getBoolean("continue_bg", true),
                aspectRatio = AspectRatioMode.fromCode(sp.getString("aspect_ratio", AspectRatioMode.FIT.code)),
                mirrorMode = sp.getBoolean("mirror_mode", false),
                loopMode = LoopMode.fromCode(sp.getString("loop_mode", LoopMode.ALL.code)),
                volume = sp.getFloat("volume", 1.0f).coerceIn(0f, 1f),
                muted = sp.getBoolean("muted", false),
            )
        }

        fun save(ctx: Context, s: WebcamSettings) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("sink_type", s.outputSinkType.code)
                .putInt("http_port", s.httpPort.coerceIn(1024, 65535))
                .putBoolean("allow_lan", s.allowLan)
                .putInt("target_fps", s.targetFps.coerceIn(5, 60))
                .putInt("jpeg_quality", s.jpegQuality.coerceIn(30, 95))
                .putInt("output_width", s.outputWidth.coerceIn(240, 1920))
                .putInt("output_height", s.outputHeight.coerceIn(240, 1920))
                .putString("rtmp_url", s.rtmpUrl.trim())
                .putString("rtmp_key", s.rtmpStreamKey.trim())
                .putBoolean("arabic_rtl", s.arabicRtl)
                .putBoolean("keep_screen_on", s.keepScreenOn)
                .putBoolean("continue_bg", s.continueInBackground)
                .putString("aspect_ratio", s.aspectRatio.code)
                .putBoolean("mirror_mode", s.mirrorMode)
                .putString("loop_mode", s.loopMode.code)
                .putFloat("volume", s.volume.coerceIn(0f, 1f))
                .putBoolean("muted", s.muted)
                .apply()
        }
    }
}

/**
 * Pure, testable playlist state operations and JSON persistence.
 */
object PlaylistStorage {
    private const val PLAYLIST_FILE = "webcam_playlist_v1.json"

    fun serialize(items: List<PlaylistItem>, activeIndex: Int = 0): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("activeIndex", activeIndex)
        val arr = JSONArray()
        for (item in items) {
            arr.put(item.toJson())
        }
        root.put("items", arr)
        return root.toString()
    }

    fun deserialize(json: String): Pair<List<PlaylistItem>, Int> {
        if (json.isBlank()) return emptyList<PlaylistItem>() to 0
        return runCatching {
            val root = JSONObject(json)
            val arr = root.optJSONArray("items") ?: JSONArray()
            val list = ArrayList<PlaylistItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val item = PlaylistItem.fromJson(obj)
                if (item.uriString.isNotBlank()) {
                    list.add(item)
                }
            }
            val active = if (list.isEmpty()) 0 else root.optInt("activeIndex", 0).coerceIn(0, list.lastIndex)
            list to active
        }.getOrElse { emptyList<PlaylistItem>() to 0 }
    }

    fun saveToFile(ctx: Context, items: List<PlaylistItem>, activeIndex: Int): Boolean =
        runCatching {
            File(ctx.filesDir, PLAYLIST_FILE).writeText(serialize(items, activeIndex), Charsets.UTF_8)
            true
        }.getOrDefault(false)

    fun loadFromFile(ctx: Context): Pair<List<PlaylistItem>, Int> =
        runCatching {
            val f = File(ctx.filesDir, PLAYLIST_FILE)
            if (!f.isFile) return emptyList<PlaylistItem>() to 0
            deserialize(f.readText(Charsets.UTF_8))
        }.getOrElse { emptyList<PlaylistItem>() to 0 }

    /**
     * Reorder an item from [fromIndex] to [toIndex] and return the updated list + updated activeIndex.
     */
    fun reorder(
        items: List<PlaylistItem>,
        fromIndex: Int,
        toIndex: Int,
        currentActiveIndex: Int,
    ): Pair<List<PlaylistItem>, Int> {
        if (fromIndex !in items.indices || toIndex !in items.indices || fromIndex == toIndex) {
            return items to currentActiveIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
        val activeId = items.getOrNull(currentActiveIndex)?.id
        val mutable = items.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        val newActive = if (activeId != null) {
            mutable.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: 0
        } else 0
        return mutable.toList() to newActive
    }

    /**
     * Remove the item at [removeIndex] and return the updated list + adjusted activeIndex.
     */
    fun removeAt(
        items: List<PlaylistItem>,
        removeIndex: Int,
        currentActiveIndex: Int,
    ): Pair<List<PlaylistItem>, Int> {
        if (removeIndex !in items.indices) {
            return items to currentActiveIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
        val mutable = items.toMutableList()
        mutable.removeAt(removeIndex)
        if (mutable.isEmpty()) return emptyList<PlaylistItem>() to 0
        val newActive = when {
            removeIndex < currentActiveIndex -> (currentActiveIndex - 1).coerceAtLeast(0)
            removeIndex == currentActiveIndex -> currentActiveIndex.coerceAtMost(mutable.lastIndex)
            else -> currentActiveIndex.coerceIn(0, mutable.lastIndex)
        }
        return mutable.toList() to newActive
    }
}

/**
 * Formats a millisecond duration as `MM:SS` or `HH:MM:SS`.
 */
fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "00:00"
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(java.util.Locale.US, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(java.util.Locale.US, minutes, seconds)
    }
}
