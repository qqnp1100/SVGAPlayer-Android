package com.opensource.svgaplayer.drawer

import android.graphics.*
import android.os.Build
import android.text.StaticLayout
import android.text.TextUtils
import android.widget.ImageView
import com.opensource.svgaplayer.SVGADynamicEntity
import com.opensource.svgaplayer.SVGADynamicImage
import com.opensource.svgaplayer.SVGASoundManager
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.entities.SVGAVideoShapeEntity
import com.opensource.svgaplayer.utils.log.LogUtils

/**
 * Created by cuiminghui on 2017/3/29.
 */

internal class SVGACanvasDrawer(videoItem: SVGAVideoEntity, val dynamicItem: SVGADynamicEntity) :
    SGVADrawer(videoItem) {

    companion object {
        private const val DEFAULT_MAX_TEMP_BITMAP_SIZE = 32766
        private const val MAX_TEMP_BITMAP_BYTES = 64L * 1024L * 1024L
    }

    private val sharedValues = ShareValues()
    private val drawTextCache: HashMap<String, Bitmap> = hashMapOf()
    private val drawTextGradientCache: HashMap<String, Bitmap> = hashMapOf()
    private val scrollTextPosition: HashMap<String, Float> = hashMapOf()
    private val pathCache = PathCache()

    private var beginIndexList: Array<Boolean>? = null
    private var endIndexList: Array<Boolean>? = null
    private var mySoundId: Int? = null
    private var textCacheCanvasWidth: Int = 0
    private var textCacheCanvasHeight: Int = 0

    override fun drawFrame(canvas: Canvas, frameIndex: Int, scaleType: ImageView.ScaleType) {
        if (canvas.width <= 0 || canvas.height <= 0) {
            return
        }
        super.drawFrame(canvas, frameIndex, scaleType)
        playAudio(frameIndex)
        if (textCacheCanvasWidth != canvas.width || textCacheCanvasHeight != canvas.height) {
            clearTextBitmapCaches()
            textCacheCanvasWidth = canvas.width
            textCacheCanvasHeight = canvas.height
        }
        this.pathCache.onSizeChanged(canvas)
        val sprites = requestFrameSprites(frameIndex)
        // Filter null sprites
        if (sprites.count() <= 0) return
        val matteSprites = mutableMapOf<String, SVGADrawerSprite>()
        var saveID = -1
        beginIndexList = null
        endIndexList = null

        // Filter no matte layer
        var hasMatteLayer = false
        sprites.get(0).imageKey?.let {
            if (it.endsWith(".matte")) {
                hasMatteLayer = true
            }
        }
        sprites.forEachIndexed { index, svgaDrawerSprite ->

            // Save matte sprite
            svgaDrawerSprite.imageKey?.let {
                /// No matte layer included or VERSION Unsopport matte
                if (!hasMatteLayer || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    // Normal sprite
                    drawSprite(svgaDrawerSprite, canvas, frameIndex)
                    // Continue
                    return@forEachIndexed
                }
                /// Cache matte sprite
                if (it.endsWith(".matte")) {
                    matteSprites.put(it, svgaDrawerSprite)
                    // Continue
                    return@forEachIndexed
                }
            }
            /// Is matte begin
            if (isMatteBegin(index, sprites)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    saveID = canvas.saveLayer(
                        0f,
                        0f,
                        canvas.width.toFloat(),
                        canvas.height.toFloat(),
                        null
                    )
                } else {
                    canvas.save()
                }
            }
            /// Normal matte
            drawSprite(svgaDrawerSprite, canvas, frameIndex)

            /// Is matte end
            if (isMatteEnd(index, sprites)) {
                matteSprites.get(svgaDrawerSprite.matteKey)?.let {
                    val matteCanvas = this.sharedValues.shareMatteCanvas(canvas.width, canvas.height)
                    val matteBitmap = this.sharedValues.sharedMatteBitmap()
                    if (matteCanvas != null && matteBitmap != null) {
                        drawSprite(it, matteCanvas, frameIndex)
                        canvas.drawBitmap(
                            matteBitmap,
                            0f,
                            0f,
                            this.sharedValues.shareMattePaint()
                        )
                    }
                    if (saveID != -1) {
                        canvas.restoreToCount(saveID)
                    } else {
                        canvas.restore()
                    }
                    // Continue
                    return@forEachIndexed
                }
            }
        }
        releaseFrameSprites(sprites)
    }

    private fun isMatteBegin(spriteIndex: Int, sprites: List<SVGADrawerSprite>): Boolean {
        if (beginIndexList == null) {
            val boolArray = Array(sprites.count()) { false }
            sprites.forEachIndexed { index, svgaDrawerSprite ->
                svgaDrawerSprite.imageKey?.let {
                    /// Filter matte sprite
                    if (it.endsWith(".matte")) {
                        // Continue
                        return@forEachIndexed
                    }
                }
                svgaDrawerSprite.matteKey?.let {
                    if (it.length > 0) {
                        sprites.get(index - 1)?.let { lastSprite ->
                            if (lastSprite.matteKey.isNullOrEmpty()) {
                                boolArray[index] = true
                            } else {
                                if (lastSprite.matteKey != svgaDrawerSprite.matteKey) {
                                    boolArray[index] = true
                                }
                            }
                        }
                    }
                }
            }
            beginIndexList = boolArray
        }
        return beginIndexList?.get(spriteIndex) ?: false
    }

    private fun isMatteEnd(spriteIndex: Int, sprites: List<SVGADrawerSprite>): Boolean {
        if (endIndexList == null) {
            val boolArray = Array(sprites.count()) { false }
            sprites.forEachIndexed { index, svgaDrawerSprite ->
                svgaDrawerSprite.imageKey?.let {
                    /// Filter matte sprite
                    if (it.endsWith(".matte")) {
                        // Continue
                        return@forEachIndexed
                    }
                }
                svgaDrawerSprite.matteKey?.let {
                    if (it.length > 0) {
                        // Last one
                        if (index == sprites.count() - 1) {
                            boolArray[index] = true
                        } else {
                            sprites.get(index + 1)?.let { nextSprite ->
                                if (nextSprite.matteKey.isNullOrEmpty()) {
                                    boolArray[index] = true
                                } else {
                                    if (nextSprite.matteKey != svgaDrawerSprite.matteKey) {
                                        boolArray[index] = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            endIndexList = boolArray
        }
        return endIndexList?.get(spriteIndex) ?: false
    }

    private fun playAudio(frameIndex: Int) {
        this.videoItem.audioList.forEach { audio ->
            if (audio.startFrame == frameIndex) {
                if (SVGASoundManager.isInit()) {
                    audio.soundID?.let { soundID ->
                        audio.playIDs?.add(
                            SVGASoundManager.play(soundID).apply { mySoundId = this })
                    }
                } else {
                    this.videoItem.soundPool?.let { soundPool ->
                        audio.soundID?.let { soundID ->
                            audio.playIDs?.add(
                                soundPool.play(soundID, 1.0f, 1.0f, 1, 0, 1.0f)
                                    .apply { mySoundId = this })
                        }
                    }
                }

            }
            if (audio.endFrame <= frameIndex) {
                mySoundId?.let {
                    if (SVGASoundManager.isInit()) {
                        SVGASoundManager.stop(it)
                    } else {
                        this.videoItem.soundPool?.stop(it)
                    }
                    audio.playIDs?.remove(mySoundId)
                }
                audio.playIDs = null
            }
        }
    }

    private fun shareFrameMatrix(transform: Matrix): Matrix {
        val matrix = this.sharedValues.sharedMatrix()
        matrix.postScale(scaleInfo.scaleFx, scaleInfo.scaleFy)
        matrix.postTranslate(scaleInfo.tranFx, scaleInfo.tranFy)
        matrix.preConcat(transform)
        return matrix
    }

    private fun safeScrollTextBitmapWidth(
        canvas: Canvas,
        drawingBitmapHeight: Int,
        frameMatrix: Matrix,
        textWidth: Float
    ): Int {
        if (canvas.width <= 0 || drawingBitmapHeight <= 0) {
            return 0
        }
        val matrixValues = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        frameMatrix.getValues(matrixValues)
        val requestedWidth = textWidth.toDouble() +
            dynamicItem.srcollTextSpace.toDouble() *
            videoItem.videoSize.width.toDouble() *
            mappedXScale(matrixValues).toDouble() /
            canvas.width.toDouble()
        if (!java.lang.Double.isFinite(requestedWidth) || requestedWidth <= 0.0) {
            return 0
        }
        val requestedInt = requestedWidth
            .coerceAtMost(Int.MAX_VALUE.toDouble())
            .toInt()
        return safeTempBitmapWidth(
            requestedInt,
            drawingBitmapHeight,
            Bitmap.Config.ARGB_8888,
            canvas,
            "scroll text"
        )
    }

    private fun createTempBitmap(
        requestedWidth: Int,
        requestedHeight: Int,
        config: Bitmap.Config,
        canvas: Canvas,
        usage: String
    ): Bitmap? {
        val safeWidth = safeTempBitmapWidth(requestedWidth, requestedHeight, config, canvas, usage)
        if (safeWidth <= 0) {
            return null
        }
        return try {
            Bitmap.createBitmap(safeWidth, requestedHeight, config)
        } catch (error: OutOfMemoryError) {
            LogUtils.error("SVGACanvasDrawer", error)
            null
        } catch (error: IllegalArgumentException) {
            LogUtils.error("SVGACanvasDrawer", error)
            null
        }
    }

    private fun clearTextBitmapCaches() {
        this.drawTextCache.clear()
        this.drawTextGradientCache.clear()
        this.scrollTextPosition.clear()
    }

    private fun mappedXScale(matrixValues: FloatArray): Float {
        val scaleX = matrixValues[Matrix.MSCALE_X].toDouble()
        val skewY = matrixValues[Matrix.MSKEW_Y].toDouble()
        val scale = Math.sqrt(scaleX * scaleX + skewY * skewY).toFloat()
        return if (java.lang.Float.isFinite(scale) && scale > 0f) scale else 1f
    }

    private fun safeTempBitmapWidth(
        requestedWidth: Int,
        requestedHeight: Int,
        config: Bitmap.Config,
        canvas: Canvas,
        usage: String
    ): Int {
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            LogUtils.warn(msg = "Skip $usage bitmap, invalid size ${requestedWidth}x${requestedHeight}.")
            return 0
        }
        val maxCanvasWidth = canvas.maximumBitmapWidth
            .takeIf { it > 0 }
            ?: DEFAULT_MAX_TEMP_BITMAP_SIZE
        val maxCanvasHeight = canvas.maximumBitmapHeight
            .takeIf { it > 0 }
            ?: DEFAULT_MAX_TEMP_BITMAP_SIZE
        if (requestedHeight > maxCanvasHeight) {
            LogUtils.warn(msg = "Skip $usage bitmap, height $requestedHeight exceeds canvas limit $maxCanvasHeight.")
            return 0
        }
        val byteLimitedWidth = (
            MAX_TEMP_BITMAP_BYTES /
                (requestedHeight.toLong() * bytesPerPixel(config).toLong())
            )
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val safeWidth = minOf(requestedWidth, maxCanvasWidth, byteLimitedWidth)
        if (safeWidth < requestedWidth) {
            LogUtils.warn(msg = "Clamp $usage bitmap width from $requestedWidth to $safeWidth.")
        }
        return safeWidth
    }

    private fun bytesPerPixel(config: Bitmap.Config): Int {
        return when (config) {
            Bitmap.Config.ALPHA_8 -> 1
            Bitmap.Config.RGB_565,
            Bitmap.Config.ARGB_4444 -> 2
            else -> 4
        }
    }

    private fun drawSprite(sprite: SVGADrawerSprite, canvas: Canvas, frameIndex: Int) {
        drawImage(sprite, canvas, frameIndex)
        drawShape(sprite, canvas)
        drawDynamic(sprite, canvas, frameIndex)
    }

    private fun drawImage(sprite: SVGADrawerSprite, canvas: Canvas, frameIndex: Int) {
        val imageKey = sprite.imageKey ?: return
        val isHidden = dynamicItem.dynamicHidden[imageKey] == true
        if (isHidden) {
            return
        }
        val bitmapKey = if (imageKey.endsWith(".matte")) imageKey.substring(
            0,
            imageKey.length - 6
        ) else imageKey
        val dynamicAnimatedImage = dynamicItem.getDynamicAnimatedImage(bitmapKey)
        val drawingBitmap = dynamicItem.getDynamicImage(bitmapKey) ?: videoItem.imageMap[bitmapKey]
        if (dynamicAnimatedImage == null && drawingBitmap == null) {
            return
        }
        val imageWidth = dynamicAnimatedImage?.width ?: drawingBitmap?.width ?: return
        val imageHeight = dynamicAnimatedImage?.height ?: drawingBitmap?.height ?: return
        if (imageWidth <= 0 || imageHeight <= 0) {
            return
        }
        val layoutWidth = sprite.frameEntity.layout.width
        val layoutHeight = sprite.frameEntity.layout.height
        val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
        val paint = this.sharedValues.sharedPaint()
        paint.isAntiAlias = videoItem.antiAlias
        paint.isFilterBitmap = videoItem.antiAlias
        paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
        if (dynamicAnimatedImage != null) {
            drawAnimatedImage(
                dynamicAnimatedImage,
                canvas,
                sprite,
                frameMatrix,
                paint,
                frameIndex
            )
            dynamicItem.dynamicIClickArea.let {
                it.get(imageKey)?.let { listener ->
                    val matrixArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                    frameMatrix.getValues(matrixArray)
                    listener.onResponseArea(
                        imageKey,
                        matrixArray[2].toInt(),
                        matrixArray[5].toInt(),
                        (layoutWidth * matrixArray[0] + matrixArray[2]).toInt(),
                        (layoutHeight * matrixArray[4] + matrixArray[5]).toInt()
                    )
                }
            }
            drawTextOnBitmap(canvas, layoutWidth.toInt(), layoutHeight.toInt(), sprite, frameMatrix)
            return
        }
        if (sprite.frameEntity.maskPath != null) {
            val maskPath = sprite.frameEntity.maskPath ?: return
            canvas.save()
            val path = this.sharedValues.sharedPath()
            maskPath.buildPath(path)
            path.transform(frameMatrix)
            canvas.clipPath(path)
            frameMatrix.preScale(
                (layoutWidth / imageWidth).toFloat(),
                (layoutHeight / imageHeight).toFloat()
            )
            if (drawingBitmap != null && !drawingBitmap.isRecycled) {
                canvas.drawBitmap(drawingBitmap, frameMatrix, paint)
            }
            canvas.restore()
        } else {
            frameMatrix.preScale(
                (layoutWidth / imageWidth).toFloat(),
                (layoutHeight / imageHeight).toFloat()
            )
            if (drawingBitmap != null && !drawingBitmap.isRecycled) {
                canvas.drawBitmap(drawingBitmap, frameMatrix, paint)
            }
        }
        dynamicItem.dynamicIClickArea.let {
            it.get(imageKey)?.let { listener ->
                val matrixArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                frameMatrix.getValues(matrixArray)
                listener.onResponseArea(
                    imageKey,
                    matrixArray[2].toInt(),
                    matrixArray[5].toInt(),
                    (imageWidth * matrixArray[0] + matrixArray[2]).toInt(),
                    (imageHeight * matrixArray[4] + matrixArray[5]).toInt()
                )
            }
        }
        drawTextOnBitmap(canvas, imageWidth, imageHeight, sprite, frameMatrix)
    }

    private fun drawAnimatedImage(
        dynamicAnimatedImage: SVGADynamicImage,
        canvas: Canvas,
        sprite: SVGADrawerSprite,
        frameMatrix: Matrix,
        paint: Paint,
        frameIndex: Int
    ) {
        val layoutWidth = sprite.frameEntity.layout.width.toFloat()
        val layoutHeight = sprite.frameEntity.layout.height.toFloat()
        if (layoutWidth <= 0f || layoutHeight <= 0f) {
            return
        }
        val scale = maxOf(
            layoutWidth / dynamicAnimatedImage.width.toFloat(),
            layoutHeight / dynamicAnimatedImage.height.toFloat()
        )
        val offsetX = (layoutWidth - dynamicAnimatedImage.width * scale) / 2f
        val offsetY = (layoutHeight - dynamicAnimatedImage.height * scale) / 2f
        val imageMatrix = this.sharedValues.sharedMatrix2()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(offsetX, offsetY)

        val saveCount = canvas.save()
        canvas.concat(frameMatrix)
        canvas.clipRect(0f, 0f, layoutWidth, layoutHeight)
        sprite.frameEntity.maskPath?.let { maskPath ->
            val path = this.sharedValues.sharedPath()
            maskPath.buildPath(path)
            canvas.clipPath(path)
        }
        dynamicAnimatedImage.draw(canvas, imageMatrix, paint, frameIndex, videoItem.FPS)
        canvas.restoreToCount(saveCount)
    }

    private fun drawTextOnBitmap(
        canvas: Canvas,
        drawingBitmapWidth: Int,
        drawingBitmapHeight: Int,
        sprite: SVGADrawerSprite,
        frameMatrix: Matrix
    ) {
        if (dynamicItem.isTextDirty) {
            clearTextBitmapCaches()
            dynamicItem.isTextDirty = false
        }
        val imageKey = sprite.imageKey ?: return
        var textBitmap: Bitmap? = null
        val scrollSpeed = dynamicItem.dynamicScrollTextSpeed[imageKey] ?: -1f
        dynamicItem.dynamicText[imageKey]?.let { drawingText ->
            dynamicItem.dynamicTextPaint[imageKey]?.let { drawingTextPaint ->
                drawTextCache[imageKey]?.let {
                    textBitmap = it
                } ?: kotlin.run {
                    var bitmapWidth = drawingBitmapWidth
                    var textDrawStart = drawingBitmapWidth / 2f
                    val originalTextAlign = drawingTextPaint.textAlign
                    var restoreTextAlign = false
                    if (scrollSpeed > 0) {
                        val textWidth = drawingTextPaint.measureText(drawingText)
                        if (textWidth > drawingBitmapWidth) {
                            bitmapWidth = safeScrollTextBitmapWidth(
                                canvas,
                                drawingBitmapHeight,
                                frameMatrix,
                                textWidth
                            )
                            textDrawStart = 0f
                            drawingTextPaint.textAlign = Paint.Align.LEFT
                            restoreTextAlign = true
                        }
                    }
                    if (bitmapWidth > 0 && drawingBitmapHeight > 0) {
                        textBitmap = createTempBitmap(
                            bitmapWidth,
                            drawingBitmapHeight,
                            Bitmap.Config.ARGB_8888,
                            canvas,
                            "dynamic text"
                        )?.apply {
                            val drawRect = Rect(0, 0, width, height)
                            val textCanvas = Canvas(this)
                            drawingTextPaint.isAntiAlias = true
                            val fontMetrics = drawingTextPaint.getFontMetrics();
                            val top = fontMetrics.top
                            val bottom = fontMetrics.bottom
                            val baseLineY = drawRect.centerY() - top / 2 - bottom / 2
                            textCanvas.drawText(
                                drawingText,
                                textDrawStart,
                                baseLineY,
                                drawingTextPaint
                            );
                            drawTextCache.put(imageKey, this)
                        }
                    }
                    if (restoreTextAlign) {
                        drawingTextPaint.textAlign = originalTextAlign
                    }
                }
            }
        }

        dynamicItem.dynamicBoringLayoutText[imageKey]?.let {
            drawTextCache[imageKey]?.let {
                textBitmap = it
            } ?: kotlin.run {
                it.paint.isAntiAlias = true
                var bitmapWidth = drawingBitmapWidth
                val originalTextAlign = it.paint.textAlign
                var restoreTextAlign = false
                if (scrollSpeed > 0) {
                    val textWidth = it.paint.measureText(it.text, 0, it.text.length)
                    if (textWidth > drawingBitmapWidth) {
                        bitmapWidth = safeScrollTextBitmapWidth(
                            canvas,
                            drawingBitmapHeight,
                            frameMatrix,
                            textWidth
                        )
                        it.paint.textAlign = Paint.Align.LEFT
                        restoreTextAlign = true
                    }
                }
                if (bitmapWidth > 0 && drawingBitmapHeight > 0) {
                    textBitmap = createTempBitmap(
                        bitmapWidth,
                        drawingBitmapHeight,
                        Bitmap.Config.ARGB_8888,
                        canvas,
                        "boring layout text"
                    )?.apply {
                        val textCanvas = Canvas(this)
                        textCanvas.translate(0f, ((drawingBitmapHeight - it.height) / 2).toFloat())
                        it.draw(textCanvas)
                        drawTextCache.put(imageKey, this)
                    }
                }
                if (restoreTextAlign) {
                    it.paint.textAlign = originalTextAlign
                }
            }
        }

        dynamicItem.dynamicStaticLayoutText[imageKey]?.let {
            drawTextCache[imageKey]?.let {
                textBitmap = it
            } ?: kotlin.run {
                it.paint.isAntiAlias = true
                var bitmapWidth = drawingBitmapWidth
                val originalTextAlign = it.paint.textAlign
                var restoreTextAlign = false
                if (scrollSpeed > 0) {
                    val textWidth = it.paint.measureText(it.text, 0, it.text.length)
                    if (textWidth > drawingBitmapWidth) {
                        bitmapWidth = safeScrollTextBitmapWidth(
                            canvas,
                            drawingBitmapHeight,
                            frameMatrix,
                            textWidth
                        )
                        it.paint.textAlign = Paint.Align.LEFT
                        restoreTextAlign = true
                    }
                }
                bitmapWidth = safeTempBitmapWidth(
                    bitmapWidth,
                    drawingBitmapHeight,
                    Bitmap.Config.ARGB_8888,
                    canvas,
                    "static layout text"
                )
                if (bitmapWidth <= 0) {
                    if (restoreTextAlign) {
                        it.paint.textAlign = originalTextAlign
                    }
                    return@run
                }
                val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val lineMax = try {
                        val field =
                            StaticLayout::class.java.getDeclaredField("mMaximumVisibleLineCount")
                        field.isAccessible = true
                        field.getInt(it)
                    } catch (e: Exception) {
                        Int.MAX_VALUE
                    }
                    StaticLayout.Builder
                        .obtain(it.text, 0, it.text.length, it.paint, bitmapWidth)
                        .setAlignment(it.alignment)
                        .setMaxLines(lineMax)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .build()
                } else {
                    StaticLayout(
                        it.text,
                        0,
                        it.text.length,
                        it.paint,
                        bitmapWidth,
                        it.alignment,
                        it.spacingMultiplier,
                        it.spacingAdd,
                        false
                    )
                }
                if (bitmapWidth > 0 && drawingBitmapHeight > 0) {
                    textBitmap = createTempBitmap(
                        bitmapWidth,
                        drawingBitmapHeight,
                        Bitmap.Config.ARGB_8888,
                        canvas,
                        "static layout text"
                    )?.apply {
                        val textCanvas = Canvas(this)
                        textCanvas.translate(0f, ((drawingBitmapHeight - layout.height) / 2).toFloat())
                        layout.draw(textCanvas)
                        drawTextCache.put(imageKey, this)
                    }
                }
                if (restoreTextAlign) {
                    it.paint.textAlign = originalTextAlign
                }
            }
        }
        textBitmap?.let { textBitmap ->
            val paint = this.sharedValues.sharedPaint()
            paint.isAntiAlias = videoItem.antiAlias
            paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
            paint.isFilterBitmap = videoItem.antiAlias
            if (drawingBitmapWidth <= 0 || drawingBitmapHeight <= 0) return@let
            val saveCount = canvas.save()
            canvas.concat(frameMatrix)
            canvas.clipRect(0f, 0f, drawingBitmapWidth.toFloat(), drawingBitmapHeight.toFloat())
            sprite.frameEntity.maskPath?.let { maskPath ->
                val path = this.sharedValues.sharedPath()
                maskPath.buildPath(path)
                canvas.clipPath(path)
            }
            if (textBitmap.width > drawingBitmapWidth && scrollSpeed > 0f) {
                drawScrollingTextBitmap(
                    canvas,
                    imageKey,
                    textBitmap,
                    drawingBitmapWidth,
                    drawingBitmapHeight,
                    frameMatrix,
                    paint
                )
            } else {
                canvas.drawBitmap(textBitmap, 0f, 0f, paint)
            }
            canvas.restoreToCount(saveCount)
        }
    }

    private fun drawScrollingTextBitmap(
        canvas: Canvas,
        imageKey: String,
        textBitmap: Bitmap,
        drawingBitmapWidth: Int,
        drawingBitmapHeight: Int,
        frameMatrix: Matrix,
        paint: Paint
    ) {
        val offset = nextScrollTextOffset(
            canvas,
            imageKey,
            textBitmap.width.toFloat(),
            frameMatrix
        )
        val saveLayer = canvas.saveLayer(
            0f,
            0f,
            drawingBitmapWidth.toFloat(),
            drawingBitmapHeight.toFloat(),
            null
        )
        var drawX = -offset
        while (drawX < drawingBitmapWidth) {
            canvas.drawBitmap(textBitmap, drawX, 0f, paint)
            drawX += textBitmap.width
        }
        val gradientBitmap = scrollTextGradientBitmap(
            canvas,
            imageKey,
            drawingBitmapWidth,
            drawingBitmapHeight
        )
        if (gradientBitmap != null) {
            val maskPaint = this.sharedValues.sharedPaint2()
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            canvas.drawBitmap(gradientBitmap, 0f, 0f, maskPaint)
        }
        canvas.restoreToCount(saveLayer)
    }

    private fun nextScrollTextOffset(
        canvas: Canvas,
        imageKey: String,
        cycleWidth: Float,
        frameMatrix: Matrix
    ): Float {
        if (cycleWidth <= 0f) {
            return 0f
        }
        val matrixValues = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        frameMatrix.getValues(matrixValues)
        val speed = (
            (dynamicItem.dynamicScrollTextSpeed[imageKey] ?: 10f) *
                videoItem.videoSize.width.toFloat() *
                mappedXScale(matrixValues) /
                canvas.width.toFloat() /
                videoItem.FPS.coerceAtLeast(1)
            )
        if (!java.lang.Float.isFinite(speed) || speed <= 0f) {
            return scrollTextPosition[imageKey] ?: 0f
        }
        var offset = (scrollTextPosition[imageKey] ?: 0f) + speed
        offset %= cycleWidth
        if (offset < 0f) {
            offset += cycleWidth
        }
        scrollTextPosition[imageKey] = offset
        return offset
    }

    private fun scrollTextGradientBitmap(
        canvas: Canvas,
        imageKey: String,
        drawingBitmapWidth: Int,
        drawingBitmapHeight: Int
    ): Bitmap? {
        val cacheKey = "$imageKey:$drawingBitmapWidth:$drawingBitmapHeight"
        return drawTextGradientCache[cacheKey] ?: kotlin.run {
            createTempBitmap(
                drawingBitmapWidth,
                drawingBitmapHeight,
                Bitmap.Config.ARGB_8888,
                canvas,
                "text gradient"
            )?.apply {
                val fadeRatio = (24f / drawingBitmapWidth.toFloat()).coerceIn(0.08f, 0.18f)
                val maskPaint = Paint()
                maskPaint.shader = LinearGradient(
                    0f,
                    0f,
                    drawingBitmapWidth.toFloat(),
                    0f,
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.BLACK,
                        Color.BLACK,
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0.0f, fadeRatio, 1f - fadeRatio, 1f),
                    Shader.TileMode.CLAMP
                )
                val gradientCanvas = Canvas(this)
                gradientCanvas.drawPaint(maskPaint)
                drawTextGradientCache[cacheKey] = this
            }
        }
    }

    private fun drawShape(sprite: SVGADrawerSprite, canvas: Canvas) {
        val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
        sprite.frameEntity.shapes.forEach { shape ->
            shape.buildPath()
            shape.shapePath?.let {
                val paint = this.sharedValues.sharedPaint()
                paint.reset()
                paint.isAntiAlias = videoItem.antiAlias
                paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
                val path = this.sharedValues.sharedPath()
                path.reset()
                path.addPath(this.pathCache.buildPath(shape))
                val shapeMatrix = this.sharedValues.sharedMatrix2()
                shapeMatrix.reset()
                shape.transform?.let {
                    shapeMatrix.postConcat(it)
                }
                shapeMatrix.postConcat(frameMatrix)
                path.transform(shapeMatrix)
                shape.styles?.fill?.let {
                    if (it != 0x00000000) {
                        paint.style = Paint.Style.FILL
                        paint.color = it
                        val alpha =
                            Math.min(255, Math.max(0, (sprite.frameEntity.alpha * 255).toInt()))
                        if (alpha != 255) {
                            paint.alpha = alpha
                        }
                        if (sprite.frameEntity.maskPath !== null) canvas.save()
                        sprite.frameEntity.maskPath?.let { maskPath ->
                            val path2 = this.sharedValues.sharedPath2()
                            maskPath.buildPath(path2)
                            path2.transform(frameMatrix)
                            canvas.clipPath(path2)
                        }
                        canvas.drawPath(path, paint)
                        if (sprite.frameEntity.maskPath !== null) canvas.restore()
                    }
                }
                shape.styles?.strokeWidth?.let {
                    if (it > 0) {
                        paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
                        paint.style = Paint.Style.STROKE
                        shape.styles?.stroke?.let {
                            paint.color = it
                            val alpha =
                                Math.min(255, Math.max(0, (sprite.frameEntity.alpha * 255).toInt()))
                            if (alpha != 255) {
                                paint.alpha = alpha
                            }
                        }
                        val scale = matrixScale(frameMatrix)
                        shape.styles?.strokeWidth?.let {
                            paint.strokeWidth = it * scale
                        }
                        shape.styles?.lineCap?.let {
                            when {
                                it.equals("butt", true) -> paint.strokeCap = Paint.Cap.BUTT
                                it.equals("round", true) -> paint.strokeCap = Paint.Cap.ROUND
                                it.equals("square", true) -> paint.strokeCap = Paint.Cap.SQUARE
                            }
                        }
                        shape.styles?.lineJoin?.let {
                            when {
                                it.equals("miter", true) -> paint.strokeJoin = Paint.Join.MITER
                                it.equals("round", true) -> paint.strokeJoin = Paint.Join.ROUND
                                it.equals("bevel", true) -> paint.strokeJoin = Paint.Join.BEVEL
                            }
                        }
                        shape.styles?.miterLimit?.let {
                            paint.strokeMiter = it.toFloat() * scale
                        }
                        shape.styles?.lineDash?.let {
                            if (it.size == 3 && (it[0] > 0 || it[1] > 0)) {
                                paint.pathEffect = DashPathEffect(
                                    floatArrayOf(
                                        (if (it[0] < 1.0f) 1.0f else it[0]) * scale,
                                        (if (it[1] < 0.1f) 0.1f else it[1]) * scale
                                    ), it[2] * scale
                                )
                            }
                        }
                        if (sprite.frameEntity.maskPath !== null) canvas.save()
                        sprite.frameEntity.maskPath?.let { maskPath ->
                            val path2 = this.sharedValues.sharedPath2()
                            maskPath.buildPath(path2)
                            path2.transform(frameMatrix)
                            canvas.clipPath(path2)
                        }
                        canvas.drawPath(path, paint)
                        if (sprite.frameEntity.maskPath !== null) canvas.restore()
                    }
                }
            }

        }
    }

    private val matrixScaleTempValues = FloatArray(16)

    private fun matrixScale(matrix: Matrix): Float {
        matrix.getValues(matrixScaleTempValues)
        if (matrixScaleTempValues[0] == 0f) {
            return 0f
        }
        var A = matrixScaleTempValues[0].toDouble()
        var B = matrixScaleTempValues[3].toDouble()
        var C = matrixScaleTempValues[1].toDouble()
        var D = matrixScaleTempValues[4].toDouble()
        if (A * D == B * C) return 0f
        var scaleX = Math.sqrt(A * A + B * B)
        A /= scaleX
        B /= scaleX
        var skew = A * C + B * D
        C -= A * skew
        D -= B * skew
        var scaleY = Math.sqrt(C * C + D * D)
        C /= scaleY
        D /= scaleY
        skew /= scaleY
        if (A * D < B * C) {
            scaleX = -scaleX
        }
        return if (scaleInfo.ratioX) Math.abs(scaleX.toFloat()) else Math.abs(scaleY.toFloat())
    }

    private fun drawDynamic(sprite: SVGADrawerSprite, canvas: Canvas, frameIndex: Int) {
        val imageKey = sprite.imageKey ?: return
        dynamicItem.dynamicDrawer[imageKey]?.let {
            val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
            val saveCount = canvas.save()
            try {
                canvas.concat(frameMatrix)
                it.invoke(canvas, frameIndex)
            } catch (error: OutOfMemoryError) {
                LogUtils.error("SVGACanvasDrawer", error)
            } catch (error: Exception) {
                LogUtils.error("SVGACanvasDrawer", error)
            } finally {
                canvas.restoreToCount(saveCount)
            }
        }
        dynamicItem.dynamicDrawerSized[imageKey]?.let {
            val layoutWidth = sprite.frameEntity.layout.width
            val layoutHeight = sprite.frameEntity.layout.height
            if (!isSafeDynamicDrawerSize(layoutWidth, layoutHeight, canvas, imageKey)) {
                return@let
            }
            val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
            val saveCount = canvas.save()
            try {
                canvas.concat(frameMatrix)
                it.invoke(
                    canvas,
                    frameIndex,
                    layoutWidth.toInt(),
                    layoutHeight.toInt()
                )
            } catch (error: OutOfMemoryError) {
                LogUtils.error("SVGACanvasDrawer", error)
            } catch (error: Exception) {
                LogUtils.error("SVGACanvasDrawer", error)
            } finally {
                canvas.restoreToCount(saveCount)
            }
        }
    }

    private fun isSafeDynamicDrawerSize(
        width: Double,
        height: Double,
        canvas: Canvas,
        imageKey: String
    ): Boolean {
        if (!java.lang.Double.isFinite(width) || !java.lang.Double.isFinite(height)) {
            LogUtils.warn(msg = "Skip dynamic drawer $imageKey, non-finite size ${width}x${height}.")
            return false
        }
        val intWidth = width.toInt()
        val intHeight = height.toInt()
        if (intWidth <= 0 || intHeight <= 0) {
            LogUtils.warn(msg = "Skip dynamic drawer $imageKey, invalid size ${intWidth}x${intHeight}.")
            return false
        }
        val maxCanvasWidth = canvas.maximumBitmapWidth
            .takeIf { it > 0 }
            ?: DEFAULT_MAX_TEMP_BITMAP_SIZE
        val maxCanvasHeight = canvas.maximumBitmapHeight
            .takeIf { it > 0 }
            ?: DEFAULT_MAX_TEMP_BITMAP_SIZE
        if (intWidth > maxCanvasWidth || intHeight > maxCanvasHeight) {
            LogUtils.warn(
                msg = "Skip dynamic drawer $imageKey, size ${intWidth}x${intHeight} exceeds canvas limit ${maxCanvasWidth}x${maxCanvasHeight}."
            )
            return false
        }
        if (intWidth.toLong() * intHeight.toLong() * bytesPerPixel(Bitmap.Config.ARGB_8888).toLong() > MAX_TEMP_BITMAP_BYTES) {
            LogUtils.warn(msg = "Skip dynamic drawer $imageKey, size ${intWidth}x${intHeight} exceeds memory budget.")
            return false
        }
        return true
    }

    class ShareValues {

        private val sharedPaint = Paint()
        private val sharedPaint2 = Paint()
        private val sharedPath = Path()
        private val sharedPath2 = Path()
        private val sharedMatrix = Matrix()
        private val sharedMatrix2 = Matrix()

        private val shareMattePaint = Paint()
        private var shareMatteCanvas: Canvas? = null
        private var sharedMatteBitmap: Bitmap? = null

        fun sharedPaint(): Paint {
            sharedPaint.reset()
            return sharedPaint
        }

        fun sharedPaint2(): Paint {
            sharedPaint2.reset()
            return sharedPaint2
        }

        fun sharedPath(): Path {
            sharedPath.reset()
            return sharedPath
        }

        fun sharedPath2(): Path {
            sharedPath2.reset()
            return sharedPath2
        }

        fun sharedMatrix(): Matrix {
            sharedMatrix.reset()
            return sharedMatrix
        }

        fun sharedMatrix2(): Matrix {
            sharedMatrix2.reset()
            return sharedMatrix2
        }

        fun shareMattePaint(): Paint {
            shareMattePaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.DST_IN))
            return shareMattePaint
        }

        fun sharedMatteBitmap(): Bitmap? {
            return sharedMatteBitmap
        }

        fun shareMatteCanvas(width: Int, height: Int): Canvas? {
            if (width <= 0 || height <= 0) {
                LogUtils.warn(msg = "Skip matte bitmap, invalid size ${width}x${height}.")
                return null
            }
            if (width > DEFAULT_MAX_TEMP_BITMAP_SIZE || height > DEFAULT_MAX_TEMP_BITMAP_SIZE) {
                LogUtils.warn(msg = "Skip matte bitmap, size ${width}x${height} exceeds limit.")
                return null
            }
            if (width.toLong() * height.toLong() > MAX_TEMP_BITMAP_BYTES) {
                LogUtils.warn(msg = "Skip matte bitmap, size ${width}x${height} exceeds memory budget.")
                return null
            }
            val bitmap = sharedMatteBitmap
            if (
                bitmap == null ||
                bitmap.isRecycled ||
                bitmap.width != width ||
                bitmap.height != height
            ) {
                try {
                    sharedMatteBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                    shareMatteCanvas = Canvas(sharedMatteBitmap!!)
                } catch (error: OutOfMemoryError) {
                    LogUtils.error("SVGACanvasDrawer", error)
                    sharedMatteBitmap = null
                    shareMatteCanvas = null
                    return null
                } catch (error: IllegalArgumentException) {
                    LogUtils.error("SVGACanvasDrawer", error)
                    sharedMatteBitmap = null
                    shareMatteCanvas = null
                    return null
                }
            }
            shareMatteCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            return shareMatteCanvas
        }
    }

    class PathCache {

        private var canvasWidth: Int = 0
        private var canvasHeight: Int = 0
        private val cache = HashMap<SVGAVideoShapeEntity, Path>()

        fun onSizeChanged(canvas: Canvas) {
            if (this.canvasWidth != canvas.width || this.canvasHeight != canvas.height) {
                this.cache.clear()
            }
            this.canvasWidth = canvas.width
            this.canvasHeight = canvas.height
        }

        fun buildPath(shape: SVGAVideoShapeEntity): Path {
            if (!this.cache.containsKey(shape) && shape.shapePath != null) {
                val path = Path()
                path.set(shape.shapePath!!)
                this.cache[shape] = path
            }
            return this.cache[shape]!!
        }

    }

}
