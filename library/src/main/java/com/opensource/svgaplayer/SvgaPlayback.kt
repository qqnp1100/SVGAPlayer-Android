package com.opensource.svgaplayer

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/** Deterministic clock shared by View adapters and Compose. A loop count of zero is infinite. */
enum class SvgaHiddenBehavior { PAUSE, CONTINUE_TIMELINE, STOP }

class SvgaPlayback(val frames: Int, val fps: Int, val startFrame: Int = 0,
    val endFrame: Int = frames - 1, val reverse: Boolean = false) {
    init { require(frames > 0 && fps > 0 && startFrame in 0 until frames && endFrame in startFrame until frames) }
    var positionNanos = 0L
        private set
    var frame = if (reverse) endFrame else startFrame
        private set
    var completedIterations = 0
        private set
    var finished = false
        private set
    private var previous = Long.MIN_VALUE
    fun pause() { previous = Long.MIN_VALUE }
    fun seek(progress: Float) {
        positionNanos = (duration * progress.coerceIn(0f, 1f)).toLong().coerceAtMost(duration - 1)
        completedIterations = 0; finished = false; previous = Long.MIN_VALUE; update()
    }
    fun seekFrame(target: Int) {
        val clamped = target.coerceIn(startFrame, endFrame)
        val offset = if (reverse) endFrame - clamped else clamped - startFrame
        // Round up to the first nanosecond in the requested frame, never through Float.
        positionNanos = (offset * 1_000_000_000L + fps - 1) / fps
        completedIterations = 0; finished = false; previous = Long.MIN_VALUE
        update()
    }
    val duration get() = (endFrame - startFrame + 1) * 1_000_000_000L / fps
    fun tick(now: Long, iterations: Int = 0, speed: Float = 1f): Boolean {
        require(iterations >= 0 && speed > 0 && speed.isFinite())
        if (finished) return false
        if (previous != Long.MIN_VALUE) positionNanos += ((now - previous).coerceAtLeast(0) * speed.toDouble()).toLong()
        previous = now
        if (iterations > 0 && positionNanos >= duration * iterations) {
            positionNanos = duration * iterations; completedIterations = iterations
            frame = if (reverse) startFrame else endFrame; finished = true
            return true
        }
        update(); return false
    }
    private fun update() {
        completedIterations = (positionNanos / duration).toInt()
        val offset = ((positionNanos % duration) * fps / 1_000_000_000L).toInt().coerceIn(0, endFrame - startFrame)
        frame = if (reverse) endFrame - offset else startFrame + offset
    }
}

/** Each presentation owns its players/files. Audio is advanced by the clock, never by drawing. */
class SvgaAudioSession(context: Context, private val resource: SvgaResource) : AutoCloseable {
    private val directory = File(context.cacheDir, "svga-audio-${java.util.UUID.randomUUID()}")
    private val players = ArrayList<MediaPlayer>()
    private var previousLoop = -1
    private val active = HashSet<Int>()
    private var playbackSpeed = 1f
    /** Call off the main thread before starting the presentation clock. */
    fun prepare() {
        if (resource.audioTracks.isEmpty()) return
        directory.mkdirs()
        try { resource.audioTracks.forEachIndexed { index, track ->
            val file = File(directory, "$index.mp3").apply { writeBytes(track.bytes) }
            val player = MediaPlayer()
            players.add(player)
            player.setDataSource(file.path); player.prepare()
        } } catch (e: Throwable) { close(); throw e }
    }
    fun advance(frame: Int, loop: Int, speed: Float = 1f, reverse: Boolean = false) {
        if (reverse || (android.os.Build.VERSION.SDK_INT < 23 && speed != 1f)) { pause(); return }
        if (playbackSpeed != speed) { pause(); playbackSpeed = speed }
        if (loop != previousLoop) { pause(); previousLoop = loop }
        resource.audioTracks.forEachIndexed { index, track ->
            val player = players.getOrNull(index) ?: return@forEachIndexed
            if (frame in track.startFrame until track.endFrame) {
                if (active.add(index)) {
                    player.seekTo(track.startTimeMillis + (frame - track.startFrame) * 1000 / resource.fps)
                    if (android.os.Build.VERSION.SDK_INT >= 23) player.playbackParams = player.playbackParams.setSpeed(speed)
                    player.start()
                }
            } else if (active.remove(index)) player.pause()
        }
    }
    fun pause() { active.forEach { players[it].pause() }; active.clear() }
    override fun close() { players.forEach { it.release() }; players.clear(); active.clear(); directory.deleteRecursively() }
}
