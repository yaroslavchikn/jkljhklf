package com.cotrix.funkedit.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

class DecodedAudio(
    val sampleRate: Int,
    val channelCount: Int,
    val samples: ShortArray
) {
    val durationSec: Double get() = samples.size.toDouble() / (sampleRate * channelCount)
}

object AudioDecoder {

    fun decode(context: Context, uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                format = f
                extractor.selectTrack(i)
                break
            }
        }
        val fmt = checkNotNull(format) { "В файле нет аудиодорожки" }
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        val chunks = ArrayList<ShortArray>()
        var total = 0
        val info = MediaCodec.BufferInfo()
        var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = codec.outputFormat
                sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (outIdx >= 0) {
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset)
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    val count = info.size / 2
                    val tmp = ShortArray(count)
                    buf.asShortBuffer().get(tmp)
                    chunks.add(tmp)
                    total += count
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        codec.stop(); codec.release(); extractor.release()

        val all = ShortArray(total)
        var p = 0
        for (c in chunks) { System.arraycopy(c, 0, all, p, c.size); p += c.size }
        return DecodedAudio(sampleRate, channels, all)
    }
}
