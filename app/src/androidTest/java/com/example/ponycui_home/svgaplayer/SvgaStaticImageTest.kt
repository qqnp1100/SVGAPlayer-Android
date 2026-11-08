package com.example.ponycui_home.svgaplayer

import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.coil3.*
import com.opensource.svgaplayer.loader.SvgaSource
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SvgaStaticImageTest {
    @Test fun staticModeShowsFrameZeroWithoutAudioAndCanSwitchToAnimation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun audioDirectories() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("svga-audio-") }.map { it.name }.toSet()
        val before = audioDirectories()
        for (controls in listOf(false, true)) {
            val ready = CountDownLatch(1); val animated = CountDownLatch(1)
            lateinit var view: SVGAImageView; lateinit var root: FrameLayout; lateinit var handle: SvgaViewHandle
            val source = SvgaSource.Asset("jojo_audio.svga")
            var failure: Throwable? = null
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    root = FrameLayout(activity); view = SVGAImageView(activity)
                    root.addView(view, FrameLayout.LayoutParams(192, 192)); activity.setContentView(root)
                    handle = view.loadSvga(source) {
                        staticImage = true; useViewControls = controls; reverse = true; startFrame = 2
                        onReady = { ready.countDown() }; onError = { failure = it; ready.countDown() }
                    }
                }
                assertTrue(ready.await(20, TimeUnit.SECONDS)); assertNull(failure)
                scenario.onActivity {
                    val drawing = view.drawable as SVGADrawable
                    assertEquals(0, drawing.currentFrame); assertFalse(drawing.cleared); assertFalse(view.isAnimating)
                    assertSame(handle, view.loadSvga(source) { staticImage = true; useViewControls = controls; reverse = true; startFrame = 2 })
                    handle.resume(); handle.seekToProgress(.5f); handle.replay()
                    handle.updateBindings(svgaBindings { hidden("unused") })
                }
                Thread.sleep(350)
                scenario.onActivity {
                    assertEquals(0, (view.drawable as SVGADrawable).currentFrame)
                    assertFalse((view.drawable as SVGADrawable).cleared); assertFalse(view.isAnimating)
                    assertEquals(before, audioDirectories())
                    val next = view.loadSvga(source) {
                        staticImage = false
                        onReady = { animated.countDown() }; onError = { failure = it; animated.countDown() }
                    }
                    assertNotSame(handle, next)
                }
                assertTrue(animated.await(20, TimeUnit.SECONDS)); assertNull(failure)
                Thread.sleep(300)
                scenario.onActivity {
                    assertTrue((view.drawable as SVGADrawable).currentFrame > 0)
                    root.removeView(view); assertNull(view.drawable)
                }
            }
        }
    }
}
