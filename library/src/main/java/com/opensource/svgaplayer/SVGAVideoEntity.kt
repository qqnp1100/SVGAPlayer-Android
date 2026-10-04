package com.opensource.svgaplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.ImageView
import com.opensource.svgaplayer.entities.SVGAAudioEntity
import com.opensource.svgaplayer.entities.SVGAVideoSpriteEntity
import com.opensource.svgaplayer.bitmap.SvgaImageDecoder
import com.opensource.svgaplayer.bitmap.SvgaImageSize
import com.opensource.svgaplayer.proto.AudioEntity
import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.proto.MovieParams
import com.opensource.svgaplayer.utils.SVGARect
import com.opensource.svgaplayer.utils.log.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*
import kotlin.coroutines.CoroutineContext

/**
 * Created by PonyCui on 16/6/18.
 */
class SVGAVideoEntity {

    private val TAG = "SVGAVideoEntity"

    internal var resource: SvgaResource? = null

    var antiAlias = true
    var movieItem: MovieEntity? = null
    /** Legacy Parser: set before attaching to a View. Null inherits global defaults when images load. */
    var decodeOptions: SvgaDecodeOptions? = null
    private var activeDecodeOptions: SvgaDecodeOptions? = null
    private var imageUsage: Map<String, SvgaImageSize> = emptyMap()

    var videoSize = SVGARect(0.0, 0.0, 0.0, 0.0)
        private set

    var FPS = 15
        private set

    var frames: Int = 0
        private set

    internal var scaleMap = HashMap<String, Pair<Float, Float>>()
    internal var spriteList: List<SVGAVideoSpriteEntity> = emptyList()
    internal var audioList: List<SVGAAudioEntity> = emptyList()
    internal var soundPool: SoundPool? = null
    private var soundCallback: SVGASoundManager.SVGASoundCallBack? = null
    internal var imageMap = HashMap<String, Bitmap>()
    private var mCacheDir: File
    private var mFrameHeight = 0
    private var mFrameWidth = 0
    private var mPlayCallback: SVGAParser.PlayCallback? = null
    private lateinit var mCallback: () -> Unit
    private var imageJson: JSONObject? = null
    @Volatile private var isClean = false
    @Volatile
    private var expectedAudioLoadCount = 0
    @Volatile
    private var audioPrepareCompleted = false

    private var isParser = false
    private var sharesResources = false

    /** A presentation owns its maps and audio state, but borrows immutable frames/bitmaps. */
    internal constructor(resource: SVGAVideoEntity) {
        mCacheDir = resource.mCacheDir
        videoSize = resource.videoSize
        FPS = resource.FPS
        frames = resource.frames
        spriteList = resource.spriteList
        imageMap.putAll(resource.imageMap)
        scaleMap.putAll(resource.scaleMap)
        isParser = true
        sharesResources = true
    }

    constructor(json: JSONObject, cacheDir: File) : this(json, cacheDir, 0, 0)

    constructor(json: JSONObject, cacheDir: File, frameWidth: Int, frameHeight: Int) {
        mFrameWidth = frameWidth
        mFrameHeight = frameHeight
        mCacheDir = cacheDir
        val movieJsonObject = json.optJSONObject("movie") ?: return
        setupByJson(movieJsonObject)
        resetSprites(json)
        imageJson = json.optJSONObject("images")
    }

    private fun setupByJson(movieObject: JSONObject) {
        movieObject.optJSONObject("viewBox")?.let { viewBoxObject ->
            val width = viewBoxObject.optDouble("width", 0.0)
            val height = viewBoxObject.optDouble("height", 0.0)
            videoSize = SVGARect(0.0, 0.0, width, height)
        }
        FPS = movieObject.optInt("fps", 20)
        frames = movieObject.optInt("frames", 0)
    }

    constructor(entity: MovieEntity, cacheDir: File) : this(entity, cacheDir, 0, 0)

    constructor(entity: MovieEntity, cacheDir: File, frameWidth: Int, frameHeight: Int) {
        this.mFrameWidth = frameWidth
        this.mFrameHeight = frameHeight
        this.mCacheDir = cacheDir
        this.movieItem = entity
        entity.params?.let(this::setupByMovie)
        resetSprites(entity)
    }

    public fun getImageSizeByKey(key: String): Size? {
        val bitmap = imageMap[key] ?: return null
        return Size(bitmap.width, bitmap.height)
    }

    public suspend fun parserImages(view: View) {
        synchronized(imageMap) {
            if (isParser || isClean) return
            activeDecodeOptions = decodeOptions ?: SvgaDecodeOptions.defaults
            isParser = true
        }
        val usage = imageUsage()
        synchronized(imageMap) {
            if (isClean) return
            imageUsage = usage
        }
        movieItem?.let {
            try {
                parserImages(view, it)
            } catch (e: Exception) {
                e.printStackTrace()
            } catch (e: OutOfMemoryError) {
                e.printStackTrace()
            }
        }
        imageJson?.let {
            try {
                parserImages(view, it)
            } catch (e: Exception) {
                e.printStackTrace()
            } catch (e: OutOfMemoryError) {
                e.printStackTrace()
            }
        }
    }

    private fun setupByMovie(movieParams: MovieParams) {
        val width = (movieParams.viewBoxWidth ?: 0.0f).toDouble()
        val height = (movieParams.viewBoxHeight ?: 0.0f).toDouble()
        videoSize = SVGARect(0.0, 0.0, width, height)
        FPS = movieParams.fps ?: 20
        frames = movieParams.frames ?: 0
    }

    internal fun prepare(callback: () -> Unit, playCallback: SVGAParser.PlayCallback?) {
        mCallback = callback
        mPlayCallback = playCallback
        expectedAudioLoadCount = 0
        audioPrepareCompleted = false
        if (movieItem == null) {
            mCallback()
        } else {
            setupAudios(movieItem!!) {
                mCallback()
            }
        }
    }

    private fun parserImages(imageView: View, imgJson: JSONObject) {
        imgJson.keys().forEach { imgKey ->
            if (isClean) {
                return
            }
            if (activeDecodeOptions?.skipInvisibleImages == true && imageUsage[imgKey.removeSuffix(".matte")]?.hasVisibleFrame != true) return@forEach
            val filePath = generateBitmapFilePath(imgJson[imgKey].toString(), imgKey)
            if (filePath.isEmpty()) {
                return
            }
            val bitmapKey = imgKey.removeSuffix(".matte")
            val maxScale = scaleMap[imgKey] ?: Pair(1f, 1f)
            var lastBitmap: Bitmap? = null
            synchronized(imageMap) {
                lastBitmap = imageMap[bitmapKey]
            }
            if (lastBitmap == null || lastBitmap.isRecycled) {
                val bitmap = createBitmap(imageView, filePath, maxScale.first, maxScale.second, imgKey)
                if (bitmap != null) acceptBitmap(bitmapKey, bitmap)
            }
        }
    }

    private fun parserImages(view: View, obj: MovieEntity) {
        obj.images?.entries?.forEach { entry ->
            if (isClean) {
                return
            }
            if (activeDecodeOptions?.skipInvisibleImages == true && imageUsage[entry.key.removeSuffix(".matte")]?.hasVisibleFrame != true) return@forEach
            val byteArray = entry.value.toByteArray()
            if (byteArray.count() < 4) {
                return@forEach
            }
            val fileTag = byteArray.slice(IntRange(0, 3))
            if (fileTag[0].toInt() == 73 && fileTag[1].toInt() == 68 && fileTag[2].toInt() == 51) {
                return@forEach
            }
            val maxScale = scaleMap[entry.key] ?: Pair(1f, 1f)
            var lastBitmap: Bitmap? = null
            synchronized(imageMap) {
                lastBitmap = imageMap[entry.key]
            }
            if (lastBitmap == null || lastBitmap.isRecycled) {
                createBitmap(
                    view,
                    byteArray,
                    entry.key,
                    maxScale.first,
                    maxScale.second
                )?.let { bitmap ->
                    acceptBitmap(entry.key, bitmap)
                }
            }
        }
    }

    private fun generateBitmapFilePath(imgName: String, imgKey: String): String {
        val path = mCacheDir.absolutePath + "/" + imgName
        val path1 = "$path.png"
        val path2 = mCacheDir.absolutePath + "/" + imgKey + ".png"

        return when {
            File(path).exists() -> path
            File(path1).exists() -> path1
            File(path2).exists() -> path2
            else -> ""
        }
    }

    /** Clear and publication share a lock, including results decoded before cancellation. */
    private fun acceptBitmap(key: String, bitmap: Bitmap) {
        synchronized(imageMap) {
            if (isClean) SVGAParser.getBitmapDecoder().onClean(bitmap)
            else imageMap.put(key, bitmap)?.takeIf { it !== bitmap }?.let {
                SVGAParser.getBitmapDecoder().onClean(it)
            }
        }
    }

    private fun createBitmap(
        imageView: View,
        filePath: String,
        scaleX: Float,
        scaleY: Float,
        imgKey: String,
    ): Bitmap? {
        if (SVGAParser.usesDefaultBitmapDecoder()) return decodeBaseImage(File(filePath), null, imgKey)
        return SVGAParser.getBitmapDecoder().onLoad(
            imageView,
            filePath,
            scaleX,
            scaleY,
            mFrameWidth,
            mFrameHeight,
            videoSize.width.toInt(),
            videoSize.height.toInt()
        )
    }

    private fun createBitmap(
        imageView: View,
        byteArray: ByteArray,
        imgKey: String,
        scaleX: Float,
        scaleY: Float,
    ): Bitmap? {
        val bitmap = if (SVGAParser.usesDefaultBitmapDecoder()) decodeBaseImage(null, byteArray, imgKey)
            else SVGAParser.getBitmapDecoder().onLoad(
                imageView,
                byteArray,
                scaleX,
                scaleY,
                mFrameWidth,
                mFrameHeight,
                videoSize.width.toInt(),
                videoSize.height.toInt()
            )
        if (bitmap != null) {
            return bitmap
        }
        val filePath = generateBitmapFilePath(String(byteArray, Charsets.UTF_8), imgKey)
        return createBitmap(imageView, filePath, scaleX, scaleY, imgKey)
    }

    private fun decodeBaseImage(file: File?, bytes: ByteArray?, key: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (file != null) BitmapFactory.decodeFile(file.path, bounds)
        else bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val viewportScale = if (mFrameWidth > 0 && mFrameHeight > 0)
            maxOf(mFrameWidth / videoSize.width, mFrameHeight / videoSize.height) else Double.NaN
        val target = SvgaImageSize.target(imageUsage[key.removeSuffix(".matte")], bounds.outWidth, bounds.outHeight, viewportScale)
        return SvgaImageDecoder.decode(file, bytes, bounds.outWidth, bounds.outHeight, target.first, target.second,
            checkNotNull(activeDecodeOptions))
    }

    private fun resetSprites(json: JSONObject) {
        val mutableList: MutableList<SVGAVideoSpriteEntity> = mutableListOf()
        json.optJSONArray("sprites")?.let { item ->
            for (i in 0 until item.length()) {
                item.optJSONObject(i)?.let { entryJson ->
                    val entity = SVGAVideoSpriteEntity(entryJson)
                    var matrixMaxScaleX = 1f
                    var matrixMaxScaleY = 1f
                    val f = FloatArray(9)
                    entity.frames.forEach { e ->
                        e.transform.getValues(f)
                        val scaleX = kotlin.math.hypot(f[Matrix.MSCALE_X], f[Matrix.MSKEW_Y])
                        val scaleY = kotlin.math.hypot(f[Matrix.MSKEW_X], f[Matrix.MSCALE_Y])
                        if (scaleX > matrixMaxScaleX) {
                            matrixMaxScaleX = scaleX
                        }
                        if (scaleY > matrixMaxScaleY) {
                            matrixMaxScaleY = scaleY
                        }
                    }
                    scaleMap[entity.imageKey ?: ""] = Pair(matrixMaxScaleX, matrixMaxScaleY)
                    mutableList.add(entity)
                }
            }
        }
        spriteList = mutableList.toList()
    }

    private fun resetSprites(entity: MovieEntity) {
        spriteList = entity.sprites?.map {
            var matrixMaxScaleX = 1f
            var matrixMaxScaleY = 1f
            val entity = SVGAVideoSpriteEntity(it)
            val f = FloatArray(9)
            entity.frames.forEach { e ->
                e.transform.getValues(f)
                val scaleX = kotlin.math.hypot(f[Matrix.MSCALE_X], f[Matrix.MSKEW_Y])
                val scaleY = kotlin.math.hypot(f[Matrix.MSKEW_X], f[Matrix.MSCALE_Y])
                if (scaleX > matrixMaxScaleX) {
                    matrixMaxScaleX = scaleX
                }
                if (scaleY > matrixMaxScaleY) {
                    matrixMaxScaleY = scaleY
                }
            }
            scaleMap[entity.imageKey ?: ""] = Pair(matrixMaxScaleX, matrixMaxScaleY)
            return@map entity
        } ?: listOf()
    }

    private fun setupAudios(entity: MovieEntity, completionBlock: () -> Unit) {
        if (entity.audios == null || entity.audios.isEmpty()) {
            completeAudioPrepare(completionBlock)
            return
        }
        val audiosFileMap = generateAudioFileMap(entity)
        //repair when audioEntity error can not callback
        //如果audiosFileMap为空 soundPool?.load 不会走 导致 setOnLoadCompleteListener 不会回调 导致外层prepare不回调卡住
        if (audiosFileMap.size == 0) {
            completeAudioPrepare(completionBlock)
            return
        }
        mPlayCallback?.let {
            it.onPlay(audiosFileMap.values.toList())
            completeAudioPrepare(completionBlock)
            return
        }
        setupSoundPool(entity, completionBlock)
        this.audioList = entity.audios.map { audio ->
            return@map createSvgaAudioEntity(audio, audiosFileMap)
        }
        if (expectedAudioLoadCount == 0) {
            completeAudioPrepare(completionBlock)
        }
    }

    private fun createSvgaAudioEntity(
        audio: AudioEntity,
        audiosFileMap: HashMap<String, File>,
    ): SVGAAudioEntity {
        val item = SVGAAudioEntity(audio)
        val startTime = (audio.startTime ?: 0).toDouble()
        val totalTime = (audio.totalTime ?: 0).toDouble()
        if (totalTime.toInt() == 0) {
            // 除数不能为 0
            return item
        }
        audiosFileMap[audio.audioKey]?.let { file ->
            FileInputStream(file).use {
                val length = it.available().toDouble()
                val offset = ((startTime / totalTime) * length).toLong()
                if (SVGASoundManager.isInit()) {
                    item.soundID = SVGASoundManager.load(
                        soundCallback,
                        it.fd,
                        offset,
                        length.toLong(),
                        1
                    )
                } else {
                    item.soundID = soundPool?.load(it.fd, offset, length.toLong(), 1)
                }
                if ((item.soundID ?: -1) > 0) {
                    expectedAudioLoadCount++
                }
            }
        }
        return item
    }

    private fun generateAudioFile(audioCache: File, value: ByteArray): File {
        audioCache.createNewFile()
        FileOutputStream(audioCache).use {
            it.write(value)
        }
        return audioCache
    }

    private fun generateAudioFileMap(entity: MovieEntity): HashMap<String, File> {
        val audiosDataMap = generateAudioMap(entity)
        val audiosFileMap = HashMap<String, File>()
        if (audiosDataMap.count() > 0) {
            audiosDataMap.forEach {
                val audioCache = SVGACache.buildAudioFile(it.key)
                audiosFileMap[it.key] =
                    audioCache.takeIf { file -> file.exists() } ?: generateAudioFile(
                        audioCache,
                        it.value
                    )
            }
        }
        return audiosFileMap
    }

    private fun generateAudioMap(entity: MovieEntity): HashMap<String, ByteArray> {
        val result = HashMap<String, ByteArray>()
        for ((key, bytes) in entity.images.orEmpty()) {
            if (bytes.size < 3) continue
            val id3 = bytes[0].toInt() == 73 && bytes[1].toInt() == 68 && bytes[2].toInt() == 51
            val mp3 = bytes[0].toInt() == -1 && bytes[1].toInt() == -5 && bytes[2].toInt() == -108
            if (id3 || mp3) result[key] = bytes.toByteArray()
        }
        return result
    }

    private fun setupSoundPool(entity: MovieEntity, completionBlock: () -> Unit) {
        var soundLoaded = 0
        if (SVGASoundManager.isInit()) {
            soundCallback = object : SVGASoundManager.SVGASoundCallBack {
                override fun onVolumeChange(value: Float) {
                    SVGASoundManager.setVolume(value, this@SVGAVideoEntity)
                }

                override fun onComplete() {
                    soundLoaded++
                    if (soundLoaded >= expectedAudioLoadCount) {
                        completeAudioPrepare(completionBlock)
                    }
                }
            }
            return
        }
        soundPool = generateSoundPool(entity)
        LogUtils.info("SVGAParser", "pool_start")
        soundPool?.setOnLoadCompleteListener { _, _, _ ->
            LogUtils.info("SVGAParser", "pool_complete")
            soundLoaded++
            if (soundLoaded >= expectedAudioLoadCount) {
                completeAudioPrepare(completionBlock)
            }
        }
    }

    private fun completeAudioPrepare(completionBlock: () -> Unit) {
        if (audioPrepareCompleted) {
            return
        }
        audioPrepareCompleted = true
        completionBlock()
    }

    private fun generateSoundPool(entity: MovieEntity): SoundPool? {
        return try {
            if (Build.VERSION.SDK_INT >= 21) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
                SoundPool.Builder().setAudioAttributes(attributes)
                    .setMaxStreams(12.coerceAtMost(entity.audios.count()))
                    .build()
            } else {
                SoundPool(12.coerceAtMost(entity.audios.count()), AudioManager.STREAM_MUSIC, 0)
            }
        } catch (e: Exception) {
            LogUtils.error(TAG, e)
            null
        }
    }

    fun imageMapSize(): Int {
        var total = 0
        synchronized(imageMap) {
            imageMap.map {
                total += it.value.width * it.value.height * 4
            }
        }
        return total
    }

    fun isRecycleImage(): Boolean {
        if (isClean) {
            return true
        }
        synchronized(imageMap) {
            for (mutableEntry in imageMap) {
                if (mutableEntry.value.isRecycled) {
                    return true
                }
            }
        }
        return false
    }

    fun clear() {
        synchronized(imageMap) { isClean = true }
        resource = null
        movieItem = null
        imageJson = null
        if (SVGASoundManager.isInit()) {
            this.audioList.forEach {
                it.soundID?.let { id -> SVGASoundManager.unload(id) }
            }
            soundCallback = null
        }
        soundPool?.release()
        soundPool = null
        audioList = emptyList()
        if (!sharesResources) spriteList.forEach { it.clear() }
        spriteList = emptyList()
        synchronized(imageMap) {
            if (!sharesResources) imageMap.forEach { SVGAParser.getBitmapDecoder().onClean(it.value) }
            imageMap.clear()
            imageUsage = emptyMap()
        }
        scaleMap.clear()
        isParser = false
    }
}

