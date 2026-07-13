package com.opensource.svgaplayer

import kotlin.math.ceil
import kotlin.math.floor

internal class TextScrollTicker(
    private val nowUptimeMillis: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val invalidate: () -> Unit,
) {
    private var enabled = false
    private var hostActive = true
    private var hasScrollableText = false
    private var scheduled = false
    private var nextDeadlineMillis = Double.NaN
    private val tick = Runnable {
        scheduled = false
        if (!canRun()) return@Runnable
        invalidate()
        scheduleNext()
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        hasScrollableText = false
        cancel()
        if (enabled && hostActive) invalidate()
    }

    fun setHostActive(active: Boolean) {
        if (hostActive == active) return
        hostActive = active
        hasScrollableText = false
        cancel()
        if (enabled && active) invalidate()
    }

    fun onDraw(hasScrollableText: Boolean) {
        this.hasScrollableText = hasScrollableText
        if (canRun()) scheduleNext() else cancel()
    }

    fun stop() {
        enabled = false
        hasScrollableText = false
        cancel()
    }

    private fun canRun(): Boolean = enabled && hostActive && hasScrollableText

    private fun scheduleNext() {
        if (scheduled) return
        val now = nowUptimeMillis().toDouble()
        if (!nextDeadlineMillis.isFinite()) nextDeadlineMillis = now
        if (nextDeadlineMillis <= now) {
            val missedFrames = floor((now - nextDeadlineMillis) / FRAME_INTERVAL_MILLIS) + 1.0
            nextDeadlineMillis += missedFrames * FRAME_INTERVAL_MILLIS
        }
        scheduled = true
        schedule(tick, ceil(nextDeadlineMillis).toLong())
    }

    private fun cancel() {
        if (scheduled) unschedule(tick)
        scheduled = false
        nextDeadlineMillis = Double.NaN
    }

    companion object {
        private const val FRAME_INTERVAL_MILLIS = 1000.0 / 60.0
    }
}
