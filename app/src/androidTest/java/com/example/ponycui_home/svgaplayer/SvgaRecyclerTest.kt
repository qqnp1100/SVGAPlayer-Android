package com.example.ponycui_home.svgaplayer

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.coil3.*
import com.opensource.svgaplayer.loader.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SvgaRecyclerTest {
    private val source = SvgaSource.Asset("rose_2.0.0.svga")
    private class Holder(val image: SVGAImageView) : RecyclerView.ViewHolder(image) {
        lateinit var handle: SvgaViewHandle
    }
    @Test fun notifyDataSetChangedKeepsDrawableFrameAndPausedState() {
        val ready = CountDownLatch(1)
        val callbacks = AtomicInteger()
        lateinit var list: RecyclerView
        lateinit var adapter: RecyclerView.Adapter<Holder>
        lateinit var holder: Holder
        lateinit var original: SVGADrawable
        var frame = 0
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                list = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity); itemAnimator = null }
                adapter = object : RecyclerView.Adapter<Holder>() {
                    init { setHasStableIds(true) }
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getItemCount() = 1
                    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(SVGAImageView(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(192, 192)
                    })
                    override fun onBindViewHolder(target: Holder, position: Int) {
                        holder = target
                        target.handle = target.image.loadSvga(source) { onReady = { callbacks.incrementAndGet(); ready.countDown() } }
                    }
                    override fun onViewRecycled(holder: Holder) { holder.image.clearSvga() }
                }
                list.adapter = adapter; activity.setContentView(list)
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS))
            lateinit var handle: SvgaViewHandle
            scenario.onActivity {
                handle = holder.handle; handle.pause(); handle.seekToProgress(.5f)
                original = holder.image.drawable as SVGADrawable; frame = original.currentFrame
                adapter.notifyDataSetChanged()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(200)
            scenario.onActivity {
                assertSame(handle, holder.handle); assertSame(original, holder.image.drawable)
                assertEquals(frame, original.currentFrame); assertEquals(1, callbacks.get())
                assertNotSame(handle, holder.image.loadSvga(source) { reuseOnRebind = false })
                holder.image.clearSvga()
            }
        }
    }

    @Test fun detachImmediatelyReleasesDrawable() {
        val ready = CountDownLatch(1)
        lateinit var root: FrameLayout; lateinit var view: SVGAImageView
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                root = FrameLayout(activity); view = SVGAImageView(activity)
                root.addView(view, FrameLayout.LayoutParams(192, 192)); activity.setContentView(root)
                view.loadSvga(source) { onReady = { ready.countDown() } }
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS))
            scenario.onActivity {
                assertNotNull(view.drawable)
                root.removeView(view)
                assertNull(view.drawable)
                root.addView(view); assertNull(view.drawable)
                view.clearSvga()
            }
        }
    }

    @Test fun repeatedPendingRequestUsesLatestCallbacksAndChangedRequestReplacesIt() {
        val server = okhttp3.mockwebserver.MockWebServer(); server.start()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = context.assets.open("rose_2.0.0.svga").use { it.readBytes() }
        val url = server.url("/pending.svga").toString()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Cache-Control", "no-store")
            .setBody(okio.Buffer().write(data)).setBodyDelay(400, TimeUnit.MILLISECONDS))
        val ready = CountDownLatch(1); val oldCalls = AtomicInteger()
        lateinit var view: SVGAImageView
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    view = SVGAImageView(activity); activity.setContentView(view)
                    val first = view.loadSvga(url) { onReady = { oldCalls.incrementAndGet() } }
                    assertSame(first, view.loadSvga(url) { onReady = { ready.countDown() } })
                }
                assertTrue(ready.await(20, TimeUnit.SECONDS)); assertEquals(0, oldCalls.get()); assertEquals(1, server.requestCount)
                scenario.onActivity {
                    val same = view.loadSvga(url)
                    val different = view.loadSvga(SvgaRequest(source).copy(namespace = "changed"))
                    assertNotSame(same, different); view.clearSvga(); assertNull(view.drawable)
                }
            }
        } finally { server.shutdown() }
    }
}
