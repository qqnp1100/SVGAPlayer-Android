@file:Suppress("DEPRECATION")
package com.opensource.svgaplayer

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ClickAreaConcurrencyTest {
    @Test fun addingARegionOnAWorkerDoesNotInvalidateAnInFlightTouchIterator() {
        val dynamic = SVGADynamicEntity().apply { setClickArea(listOf("first", "second")) }
        dynamic.dynamicIClickArea.getValue("first").onResponseArea("first", 0, 0, 16, 16)
        val iterator = dynamic.mClickMap.entries.iterator()
        assertTrue(iterator.hasNext())
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try { dynamic.dynamicIClickArea.getValue("second").onResponseArea("second", 16, 0, 32, 16) }
            catch (e: Throwable) { failure.set(e) }
        }.apply { start() }
        worker.join(5_000)
        assertFalse("Region publication must finish", worker.isAlive)
        assertNull(failure.get())
        while (iterator.hasNext()) assertEquals(4, iterator.next().value.size)
        assertArrayEquals(intArrayOf(16, 0, 32, 16), dynamic.mClickMap["second"])
    }

    @Test fun updatingARegionDoesNotChangeCoordinatesAlreadyReadByTouchHandling() {
        val dynamic = SVGADynamicEntity().apply { setClickArea("image") }
        val listener = dynamic.dynamicIClickArea.getValue("image")
        listener.onResponseArea("image", 0, 0, 16, 16)
        val inFlightTouch = dynamic.mClickMap.getValue("image")
        listener.onResponseArea("image", 32, 48, 64, 80)
        assertArrayEquals(intArrayOf(0, 0, 16, 16), inFlightTouch)
        assertArrayEquals(intArrayOf(32, 48, 64, 80), dynamic.mClickMap["image"])
    }
}
