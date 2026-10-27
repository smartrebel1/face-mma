package com.facefusion.mobile.webcam

import android.content.Context
import android.graphics.Bitmap
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
 * Pure Kotlin JSON helper & Playlist state manager (avoids android.jar stubbed org.json in unit tests).
 */
object PlaylistStorage {
    private const val PLAYLIST_FILE = "webcam_playlist_v1.dat"

    private fun esc(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    private fun unesc(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    fun serialize(items: List<PlaylistItem>, activeIndex: Int = 0): String {
        val sb = StringBuilder()
        sb.append("ACTIVE\t").append(activeIndex).append('\n')
        for (item in items) {
            sb.append("ITEM\t")
                .append(esc(item.id)).append('\t')
                .append(esc(item.uriString)).append('\t')
                .append(esc(item.displayName)).append('\t')
                .append(item.durationMs).append('\t')
                .append(item.width).append('\t')
                .append(item.height).append('\t')
                .append(esc(item.mimeType)).append('\t')
                .append(item.addedAt).append('\n')
        }
        return sb.toString()
    }

    fun deserialize(raw: String): Pair<List<PlaylistItem>, Int> {
        if (raw.isBlank()) return emptyList<PlaylistItem>() to 0
        return runCatching {
            var activeIdx = 0
            val list = ArrayList<PlaylistItem>()
            for (line in raw.lineSequence()) {
                if (line.isBlank()) continue
                val parts = line.split('\t')
                when (parts.firstOrNull()) {
                    "ACTIVE" -> activeIdx = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    "ITEM" -> if (parts.size >= 9) {
                        val uri = unesc(parts[2])
                        if (uri.isNotBlank()) {
                            list.add(
                                PlaylistItem(
                                    id = unesc(parts[1]).ifBlank { UUID.randomUUID().toString() },
                                    uriString = uri,
                                    displayName = unesc(parts[3]).ifBlank { "video.mp4" },
                                    durationMs = parts[4].toLongOrNull() ?: 0L,
                                    width = parts[5].toIntOrNull() ?: 0,
                                    height = parts[6].toIntOrNull() ?: 0,
                                    mimeType = unesc(parts[7]).ifBlank { "video/mp4" },
                                    addedAt = parts[8].toLongOrNull() ?: 0L,
                                    thumbnail = null,
                                )
                            )
                        }
                    }
                }
            }
            val clamped = if (list.isEmpty()) 0 else activeIdx.coerceIn(0, list.lastIndex)
            list to clamped
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
