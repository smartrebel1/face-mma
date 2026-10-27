package com.facefusion.mobile.webcam

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Central controller for the Android Virtual Webcam & Live Stream Video Player.
 *
 * Manages:
 * - Android Media3 [ExoPlayer] playback and playlist queue
 * - Storage Access Framework (SAF) video ingestion, thumbnails, and JSON persistence
 * - Real-time [TextureView] frame extraction + [FrameCompositor] (Fit/Fill/Original + Mirror)
 * - Genuine ON/OFF output activation across [MjpegHttpOutputSink], [CleanStageOutputSink],
 *   and [RtmpLiveOutputSink] backed by [VirtualCamService].
 */
class WebcamController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var settings by mutableStateOf(WebcamSettings.load(context))
        private set

    var playlist by mutableStateOf<List<PlaylistItem>>(emptyList())
        private set

    var activeIndex by mutableIntStateOf(0)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var isStopped by mutableStateOf(true)
        private set

    var isBuffering by mutableStateOf(false)
        private set

    var positionMs by mutableLongStateOf(0L)
        private set

    var durationMs by mutableLongStateOf(0L)
        private set

    var videoWidth by mutableIntStateOf(1280)
        private set

    var videoHeight by mutableIntStateOf(720)
        private set

    // ---- Output Engine State (controlled by the ON/OFF toggle) ----
    var outputEnabled by mutableStateOf(false)
        private set

    var outputState by mutableStateOf(OutputState.OFF)
        private set

    var activeEndpointUrl by mutableStateOf("")
        private set

    var activePlayerUrl by mutableStateOf("")
        private set

    var connectedClients by mutableIntStateOf(0)
        private set

    var outputFps by mutableFloatStateOf(0f)
        private set

    var framesSent by mutableLongStateOf(0L)
        private set

    var bytesSent by mutableLongStateOf(0L)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var statusBanner by mutableStateOf<String?>(null)
        private set

    var cleanStageFullscreen by mutableStateOf(false)
        private set

    private var player: ExoPlayer? = null
    private var boundTextureView: TextureView? = null
    private var activeSink: VideoOutputSink? = null
    private var framePumpJob: Job? = null
    private var progressPollJob: Job? = null
    private var compositedReusable: Bitmap? = null

    init {
        initExoPlayer()
        loadSavedPlaylist()
        VirtualCamService.onRemoteAction = { action ->
            scope.launch(Dispatchers.Main) {
                handleRemoteCommand(action)
            }
        }
    }

    private fun initExoPlayer() {
        if (player != null) return
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            // Crucial for TikTok LIVE Mobile Gaming / Screen Share mode: allows Android 10+
            // AudioPlaybackCapture to record clean internal stereo audio directly from ExoPlayer.
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
            .build()

        val exo = ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttrs, false)
            .build()

        applyLoopModeToPlayer(exo, settings.loopMode)
        exo.volume = if (settings.muted) 0f else settings.volume

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) isStopped = false
                updateOutputStateBadge()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                val d = exo.duration
                if (d != C.TIME_UNSET && d > 0) {
                    durationMs = d
                }
                if (playbackState == Player.STATE_ENDED && settings.loopMode == LoopMode.OFF) {
                    isPlaying = false
                }
                updateOutputStateBadge()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = exo.currentMediaItemIndex
                if (idx in playlist.indices) {
                    activeIndex = idx
                    val itemDur = playlist[idx].durationMs
                    if (itemDur > 0) durationMs = itemDur
                }
                val d = exo.duration
                if (d != C.TIME_UNSET && d > 0) durationMs = d
                updateServiceNotification()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val itemName = playlist.getOrNull(activeIndex)?.displayName ?: "video"
                errorMessage = if (settings.arabicRtl) {
                    "تعذر تشغيل الملف ($itemName): الصيغة غير مدعومة أو الملف غير متاح [${error.errorCodeName}]"
                } else {
                    "Playback error ($itemName): Unsupported format or inaccessible file [${error.errorCodeName}]"
                }
                updateOutputStateBadge()
            }
        })

        player = exo
        startProgressPoller()
    }

    private fun startProgressPoller() {
        progressPollJob?.cancel()
        progressPollJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val p = player
                if (p != null) {
                    positionMs = p.currentPosition.coerceAtLeast(0L)
                    val d = p.duration
                    if (d != C.TIME_UNSET && d > 0L) {
                        durationMs = d
                    }
                }
                val sink = activeSink
                if (sink != null && outputEnabled) {
                    connectedClients = sink.connectedClients
                    framesSent = sink.framesDispatched
                    bytesSent = sink.bytesDispatched
                    sink.lastError?.let { sinkErr ->
                        if (errorMessage != sinkErr) {
                            errorMessage = sinkErr
                            outputState = OutputState.ERROR
                        }
                    }
                }
                delay(200L)
            }
        }
    }

    fun bindTextureView(textureView: TextureView) {
        if (boundTextureView !== textureView) {
            boundTextureView = textureView
            player?.setVideoTextureView(textureView)
        }
    }

    // ------------------------------------------------------------------ Playlist Management (SAF)

    private fun loadSavedPlaylist() {
        val (savedItems, savedActive) = PlaylistStorage.loadFromFile(context)
        if (savedItems.isNotEmpty()) {
            playlist = savedItems
            activeIndex = savedActive.coerceIn(0, savedItems.lastIndex)
            syncPlayerPlaylist(seekToIndex = activeIndex, autoPlay = false)
            hydrateThumbnailsAsync(savedItems)
        }
    }

    fun addVideosFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        errorMessage = null
        scope.launch(Dispatchers.IO) {
            val newItems = ArrayList<PlaylistItem>(uris.size)
            for (uri in uris) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val meta = inspectVideoUri(uri)
                newItems.add(meta)
            }
            withContext(Dispatchers.Main) {
                val wasEmpty = playlist.isEmpty()
                playlist = playlist + newItems
                if (wasEmpty && playlist.isNotEmpty()) {
                    activeIndex = 0
                    syncPlayerPlaylist(seekToIndex = 0, autoPlay = false)
                } else {
                    val p = player
                    if (p != null) {
                        for (item in newItems) {
                            p.addMediaItem(
                                MediaItem.Builder()
                                    .setMediaId(item.id)
                                    .setUri(Uri.parse(item.uriString))
                                    .build()
                            )
                        }
                        if (p.playbackState == Player.STATE_IDLE) {
                            p.prepare()
                        }
                    }
                }
                PlaylistStorage.saveToFile(context, playlist, activeIndex)
                statusBanner = if (settings.arabicRtl) {
                    "تمت إضافة ${newItems.size} فيديو إلى قائمة التشغيل"
                } else {
                    "Added ${newItems.size} video(s) to playlist"
                }
            }
        }
    }

    private fun inspectVideoUri(uri: Uri): PlaylistItem {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
        var mimeType = context.contentResolver.getType(uri) ?: "video/mp4"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) displayName = name
                        }
                    }
                }
        }

        var durationMs = 0L
        var width = 0
        var height = 0
        var thumb: Bitmap? = null
        runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) {
                    width = h
                    height = w
                } else {
                    width = w
                    height = h
                }
                val rawFrame = mmr.getFrameAtTime(
                    minOf(500_000L, durationMs * 500L),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
                if (rawFrame != null) {
                    val targetW = 160
                    val targetH = ((rawFrame.height.toFloat() / rawFrame.width.coerceAtLeast(1)) * targetW)
                        .toInt().coerceIn(90, 240)
                    thumb = Bitmap.createScaledBitmap(rawFrame, targetW, targetH, true)
                    if (thumb !== rawFrame) rawFrame.recycle()
                }
            } finally {
                runCatching { mmr.release() }
            }
        }

        return PlaylistItem(
            uriString = uri.toString(),
            displayName = displayName,
            durationMs = durationMs,
            width = width,
            height = height,
            mimeType = mimeType,
            thumbnail = thumb,
        )
    }

    private fun hydrateThumbnailsAsync(items: List<PlaylistItem>) {
        scope.launch(Dispatchers.IO) {
            for (item in items) {
                if (item.thumbnail != null) continue
                val hydrated = runCatching { inspectVideoUri(Uri.parse(item.uriString)) }.getOrNull() ?: continue
                withContext(Dispatchers.Main) {
                    playlist = playlist.map { existing ->
                        if (existing.id == item.id) {
                            existing.copy(
                                durationMs = if (existing.durationMs > 0) existing.durationMs else hydrated.durationMs,
                                width = if (existing.width > 0) existing.width else hydrated.width,
                                height = if (existing.height > 0) existing.height else hydrated.height,
                                thumbnail = hydrated.thumbnail,
                            )
                        } else existing
                    }
                }
            }
        }
    }

    fun selectTrack(index: Int, autoPlay: Boolean = true) {
        if (index !in playlist.indices) return
        errorMessage = null
        activeIndex = index
        val p = player ?: return
        if (p.mediaItemCount != playlist.size) {
            syncPlayerPlaylist(seekToIndex = index, autoPlay = autoPlay)
        } else {
            p.seekToDefaultPosition(index)
            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
                p.prepare()
            }
            if (autoPlay) {
                isStopped = false
                p.play()
            }
        }
        PlaylistStorage.saveToFile(context, playlist, activeIndex)
        updateServiceNotification()
    }

    fun removeVideoAt(index: Int) {
        if (index !in playlist.indices) return
        val wasPlaying = isPlaying
        val (updated, newActive) = PlaylistStorage.removeAt(playlist, index, activeIndex)
        playlist = updated
        activeIndex = newActive
        if (updated.isEmpty()) {
            stopPlayback()
            player?.clearMediaItems()
        } else {
            syncPlayerPlaylist(seekToIndex = activeIndex, autoPlay = wasPlaying)
        }
        PlaylistStorage.saveToFile(context, playlist, activeIndex)
    }

    fun moveVideoUp(index: Int) {
        if (index <= 0 || index !in playlist.indices) return
        reorderPlaylist(index, index - 1)
    }

    fun moveVideoDown(index: Int) {
        if (index < 0 || index >= playlist.lastIndex) return
        reorderPlaylist(index, index + 1)
    }

    private fun reorderPlaylist(fromIndex: Int, toIndex: Int) {
        val (updated, newActive) = PlaylistStorage.reorder(playlist, fromIndex, toIndex, activeIndex)
        playlist = updated
        activeIndex = newActive
        player?.moveMediaItem(fromIndex, toIndex)
        PlaylistStorage.saveToFile(context, playlist, activeIndex)
    }

    fun clearPlaylist() {
        stopPlayback()
        playlist = emptyList()
        activeIndex = 0
        durationMs = 0L
        positionMs = 0L
        player?.clearMediaItems()
        PlaylistStorage.saveToFile(context, emptyList(), 0)
        statusBanner = if (settings.arabicRtl) "تم مسح قائمة التشغيل" else "Playlist cleared"
    }

    fun savePlaylistExplicitly() {
        val ok = PlaylistStorage.saveToFile(context, playlist, activeIndex)
        statusBanner = if (ok) {
            if (settings.arabicRtl) {
                "تم حفظ قائمة التشغيل (${playlist.size} فيديو)"
            } else {
                "Playlist saved (${playlist.size} videos)"
            }
        } else {
            if (settings.arabicRtl) "تعذر حفظ القائمة" else "Failed to save playlist"
        }
    }

    private fun syncPlayerPlaylist(seekToIndex: Int, autoPlay: Boolean) {
        val p = player ?: return
        val mediaItems = playlist.map { item ->
            MediaItem.Builder()
                .setMediaId(item.id)
                .setUri(Uri.parse(item.uriString))
                .build()
        }
        p.setMediaItems(mediaItems, seekToIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0)), 0L)
        p.prepare()
        p.playWhenReady = autoPlay
    }

    // ------------------------------------------------------------------ Playback Controls

    fun play() {
        if (playlist.isEmpty()) {
            errorMessage = if (settings.arabicRtl) {
                "الرجاء إضافة فيديو أولاً من وحدة التخزين (Add Videos)"
            } else {
                "Please add at least one video to the playlist first"
            }
            return
        }
        errorMessage = null
        val p = player ?: return
        if (p.mediaItemCount == 0) {
            syncPlayerPlaylist(seekToIndex = activeIndex, autoPlay = true)
            return
        }
        if (p.playbackState == Player.STATE_IDLE) {
            p.prepare()
        } else if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(activeIndex, 0L)
        }
        isStopped = false
        p.play()
        updateOutputStateBadge()
        updateServiceNotification()
    }

    fun pause() {
        player?.pause()
        updateOutputStateBadge()
        updateServiceNotification()
    }

    fun stopPlayback() {
        val p = player ?: return
        p.pause()
        p.seekTo(0L)
        positionMs = 0L
        isPlaying = false
        isStopped = true
        if (outputEnabled) {
            val standby = FrameCompositor.createStandbyBitmap(
                width = settings.outputWidth,
                height = settings.outputHeight,
            )
            activeSink?.onPlaybackStopped(standby)
        }
        updateOutputStateBadge()
        updateServiceNotification()
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val target = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        p.seekTo(target)
        positionMs = target
    }

    fun nextTrack() {
        if (playlist.isEmpty()) return
        val nextIdx = if (activeIndex + 1 <= playlist.lastIndex) activeIndex + 1 else 0
        selectTrack(nextIdx, autoPlay = isPlaying || !isStopped)
    }

    fun previousTrack() {
        if (playlist.isEmpty()) return
        val prevIdx = if (activeIndex - 1 >= 0) activeIndex - 1 else playlist.lastIndex
        selectTrack(prevIdx, autoPlay = isPlaying || !isStopped)
    }

    fun cycleLoopMode() {
        updateSettings(settings.copy(loopMode = settings.loopMode.next()))
    }

    fun setLoopMode(mode: LoopMode) {
        updateSettings(settings.copy(loopMode = mode))
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        updateSettings(settings.copy(volume = clamped, muted = clamped == 0f))
    }

    fun toggleMute() {
        updateSettings(settings.copy(muted = !settings.muted))
    }

    fun setAspectRatio(mode: AspectRatioMode) {
        updateSettings(settings.copy(aspectRatio = mode))
    }

    fun toggleMirror() {
        updateSettings(settings.copy(mirrorMode = !settings.mirrorMode))
    }

    fun updateCleanStageFullscreen(enabled: Boolean) {
        cleanStageFullscreen = enabled
    }

    fun clearMessages() {
        errorMessage = null
        statusBanner = null
    }

    fun updateSettings(newSettings: WebcamSettings) {
        val sinkChanged = newSettings.outputSinkType != settings.outputSinkType ||
            newSettings.httpPort != settings.httpPort ||
            newSettings.allowLan != settings.allowLan ||
            newSettings.rtmpUrl != settings.rtmpUrl ||
            newSettings.rtmpStreamKey != settings.rtmpStreamKey
        settings = newSettings
        WebcamSettings.save(context, newSettings)

        player?.let { exo ->
            applyLoopModeToPlayer(exo, newSettings.loopMode)
            exo.volume = if (newSettings.muted) 0f else newSettings.volume
        }

        if (outputEnabled && sinkChanged) {
            // Restart output sink cleanly with the updated configuration
            applyOutputEnabled(false)
            applyOutputEnabled(true)
        }
    }

    private fun applyLoopModeToPlayer(exo: ExoPlayer, mode: LoopMode) {
        exo.repeatMode = when (mode) {
            LoopMode.OFF -> Player.REPEAT_MODE_OFF
            LoopMode.ONE -> Player.REPEAT_MODE_ONE
            LoopMode.ALL -> Player.REPEAT_MODE_ALL
        }
    }

    // ------------------------------------------------------------------ Live Output Engine (ON / OFF)

    fun toggleOutput() {
        applyOutputEnabled(!outputEnabled)
    }

    /**
     * Genuinely activates or deactivates the live video output pipeline and foreground service.
     */
    fun applyOutputEnabled(enabled: Boolean) {
        if (enabled == outputEnabled) return
        errorMessage = null

        if (!enabled) {
            outputEnabled = false
            framePumpJob?.cancel()
            framePumpJob = null
            activeSink?.stop()
            activeSink = null
            VirtualCamService.stop(context)
            outputState = OutputState.OFF
            outputFps = 0f
            connectedClients = 0
            activeEndpointUrl = ""
            activePlayerUrl = ""
            statusBanner = if (settings.arabicRtl) {
                "تم إيقاف مخرج الفيديو المباشر (OUTPUT OFF)"
            } else {
                "Live video output deactivated (OUTPUT OFF)"
            }
            return
        }

        outputState = OutputState.STARTING
        val sink: VideoOutputSink = when (settings.outputSinkType) {
            OutputSinkType.MJPEG_HTTP -> MjpegHttpOutputSink(
                port = settings.httpPort,
                allowLan = settings.allowLan,
                jpegQuality = settings.jpegQuality,
                onRemoteCommand = { cmd ->
                    scope.launch(Dispatchers.Main) { handleRemoteCommand(cmd) }
                },
                statusProvider = {
                    mapOf(
                        "activeTrack" to (playlist.getOrNull(activeIndex)?.displayName ?: "None"),
                        "playing" to isPlaying,
                        "aspectRatio" to settings.aspectRatio.code,
                        "mirror" to settings.mirrorMode,
                        "fps" to "%.1f".format(java.util.Locale.US, outputFps),
                    )
                },
            )
            OutputSinkType.TIKTOK_CLEAN_STAGE -> CleanStageOutputSink()
            OutputSinkType.RTMP_PUSH -> RtmpLiveOutputSink(
                rtmpUrl = settings.rtmpUrl,
                streamKey = settings.rtmpStreamKey,
                width = settings.outputWidth,
                height = settings.outputHeight,
                fps = settings.targetFps,
            )
        }

        val startResult = sink.start()
        if (startResult.isFailure) {
            val err = startResult.exceptionOrNull()?.message ?: "Failed to start output sink"
            outputEnabled = false
            outputState = OutputState.ERROR
            errorMessage = if (settings.arabicRtl) {
                "فشل تشغيل مخرج البث: $err"
            } else {
                "Failed to start video output: $err"
            }
            return
        }

        activeSink = sink
        outputEnabled = true
        activeEndpointUrl = sink.endpointUrl
        activePlayerUrl = if (sink is MjpegHttpOutputSink) sink.playerUrl else sink.endpointUrl
        framesSent = 0L
        bytesSent = 0L

        // Start playback automatically if a video is queued and currently stopped
        if (playlist.isNotEmpty() && !isPlaying) {
            play()
        }

        updateOutputStateBadge()
        updateServiceNotification()
        startFramePump(sink)

        statusBanner = if (settings.arabicRtl) {
            "تم تفعيل البث المباشر (ON) • ${sink.endpointUrl}"
        } else {
            "Live Output Activated (ON) • ${sink.endpointUrl}"
        }
    }

    private fun startFramePump(sink: VideoOutputSink) {
        framePumpJob?.cancel()
        framePumpJob = scope.launch(Dispatchers.Default) {
            var fpsFrames = 0
            var fpsWindowStart = SystemClock.elapsedRealtime()
            val standbyBitmap = FrameCompositor.createStandbyBitmap(
                width = settings.outputWidth,
                height = settings.outputHeight,
            )

            while (isActive && outputEnabled && sink.isRunning) {
                val loopStart = SystemClock.elapsedRealtime()
                val intervalMs = (1000L / settings.targetFps.coerceIn(5, 60)).coerceAtLeast(16L)

                val tv = boundTextureView
                val playingNow = isPlaying && !isStopped
                var rawFrame: Bitmap? = null

                if (playingNow && tv != null && tv.isAvailable && tv.width > 0 && tv.height > 0) {
                    rawFrame = withContext(Dispatchers.Main) {
                        runCatching {
                            val capW = videoWidth.coerceIn(160, settings.outputWidth.coerceAtLeast(360))
                            val capH = videoHeight.coerceIn(160, settings.outputHeight.coerceAtLeast(360))
                            tv.getBitmap(capW, capH)
                        }.getOrNull()
                    }
                }

                if (rawFrame != null) {
                    val composited = FrameCompositor.compositeBitmap(
                        source = rawFrame,
                        targetW = settings.outputWidth,
                        targetH = settings.outputHeight,
                        aspectRatio = settings.aspectRatio,
                        mirror = settings.mirrorMode,
                        reuseBitmap = compositedReusable,
                    )
                    compositedReusable = composited
                    rawFrame.recycle()
                    sink.pushFrame(composited, loopStart)
                    fpsFrames++
                } else {
                    sink.pushFrame(standbyBitmap, loopStart)
                    fpsFrames++
                }

                val now = SystemClock.elapsedRealtime()
                if (now - fpsWindowStart >= 1000L) {
                    val measured = (fpsFrames * 1000f) / (now - fpsWindowStart).coerceAtLeast(1L)
                    withContext(Dispatchers.Main) {
                        outputFps = measured
                    }
                    fpsFrames = 0
                    fpsWindowStart = now
                }

                val elapsed = SystemClock.elapsedRealtime() - loopStart
                val sleepMs = (intervalMs - elapsed).coerceAtLeast(4L)
                delay(sleepMs)
            }
        }
    }

    private fun handleRemoteCommand(cmd: String) {
        when (cmd.lowercase()) {
            "play" -> play()
            "pause" -> pause()
            "play_pause" -> if (isPlaying) pause() else play()
            "stop" -> stopPlayback()
            "next" -> nextTrack()
            "prev" -> previousTrack()
            "mirror" -> toggleMirror()
            "aspect" -> {
                val nextAspect = when (settings.aspectRatio) {
                    AspectRatioMode.FIT -> AspectRatioMode.FILL
                    AspectRatioMode.FILL -> AspectRatioMode.ORIGINAL
                    AspectRatioMode.ORIGINAL -> AspectRatioMode.FIT
                }
                setAspectRatio(nextAspect)
            }
            "off" -> applyOutputEnabled(false)
        }
    }

    private fun updateOutputStateBadge() {
        outputState = when {
            !outputEnabled -> OutputState.OFF
            activeSink?.lastError != null -> OutputState.ERROR
            isPlaying && !isStopped -> OutputState.LIVE
            else -> OutputState.STANDBY
        }
    }

    private fun updateServiceNotification() {
        if (!outputEnabled) return
        val track = playlist.getOrNull(activeIndex)?.displayName ?: "Standby"
        val stateLabel = if (isPlaying) "▶ LIVE: $track" else "⏸ STANDBY: $track"
        VirtualCamService.startOrUpdate(
            ctx = context,
            title = stateLabel,
            subtitle = activeEndpointUrl.ifBlank { settings.outputSinkType.labelEn },
        )
    }

    fun onHostPause() {
        if (!outputEnabled && !settings.continueInBackground && isPlaying) {
            pause()
        }
    }

    fun release() {
        applyOutputEnabled(false)
        progressPollJob?.cancel()
        boundTextureView = null
        player?.release()
        player = null
    }
}
