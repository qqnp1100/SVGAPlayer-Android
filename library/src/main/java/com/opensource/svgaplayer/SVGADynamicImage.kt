@file:Suppress("DEPRECATION")

package com.opensource.svgaplayer

import android.annotation.TargetApi
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer

internal abstract class SVGADynamicImage {

    abstract val width: Int
    abstract val height: Int

    abstract fun draw(
        canvas: Canvas,
        matrix: Matrix,
        paint: Paint,
        frameIndex: Int,
        fps: Int
    )

    open fun clear() {}

    companion object {
        private const val DEFAULT_ANIMATION_DURATION = 1000

        fun decode(data: ByteArray): SVGADynamicImage? {
            decodeMovie(data)?.let {
                return it
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeAnimatedDrawable(data)?.let {
                    return it
                }
            }
            return null
        }

        @Suppress("DEPRECATION")
        private fun decodeMovie(data: ByteArray): SVGADynamicImage? {
            val movie = Movie.decodeByteArray(data, 0, data.size) ?: return null
            if (movie.width() <= 0 || movie.height() <= 0) {
                return null
            }
            return MovieDynamicImage(movie)
        }

        @TargetApi(Build.VERSION_CODES.P)
        private fun decodeAnimatedDrawable(data: ByteArray): SVGADynamicImage? {
            return try {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(data))
                (ImageDecoder.decodeDrawable(source) as? AnimatedImageDrawable)?.let {
                    AnimatedDrawableDynamicImage(it)
                }
            } catch (ignore: Throwable) {
                null
            }
        }

        @Suppress("DEPRECATION")
        private class MovieDynamicImage(
            private val movie: Movie
        ) : SVGADynamicImage() {

            private val duration = movie.duration()
                .takeIf { it > 0 }
                ?: DEFAULT_ANIMATION_DURATION

            override val width: Int = movie.width()
            override val height: Int = movie.height()

            override fun draw(
                canvas: Canvas,
                matrix: Matrix,
                paint: Paint,
                frameIndex: Int,
                fps: Int
            ) {
                val frameDuration = 1000f / fps.coerceAtLeast(1)
                val time = ((frameIndex * frameDuration) % duration).toInt()
                movie.setTime(time)
                val saveCount = canvas.save()
                canvas.concat(matrix)
                movie.draw(canvas, 0f, 0f, paint)
                canvas.restoreToCount(saveCount)
            }
        }

        @TargetApi(Build.VERSION_CODES.P)
        private class AnimatedDrawableDynamicImage(
            private val drawable: AnimatedImageDrawable
        ) : SVGADynamicImage() {

            private val handler = Handler(Looper.getMainLooper())
            private val callback = object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) {}

                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                    handler.postAtTime(what, who, `when`)
                }

                override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                    handler.removeCallbacks(what, who)
                }
            }

            override val width: Int = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
            override val height: Int = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1

            init {
                drawable.callback = callback
            }

            override fun draw(
                canvas: Canvas,
                matrix: Matrix,
                paint: Paint,
                frameIndex: Int,
                fps: Int
            ) {
                if (!drawable.isRunning) {
                    drawable.start()
                }
                drawable.alpha = paint.alpha
                drawable.setBounds(0, 0, width, height)
                val saveCount = canvas.save()
                canvas.concat(matrix)
                drawable.draw(canvas)
                canvas.restoreToCount(saveCount)
            }

            override fun clear() {
                drawable.stop()
                drawable.callback = null
            }
        }
    }
}
