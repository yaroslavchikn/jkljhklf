package com.cotrix.funkedit.engine

enum class SegKind { INTRO, VIDEO, OUTRO }
enum class Effect { ZOOM_IN, SHAKE, CHROMA, ZOOM_OUT, BLUR_PULSE }

data class Segment(
    val kind: SegKind,
    val start: Double,
    val end: Double,
    val clipIndex: Int = -1,
    val clipOffset: Double = 0.0,
    val effect: Effect = Effect.ZOOM_IN,
    val flash: Boolean = false
)

data class Timeline(val segments: List<Segment>, val duration: Double) {
    fun segmentAt(t: Double): Segment = segments.firstOrNull { t < it.end } ?: segments.last()
}

object TimelineBuilder {

    private val PATTERN = arrayOf(
        Effect.ZOOM_IN, Effect.SHAKE, Effect.CHROMA,
        Effect.ZOOM_OUT, Effect.BLUR_PULSE, Effect.SHAKE
    )

    fun build(
        clipDurations: List<Double>,
        beats: List<Double>,
        musicDur: Double,
        introDur: Double = 1.8,
        outroDur: Double = 2.2
    ): Timeline {
        val segs = ArrayList<Segment>()
        segs.add(Segment(SegKind.INTRO, 0.0, introDur))

        var t = introDur
        val maxEnd = maxOf(introDur + 2.0, musicDur - outroDur)
        val usage = IntArray(clipDurations.size)
        val effects = PATTERN
        var i = 0
        var beatIdx = 0
        val beatList = beats.filter { it > 0.3 }

        while (t < maxEnd && i < 400) {
            val musicNow = t - introDur
            while (beatIdx < beatList.size && beatList[beatIdx] <= musicNow) beatIdx++
            val nextBeat = if (beatIdx < beatList.size) introDur + beatList[beatIdx] else t + 0.45
            val end = minOf(nextBeat, maxEnd)
            if (end - t < 0.12) {
                if (nextBeat <= t) break
                t = end
                continue
            }

            val clip = if (clipDurations.isEmpty()) -1 else i % clipDurations.size
            var offset = 0.0
            if (clip >= 0) {
                val dur = clipDurations[clip]
                offset = usage[clip] * 0.5
                if (offset + (end - t) + 0.1 > dur) offset = 0.0
                usage[clip]++
            }

            segs.add(
                Segment(
                    kind = SegKind.VIDEO,
                    start = t,
                    end = end,
                    clipIndex = clip,
                    clipOffset = offset,
                    effect = effects[i % effects.size],
                    flash = i % 2 == 0
                )
            )
            t = end
            i++
        }

        segs.add(Segment(SegKind.OUTRO, t, t + outroDur))
        return Timeline(segs, t + outroDur)
    }
}
