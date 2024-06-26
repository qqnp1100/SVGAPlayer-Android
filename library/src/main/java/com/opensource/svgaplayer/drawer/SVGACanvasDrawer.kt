package com.opensource.svgaplayer.drawer

import android.graphics.*
import android.os.Build
import android.text.StaticLayout
import android.text.TextUtils
import android.widget.ImageView
import com.opensource.svgaplayer.SVGADynamicEntity
import com.opensource.svgaplayer.SVGASoundManager
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.entities.SVGAVideoShapeEntity
import com.opensource.svgaplayer.utils.log.LogUtils

/**
 * Created by cuiminghui on 2017/3/29.
 */

internal class SVGACanvasDrawer(videoItem: SVGAVideoEntity, val dynamicItem: SVGADynamicEntity) :
    SGVADrawer(videoItem) {

    private val sharedValues = ShareValues()
    private val drawTextCache: HashMap<String, Bitmap> = hashMapOf()
    private val drawTextGradientCache: HashMap<String, Bitmap> = hashMapOf()
    private val scrollTextPosition: HashMap<String, Float> = hashMapOf()
    private val scrollTextSpeed: HashMap<String, Float> = hashMapOf()
    private val pathCache = PathCache()

    private var beginIndexList: Array<Boolean>? = null
    private var endIndexList: Array<Boolean>? = null
    private var mySoundId: Int? = null

    override fun drawFrame(canvas: Canvas, frameIndex: Int, scaleType: ImageView.ScaleType) {
        super.drawFrame(canvas, frameIndex, scaleType)
        playAudio(frameIndex)
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
                    drawSprite(
                        it,
                        this.sharedValues.shareMatteCanvas(canvas.width, canvas.height),
                        frameIndex
                    )
                    canvas.drawBitmap(
                        this.sharedValues.sharedMatteBitmap(),
                        0f,
                        0f,
                        this.sharedValues.shareMattePaint()
                    )
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

    private fun drawSprite(sprite: SVGADrawerSprite, canvas: Canvas, frameIndex: Int) {
        drawImage(sprite, canvas)
        drawShape(sprite, canvas)
        drawDynamic(sprite, canvas, frameIndex)
    }

    private fun drawImage(sprite: SVGADrawerSprite, canvas: Canvas) {
        val imageKey = sprite.imageKey ?: return
        val isHidden = dynamicItem.dynamicHidden[imageKey] == true
        if (isHidden) {
            return
        }
        val bitmapKey = if (imageKey.endsWith(".matte")) imageKey.substring(
            0,
            imageKey.length - 6
        ) else imageKey
        val drawingBitmap =
            (dynamicItem.getDynamicImage(bitmapKey) ?: videoItem.imageMap[bitmapKey])
                ?: return
        val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
        val paint = this.sharedValues.sharedPaint()
        paint.isAntiAlias = videoItem.antiAlias
        paint.isFilterBitmap = videoItem.antiAlias
        paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
        if (sprite.frameEntity.maskPath != null) {
            val maskPath = sprite.frameEntity.maskPath ?: return
            canvas.save()
            val path = this.sharedValues.sharedPath()
            maskPath.buildPath(path)
            path.transform(frameMatrix)
            canvas.clipPath(path)
            frameMatrix.preScale(
                (sprite.frameEntity.layout.width / drawingBitmap.width).toFloat(),
                (sprite.frameEntity.layout.height / drawingBitmap.height).toFloat()
            )
            if (!drawingBitmap.isRecycled) {
                canvas.drawBitmap(drawingBitmap, frameMatrix, paint)
            }
            canvas.restore()
        } else {
            frameMatrix.preScale(
                (sprite.frameEntity.layout.width / drawingBitmap.width).toFloat(),
                (sprite.frameEntity.layout.height / drawingBitmap.height).toFloat()
            )
            if (!drawingBitmap.isRecycled) {
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
                    (drawingBitmap.width * matrixArray[0] + matrixArray[2]).toInt(),
                    (drawingBitmap.height * matrixArray[4] + matrixArray[5]).toInt()
                )
            }
        }
        drawTextOnBitmap(canvas, drawingBitmap, sprite, frameMatrix)
    }

    private fun drawTextOnBitmap(
        canvas: Canvas,
        drawingBitmap: Bitmap,
        sprite: SVGADrawerSprite,
        frameMatrix: Matrix
    ) {
        if (dynamicItem.isTextDirty) {
            this.drawTextCache.clear()
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
                    var bitmapWidth = drawingBitmap.width
                    var textDrawStart = drawingBitmap.width / 2f
                    if (scrollSpeed > 0) {
                        val textWidth = drawingTextPaint.measureText(drawingText)
                        if (textWidth > drawingBitmap.width) {
                            val fm = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                            frameMatrix.getValues(fm)
                            bitmapWidth =
                                (textWidth.toInt() + dynamicItem.srcollTextSpace * videoItem.videoSize.width * fm[0] / canvas.width).toInt()
                            textDrawStart = 0f
                            drawingTextPaint.textAlign = Paint.Align.LEFT
                        }
                    }
                    textBitmap = Bitmap.createBitmap(
                        bitmapWidth,
                        drawingBitmap.height,
                        Bitmap.Config.ARGB_8888
                    ).apply {
                        val drawRect = Rect(0, 0, bitmapWidth, drawingBitmap.height)
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
            }
        }

        dynamicItem.dynamicBoringLayoutText[imageKey]?.let {
            drawTextCache[imageKey]?.let {
                textBitmap = it
            } ?: kotlin.run {
                it.paint.isAntiAlias = true
                var bitmapWidth = drawingBitmap.width
                if (scrollSpeed > 0) {
                    val textWidth = it.paint.measureText(it.text, 0, it.text.length)
                    if (textWidth > drawingBitmap.width) {
                        val fm = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                        frameMatrix.getValues(fm)
                        bitmapWidth =
                            (textWidth.toInt() + dynamicItem.srcollTextSpace * videoItem.videoSize.width * fm[0] / canvas.width).toInt()
                        it.paint.textAlign = Paint.Align.LEFT
                    }
                }
                textBitmap = Bitmap.createBitmap(
                    bitmapWidth,
                    drawingBitmap.height,
                    Bitmap.Config.ARGB_8888
                ).apply {
                    val textCanvas = Canvas(this)
                    textCanvas.translate(0f, ((drawingBitmap.height - it.height) / 2).toFloat())
                    it.draw(textCanvas)
                    drawTextCache.put(imageKey, this)
                }
            }
        }

        dynamicItem.dynamicStaticLayoutText[imageKey]?.let {
            drawTextCache[imageKey]?.let {
                textBitmap = it
            } ?: kotlin.run {
                it.paint.isAntiAlias = true
                var bitmapWidth = drawingBitmap.width
                if (scrollSpeed > 0) {
                    val textWidth = it.paint.measureText(it.text, 0, it.text.length)
                    if (textWidth > drawingBitmap.width) {
                        val fm = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                        frameMatrix.getValues(fm)
                        bitmapWidth =
                            (textWidth.toInt() + dynamicItem.srcollTextSpace * videoItem.videoSize.width * fm[0] / canvas.width).toInt()
                        it.paint.textAlign = Paint.Align.LEFT
                    }
                }
                var layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    var lineMax = try {
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
                textBitmap = Bitmap.createBitmap(
                    bitmapWidth,
                    drawingBitmap.height,
                    Bitmap.Config.ARGB_8888
                )?.apply {
                    val textCanvas = Canvas(this)
                    textCanvas.translate(0f, ((drawingBitmap.height - layout.height) / 2).toFloat())
                    layout.draw(textCanvas)
                    drawTextCache.put(imageKey, this)
                }
            }
        }
        textBitmap?.let { textBitmap ->
            val paint = this.sharedValues.sharedPaint()
            paint.isAntiAlias = videoItem.antiAlias
            paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
            if (sprite.frameEntity.maskPath != null) {
                val maskPath = sprite.frameEntity.maskPath ?: return@let
                canvas.save()
                canvas.concat(frameMatrix)
                canvas.clipRect(0, 0, drawingBitmap.width, drawingBitmap.height)
                val bitmapShader =
                    BitmapShader(textBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                paint.shader = bitmapShader
                val path = this.sharedValues.sharedPath()
                maskPath.buildPath(path)
                canvas.drawPath(path, paint)
                canvas.restore()
            } else {
                paint.isFilterBitmap = videoItem.antiAlias
                if (textBitmap.width > drawingBitmap.width) {
                    val gradientBitmap = drawTextGradientCache[imageKey] ?: kotlin.run {
                        Bitmap.createBitmap(
                            drawingBitmap.width,
                            drawingBitmap.height,
                            Bitmap.Config.ARGB_8888
                        ).apply {
                            val tp = Paint()
                            tp.shader = LinearGradient(
                                0f,
                                0f,
                                drawingBitmap.width.toFloat(),
                                0f,
                                intArrayOf(
                                    Color.TRANSPARENT,
                                    Color.BLACK,
                                    Color.BLACK,
                                    Color.TRANSPARENT
                                ),
                                floatArrayOf(0.0f, 0.2f, 0.8f, 1f),
                                Shader.TileMode.CLAMP
                            )
                            val tCanvas = Canvas(this)
                            tCanvas.drawPaint(tp)
                            drawTextGradientCache[imageKey] = this
                        }
                    }
                    var textScrollX = 0f
                    val drawingBitmapWidth = drawingBitmap.width
                    val fm = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                    frameMatrix.getValues(fm)
                    val startX = fm[2]
                    val startY = fm[5]
                    val scaleX = fm[0]
                    val scaleY = fm[4]
                    val dstWidth = drawingBitmapWidth * scaleX
                    val dstHeight = drawingBitmap.height * scaleY
                    var speed = scrollTextSpeed[imageKey] ?: -1f
                    if (speed < 0) {
                        val canvasWidth = canvas.width
                        val videoWidth = videoItem.videoSize.width
                        speed = ((dynamicItem.dynamicScrollTextSpeed[imageKey]
                            ?: 10f) * videoWidth * scaleX / canvasWidth.toFloat() / videoItem.FPS).toFloat()
                        scrollTextSpeed[imageKey] = speed
                    }
                    textScrollX = ((scrollTextPosition[imageKey] ?: 0f) + speed).toFloat()
                    if (textScrollX > textBitmap.width * 2) {
                        textScrollX = 0f
                    }
                    scrollTextPosition[imageKey] = textScrollX
                    val textScrollXTwo =
                        textScrollX
                    var textScrollXOne = textScrollXTwo
                    if (textScrollXOne >= textBitmap.width * 2 - drawingBitmapWidth) {
                        textScrollXOne =
                            (textScrollXOne - (textBitmap.width * 2 - drawingBitmapWidth)) - drawingBitmapWidth + 1
                    }

                    val saveLayer = canvas.saveLayer(
                        0f,
                        0f,
                        canvas.width.toFloat(),
                        canvas.height.toFloat(),
                        null
                    )
                    canvas.drawBitmap(
                        gradientBitmap, Rect(
                            0,
                            0,
                            gradientBitmap.width,
                            gradientBitmap.height
                        ),
                        RectF(
                            startX,
                            startY,
                            startX + dstWidth,
                            startY + dstHeight
                        ), null
                    )
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN);
                    canvas.drawBitmap(
                        textBitmap,
                        Rect(
                            (0 + textScrollXOne).toInt(),
                            0,
                            (drawingBitmapWidth + textScrollXOne).toInt(),
                            textBitmap.height
                        ),
                        RectF(
                            startX,
                            startY - 1,
                            startX + dstWidth,
                            startY + dstHeight + 1
                        ),
                        paint
                    )
                    canvas.drawBitmap(
                        textBitmap,
                        Rect(
                            (textScrollXTwo - textBitmap.width).toInt(),
                            0,
                            (drawingBitmapWidth + textScrollXTwo - textBitmap.width).toInt(),
                            textBitmap.height
                        ),
                        Rect(
                            startX.toInt(),
                            startY.toInt() - 1,
                            (startX + dstWidth).toInt(),
                            (startY + dstHeight).toInt() + 1
                        ),
                        paint
                    )
                    canvas.restoreToCount(saveLayer)
                } else {
                    canvas.drawBitmap(textBitmap, frameMatrix, paint)
                }
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
            canvas.save()
            canvas.concat(frameMatrix)
            it.invoke(canvas, frameIndex)
            canvas.restore()
        }
        dynamicItem.dynamicDrawerSized[imageKey]?.let {
            val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
            canvas.save()
            canvas.concat(frameMatrix)
            it.invoke(
                canvas,
                frameIndex,
                sprite.frameEntity.layout.width.toInt(),
                sprite.frameEntity.layout.height.toInt()
            )
            canvas.restore()
        }
    }

    class ShareValues {

        private val sharedPaint = Paint()
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

        fun sharedMatteBitmap(): Bitmap {
            return sharedMatteBitmap as Bitmap
        }

        fun shareMatteCanvas(width: Int, height: Int): Canvas {
            if (shareMatteCanvas == null) {
                sharedMatteBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
//                shareMatteCanvas = Canvas(sharedMatteBitmap)
            }
//            val matteCanvas = shareMatteCanvas as Canvas
//            matteCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
//            return matteCanvas
            return Canvas(
                sharedMatteBitmap ?: Bitmap.createBitmap(
                    width,
                    height,
                    Bitmap.Config.ALPHA_8
                )
            )
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
