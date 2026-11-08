package com.example.ponycui_home.svgaplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SVGAImageView
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SvgaLoadTestActivityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("svga_load_test", Context.MODE_PRIVATE)
    private var saved: Map<String, *> = emptyMap<String, String>()

    @Before fun savePreferences() {
        saved = preferences.all
        preferences.edit().clear().commit()
    }

    @After fun restorePreferences() {
        preferences.edit().clear().apply {
            saved.forEach { (key, value) -> putString(key, value as String) }
        }.commit()
    }

    @Test fun editsSurviveRecreationAndReopeningWithoutLoading() {
        launch().use { scenario ->
            scenario.onActivity {
                edit(it, "url", "https://example.com/custom.svga")
                edit(it, "speed", "invalid draft")
                edit(it, "headers", "X-Test: saved")
                select(it, "bitmapConfig", "RGB_565")
                check(it, "skipInvisibleImages", true)
            }
            scenario.recreate()
            scenario.onActivity { assertDraft(it) }
        }
        launch().use { scenario ->
            scenario.onActivity {
                assertDraft(it)
                assertEquals("等待加载", status(it))
                assertNull(it.findViewById<android.view.View>(android.R.id.content)
                    .findViewWithTag<SVGAImageView>("svga_preview").drawable)
            }
        }
    }

    @Test fun invalidSettingsDoNotStartNetworkOrReplaceThePreview() {
        launch().use { scenario ->
            scenario.onActivity {
                edit(it, "url", "file:///tmp/demo.svga")
                load(it)
                assertTrue(status(it).contains("HTTP"))
                edit(it, "url", "https://example.com/demo.svga")
                edit(it, "speed", "NaN")
                load(it)
                assertTrue(status(it).contains("速度"))
                edit(it, "speed", "1")
                check(it, "cacheOnly", true)
                check(it, "refresh", true)
                load(it)
                assertTrue(status(it).contains("同时"))
                check(it, "refresh", false)
                edit(it, "headers", "missing colon")
                load(it)
                assertTrue(status(it).contains("请求头"))
            }
        }
    }

    @Test fun networkSettingsReachServerAndEveryClickLoadsAgain() {
        MockWebServer().use { server ->
            server.start()
            val bytes = context.assets.open("rose_2.0.0.svga").use { it.readBytes() }
            repeat(2) { server.enqueue(MockResponse().setBody(Buffer().write(bytes))) }
            val url = server.url("/playground.svga").toString()
            val missingUrl = server.url("/missing.svga").toString()
            launch().use { scenario ->
                scenario.onActivity {
                    edit(it, "url", url)
                    edit(it, "headers", "X-SVGA-Test: playground\nX-Second: value:with:colons")
                    edit(it, "width", "128")
                    edit(it, "height", "128")
                    edit(it, "startFrame", "3")
                    edit(it, "endFrame", "6")
                    edit(it, "iterations", "1")
                    select(it, "cachePolicy", "NONE")
                    check(it, "autoPlay", false)
                    check(it, "reverse", true)
                    load(it)
                }
                awaitStatus(scenario, "加载成功")
                assertEquals("playground", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("X-SVGA-Test"))
                scenario.onActivity {
                    val preview = it.window.decorView.findViewWithTag<SVGAImageView>("svga_preview")
                    assertEquals(6, (preview.drawable as SVGADrawable).currentFrame)
                    assertTrue(it.window.decorView.findViewWithTag<TextView>("current_result").text.contains("128"))
                    load(it)
                }
                awaitStatus(scenario, "加载成功")
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("value:with:colons", request.getHeader("X-Second"))
                assertEquals(2, server.requestCount)
                scenario.onActivity {
                    assertTrue(it.window.decorView.findViewWithTag<TextView>("previous_result").text.contains("上次"))
                    check(it, "staticImage", true)
                    // This failure must replace success feedback rather than leave a stale result.
                    edit(it, "url", missingUrl)
                    server.enqueue(MockResponse().setResponseCode(404))
                    load(it)
                }
                awaitStatus(scenario, "加载失败")
            }
        }
    }

    @Test fun comparisonReportsEffectiveDecodeDimensions() {
        fun summary(scale: String, width: String, height: String): String = SvgaLoadTestConfig(mapOf(
            LoadTestField.SCALE to scale, LoadTestField.WIDTH to width, LoadTestField.HEIGHT to height,
        )).validate().summary(193, 257)
        assertTrue(summary("FIT_CENTER", "0", "0").contains("256×320px"))
        assertTrue(summary("FIT_CENTER", "128", "0").contains("128×320px"))
        assertTrue(summary("CENTER", "128", "128").contains("原图尺寸"))
    }

    @Test fun cacheOnlyAndStaticFirstFrameSettingsAreApplied() {
        MockWebServer().use { server ->
            server.start()
            val url = server.url("/cache-only.svga").toString()
            val bytes = context.assets.open("rose_2.0.0.svga").use { it.readBytes() }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            launch().use { scenario ->
                scenario.onActivity {
                    edit(it, "url", url)
                    edit(it, "width", "128")
                    edit(it, "height", "128")
                    edit(it, "namespace", "test-${server.port}")
                    edit(it, "startFrame", "3")
                    check(it, "autoPlay", false)
                    load(it)
                }
                awaitStatus(scenario, "加载成功")
                scenario.onActivity {
                    check(it, "cacheOnly", true)
                    check(it, "staticImage", true)
                    load(it)
                }
                awaitStatus(scenario, "加载成功")
                assertEquals(1, server.requestCount)
                scenario.onActivity {
                    val preview = it.window.decorView.findViewWithTag<SVGAImageView>("svga_preview")
                    assertEquals(0, (preview.drawable as SVGADrawable).currentFrame)
                    assertTrue(status(it).contains("静态首帧"))
                }
            }
        }
    }

    private fun assertDraft(activity: Activity) {
        assertEquals("https://example.com/custom.svga", activity.window.decorView.findViewWithTag<EditText>("url").text.toString())
        assertEquals("invalid draft", activity.window.decorView.findViewWithTag<EditText>("speed").text.toString())
        assertEquals("X-Test: saved", activity.window.decorView.findViewWithTag<EditText>("headers").text.toString())
        assertEquals("RGB_565", activity.window.decorView.findViewWithTag<Spinner>("bitmapConfig").selectedItem)
        assertTrue(activity.window.decorView.findViewWithTag<CheckBox>("skipInvisibleImages").isChecked)
    }

    private fun launch(): ActivityScenario<Activity> = ActivityScenario.launch(
        Intent().setClassName(context, "com.example.ponycui_home.svgaplayer.SvgaLoadTestActivity")
    )

    private fun edit(activity: Activity, key: String, value: String) {
        activity.window.decorView.findViewWithTag<EditText>(key).setText(value)
    }

    private fun check(activity: Activity, key: String, value: Boolean) {
        activity.window.decorView.findViewWithTag<CheckBox>(key).isChecked = value
    }

    private fun select(activity: Activity, key: String, value: String) {
        val spinner = activity.window.decorView.findViewWithTag<Spinner>(key)
        spinner.setSelection((0 until spinner.count).first { spinner.getItemAtPosition(it) == value })
    }

    private fun load(activity: Activity) { activity.window.decorView.findViewWithTag<Button>("load").performClick() }
    private fun status(activity: Activity) = activity.window.decorView.findViewWithTag<TextView>("load_status").text.toString()

    private fun awaitStatus(scenario: ActivityScenario<Activity>, prefix: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var last = ""
        while (System.nanoTime() < deadline) {
            scenario.onActivity { last = status(it) }
            if (last.startsWith(prefix)) return
            Thread.sleep(50)
        }
        fail("Expected $prefix, got $last")
    }
}
