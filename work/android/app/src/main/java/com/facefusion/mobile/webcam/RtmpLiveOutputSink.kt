package com.facefusion.mobile.webcam

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Parsed RTMP destination URL components.
 */
data class RtmpEndpoint(
    val host: String,
    val port: Int,
    val app: String,
    val streamKey: String,
    val tcUrl: String,
)

/**
 * Pure, testable RTMP handshake, AMF0 command encoder, and FLV AVC tag muxer.
 */
object RtmpProtocol {
    const val RTMP_VERSION: Byte = 0x03
    const val HANDSHAKE_SIZE = 1536

    fun parseRtmpUrl(rawUrl: String, explicitStreamKey: String = ""): Result<RtmpEndpoint> = runCatching {
        val trimmed = rawUrl.trim()
        require(trimmed.startsWith("rtmp://", ignoreCase = true)) {
            "RTMP URL must start with rtmp:// (e.g., rtmp://live.example.com/app)"
        }
        val withoutScheme = trimmed.substring(7)
        val slashIdx = withoutScheme.indexOf('/')
        require(slashIdx > 0) { "RTMP URL is missing the application path (/app)" }

        val hostPort = withoutScheme.substring(0, slashIdx)
        val pathPart = withoutScheme.substring(slashIdx + 1).trim('/')
        require(pathPart.isNotEmpty()) { "RTMP URL application name cannot be empty" }

        val colonIdx = hostPort.indexOf(':')
        val host: String
        val port: Int
        if (colonIdx > 0) {
            host = hostPort.substring(0, colonIdx)
            port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: 1935
        } else {
            host = hostPort
            port = 1935
        }
        require(host.isNotBlank()) { "RTMP host cannot be empty" }

        val segments = pathPart.split('/').filter { it.isNotBlank() }
        val app: String
        val key: String
        if (explicitStreamKey.isNotBlank()) {
            app = segments.joinToString("/")
            key = explicitStreamKey.trim()
        } else if (segments.size >= 2) {
            app = segments.dropLast(1).joinToString("/")
            key = segments.last()
        } else {
            app = segments.first()
            key = ""
        }
        val tcUrl = "rtmp://$host:$port/$app"
        RtmpEndpoint(host = host, port = port, app = app, streamKey = key, tcUrl = tcUrl)
    }

    /**
     * Builds the 1537-byte C0+C1 client handshake buffer.
     */
    fun buildC0C1(timestampSec: Int = 0): ByteArray {
        val out = ByteArray(1 + HANDSHAKE_SIZE)
        out[0] = RTMP_VERSION
        val buf = ByteBuffer.wrap(out, 1, HANDSHAKE_SIZE)
        buf.putInt(timestampSec)
        buf.putInt(0) // zero marker for simple handshake
        for (i in 8 until HANDSHAKE_SIZE) {
            out[1 + i] = ((i * 31 + 17) and 0xFF).toByte()
        }
        return out
    }

    fun amfString(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(3 + bytes.size)
        out.write(0x02) // AMF0 String marker
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
        return out.toByteArray()
    }

    fun amfNumber(value: Double): ByteArray {
        val buf = ByteBuffer.allocate(9)
        buf.put(0x00) // AMF0 Number marker
        buf.putDouble(value)
        return buf.array()
    }

    fun amfNull(): ByteArray = byteArrayOf(0x05)

    fun amfObject(props: Map<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x03) // AMF0 Object start
        for ((k, v) in props) {
            val kb = k.toByteArray(Charsets.UTF_8)
            out.write((kb.size ushr 8) and 0xFF)
            out.write(kb.size and 0xFF)
            out.write(kb)
            when (v) {
                is String -> out.write(amfString(v))
                is Number -> out.write(amfNumber(v.toDouble()))
                is Boolean -> out.write(byteArrayOf(0x01, if (v) 1 else 0))
            }
        }
        // Object end marker: 0x00 0x00 0x09
        out.write(byteArrayOf(0x00, 0x00, 0x09))
        return out.toByteArray()
    }

    /**
     * Wraps an AMF0 command payload into an RTMP Chunk Stream packet (Format 0).
     */
    fun wrapRtmpChunk(
        chunkStreamId: Int,
        timestampMs: Int,
        messageTypeId: Int,
        messageStreamId: Int,
        payload: ByteArray,
        chunkSize: Int = 4096,
    ): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 32)
        // Basic Header (fmt=0, csid in 2..63)
        out.write(chunkStreamId and 0x3F)
        // Timestamp (3 bytes big-endian)
        val ts = timestampMs.coerceIn(0, 0xFFFFFF)
        out.write((ts ushr 16) and 0xFF)
        out.write((ts ushr 8) and 0xFF)
        out.write(ts and 0xFF)
        // Message Length (3 bytes big-endian)
        val len = payload.size
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        // Message Type ID (1 byte: 20 = AMF0 Command, 9 = Video)
        out.write(messageTypeId and 0xFF)
        // Message Stream ID (4 bytes little-endian per RTMP spec)
        out.write(messageStreamId and 0xFF)
        out.write((messageStreamId ushr 8) and 0xFF)
        out.write((messageStreamId ushr 16) and 0xFF)
        out.write((messageStreamId ushr 24) and 0xFF)

        var offset = 0
        while (offset < payload.size) {
            if (offset > 0) {
                // Format 3 continuation header
                out.write(0xC0 or (chunkStreamId and 0x3F))
            }
            val n = minOf(chunkSize, payload.size - offset)
            out.write(payload, offset, n)
            offset += n
        }
        return out.toByteArray()
    }

    /**
     * Builds an FLV AVC Video Tag body for an SPS/PPS sequence header.
     */
    fun buildFlvAvcSequenceHeader(sps: ByteArray, pps: ByteArray): ByteArray {
        val cleanSps = stripStartCode(sps)
        val cleanPps = stripStartCode(pps)
        val profileIdc = if (cleanSps.size > 1) cleanSps[1].toInt() and 0xFF else 0x42
        val profileCompat = if (cleanSps.size > 2) cleanSps[2].toInt() and 0xFF else 0x00
        val levelIdc = if (cleanSps.size > 3) cleanSps[3].toInt() and 0xFF else 0x1F

        val out = ByteArrayOutputStream(32 + cleanSps.size + cleanPps.size)
        out.write(0x17) // Keyframe (1) + AVC (7)
        out.write(0x00) // AVC sequence header
        out.write(byteArrayOf(0x00, 0x00, 0x00)) // Composition time = 0

        // AVCDecoderConfigurationRecord
        out.write(0x01) // configurationVersion = 1
        out.write(profileIdc)
        out.write(profileCompat)
        out.write(levelIdc)
        out.write(0xFF) // 4-byte NALU length size minus 1
        out.write(0xE1) // 1 SPS
        out.write((cleanSps.size ushr 8) and 0xFF)
        out.write(cleanSps.size and 0xFF)
        out.write(cleanSps)
        out.write(0x01) // 1 PPS
        out.write((cleanPps.size ushr 8) and 0xFF)
        out.write(cleanPps.size and 0xFF)
        out.write(cleanPps)
        return out.toByteArray()
    }

    /**
     * Builds an FLV AVC Video Tag body for an encoded H.264 NALU.
     */
    fun buildFlvAvcNaluBody(nalu: ByteArray, isKeyframe: Boolean): ByteArray {
        val clean = stripStartCode(nalu)
        val out = ByteArrayOutputStream(9 + clean.size)
        out.write(if (isKeyframe) 0x17 else 0x27) // 1=Keyframe / 2=Interframe, 7=AVC
        out.write(0x01) // AVC NALU
        out.write(byteArrayOf(0x00, 0x00, 0x00)) // Composition time = 0
        val len = clean.size
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(clean)
        return out.toByteArray()
    }

    fun stripStartCode(buf: ByteArray): ByteArray {
        if (buf.size >= 4 && buf[0] == 0.toByte() && buf[1] == 0.toByte() &&
            buf[2] == 0.toByte() && buf[3] == 1.toByte()
        ) {
            return buf.copyOfRange(4, buf.size)
        }
        if (buf.size >= 3 && buf[0] == 0.toByte() && buf[1] == 0.toByte() && buf[2] == 1.toByte()) {
            return buf.copyOfRange(3, buf.size)
        }
        return buf
    }
}

/**
 * Direct Phone-Only RTMP Live Stream Output Sink.
 *
 * Encodes composited video frames to H.264/AVC via Android's hardware [MediaCodec]
 * and streams FLV AVC packets over TCP RTMP to the target server.
 */
class RtmpLiveOutputSink(
    private val rtmpUrl: String,
    private val streamKey: String,
    private val width: Int = 720,
    private val height: Int = 1280,
    private val fps: Int = 24,
) : VideoOutputSink {

    override val type: OutputSinkType = OutputSinkType.RTMP_PUSH

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0L)
    private val byteCounter = AtomicLong(0L)

    @Volatile
    override var lastError: String? = null
        private set

    private var socket: Socket? = null
    private var workerThread: Thread? = null
    private val frameQueue = LinkedBlockingQueue<Pair<Bitmap, Long>>(4)

    override val isRunning: Boolean
        get() = running.get()

    override val endpointUrl: String
        get() = if (streamKey.isNotBlank()) "${rtmpUrl.trimEnd('/')}/***" else rtmpUrl

    override val connectedClients: Int
        get() = if (connected.get()) 1 else 0

    override val framesDispatched: Long
        get() = frameCounter.get()

    override val bytesDispatched: Long
        get() = byteCounter.get()

    override fun start(): Result<String> {
        stop()
        val parsed = RtmpProtocol.parseRtmpUrl(rtmpUrl, streamKey)
        if (parsed.isFailure) {
            val msg = parsed.exceptionOrNull()?.message ?: "Invalid RTMP URL"
            lastError = msg
            return Result.failure(IllegalArgumentException(msg))
        }
        val endpoint = parsed.getOrThrow()
        lastError = null
        frameCounter.set(0L)
        byteCounter.set(0L)
        frameQueue.clear()
        running.set(true)

        workerThread = Thread({ runPublisherLoop(endpoint) }, "webcam-rtmp-publisher").apply {
            isDaemon = true
            start()
        }
        return Result.success(endpointUrl)
    }

    override fun pushFrame(frame: Bitmap, timestampMs: Long) {
        if (!running.get() || frame.isRecycled) return
        val copy = frame.copy(Bitmap.Config.ARGB_8888, false) ?: return
        while (!frameQueue.offer(copy to timestampMs)) {
            frameQueue.poll()?.first?.recycle()
        }
    }

    override fun onPlaybackStopped(standbyFrame: Bitmap?) {
        if (standbyFrame != null && running.get()) {
            pushFrame(standbyFrame, System.currentTimeMillis())
        }
    }

    override fun stop() {
        running.set(false)
        connected.set(false)
        runCatching { socket?.close() }
        socket = null
        workerThread?.let { runCatching { it.join(1000L) } }
        workerThread = null
        while (true) {
            val item = frameQueue.poll() ?: break
            runCatching { item.first.recycle() }
        }
    }

    private fun runPublisherLoop(endpoint: RtmpEndpoint) {
        var codec: MediaCodec? = null
        try {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.soTimeout = 10_000
            sock.connect(InetSocketAddress(endpoint.host, endpoint.port), 8_000)
            socket = sock

            val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val output = BufferedOutputStream(sock.getOutputStream())

            // 1. RTMP Handshake (C0 + C1 -> S0 + S1 + S2 -> C2)
            output.write(RtmpProtocol.buildC0C1())
            output.flush()
            val s0 = input.readByte()
            if (s0 != RtmpProtocol.RTMP_VERSION) {
                throw IllegalStateException("Unsupported RTMP server version: $s0")
            }
            val s1 = ByteArray(RtmpProtocol.HANDSHAKE_SIZE)
            input.readFully(s1)
            val s2 = ByteArray(RtmpProtocol.HANDSHAKE_SIZE)
            input.readFully(s2)
            output.write(s1) // C2 echoes S1
            output.flush()

            // 2. Send connect(app), createStream(), publish(streamKey, "live")
            sendConnect(output, endpoint)
            sendCreateStream(output)
            sendPublish(output, endpoint.streamKey.ifBlank { "live" })
            connected.set(true)

            // 3. Configure Hardware H.264/AVC Encoder
            val encW = (width.coerceAtLeast(320) and 1.inv())
            val encH = (height.coerceAtLeast(240) and 1.inv())
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 2_200_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(10, 60))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            val info = MediaCodec.BufferInfo()
            val startMs = System.currentTimeMillis()
            var seqHeaderSent = false

            while (running.get()) {
                val pair = frameQueue.poll(200L, TimeUnit.MILLISECONDS)
                if (pair != null) {
                    val (bmp, _) = pair
                    val scaled = if (bmp.width == encW && bmp.height == encH) bmp
                    else Bitmap.createScaledBitmap(bmp, encW, encH, true)

                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val nv12 = argbBitmapToYuv420(scaled, encW, encH)
                            val copyLen = minOf(inBuf.remaining(), nv12.size)
                            inBuf.put(nv12, 0, copyLen)
                            val ptsUs = (System.currentTimeMillis() - startMs) * 1000L
                            codec.queueInputBuffer(inIdx, 0, copyLen, ptsUs, 0)
                        }
                    }
                    if (scaled !== bmp) scaled.recycle()
                    bmp.recycle()
                }

                while (running.get()) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0L)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val outFmt = codec.outputFormat
                        val spsBuf = outFmt.getByteBuffer("csd-0")
                        val ppsBuf = outFmt.getByteBuffer("csd-1")
                        if (spsBuf != null && ppsBuf != null) {
                            val sps = ByteArray(spsBuf.remaining()).also { spsBuf.get(it) }
                            val pps = ByteArray(ppsBuf.remaining()).also { ppsBuf.get(it) }
                            val seqTag = RtmpProtocol.buildFlvAvcSequenceHeader(sps, pps)
                            val chunk = RtmpProtocol.wrapRtmpChunk(6, 0, 9, 1, seqTag)
                            output.write(chunk)
                            output.flush()
                            byteCounter.addAndGet(chunk.size.toLong())
                            seqHeaderSent = true
                        }
                    } else if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val nalu = ByteArray(info.size)
                            outBuf.get(nalu)
                            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            val tsMs = (info.presentationTimeUs / 1000L).toInt().coerceAtLeast(0)
                            val body = RtmpProtocol.buildFlvAvcNaluBody(nalu, isKey)
                            val chunk = RtmpProtocol.wrapRtmpChunk(6, tsMs, 9, 1, body)
                            output.write(chunk)
                            output.flush()
                            frameCounter.incrementAndGet()
                            byteCounter.addAndGet(chunk.size.toLong())
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    } else {
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                lastError = "RTMP connection failed: ${t.message ?: t.javaClass.simpleName}"
            }
        } finally {
            connected.set(false)
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { socket?.close() }
        }
    }

    private fun sendConnect(out: OutputStream, endpoint: RtmpEndpoint) {
        val payload = ByteArrayOutputStream().apply {
            write(RtmpProtocol.amfString("connect"))
            write(RtmpProtocol.amfNumber(1.0))
            write(
                RtmpProtocol.amfObject(
                    mapOf(
                        "app" to endpoint.app,
                        "type" to "nonprivate",
                        "tcUrl" to endpoint.tcUrl,
                    )
                )
            )
        }.toByteArray()
        out.write(RtmpProtocol.wrapRtmpChunk(3, 0, 20, 0, payload))
        out.flush()
    }

    private fun sendCreateStream(out: OutputStream) {
        val payload = ByteArrayOutputStream().apply {
            write(RtmpProtocol.amfString("createStream"))
            write(RtmpProtocol.amfNumber(2.0))
            write(RtmpProtocol.amfNull())
        }.toByteArray()
        out.write(RtmpProtocol.wrapRtmpChunk(3, 0, 20, 0, payload))
        out.flush()
    }

    private fun sendPublish(out: OutputStream, key: String) {
        val payload = ByteArrayOutputStream().apply {
            write(RtmpProtocol.amfString("publish"))
            write(RtmpProtocol.amfNumber(3.0))
            write(RtmpProtocol.amfNull())
            write(RtmpProtocol.amfString(key))
            write(RtmpProtocol.amfString("live"))
        }.toByteArray()
        out.write(RtmpProtocol.wrapRtmpChunk(8, 0, 20, 1, payload))
        out.flush()
    }

    private fun argbBitmapToYuv420(bmp: Bitmap, w: Int, h: Int): ByteArray {
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val yuv = ByteArray(w * h * 3 / 2)
        var yIndex = 0
        var uvIndex = w * h
        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[j * w + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0 && uvIndex + 1 < yuv.size) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }
}
