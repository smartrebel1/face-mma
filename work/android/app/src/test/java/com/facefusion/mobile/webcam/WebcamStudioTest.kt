package com.facefusion.mobile.webcam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class WebcamStudioTest {

    @Test
    fun testPlaylistSerializationAndReorder() {
        val items = listOf(
            PlaylistItem(id = "v1", uriString = "content://media/1", displayName = "intro.mp4", durationMs = 15_000L, width = 1080, height = 1920),
            PlaylistItem(id = "v2", uriString = "content://media/2", displayName = "main_segment.webm", durationMs = 125_000L, width = 1920, height = 1080),
            PlaylistItem(id = "v3", uriString = "content://media/3", displayName = "outro.mp4", durationMs = 30_000L, width = 720, height = 1280),
        )

        val json = PlaylistStorage.serialize(items, activeIndex = 1)
        val (restored, activeIdx) = PlaylistStorage.deserialize(json)

        assertEquals(3, restored.size)
        assertEquals(1, activeIdx)
        assertEquals("main_segment.webm", restored[1].displayName)
        assertEquals("02:05", restored[1].durationLabel)
        assertEquals("1920x1080", restored[1].resolutionLabel)

        // Move active item (index 1, "v2") up to index 0; activeIndex should follow "v2" to 0
        val (reorderedUp, newActiveUp) = PlaylistStorage.reorder(restored, fromIndex = 1, toIndex = 0, currentActiveIndex = 1)
        assertEquals("v2", reorderedUp[0].id)
        assertEquals("v1", reorderedUp[1].id)
        assertEquals(0, newActiveUp)

        // Remove item at index 0; activeIndex should stay valid inside bounds
        val (afterRemove, newActiveAfterRemove) = PlaylistStorage.removeAt(reorderedUp, removeIndex = 0, currentActiveIndex = newActiveUp)
        assertEquals(2, afterRemove.size)
        assertEquals("v1", afterRemove[0].id)
        assertEquals(0, newActiveAfterRemove)
    }

    @Test
    fun testFormatDuration() {
        assertEquals("00:00", formatDuration(0L))
        assertEquals("00:09", formatDuration(9_400L))
        assertEquals("02:05", formatDuration(125_000L))
        assertEquals("1:01:01", formatDuration(3_661_000L))
    }

    @Test
    fun testFrameCompositorAspectRatiosAndMirror() {
        // 1920x1080 landscape source into 720x1280 portrait canvas:
        // FIT should letterbox horizontally to width=720, height=405, centered vertically
        val fit = FrameCompositor.computePlacement(1920, 1080, 720, 1280, AspectRatioMode.FIT)
        assertEquals(720, fit.canvasWidth)
        assertEquals(1280, fit.canvasHeight)
        assertEquals(720f, fit.width, 0.5f)
        assertEquals(405f, fit.height, 0.5f)
        assertTrue(fit.top > 400f)

        // FILL should scale to height=1280, width=2275.5, cropping left/right (left < 0)
        val fill = FrameCompositor.computePlacement(1920, 1080, 720, 1280, AspectRatioMode.FILL)
        assertEquals(1280f, fill.height, 0.5f)
        assertTrue(fill.width > 2200f)
        assertTrue(fill.left < 0f)

        // ORIGINAL should preserve 16:9 aspect ratio within maxEdge=1280 -> 1280x720
        val orig = FrameCompositor.computePlacement(1920, 1080, 720, 1280, AspectRatioMode.ORIGINAL)
        assertEquals(1280, orig.canvasWidth)
        assertEquals(720, orig.canvasHeight)

        // Mirror horizontal pixel flip
        val srcPixels = intArrayOf(
            1, 2, 3,
            4, 5, 6,
        )
        val mirrored = FrameCompositor.mirrorPixelsHorizontally(srcPixels, width = 3, height = 2)
        assertArrayEquals(
            intArrayOf(
                3, 2, 1,
                6, 5, 4,
            ),
            mirrored,
        )
    }

    @Test
    fun testMjpegHttpOutputSinkLifecycleAndOnOffToggle() {
        var receivedCmd: String? = null
        val sink = MjpegHttpOutputSink(
            port = 0, // ephemeral free port
            allowLan = false,
            jpegQuality = 80,
            onRemoteCommand = { cmd -> receivedCmd = cmd },
            statusProvider = { mapOf("activeTrack" to "demo.mp4", "aspectRatio" to "fit") },
        )

        // Initially OFF
        assertFalse(sink.isRunning)

        // Switch ON
        val startRes = sink.start()
        assertTrue(startRes.isSuccess)
        assertTrue(sink.isRunning)
        val port = sink.actualPort
        assertTrue(port > 0)

        try {
            // Push fake JPEG payload (SOI 0xFFD8 ... EOI 0xFFD9)
            val sampleJpeg = byteArrayOf(
                0xFF.toByte(), 0xD8.toByte(),
                0x01, 0x02, 0x03, 0x04,
                0xFF.toByte(), 0xD9.toByte(),
            )
            sink.pushJpegBytes(sampleJpeg, System.currentTimeMillis())
            assertEquals(1L, sink.framesDispatched)

            // Verify /status endpoint
            val statusConn = URL("http://127.0.0.1:$port/status").openConnection() as HttpURLConnection
            assertEquals(200, statusConn.responseCode)
            val statusBody = BufferedReader(InputStreamReader(statusConn.inputStream)).use { it.readText() }
            assertTrue(statusBody.contains("\"running\":true"))
            assertTrue(statusBody.contains("\"activeTrack\":\"demo.mp4\""))

            // Verify /frame.jpg snapshot endpoint returns exact pushed JPEG bytes
            val frameConn = URL("http://127.0.0.1:$port/frame.jpg").openConnection() as HttpURLConnection
            assertEquals(200, frameConn.responseCode)
            val receivedBytes = frameConn.inputStream.use { it.readBytes() }
            assertArrayEquals(sampleJpeg, receivedBytes)

            // Verify /control remote command endpoint
            val ctrlConn = (URL("http://127.0.0.1:$port/control?cmd=next").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
            }
            assertEquals(200, ctrlConn.responseCode)
            assertEquals("next", receivedCmd)
        } finally {
            // Switch OFF
            sink.stop()
        }

        // Verify genuinely stopped when OFF
        assertFalse(sink.isRunning)
        val afterStopResult = runCatching {
            val conn = URL("http://127.0.0.1:$port/status").openConnection() as HttpURLConnection
            conn.connectTimeout = 500
            conn.responseCode
        }
        assertTrue(afterStopResult.isFailure)
    }

    @Test
    fun testRtmpProtocolParsingAndPacketFraming() {
        val parsed = RtmpProtocol.parseRtmpUrl("rtmp://live.tiktok.com:1935/live", "stream_key_123")
        assertTrue(parsed.isSuccess)
        val ep = parsed.getOrThrow()
        assertEquals("live.tiktok.com", ep.host)
        assertEquals(1935, ep.port)
        assertEquals("live", ep.app)
        assertEquals("stream_key_123", ep.streamKey)
        assertEquals("rtmp://live.tiktok.com:1935/live", ep.tcUrl)

        val c0c1 = RtmpProtocol.buildC0C1(timestampSec = 42)
        assertEquals(1537, c0c1.size)
        assertEquals(0x03.toByte(), c0c1[0])

        val sps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F, 0xAA.toByte())
        val pps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val seqHeader = RtmpProtocol.buildFlvAvcSequenceHeader(sps, pps)
        assertNotNull(seqHeader)
        assertEquals(0x17.toByte(), seqHeader[0]) // Keyframe + AVC
        assertEquals(0x00.toByte(), seqHeader[1]) // AVC sequence header
    }
}
