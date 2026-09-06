package com.cotrix.funkedit.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object BeatDetector {

    /** Энергетический детектор битов: адаптивный порог + пик-пикинг + сетка-фолбэк. */
    fun detect(audio: DecodedAudio, minGap: Double = 0.22, maxGap: Double = 0.85): List<Double> {
        val ch = max(1, audio.channelCount)
        val sr = audio.sampleRate
        val n = audio.samples.size / ch
        val mono = DoubleArray(n) { i ->
            var s = 0L
            for (c in 0 until ch) s += audio.samples[i * ch + c]
            (s / ch).toDouble()
        }

        val frame = 1024
        val frames = n / frame
        if (frames < 8) return listOf(0.0, 0.5, 1.0)
        val energy = DoubleArray(frames) { f ->
            var e = 0.0
            val base = f * frame
            for (i in 0 until frame) {
                val v = mono[base + i]
                e += v * v
            }
            sqrt(e / frame)
        }

        val beats = ArrayList<Double>()
        val window = 43
        var last = -1.0
        for (f in 2 until frames - 2) {
            val from = max(0, f - window)
            val to = min(frames, f + window)
            var mean = 0.0
            for (i in from until to) mean += energy[i]
            mean /= (to - from)
            val t = (f * frame + frame / 2).toDouble() / sr
            if (energy[f] > mean * 1.35 &&
                energy[f] > energy[f - 1] &&
                energy[f] >= energy[f + 1] &&
                t - last >= minGap
            ) {
                beats.add(t)
                last = t
            }
        }

        // Если между ударами дыра — вставляем ровную сетку (~133 BPM), как в фонке
        val result = ArrayList<Double>()
        result.add(0.0)
        var prev = 0.0
        for (b in beats) {
            if (b - prev > maxGap) {
                var t = prev + 0.45
                while (t < b - 0.1) {
                    result.add(t)
                    t += 0.45
                }
            }
            result.add(b)
            prev = b
        }
        return result
    }
}
