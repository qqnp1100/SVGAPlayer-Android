package com.opensource.svgaplayer

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageView
import com.opensource.svgaplayer.utils.SVGARange
import com.opensource.svgaplayer.utils.log.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.lang.ref.WeakReference
import java.net.URL
import kotlin.coroutines.CoroutineContext


/**
 * Created by PonyCui on 2017/3/29.
 */
open class SVGAImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr), ViewTreeObserver.OnPreDrawListener {

    private val TAG = "SVGAImageView"

    enum class FillMode {
        Backward,
        Forward,
        Clear,
    }

    @Deprecated("Deprecated since 3.0.0. Use SvgaState.isPlaying in Compose; control View playback with SvgaViewHandle.")
    var isAnimating = false
        internal set

    @Deprecated("Deprecated since 3.0.0. Set iterations in loadSvga or SvgaView; useViewControls retains this legacy setting.")
    var loops = 0

    @Deprecated(
        "Deprecated. Use clearSvga or SvgaViewHandle.cancel to cancel loading and clear playback.",
        level = DeprecationLevel.WARNING
    )
    var clearsAfterStop = false
    @Deprecated("Deprecated since 3.0.0. loadSvga releases its presentation automatically on detach; use restartOnAttach to reload.")
    var clearsAfterDetached = false
    @Deprecated("Deprecated since 3.0.0. Modern playback retains the final frame; use clearSvga to clear it or useViewControls for legacy fill modes.")
    var fillMode: FillMode = FillMode.Forward
    @Deprecated("Deprecated since 3.0.0. Set onReady/onFinished/onError in loadSvga; use SvgaState for Compose playback state.")
    var callback: SVGACallback? = null

    private var modernPlayback: SvgaLegacyViewSession? = null
    private var mAnimator: ValueAnimator? = null
    private var mItemClickAreaListener: SVGAClickAreaListener? = null
    private var mAntiAlias = true
    private var mAutoPlay = true
    private val mAnimatorListener = AnimatorListener(this)
    private val mAnimatorUpdateListener = AnimatorUpdateListener(this)
    private var mStartFrame = 0
    private var mEndFrame = 0
    private var updateSvagDrawableCallBackWait = false
    private var updateSvagDrawableCallBack = Runnable {
        updateSvagDrawableCallBackWait = false
        getSVGADrawable()?.invalidateSelf()
    }

    @Deprecated("Deprecated since 3.0.0. Set onReady in loadSvga or SvgaView to observe fully prepared resources.")
    var parserImagesEndCallBack: (() -> Unit)? = null

    internal class CloseableCoroutineScope(context: CoroutineContext) : Closeable, CoroutineScope {
        override val coroutineContext: CoroutineContext = context

        override fun close() {
            coroutineContext.cancel()
        }
    }

    private var scope: CloseableCoroutineScope? = null
    private var pendingLoadDrawable: SVGADrawable? = null
    private var isStaticVideoItem = false
    private var isViewVisible = true
    private var isRectVisible = true
    private var isAddOnPreDraw = false
    @Deprecated("Deprecated since 3.0.0. Set hiddenBehavior in loadSvga or SvgaView.")
    var pauseWhenHide = true
    private val visibleRect = Rect()
    private var lastRectVisibleCheckUptimeMillis = 0L

    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            this.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        attrs?.let { loadAttrs(it) }
    }

    private fun loadAttrs(attrs: AttributeSet) {
        val typedArray =
            context.theme.obtainStyledAttributes(attrs, R.styleable.SVGAImageView, 0, 0)
        loops = typedArray.getInt(R.styleable.SVGAImageView_loopCount, 0)
        clearsAfterStop = typedArray.getBoolean(R.styleable.SVGAImageView_clearsAfterStop, false)
        clearsAfterDetached =
            typedArray.getBoolean(R.styleable.SVGAImageView_clearsAfterDetached, false)
        mAntiAlias = typedArray.getBoolean(R.styleable.SVGAImageView_antiAlias, true)
        mAutoPlay = typedArray.getBoolean(R.styleable.SVGAImageView_autoPlay, true)
        typedArray.getString(R.styleable.SVGAImageView_fillMode)?.let {
            when (it) {
                "0" -> {
                    fillMode = FillMode.Backward
                }

                "1" -> {
                    fillMode = FillMode.Forward
                }

                "2" -> {
                    fillMode = FillMode.Clear
                }
            }
        }
        typedArray.getString(R.styleable.SVGAImageView_source)?.let {
            parserSource(it)
        }
        typedArray.recycle()
    }

    private fun parserSource(source: String) {
        sourceLoader?.let { it(this, source, mAutoPlay); return }
        val refImgView = WeakReference<SVGAImageView>(this)
        val parser = SVGAParser(context)
        if (source.startsWith("http://") || source.startsWith("https://")) {
            parser.decodeFromURL(URL(source), createParseCompletion(refImgView))
        } else {
            parser.decodeFromAssets(source, createParseCompletion(refImgView))
        }
    }

    private fun createParseCompletion(ref: WeakReference<SVGAImageView>): SVGAParser.ParseCompletion {
        return object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) {
                ref.get()?.startAnimation(videoItem)
            }

            override fun onError() {}
        }
    }

    private fun startAnimation(videoItem: SVGAVideoEntity) {
        this@SVGAImageView.post {
            videoItem.antiAlias = mAntiAlias
            setVideoItem(videoItem)
            getSVGADrawable()?.scaleType = scaleType
            if (mAutoPlay) {
                startAnimation()
            }
        }
    }

    @Deprecated("Deprecated since 3.0.0. Use loadSvga autoPlay, or SvgaViewHandle.resume/replay.")
    fun startAnimation() {
        startAnimation(null, false)
    }

    @Deprecated("Deprecated since 3.0.0. Set startFrame/endFrame/reverse in loadSvga and control playback with SvgaViewHandle.")
    fun startAnimation(range: SVGARange?, reverse: Boolean = false) {
        modernSession()?.let { it.start(range, reverse); return }
        stopAnimation(false)
        play(range, reverse)
    }

    private fun play(range: SVGARange?, reverse: Boolean) {
        LogUtils.info(TAG, "================ start animation ================")
        val drawable = getSVGADrawable() ?: return
        setupDrawable()
        mStartFrame = Math.max(0, range?.location ?: 0)
        val videoItem = drawable.videoItem
        mEndFrame = Math.min(
            videoItem.frames - 1,
            ((range?.location ?: 0) + (range?.length ?: Int.MAX_VALUE) - 1)
        )
        val animator = ValueAnimator.ofInt(mStartFrame, mEndFrame)
        animator.interpolator = LinearInterpolator()
        animator.duration =
            ((mEndFrame - mStartFrame + 1) * (1000.0 / videoItem.FPS.coerceAtLeast(1))).toLong()
        animator.repeatCount = if (loops <= 0) 99999 else loops - 1
        animator.addUpdateListener(mAnimatorUpdateListener)
        animator.addListener(mAnimatorListener)
        if (reverse) {
            animator.reverse()
        } else {
            animator.start()
        }
        mAnimator = animator
    }

    private fun setupDrawable() {
        val drawable = getSVGADrawable() ?: return
        drawable.cleared = false
        drawable.scaleType = scaleType
        isStaticVideoItem = false
        drawable.setTextScrollEnabled(true)
    }

    private fun getSVGADrawable(): SVGADrawable? {
        return drawable as? SVGADrawable
    }

    @Suppress("UNNECESSARY_SAFE_CALL")
    private fun generateScale(): Double {
        var scale = 1.0
        try {
            val animatorClass = Class.forName("android.animation.ValueAnimator") ?: return scale
            val getMethod = animatorClass.getDeclaredMethod("getDurationScale") ?: return scale
            scale = (getMethod.invoke(animatorClass) as Float).toDouble()
            if (scale == 0.0) {
                val setMethod =
                    animatorClass.getDeclaredMethod("setDurationScale", Float::class.java)
                        ?: return scale
                setMethod.isAccessible = true
                setMethod.invoke(animatorClass, 1.0f)
                scale = 1.0
                LogUtils.info(
                    TAG,
                    "The animation duration scale has been reset to" +
                            " 1.0x, because you closed it on developer options."
                )
            }
        } catch (ignore: Exception) {
            ignore.printStackTrace()
        }
        return scale
    }

    private fun onAnimatorUpdate(animator: ValueAnimator?) {
        val drawable = getSVGADrawable() ?: return
        if ((!isViewVisible || !isRectVisible) && pauseWhenHide) {
            drawable.updateCurrentFrame(animator?.animatedValue as Int, false)
        } else {
            drawable.updateCurrentFrame(animator?.animatedValue as Int)
        }
        val percentage =
            (drawable.currentFrame + 1).toDouble() / drawable.videoItem.frames.toDouble()
        callback?.onStep(drawable.currentFrame, percentage)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        this.isViewVisible = isVisible
        if (isVisible) {
            lastRectVisibleCheckUptimeMillis = 0L
        }
    }

    override fun onPreDraw(): Boolean {
        updateRectVisible()
        return true
    }

    private fun updateRectVisible(force: Boolean = false) {
        if (!pauseWhenHide || !isViewVisible || !isAnimating) return

        val now = SystemClock.uptimeMillis()
        if (!force && now - lastRectVisibleCheckUptimeMillis < RECT_VISIBLE_CHECK_INTERVAL_MILLIS) {
            return
        }
        isRectVisible = getGlobalVisibleRect(visibleRect)
        lastRectVisibleCheckUptimeMillis = now
    }


    private fun onAnimationEnd(animation: Animator?) {
        isAnimating = false
        stopAnimation()
        val drawable = getSVGADrawable()
        if (drawable != null) {
            when (fillMode) {
                FillMode.Backward -> {
                    drawable.updateCurrentFrame(mStartFrame)
                }

                FillMode.Forward -> {
                    drawable.updateCurrentFrame(mEndFrame)
                }

                FillMode.Clear -> {
                    drawable.cleared = true
                }
            }
        }
        callback?.onFinished()
    }

    @Deprecated("Deprecated since 3.0.0. Use clearSvga or SvgaViewHandle.cancel to cancel loading and clear playback.")
    fun clear() {
        modernPlayback?.close(); modernPlayback = null
        getSVGADrawable()?.cleared = true
        getSVGADrawable()?.clear()
        // 清除对 drawable 的引用
        setImageDrawable(null)
    }

    @Deprecated("Deprecated since 3.0.0. Use SvgaViewHandle.pause or SvgaState.pause.")
    fun pauseAnimation() {
        stopAnimation(false)
        callback?.onPause()
    }

    /** Resume a resource-backed presentation without resetting its position or loop count. */
    fun resumeAnimation() {
        modernSession()?.let { it.resume(); return }
        startAnimation()
    }

    @Deprecated("Deprecated since 3.0.0. Use SvgaViewHandle.pause, or clearSvga to cancel loading and clear playback.")
    fun stopAnimation() {
        stopAnimation(clear = clearsAfterStop)
    }

    @Deprecated("Deprecated since 3.0.0. Use SvgaViewHandle.pause, or clearSvga to cancel loading and clear playback.")
    fun stopAnimation(clear: Boolean) {
        modernPlayback?.let { it.pause(); getSVGADrawable()?.cleared = clear; return }
        mAnimator?.cancel()
        mAnimator?.removeAllListeners()
        mAnimator?.removeAllUpdateListeners()
        mAnimator = null
        getSVGADrawable()?.stop()
        getSVGADrawable()?.setTextScrollEnabled(false)
        getSVGADrawable()?.cleared = clear
    }

    override fun setImageDrawable(drawable: Drawable?) {
        modernPlayback?.close(); modernPlayback = null
        getSVGADrawable()?.setTextScrollEnabled(false)
        isStaticVideoItem = false
        (drawable as? SVGADrawable)?.setTextScrollAttached(false)
        super.setImageDrawable(drawable)
        if (drawable is SVGADrawable) {
            drawable.setTextScrollAttached(isAttachedToWindow)
            if (scope == null) {
                pendingLoadDrawable = drawable
            } else {
                startLoadDrawable(drawable)
            }
        } else {
            pendingLoadDrawable = null
        }
    }

    @Deprecated("Deprecated since 3.0.0. Use loadSvga to load and bind resources; useViewControls retains manual entity binding.")
    fun setVideoItem(videoItem: SVGAVideoEntity?) {
        setVideoItem(videoItem, SVGADynamicEntity())
    }

    @Deprecated("Deprecated since 3.0.0. Use loadSvga with SvgaBindings, or SvgaViewHandle.updateBindings for dynamic updates.")
    fun setVideoItem(videoItem: SVGAVideoEntity?, dynamicItem: SVGADynamicEntity?) {
        if (videoItem == null) {
            setImageDrawable(null)
        } else {
            val drawable = SVGADrawable(videoItem, dynamicItem ?: SVGADynamicEntity())
            drawable.cleared = true
            setImageDrawable(drawable)
        }
    }

    //静止的
    @Deprecated("Deprecated since 3.0.0. Use loadSvga with staticImage = true.")
    fun setStaticVideoItem(videoItem: SVGAVideoEntity?) {
        setStaticVideoItem(videoItem, SVGADynamicEntity())
    }

    @Deprecated("Deprecated since 3.0.0. Use loadSvga with staticImage = true and SvgaBindings.")
    fun setStaticVideoItem(
        videoItem: SVGAVideoEntity?,
        dynamicItem: SVGADynamicEntity?,
    ): SVGADrawable? {
        if (videoItem == null) {
            setImageDrawable(null)
        } else {
            val drawable = SVGADrawable(videoItem, dynamicItem ?: SVGADynamicEntity())
            drawable.cleared = false
            drawable.setTextScrollEnabled(true)
            setImageDrawable(drawable)
            isStaticVideoItem = true
            return drawable
        }
        return null
    }

    private fun startLoadDrawable(drawable: SVGADrawable) {
        pendingLoadDrawable = null
        startLoadVideoItemImage(drawable.videoItem)
        startLoadDynamicItemImage(drawable.dynamicItem)
    }

    private fun startLoadVideoItemImage(videoItem: SVGAVideoEntity) {
        scope?.launch {
            videoItem.parserImages(this@SVGAImageView)
            parserImagesEndCallBack?.invoke()
            launch(Dispatchers.Main) {
                updateSvagDrawable()
            }
        }
    }

    private fun startLoadDynamicItemImage(dynamicItem: SVGADynamicEntity?) {
        dynamicItem ?: return
        scope?.launch {
            try {
                dynamicItem.requestDynamicImage(this@SVGAImageView)
            } catch (e: Exception) {
            }
            launch(Dispatchers.Main) {
                updateSvagDrawable()
            }
        }
    }

    private fun updateSvagDrawable() {
        if (isAnimating) {
            return
        }
        if (!updateSvagDrawableCallBackWait) {
            updateSvagDrawableCallBackWait = true
            postDelayed(updateSvagDrawableCallBack, 16)
        }
    }

    private fun modernSession(): SvgaLegacyViewSession? {
        val drawable = getSVGADrawable() ?: return null
        val resource = drawable.videoItem.resource ?: return null
        return modernPlayback ?: SvgaLegacyViewSession(this, drawable, resource).also { modernPlayback = it }
    }

    @Deprecated("Deprecated since 3.0.0. Use SvgaViewHandle.seekToProgress and pause/resume; use SvgaPlayback.seekFrame for a custom renderer.")
    fun stepToFrame(frame: Int, andPlay: Boolean) {
        modernSession()?.let { it.seek(frame, andPlay); return }
        pauseAnimation()
        val drawable = getSVGADrawable() ?: return
        drawable.updateCurrentFrame(frame)
        if (andPlay) {
            startAnimation()
            mAnimator?.let {
                it.currentPlayTime = (Math.max(
                    0.0f,
                    Math.min(1.0f, (frame.toFloat() / drawable.videoItem.frames.toFloat()))
                ) * it.duration).toLong()
            }
        }
    }

    @Deprecated("Deprecated since 3.0.0. Use SvgaViewHandle.seekToProgress and pause/resume, or the corresponding SvgaState methods.")
    fun stepToPercentage(percentage: Double, andPlay: Boolean) {
        val drawable = drawable as? SVGADrawable ?: return
        var frame = (drawable.videoItem.frames * percentage).toInt()
        if (frame >= drawable.videoItem.frames && frame > 0) {
            frame = drawable.videoItem.frames - 1
        }
        stepToFrame(frame, andPlay)
    }

    @Deprecated("Deprecated since 3.0.0. Retained for legacy View click areas; Compose supports SvgaView.onLayerClick.")
    fun setOnAnimKeyClickListener(clickListener: SVGAClickAreaListener) {
        mItemClickAreaListener = clickListener
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action != MotionEvent.ACTION_DOWN) {
            return super.onTouchEvent(event)
        }
        val drawable = getSVGADrawable() ?: return super.onTouchEvent(event)
        for ((key, value) in drawable.dynamicItem.mClickMap) {
            if (event.x >= value[0] && event.x <= value[2] && event.y >= value[1] && event.y <= value[3]) {
                mItemClickAreaListener?.let {
                    it.onClick(key)
                    return true
                }
            }
        }

        return super.onTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        scope?.close()
        scope = CloseableCoroutineScope(SupervisorJob() + SVGAParser.coroutineDispatcher)
        super.onAttachedToWindow()
        getSVGADrawable()?.setTextScrollAttached(true)
        (pendingLoadDrawable ?: getSVGADrawable())?.let {
            startLoadDrawable(it)
        }
        if (!isAddOnPreDraw) {
            viewTreeObserver.addOnPreDrawListener(this)
            isAddOnPreDraw = true
        }
    }

    override fun onDetachedFromWindow() {
        modernPlayback?.close(); modernPlayback = null
        scope?.close()
        scope = null
        if (isAddOnPreDraw) {
            viewTreeObserver.removeOnPreDrawListener(this)
            isAddOnPreDraw = false
        }
        val drawable = getSVGADrawable()
        drawable?.setTextScrollAttached(false)
        super.onDetachedFromWindow()
        removeCallbacks(updateSvagDrawableCallBack)
        if (isStaticVideoItem && !clearsAfterDetached) {
            drawable?.stop()
        } else {
            stopAnimation(clearsAfterDetached)
        }
        parserImagesEndCallBack = null
        if (clearsAfterDetached) {
            clear()
        }
    }


    private class AnimatorListener(view: SVGAImageView) : Animator.AnimatorListener {
        private val weakReference = WeakReference<SVGAImageView>(view)

        override fun onAnimationRepeat(animation: Animator) {
            weakReference.get()?.callback?.onRepeat()
        }

        override fun onAnimationEnd(animation: Animator) {
            weakReference.get()?.onAnimationEnd(animation)
        }

        override fun onAnimationCancel(animation: Animator) {
            weakReference.get()?.isAnimating = false
        }

        override fun onAnimationStart(animation: Animator) {
            weakReference.get()?.run {
                isAnimating = true
                updateRectVisible(force = true)
            }
        }
    } // end of AnimatorListener


    private class AnimatorUpdateListener(view: SVGAImageView) :
        ValueAnimator.AnimatorUpdateListener {
        private val weakReference = WeakReference<SVGAImageView>(view)

        override fun onAnimationUpdate(animation: ValueAnimator) {
            weakReference.get()?.onAnimatorUpdate(animation)
        }
    } // end of AnimatorUpdateListener

    companion object {
        /** Install at application startup to route XML sources through the host's modern loader. */
        var sourceLoader: ((SVGAImageView, String, Boolean) -> Unit)? = null
        const val RECT_VISIBLE_CHECK_INTERVAL_MILLIS = 100L
    }
}
