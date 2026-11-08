package com.opensource.svgaplayer.glide5

import android.os.Looper
import com.opensource.svgaplayer.loader.SvgaCachePolicy
import com.opensource.svgaplayer.loader.SvgaEngine
import com.opensource.svgaplayer.loader.SvgaRequest
import com.opensource.svgaplayer.loader.SvgaSource
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SvgaSharedLoaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sharedAdapterLoadsAfterEngineCloseAndAdapterClose() {
        val previousEngine = ReflectionHelpers.getStaticField<SvgaEngine?>(SvgaEngine::class.java, "shared")
        val previousLoader = ReflectionHelpers.getStaticField<SvgaImageLoader?>(SvgaImageLoader::class.java, "shared")
        ReflectionHelpers.setStaticField(SvgaEngine::class.java, "shared", null)
        ReflectionHelpers.setStaticField(SvgaImageLoader::class.java, "shared", null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loaders = ArrayList<SvgaImageLoader>()
        try {
            val file = temporary.newFile("gift.svga")
            file.writeBytes(ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("movie.spec"))
                    zip.write("""{"movie":{"viewBox":{"width":100,"height":80},"fps":20,"frames":2},"images":{},"sprites":[]}""".toByteArray())
                    zip.closeEntry()
                }
            }.toByteArray())
            val request = SvgaRequest(SvgaSource.LocalFile(file), cachePolicy = SvgaCachePolicy.NONE)
            fun verifyLoad(loader: SvgaImageLoader) {
                val task = scope.async { loader.load(request) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (!task.isCompleted && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(5)
                }
                assertTrue("Load did not complete", task.isCompleted)
                assertEquals(2, runBlocking { task.await() }.frames)
            }
            val context = RuntimeEnvironment.getApplication()
            val first = SvgaImageLoader.get(context).also { loaders.add(it) }
            verifyLoad(first)
            first.engine.close()
            val second = SvgaImageLoader.get(context).also { loaders.add(it) }
            assertNotSame(first, second)
            assertNotSame(first.engine, second.engine)
            assertSame(SvgaEngine.get(context), second.engine)
            verifyLoad(second)
            second.close()
            val third = SvgaImageLoader.get(context).also { loaders.add(it) }
            assertNotSame(second, third)
            assertSame(second.engine, third.engine)
            assertSame(third, SvgaImageLoader.get(context))
            verifyLoad(third)
        } finally {
            scope.cancel()
            loaders.forEach { it.close(); it.engine.close() }
            ReflectionHelpers.setStaticField(SvgaImageLoader::class.java, "shared", previousLoader)
            ReflectionHelpers.setStaticField(SvgaEngine::class.java, "shared", previousEngine)
        }
    }
}

