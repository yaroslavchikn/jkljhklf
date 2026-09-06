package com.cotrix.funkedit.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Surface

/** Декодер одного клипа с пошаговой подачей кадров в SurfaceTexture. */
class ClipDecoder(
    private val context: Context,
    val uri: Uri,
    screenW: Int,
    screenH: Int
) {
    val duration: Double
    val coverX: Float
    val coverY: Float
    val texCoords: FloatArray
    val textureId: Int
    val surfaceTexture: SurfaceTexture

    private val extractor = MediaExtractor()
    private val decoder: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var firstPts = Long.MIN_VALUE
    private var lastTs = -1L
    private var eos = false
    private val stMatrix = FloatArray(16)

    var texTime = -1.0
        private set

    init {
        val mr = MediaMetadataRetriever()
        mr.setDataSource(context, uri)
        duration = (mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000) / 1000.0
        val rotation = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val w = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 16f
        val h = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 9f
        mr.release()

        val ew = if (rotation == 90 || rotation == 270) h else w
        val eh = if (rotation == 90 || rotation == 270) w else h
        val s = maxOf(screenW / ew, screenH / eh)
        coverX = s * ew / screenW
        coverY = s * eh / screenH
        texCoords = GlCore.texCoordsFor(rotation)

        textureId = GlCore.createOesTexture()
        surfaceTexture = SurfaceTexture(textureId)
        val surface = Surface(surfaceTexture)

        extractor.setDataSource(context, uri, null)
        var fmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                fmt = f
                extractor.selectTrack(i)
                break
            }
        }
        val format = checkNotNull(fmt) { "В клипе нет видео" }
        decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, surface, null, 0)
        decoder.start()
    }

    fun getStMatrix(): FloatArray {
        surfaceTexture.getTransformMatrix(stMatrix)
        return stMatrix
    }

    /** Крутит декодер, пока в текстуре не окажется кадр с временем >= t (сек). */
    fun stepUntil(t: Double): Boolean {
        var guard = 0
        while (texTime < t && !eos && guard++ < 900) {
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val b = decoder.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(b, 0)
                    if (n < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val out = decoder.dequeueOutputBuffer(info, 10_000)
            if (out >= 0) {
                if (firstPts == Long.MIN_VALUE) firstPts = info.presentationTimeUs
                val pts = (info.presentationTimeUs - firstPts) / 1e6
                val isEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                decoder.releaseOutputBuffer(out, true)
                var tries = 0
                while (tries++ < 60) {
                    surfaceTexture.updateTexImage()
                    if (surfaceTexture.timestamp != lastTs) break
                    Thread.sleep(2)
                }
                lastTs = surfaceTexture.timestamp
                texTime = pts
                if (isEnd) eos = true
            }
        }
        return texTime >= t
    }

    fun reset() {
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        decoder.flush()
        inputDone = false
        eos = false
        texTime = -1.0
        firstPts = Long.MIN_VALUE
    }

    fun release() {
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { extractor.release() }
        runCatching { surfaceTexture.release() }
    }
}
