package com.opensource.svgaplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.text.BoringLayout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by cuiminghui on 2017/3/30.
 */
class SVGADynamicEntity {
    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val MAX_DYNAMIC_IMAGE_BYTES = 32 * 1024 * 1024
    }

    internal var dynamicHidden: HashMap<String, Boolean> = hashMapOf()

    internal var dynamicInImage: MutableMap<String, Bitmap> = ConcurrentHashMap()
    internal var dynamicOutImage: MutableMap<String, Bitmap> = ConcurrentHashMap()

    internal var dynamicInAnimatedImage: MutableMap<String, SVGADynamicImage> = ConcurrentHashMap()
    internal var dynamicOutAnimatedImage: MutableMap<String, SVGADynamicImage> = ConcurrentHashMap()

    internal var dynamicOutImageKeyUrl: MutableMap<String, String> = ConcurrentHashMap()
    private val ownedBitmapKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    internal var dynamicText: HashMap<String, String> = hashMapOf()
    internal var dynamicScrollTextSpeed: HashMap<String, Float> = hashMapOf()

    internal var dynamicTextPaint: HashMap<String, TextPaint> = hashMapOf()

    internal var dynamicStaticLayoutText: HashMap<String, StaticLayout> = hashMapOf()

    internal var dynamicBoringLayoutText: HashMap<String, BoringLayout> = hashMapOf()

    internal var dynamicDrawer: HashMap<String, (canvas: Canvas, frameIndex: Int) -> Boolean> =
        hashMapOf()

    //点击事件回调map
    internal var mClickMap: HashMap<String, IntArray> = hashMapOf()
    internal var dynamicIClickArea: HashMap<String, IClickAreaListener> = hashMapOf()

    internal var dynamicDrawerSized: HashMap<String, (canvas: Canvas, frameIndex: Int, width: Int, height: Int) -> Boolean> =
        hashMapOf()


    internal var isTextDirty = false


    var srcollTextSpace = 10f
    @Volatile
    private var isClean = false
    @Volatile
    private var isLoad = false

    fun setHidden(value: Boolean, forKey: String) {
        this.dynamicHidden.put(forKey, value)
    }

    fun setDynamicImage(bitmap: Bitmap, forKey: String) {
        this.isClean = false
        replaceBitmap(forKey, bitmap, dynamicOutImage, ownedByLibrary = false)
    }

    fun getDynamicImage(key: String): Bitmap? {
        return dynamicInImage[key] ?: dynamicOutImage[key]
    }

    internal fun getDynamicAnimatedImage(key: String): SVGADynamicImage? {
        return dynamicInAnimatedImage[key] ?: dynamicOutAnimatedImage[key]
    }

    fun setDynamicImage(data: ByteArray, forKey: String) {
        this.isClean = false
        SVGADynamicImage.decode(data)?.let {
            clearBitmapForKey(forKey)
            clearAnimatedImageForKey(forKey)
            this.dynamicOutAnimatedImage[forKey] = it
            return
        }
        BitmapFactory.decodeByteArray(data, 0, data.size)?.let {
            replaceBitmap(forKey, it, dynamicOutImage, ownedByLibrary = true)
        }
    }

    fun setDynamicGif(data: ByteArray, forKey: String) {
        setDynamicImage(data, forKey)
    }

    fun setDynamicWebp(data: ByteArray, forKey: String) {
        setDynamicImage(data, forKey)
    }

    fun setDynamicImage(url: String, forKey: String) {
        this.isClean = false
        dynamicOutImageKeyUrl[forKey] = url
    }

    suspend fun requestDynamicImage(imageView: ImageView) {
        if (isLoad) {
            return
        }
        isLoad = true
        if (SVGAParser.customDynamicImageLoad != null) {
            val dynamicImageLoad = SVGAParser.customDynamicImageLoad ?: return
            val dynamicImageDataLoad =
                dynamicImageLoad as? SVGAParser.CustomDynamicImageLoadWithData
            for (entry in dynamicOutImageKeyUrl.entries.toList()) {
                if (isClean) {
                    return
                }
                var hasDecodedImageData = false
                dynamicImageDataLoad?.loadImageData(imageView, entry.value, entry.key)
                    ?.let { data ->
                        hasDecodedImageData = decodeDynamicImage(data, entry.key)
                    }
                if (hasDecodedImageData) {
                    continue
                }
                dynamicImageLoad.loadImage(imageView, entry.value, entry.key)
                    ?.let {
                        if (isClean) {
                            return
                        }
                        replaceBitmap(entry.key, it, dynamicOutImage, ownedByLibrary = false)
                    }
            }
            return
        }
        for (entry in dynamicOutImageKeyUrl.entries.toList()) {
            if (isClean) {
                return
            }
            (URL(entry.value).openConnection() as? HttpURLConnection)?.let {
                try {
                    it.connectTimeout = 20 * 1000
                    it.requestMethod = "GET"
                    it.readTimeout = 20 * 1000
                    it.connect()
                    it.inputStream.use { stream ->
                        val data = readDynamicImageBytes(stream)
                        decodeDynamicImage(data, entry.key)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try {
                        it.disconnect()
                    } catch (disconnectException: Throwable) {
                        // ignored here
                    }
                }
            }
        }
    }

    private fun decodeDynamicImage(data: ByteArray, key: String): Boolean {
        if (isClean) {
            return false
        }
        SVGADynamicImage.decode(data)?.let {
            if (isClean) {
                return false
            }
            clearBitmapForKey(key)
            clearAnimatedImageForKey(key)
            dynamicInAnimatedImage[key] = it
            return true
        }
        BitmapFactory.decodeByteArray(data, 0, data.size)?.let {
            if (isClean) {
                return false
            }
            replaceBitmap(key, it, dynamicInImage, ownedByLibrary = true)
            return true
        }
        return false
    }

    private fun replaceBitmap(
        key: String,
        bitmap: Bitmap,
        target: MutableMap<String, Bitmap>,
        ownedByLibrary: Boolean
    ) {
        clearBitmapForKey(key)
        clearAnimatedImageForKey(key)
        target[key] = bitmap
        if (ownedByLibrary) {
            ownedBitmapKeys.add(key)
        } else {
            ownedBitmapKeys.remove(key)
        }
    }

    private fun clearBitmapForKey(key: String) {
        if (ownedBitmapKeys.remove(key)) {
            dynamicInImage[key]?.takeIf { !it.isRecycled }?.recycle()
            dynamicOutImage[key]?.takeIf { !it.isRecycled }?.recycle()
        }
        dynamicInImage.remove(key)
        dynamicOutImage.remove(key)
    }

    private fun clearAnimatedImageForKey(key: String) {
        dynamicInAnimatedImage.remove(key)?.clear()
        dynamicOutAnimatedImage.remove(key)?.clear()
    }

    private fun readDynamicImageBytes(inputStream: InputStream): ByteArray {
        val outputStream = com.opensource.svgaplayer.utils.MyByteArrayOutputStream(BUFFER_SIZE)
        outputStream.use {
            val buffer = ByteArray(BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val count = inputStream.read(buffer)
                if (count == -1) {
                    break
                }
                totalBytes += count
                if (totalBytes > MAX_DYNAMIC_IMAGE_BYTES) {
                    throw IOException("Dynamic image exceeds $MAX_DYNAMIC_IMAGE_BYTES bytes.")
                }
                it.write(buffer, 0, count)
            }
            return it.toUnSafeByteArray()
        }
    }

    /**
     * @speed 我也不知道是什么单位，反正速度能统一，大家伙看着设吧
     */
    fun setDynamicTextScrollSpeed(forKey: String, speed: Float) {
        this.dynamicScrollTextSpeed.put(forKey, speed)
    }

    fun setDynamicText(text: String, textPaint: TextPaint, forKey: String) {
        this.isTextDirty = true
        this.dynamicText.put(forKey, text)
        this.dynamicTextPaint.put(forKey, textPaint)
    }

    fun setDynamicText(layoutText: StaticLayout, forKey: String) {
        this.isTextDirty = true
        this.dynamicStaticLayoutText.put(forKey, layoutText)
    }

    fun setDynamicText(layoutText: BoringLayout, forKey: String) {
        this.isTextDirty = true
        BoringLayout.isBoring(layoutText.text, layoutText.paint)?.let {
            this.dynamicBoringLayoutText.put(forKey, layoutText)
        }
    }

    fun setDynamicDrawer(drawer: (canvas: Canvas, frameIndex: Int) -> Boolean, forKey: String) {
        this.dynamicDrawer.put(forKey, drawer)
    }

    fun setClickArea(clickKey: List<String>) {
        for (itemKey in clickKey) {
            dynamicIClickArea.put(itemKey, object : IClickAreaListener {
                override fun onResponseArea(key: String, x0: Int, y0: Int, x1: Int, y1: Int) {
                    mClickMap.let {
                        if (it.get(key) == null) {
                            it.put(key, intArrayOf(x0, y0, x1, y1))
                        } else {
                            it.get(key)?.let {
                                it[0] = x0
                                it[1] = y0
                                it[2] = x1
                                it[3] = y1
                            }
                        }
                    }
                }
            })
        }
    }

    fun setClickArea(clickKey: String) {
        dynamicIClickArea.put(clickKey, object : IClickAreaListener {
            override fun onResponseArea(key: String, x0: Int, y0: Int, x1: Int, y1: Int) {
                mClickMap.let {
                    if (it.get(key) == null) {
                        it.put(key, intArrayOf(x0, y0, x1, y1))
                    } else {
                        it.get(key)?.let {
                            it[0] = x0
                            it[1] = y0
                            it[2] = x1
                            it[3] = y1
                        }
                    }
                }
            }
        })
    }

    fun setDynamicDrawerSized(
        drawer: (canvas: Canvas, frameIndex: Int, width: Int, height: Int) -> Boolean,
        forKey: String,
    ) {
        this.dynamicDrawerSized.put(forKey, drawer)
    }

    fun clearDynamicObjects() {
        isLoad = false
        this.isClean = true
        this.isTextDirty = true
        this.dynamicHidden.clear()
        ownedBitmapKeys.toList().forEach {
            clearBitmapForKey(it)
        }
        ownedBitmapKeys.clear()
        this.dynamicInImage.clear()
        this.dynamicOutImage.clear()
        this.dynamicInAnimatedImage.values.forEach {
            it.clear()
        }
        this.dynamicOutAnimatedImage.values.forEach {
            it.clear()
        }
        this.dynamicInAnimatedImage.clear()
        this.dynamicOutAnimatedImage.clear()
        this.dynamicOutImageKeyUrl.clear()
        this.dynamicText.clear()
        this.dynamicScrollTextSpeed.clear()
        this.dynamicTextPaint.clear()
        this.dynamicStaticLayoutText.clear()
        this.dynamicBoringLayoutText.clear()
        this.dynamicDrawer.clear()
        this.dynamicIClickArea.clear()
        this.mClickMap.clear()
        this.dynamicDrawerSized.clear()
    }
}
