package com.weaudio.pro.arrangement

/** Horizontal timeline geometry in dp: zoom (dp per quarter note), scroll offset and tick<->x conversion. */
data class TimelineViewport(val dpPerBeat: Float = 56f, val scrollDp: Float = 0f) {
    val dpPerTick get() = dpPerBeat / PPQ
    fun tickToDp(tick: Long) = tick * dpPerTick
    fun dpToTick(dp: Float) = (dp / dpPerTick).toLong()
    fun contentWidthDp(lengthTicks: Long) = tickToDp(lengthTicks) + 120f   // trailing room to append clips
    fun zoomBy(factor: Float, anchorDp: Float = 0f): TimelineViewport {
        val z = (dpPerBeat * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val anchorTick = (scrollDp + anchorDp) / dpPerTick           // keep the tick under the anchor fixed
        return copy(dpPerBeat = z, scrollDp = (anchorTick * (z / PPQ) - anchorDp).coerceAtLeast(0f))
    }
    fun scrollBy(deltaDp: Float, contentDp: Float, viewDp: Float) = copy(scrollDp = (scrollDp + deltaDp).coerceIn(0f, (contentDp - viewDp).coerceAtLeast(0f)))
    /** Keeps the playhead visible while playing (page-style follow). */
    fun followPlayhead(tick: Long, viewDp: Float, contentDp: Float): TimelineViewport {
        val x = tickToDp(tick)
        return if (x < scrollDp || x > scrollDp + viewDp * 0.9f) copy(scrollDp = (x - viewDp * 0.1f).coerceIn(0f, (contentDp - viewDp).coerceAtLeast(0f))) else this
    }
    companion object { const val MIN_ZOOM = 16f; const val MAX_ZOOM = 240f }
}
