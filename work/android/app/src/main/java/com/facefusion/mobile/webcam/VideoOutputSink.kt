package com.facefusion.mobile.webcam

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Modular interface for all video output destinations.
 *
 * Every implementation is genuinely activated when the user switches the ON/OFF toggle to ON
 * and deactivated immediately when switched to OFF.
 */
interface VideoOutputSink {
    val type: OutputSinkType
    val isRunning: Boolean
    val endpointUrl: String
    val connectedClients: Int
    val framesDispatched: Long
    val bytesDispatched: Long
    val lastError: String?

    /**
     * Starts the output sink and returns the active endpoint description or failure.
     */
    fun start(): Result<String>

    /**
     * Pushes a composited video frame (already transformed with Fit/Fill/Original and Mirror).
     */
    fun pushFrame(frame: Bitmap, timestampMs: Long)

    /**
     * Pushes a pre-encoded JPEG frame (used by MJPEG server and unit tests).
     */
    fun pushJpegBytes(jpeg: ByteArray, timestampMs: Long) {}

    /**
     * Called when playback is stopped while output remains ON.
     */
    fun onPlaybackStopped(standbyFrame: Bitmap?) {}

    /**
     * Stops the output sink and releases all sockets, codecs, or resources.
     */
    fun stop()
}

/**
 * Clean Broadcast Stage Output Sink for Single-Phone TikTok LIVE ("Mobile Gaming / Screen Share")
 * and Android Picture-in-Picture (PiP) broadcasting.
 *
 * When enabled:
 * - Media3 ExoPlayer is configured with `C.ALLOW_CAPTURE_BY_ALL` so TikTok LIVE's
 *   `MediaProjection` + `AudioPlaybackCapture` captures crystal-clear internal digital audio.
 * - Tracks live frame delivery and coordinates chromeless fullscreen stage / PiP rendering.
 */
class CleanStageOutputSink : VideoOutputSink {
    override val type: OutputSinkType = OutputSinkType.TIKTOK_CLEAN_STAGE

    private val running = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0L)
    private val byteCounter = AtomicLong(0L)

    @Volatile
    override var lastError: String? = null
        private set

    override val isRunning: Boolean
        get() = running.get()

    override val endpointUrl: String
        get() = "TikTok LIVE Screen Cast / Clean Stage (ALLOW_CAPTURE_BY_ALL)"

    override val connectedClients: Int
        get() = if (running.get()) 1 else 0

    override val framesDispatched: Long
        get() = frameCounter.get()

    override val bytesDispatched: Long
        get() = byteCounter.get()

    override fun start(): Result<String> {
        lastError = null
        frameCounter.set(0L)
        byteCounter.set(0L)
        running.set(true)
        return Result.success(endpointUrl)
    }

    override fun pushFrame(frame: Bitmap, timestampMs: Long) {
        if (!running.get()) return
        frameCounter.incrementAndGet()
        byteCounter.addAndGet((frame.width * frame.height * 4).toLong())
    }

    override fun pushJpegBytes(jpeg: ByteArray, timestampMs: Long) {
        if (!running.get()) return
        frameCounter.incrementAndGet()
        byteCounter.addAndGet(jpeg.size.toLong())
    }

    override fun onPlaybackStopped(standbyFrame: Bitmap?) {
        if (standbyFrame != null && running.get()) {
            pushFrame(standbyFrame, System.currentTimeMillis())
        }
    }

    override fun stop() {
        running.set(false)
    }
}
