package com.opensource.svgaplayer.drawer

internal class TextScrollTimeline(
    private val maxFrameDeltaNanos: Long = 100_000_000L,
) {
    private data class State(
        var offset: Float = 0f,
        var lastFrameTimeNanos: Long? = null,
    )

    private val states = HashMap<String, State>()

    fun offsetFor(
        key: String,
        cycleWidth: Float,
        pixelsPerSecond: Float,
        frameTimeNanos: Long,
    ): Float {
        val state = states.getOrPut(key) { State() }
        val lastFrameTimeNanos = state.lastFrameTimeNanos
        state.lastFrameTimeNanos = frameTimeNanos
        if (!cycleWidth.isFinite() || cycleWidth <= 0f ||
            !pixelsPerSecond.isFinite() || pixelsPerSecond <= 0f ||
            lastFrameTimeNanos == null
        ) {
            return state.offset
        }
        val elapsedNanos = (frameTimeNanos - lastFrameTimeNanos)
            .coerceIn(0L, maxFrameDeltaNanos)
        val distance = pixelsPerSecond * elapsedNanos.toFloat() / 1_000_000_000f
        state.offset = (state.offset + distance) % cycleWidth
        if (state.offset < 0f) {
            state.offset += cycleWidth
        }
        return state.offset
    }

    fun pause() {
        states.values.forEach { it.lastFrameTimeNanos = null }
    }

    fun clear() {
        states.clear()
    }

    companion object {
        fun isActive(textWidth: Int, viewportWidth: Int, speed: Float): Boolean {
            return textWidth > viewportWidth &&
                viewportWidth > 0 &&
                speed.isFinite() &&
                speed > 0f
        }
    }
}
