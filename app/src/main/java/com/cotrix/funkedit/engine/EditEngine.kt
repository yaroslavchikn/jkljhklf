package com.cotrix.funkedit.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class EditEngine(
    private val context: Context,
    private val clipUris: List<Uri>,
    private val musicUri: Uri,
    private val title: String,
    private val watermark: String,
    private val onProgress: (String, Int) -> Unit
) {
    private val W = 720
    private val H = 1280
    private val FPS = 30

    private lateinit var env: EglEnv
    private lateinit var quad: Quad
    private lateinit var vEnc: MediaCodec
    private lateinit var aEnc: MediaCodec
    private lateinit var muxer: MediaMuxer

    private var progOes = 0
    private var progScene = 0
    private var progOverlay = 0

    private var introTex = 0; private var introAspect = 1f
    private var outroTex = 0; private var outroAspect = 1f
    private var wmTex = 0; private var wmAspect = 1f

    private val vInfo = MediaCodec.BufferInfo()
    private val aInfo = MediaCodec.BufferInfo()
    private var vTrack = -1
    private var aTrack = -1
    private var muxerStarted = false

    private var audio: DecodedAudio? = null
    private var audioPos = 0
    private var audioEosSent = false

    private val decoders = HashMap<Int, ClipDecoder>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun progress(msg: String, pct: Int) {
        mainHandler.post { onProgress(msg, pct) }
    }

    fun run(): Uri {
        progress("Декодирую музыку…", 4)
        val aud = AudioDecoder.decode(context, musicUri)
        audio = aud

        progress("Ищу биты…", 9)
        val beats = BeatDetector.detect(aud)

        progress("Считаю таймлайн…", 12)
        val durations = clipUris.map { uri ->
            val mr = android.media.MediaMetadataRetriever()
            mr.setDataSource(context, uri)
            val d = (mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 3000) / 1000.0
            mr.release()
            d
        }
        val timeline = TimelineBuilder.build(durations, beats, aud.durationSec)

        val tmp = File(context.cacheDir, "funkedit_${System.currentTimeMillis()}.mp4")
        setupEncoders(tmp)

        try {
            render(timeline)
            finishEncoders()
        } finally {
            decoders.values.forEach { it.release() }
            runCatching { env.release() }
        }

        progress("Сохраняю в галерею…", 96)
        val out = publish(tmp)
        progress("Готово!", 100)
        return out
    }

    // ---------- setup ----------

    private fun setupEncoders(tmp: File) {
        val vf = MediaFormat.createVideoFormat("video/avc", W, H)
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        vf.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        vEnc = MediaCodec.createEncoderByType("video/avc")
        vEnc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = vEnc.createInputSurface()
        vEnc.start()

        val aud = audio!!
        val af = MediaFormat.createAudioFormat("audio/mp4a-latm", aud.sampleRate, aud.channelCount)
        af.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        aEnc = MediaCodec.createEncoderByType("audio/mp4a-latm")
        aEnc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aEnc.start()

        muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        env = EglEnv(inputSurface)
        GLES20.glViewport(0, 0, W, H)
        quad = Quad()
        progOes = GlCore.program(GlCore.FRAG_OES)
        progScene = GlCore.program(GlCore.FRAG_SCENE)
        progOverlay = GlCore.program(GlCore.FRAG_OVERLAY)

        val bi = TextArt.intro(title)
        introTex = GlCore.createTexFromBitmap(bi); introAspect = bi.width.toFloat() / bi.height
        val bo = TextArt.outro(watermark)
        outroTex = GlCore.createTexFromBitmap(bo); outroAspect = bo.width.toFloat() / bo.height
        val bw = TextArt.watermark(watermark)
        wmTex = GlCore.createTexFromBitmap(bw); wmAspect = bw.width.toFloat() / bw.height
    }

    // ---------- render loop ----------

    private fun render(timeline: Timeline) {
        val totalFrames = (timeline.duration * FPS).toInt()
        for (n in 0 until totalFrames) {
            val t = n.toDouble() / FPS
            val seg = timeline.segmentAt(t)
            when (seg.kind) {
                SegKind.INTRO -> drawIntro(t - seg.start)
                SegKind.OUTRO -> drawOutro(t - seg.start)
                SegKind.VIDEO -> drawVideo(seg, t)
            }
            env.swap((t * 1e9).toLong())
            drainVideo(0)
            feedAudio()
            drainAudio(0)
            if (n % FPS == 0) {
                progress("Рендер… ${n * 100 / totalFrames}%", 15 + n * 75 / totalFrames)
            }
        }
    }

    private fun drawIntro(local: Double) {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(progScene)
        quad.setTex(GlCore.TEX_FLAT)
        setMat(progScene, GlCore.IDENTITY)
        val hFrac = 0.30 * (1.0 + 0.03 * sin(local * 3.0))
        setQuadScale(progScene, introAspect, hFrac, 0.0, 0.45)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(progScene, "uBg"), 0.96f, 0.96f, 0.97f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progScene, "uAlpha"), 1f)
        bind2d(introTex)
        quad.draw(progScene)
        drawWatermark()
    }

    private fun drawOutro(local: Double) {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(progScene)
        quad.setTex(GlCore.TEX_FLAT)
        setMat(progScene, GlCore.IDENTITY)
        setQuadScale(progScene, outroAspect, 0.42, 0.0, 0.0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(progScene, "uBg"), 0.02f, 0.04f, 0.06f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progScene, "uAlpha"), min(1.0, local / 0.4).toFloat())
        bind2d(outroTex)
        quad.draw(progScene)
    }

    private fun drawVideo(seg: Segment, t: Double) {
        val cd = decoders.getOrPut(seg.clipIndex) { ClipDecoder(context, clipUris[seg.clipIndex], W, H) }
        val local = t - seg.start
        val target = seg.clipOffset + local
        if (seg.clipOffset < cd.texTime - 0.05 && cd.texTime >= 0) cd.reset()
        cd.stepUntil(target)

        val len = max(0.05, seg.end - seg.start)
        val p = (local / len).coerceIn(0.0, 1.0)
        var zoom = 1.0; var shx = 0.0; var shy = 0.0
        var blur = 0f; var chroma = 0f
        when (seg.effect) {
            Effect.ZOOM_IN -> zoom = 1.0 + 0.25 * p
            Effect.ZOOM_OUT -> zoom = 1.3 - 0.3 * p
            Effect.SHAKE -> {
                zoom = 1.15
                shx = sin(t * 70.0) * 0.04
                shy = sin(t * 63.0 + 1.3) * 0.04
            }
            Effect.BLUR_PULSE -> { zoom = 1.1; blur = sin(p * Math.PI).toFloat() }
            Effect.CHROMA -> { zoom = 1.05 + 0.1 * p; chroma = 1f }
        }
        val flash = if (seg.flash) max(0.0, 1.0 - local / 0.09).toFloat() else 0f

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(progOes)
        quad.setTex(cd.texCoords)
        setMat(progOes, cd.getStMatrix())
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(progOes, "uScale"),
            (zoom * cd.coverX).toFloat(), (zoom * cd.coverY).toFloat()
        )
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progOes, "uShift"), shx.toFloat(), shy.toFloat())
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progOes, "uTexel"), 1f / W, 1f / H)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progOes, "uBlur"), blur)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progOes, "uFlash"), flash)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progOes, "uChroma"), chroma)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cd.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progOes, "uTex"), 0)
        quad.draw(progOes)

        drawWatermark()
    }

    private fun drawWatermark() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(progOverlay)
        quad.setTex(GlCore.TEX_FLAT)
        setMat(progOverlay, GlCore.IDENTITY)
        setQuadScale(progOverlay, wmAspect, 0.035, 0.0, -0.88)
        bind2d(wmTex)
        quad.draw(progOverlay)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ---------- gl helpers ----------

    private fun setMat(prog: Int, m: FloatArray) {
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uSt"), 1, false, m, 0)
    }

    private fun setQuadScale(prog: Int, bmpAspect: Float, hFrac: Double, cx: Double, cy: Double) {
        val scaleY = hFrac
        val scaleX = scaleY * bmpAspect * (H.toDouble() / W.toDouble())
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uScale"), scaleX.toFloat(), scaleY.toFloat())
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uShift"), cx.toFloat(), cy.toFloat())
    }

    private fun bind2d(tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(GLES20.glGetProgramInt(progActive()), "uTex"), 0)
    }

    private fun progActive(): Int {
        val p = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, p, 0)
        return p[0]
    }

    // ---------- audio feed ----------

    private fun feedAudio() {
        val aud = audio ?: return
        while (audioPos < aud.samples.size) {
            val idx = aEnc.dequeueInputBuffer(0)
            if (idx < 0) break
            val buf = aEnc.getInputBuffer(idx)!!
            val cap = buf.capacity() / 2
            val remain = aud.samples.size - audioPos
            val n = min(cap, remain)
            buf.clear()
            buf.order(ByteOrder.LITTLE_ENDIAN)
            buf.asShortBuffer().put(aud.samples, audioPos, n)
            audioPos += n
            val pts = (audioPos.toDouble() / (aud.sampleRate * aud.channelCount) * 1_000_000).toLong()
            aEnc.queueInputBuffer(idx, 0, n * 2, pts, 0)
        }
    }

    private fun sendAudioEos() {
        if (audioEosSent) return
        audioEosSent = true
        val aud = audio ?: return
        val idx = aEnc.dequeueInputBuffer(20_000)
        if (idx >= 0) {
            val pts = (audioPos.toDouble() / (aud.sampleRate * aud.channelCount) * 1_000_000).toLong()
            aEnc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    // ---------- mux ----------

    private fun maybeStartMuxer() {
        if (!muxerStarted && vTrack >= 0 && aTrack >= 0) {
            muxer.start()
            muxerStarted = true
        }
    }

    private fun drainVideo(timeout: Long): Boolean {
        var eos = false
        while (true) {
            val idx = vEnc.dequeueOutputBuffer(vInfo, timeout)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                vTrack = muxer.addTrack(vEnc.outputFormat)
                maybeStartMuxer()
                continue
            }
            if (idx < 0) break
            val buf = vEnc.getOutputBuffer(idx)!!
            if (muxerStarted && vInfo.size > 0) {
                buf.position(vInfo.offset)
                buf.limit(vInfo.offset + vInfo.size)
                muxer.writeSampleData(vTrack, buf, vInfo)
            }
            vEnc.releaseOutputBuffer(idx, false)
            if (vInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { eos = true; break }
        }
        return eos
    }

    private fun drainAudio(timeout: Long): Boolean {
        var eos = false
        while (true) {
            val idx = aEnc.dequeueOutputBuffer(aInfo, timeout)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                aTrack = muxer.addTrack(aEnc.outputFormat)
                maybeStartMuxer()
                continue
            }
            if (idx < 0) break
            val buf = aEnc.getOutputBuffer(idx)!!
            if (muxerStarted && aInfo.size > 0) {
                buf.position(aInfo.offset)
                buf.limit(aInfo.offset + aInfo.size)
                muxer.writeSampleData(aTrack, buf, aInfo)
            }
            aEnc.releaseOutputBuffer(idx, false)
            if (aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { eos = true; break }
        }
        return eos
    }

    private fun finishEncoders() {
        vEnc.signalEndOfInputStream()
        drainVideo(30_000)
        sendAudioEos()
        drainAudio(30_000)
        if (muxerStarted) {
            muxer.stop()
            muxer.release()
        }
        runCatching { vEnc.stop(); vEnc.release() }
        runCatching { aEnc.stop(); aEnc.release() }
    }

    // ---------- publish ----------

    private fun publish(tmp: File): Uri {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, tmp.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FunkEdit")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)!!.use { os ->
                tmp.inputStream().use { it.copyTo(os) }
            }
            tmp.delete()
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "FunkEdit").apply { mkdirs() }
            val out = File(dir, tmp.name)
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
        }
    }
}
