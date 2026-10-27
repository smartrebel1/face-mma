package com.facefusion.mobile.webcam

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Multi-client MJPEG-over-HTTP Live Video Streaming Server + Browser Studio Receiver.
 *
 * When the user switches the ON/OFF toggle to ON:
 * - Binds to `0.0.0.0:port` (Wi-Fi LAN + USB) or `127.0.0.1:port` (USB `adb forward` / on-device loopback).
 * - Serves `/stream.mjpeg` (`multipart/x-mixed-replace; boundary=frame`) for OBS Studio,
 *   TikTok LIVE Studio, VLC, FFmpeg, SplitCam, WebcamMax, or Linux `v4l2loopback`.
 * - Serves `/player` — a self-contained dark-themed HTML5 Live Studio Receiver & Remote Control.
 * - Serves `/frame.jpg` — latest composited JPEG frame snapshot.
 * - Serves `/status` — real-time JSON telemetry.
 * - Serves `/control?cmd=...` — remote control webhook for Play/Pause/Stop/Next/Prev/Mirror/Aspect.
 *
 * When the user switches the ON/OFF toggle to OFF:
 * - Immediately closes the listening socket and disconnects all active stream clients.
 */
class MjpegHttpOutputSink(
    private val port: Int = 8080,
    private val allowLan: Boolean = true,
    private val jpegQuality: Int = 80,
    private val onRemoteCommand: ((String) -> Unit)? = null,
    private val statusProvider: (() -> Map<String, Any?>)? = null,
) : VideoOutputSink {

    override val type: OutputSinkType = OutputSinkType.MJPEG_HTTP

    private val running = AtomicBoolean(false)
    private val activeClients = AtomicInteger(0)
    private val frameCounter = AtomicLong(0L)
    private val byteCounter = AtomicLong(0L)
    private val frameLock = Object()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = port

    @Volatile
    private var latestJpeg: ByteArray? = null

    @Volatile
    private var latestFrameSeq: Long = 0L

    @Volatile
    override var lastError: String? = null
        private set

    private val clientSockets = CopyOnWriteArrayList<Socket>()
    private var executor = Executors.newCachedThreadPool()

    override val isRunning: Boolean
        get() = running.get()

    override val connectedClients: Int
        get() = activeClients.get().coerceAtLeast(0)

    override val framesDispatched: Long
        get() = frameCounter.get()

    override val bytesDispatched: Long
        get() = byteCounter.get()

    override val endpointUrl: String
        get() {
            val host = if (allowLan) detectLanIp() else "127.0.0.1"
            return "http://$host:$boundPort/stream.mjpeg"
        }

    val playerUrl: String
        get() {
            val host = if (allowLan) detectLanIp() else "127.0.0.1"
            return "http://$host:$boundPort/player"
        }

    val actualPort: Int
        get() = boundPort

    override fun start(): Result<String> {
        stop()
        return runCatching {
            lastError = null
            frameCounter.set(0L)
            byteCounter.set(0L)
            latestFrameSeq = 0L
            executor = Executors.newCachedThreadPool()

            val bindAddr = if (allowLan) {
                InetAddress.getByName("0.0.0.0")
            } else {
                InetAddress.getByName("127.0.0.1")
            }
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(bindAddr, port), 16)
            boundPort = server.localPort
            serverSocket = server
            running.set(true)

            Thread({ acceptLoop(server) }, "webcam-mjpeg-accept").apply {
                isDaemon = true
                start()
            }
            endpointUrl
        }.onFailure { err ->
            lastError = err.message ?: err.javaClass.simpleName
            running.set(false)
        }
    }

    override fun pushFrame(frame: Bitmap, timestampMs: Long) {
        if (!running.get() || frame.isRecycled) return
        val out = ByteArrayOutputStream(32 * 1024)
        val ok = frame.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(30, 95), out)
        if (!ok) return
        pushJpegBytes(out.toByteArray(), timestampMs)
    }

    override fun pushJpegBytes(jpeg: ByteArray, timestampMs: Long) {
        if (!running.get() || jpeg.isEmpty()) return
        synchronized(frameLock) {
            latestJpeg = jpeg
            latestFrameSeq = frameCounter.incrementAndGet()
            frameLock.notifyAll()
        }
    }

    override fun onPlaybackStopped(standbyFrame: Bitmap?) {
        if (standbyFrame != null && running.get()) {
            pushFrame(standbyFrame, System.currentTimeMillis())
        }
    }

    override fun stop() {
        running.set(false)
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
        runCatching { serverSocket?.close() }
        serverSocket = null
        for (sock in clientSockets) {
            runCatching { sock.close() }
        }
        clientSockets.clear()
        activeClients.set(0)
        runCatching { executor.shutdownNow() }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                if (!running.get()) return
                continue
            }
            clientSockets.add(client)
            runCatching {
                executor.execute {
                    try {
                        handleClient(client)
                    } finally {
                        clientSockets.remove(client)
                        runCatching { client.close() }
                    }
                }
            }.onFailure {
                clientSockets.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()

        val reqLine = readRequestLine(input) ?: return
        val parts = reqLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val fullPath = parts[1]
        val qIdx = fullPath.indexOf('?')
        val path = if (qIdx < 0) fullPath else fullPath.substring(0, qIdx)
        val query = if (qIdx < 0) emptyMap() else parseQuery(fullPath.substring(qIdx + 1))

        when {
            path == "/stream.mjpeg" || path == "/video.mjpeg" -> {
                socket.soTimeout = 0
                streamMjpeg(output)
            }
            path == "/frame.jpg" || path == "/snapshot.jpg" -> {
                serveSnapshot(output)
            }
            path == "/status" -> {
                serveStatus(output)
            }
            path == "/control" && (method == "POST" || method == "GET") -> {
                val cmd = query["cmd"].orEmpty()
                if (cmd.isNotBlank()) {
                    onRemoteCommand?.invoke(cmd)
                }
                val resp = JSONObject().put("ok", true).put("cmd", cmd).toString().toByteArray()
                writeHttpResponse(output, 200, "application/json; charset=utf-8", resp)
            }
            path == "/" || path == "/player" || path == "/index.html" -> {
                val html = buildPlayerHtml().toByteArray(Charsets.UTF_8)
                writeHttpResponse(output, 200, "text/html; charset=utf-8", html)
            }
            else -> {
                val err = JSONObject().put("error", "Not found: $path").toString().toByteArray()
                writeHttpResponse(output, 404, "application/json; charset=utf-8", err)
            }
        }
    }

    private fun streamMjpeg(out: OutputStream) {
        activeClients.incrementAndGet()
        try {
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n")
                append("Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n")
                append("Pragma: no-cache\r\n")
                append("Connection: close\r\n")
                append("Access-Control-Allow-Origin: *\r\n\r\n")
            }
            out.write(headers.toByteArray(Charsets.US_ASCII))
            out.flush()

            var lastSentSeq = -1L
            while (running.get()) {
                var frameBytes: ByteArray? = null
                var seq = lastSentSeq
                synchronized(frameLock) {
                    if (latestFrameSeq == lastSentSeq || latestJpeg == null) {
                        runCatching { frameLock.wait(250L) }
                    }
                    if (latestJpeg != null) {
                        frameBytes = latestJpeg
                        seq = latestFrameSeq
                    }
                }
                if (!running.get()) break
                val jpeg = frameBytes ?: continue
                if (seq == lastSentSeq && connectedClients > 0) {
                    // Keep stream alive by resending latest frame every ~250ms if paused
                }
                lastSentSeq = seq

                val partHeader = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                out.write(partHeader.toByteArray(Charsets.US_ASCII))
                out.write(jpeg)
                out.write("\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                byteCounter.addAndGet(jpeg.size.toLong())
            }
        } catch (_: Exception) {
            // Client disconnected
        } finally {
            activeClients.decrementAndGet()
        }
    }

    private fun serveSnapshot(out: OutputStream) {
        val jpeg = latestJpeg
        if (jpeg == null) {
            writeHttpResponse(out, 503, "text/plain; charset=utf-8", "No frame available yet".toByteArray())
            return
        }
        byteCounter.addAndGet(jpeg.size.toLong())
        writeHttpResponse(out, 200, "image/jpeg", jpeg)
    }

    private fun serveStatus(out: OutputStream) {
        val json = JSONObject().apply {
            put("running", running.get())
            put("sink", type.code)
            put("port", boundPort)
            put("streamUrl", endpointUrl)
            put("playerUrl", playerUrl)
            put("connectedClients", connectedClients)
            put("framesDispatched", framesDispatched)
            put("bytesDispatched", bytesDispatched)
            statusProvider?.invoke()?.forEach { (k, v) -> put(k, v) }
        }
        writeHttpResponse(out, 200, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun writeHttpResponse(out: OutputStream, code: Int, contentType: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }
    }

    private fun readRequestLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 4096) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        // Drain remaining headers up to blank line
        var state = 0
        var guard = 0
        while (state < 3 && guard < 16384) {
            val b = input.read()
            if (b < 0) break
            guard++
            state = when {
                b == '\n'.code && state == 0 -> 1
                b == '\r'.code && state == 1 -> 2
                b == '\n'.code && (state == 1 || state == 2) -> 3
                else -> 0
            }
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
        }.toMap()

    private fun buildPlayerHtml(): String = """
        <!DOCTYPE html>
        <html lang="ar" dir="rtl">
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width,initial-scale=1" />
            <title>Virtual Webcam Live Stream Studio</title>
            <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                    background: #0d1017;
                    color: #f0f4f8;
                    font-family: system-ui, -apple-system, sans-serif;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    min-height: 100vh;
                    padding: 12px;
                }
                body.clean { padding: 0; background: #000; justify-content: center; }
                body.clean .hud { display: none; }
                .hud {
                    width: 100%;
                    max-width: 900px;
                    background: #161b26;
                    border: 1px solid #262f42;
                    border-radius: 12px;
                    padding: 12px 16px;
                    margin-bottom: 12px;
                    display: flex;
                    flex-wrap: wrap;
                    justify-content: space-between;
                    align-items: center;
                    gap: 10px;
                }
                .badge {
                    background: #00e676;
                    color: #051b0e;
                    font-weight: 700;
                    padding: 4px 10px;
                    border-radius: 999px;
                    font-size: 12px;
                }
                .controls {
                    display: flex;
                    flex-wrap: wrap;
                    gap: 8px;
                }
                button {
                    background: #232c3d;
                    color: #fff;
                    border: 1px solid #384661;
                    border-radius: 8px;
                    padding: 8px 12px;
                    cursor: pointer;
                    font-weight: 600;
                }
                button:hover { background: #2f3b52; }
                .stage {
                    width: 100%;
                    max-width: 900px;
                    background: #000;
                    border-radius: 12px;
                    overflow: hidden;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                }
                body.clean .stage { max-width: 100vw; height: 100vh; border-radius: 0; }
                img {
                    max-width: 100%;
                    max-height: 82vh;
                    object-fit: contain;
                    display: block;
                }
                body.clean img { max-height: 100vh; width: 100vw; }
            </style>
        </head>
        <body>
            <div class="hud">
                <div>
                    <span class="badge">LIVE OUTPUT ON</span>
                    <strong style="margin-right:8px;">بث الكاميرا الافتراضية المباشر • Virtual Webcam Stream</strong>
                    <div id="info" style="font-size:12px;color:#9aa5b8;margin-top:4px;">Connecting to /stream.mjpeg...</div>
                </div>
                <div class="controls">
                    <button onclick="cmd('prev')">⏮ Prev</button>
                    <button onclick="cmd('play')">▶ Play</button>
                    <button onclick="cmd('pause')">⏸ Pause</button>
                    <button onclick="cmd('stop')">⏹ Stop</button>
                    <button onclick="cmd('next')">⏭ Next</button>
                    <button onclick="cmd('mirror')">⇄ Mirror</button>
                    <button onclick="cmd('aspect')">⛶ Aspect</button>
                    <button onclick="document.body.classList.toggle('clean')">Clean OBS View</button>
                </div>
            </div>
            <div class="stage" ondblclick="document.body.classList.toggle('clean')">
                <img id="feed" src="/stream.mjpeg" alt="Live Stream Feed" />
            </div>
            <script>
                if (location.search.includes('clean=1')) document.body.classList.add('clean');
                function cmd(c) { fetch('/control?cmd=' + c, { method: 'POST' }); }
                setInterval(async () => {
                    try {
                        const r = await fetch('/status');
                        const j = await r.json();
                        document.getElementById('info').textContent =
                            'Frames: ' + j.framesDispatched + ' | Clients: ' + j.connectedClients +
                            ' | Track: ' + (j.activeTrack || '-') + ' | Aspect: ' + (j.aspectRatio || 'FIT');
                    } catch (e) {}
                }, 1500);
            </script>
        </body>
        </html>
    """.trimIndent()

    companion object {
        fun detectLanIp(): String = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                ?.hostAddress ?: "127.0.0.1"
        }.getOrDefault("127.0.0.1")
    }
}
