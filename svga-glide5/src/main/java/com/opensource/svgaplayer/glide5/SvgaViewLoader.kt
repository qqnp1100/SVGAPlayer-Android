package com.opensource.svgaplayer.glide5

import android.view.Choreographer
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.opensource.svgaplayer.*
import com.opensource.svgaplayer.loader.*
import kotlinx.coroutines.*

/** Callback contract for existing applications that configure prepared video entities. */
interface SvgaViewResourceCallback {
    fun onProgress(progress: Float) {}
    fun onComplete(url: String, videoItem: SVGAVideoEntity): Boolean
    fun onError() {}
}

class SvgaViewOptions {
    /** Preserve matching requests during repeated list binds. */
    var reuseOnRebind = true
    /** Keep the request on this View and reload it after detach/attach. */
    var restartOnAttach = false
    /** Delegate playback to SVGAImageView's resource-backed controls. */
    var useViewControls = false
    var requestFactory: (suspend (Int, Int) -> SvgaRequest)? = null
    /** Return true when the callback takes ownership of the presentation. */
    var onResourceReady: ((SVGAVideoEntity) -> Boolean)? = null
    var cachePolicy = SvgaCachePolicy.ALL
    var memoryCache: Boolean? = null
    var weakMemoryCache: Boolean? = null
    var bitmapConfig: android.graphics.Bitmap.Config? = null
    var skipInvisibleImages: Boolean? = null
    /** Single playback only: decode single-use images during playback with a private Bitmap pool. */
    var inBitmap = false
    var iterations = 0
    var autoPlay = true
    /** Display frame zero only, without preparing audio or starting a playback clock. */
    var staticImage = false
    var speed = 1f
    var startFrame = 0
    var endFrame: Int? = null
    var reverse = false
    var hiddenBehavior = SvgaHiddenBehavior.PAUSE
    var bindings = SvgaBindings.Empty
    /** Build bindings after decoding, within the cancellable View request. */
    var bindingsFactory: (suspend (SvgaResource) -> SvgaBindings)? = null
    var loader: SvgaImageLoader? = null
    var onDownloadProgress: (SvgaDownloadProgress) -> Unit = {}
    var onReady: () -> Unit = {}
    var onError: (Throwable) -> Unit = {}
    var onFinished: () -> Unit = {}
}

/** Main-thread handle. A replaced or cancelled handle cannot modify its previous View. */
class SvgaViewHandle internal constructor(view: SVGAImageView, private var source: Any, private var options: SvgaViewOptions) : AutoCloseable,
    View.OnAttachStateChangeListener, Choreographer.FrameCallback {
    private var targetView: SVGAImageView? = view
    private val view get() = checkNotNull(targetView)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val loader = options.loader ?: SvgaImageLoader.get(view.context)
    private val decodeDefaults = SvgaDecodeOptions.defaults
    private val decodeScaleType = view.scaleType
    private val useInBitmap = options.inBitmap && !options.staticImage &&
        (if (options.useViewControls) view.loops == 1 else options.iterations == 1)
    private var clock: SvgaPlayback? = null
    private var drawable: SVGADrawable? = null
    private var audio: SvgaAudioSession? = null
    private var resource: SvgaResource? = null
    private var updateJob: Job? = null
    private var frameJob: Job? = null
    private var pendingCompletion = false
    private var playing = options.autoPlay && !options.staticImage
    private var loadStarted = false
    private var closed = false
    private var version = 0
    private val visibleRect = android.graphics.Rect()
    private val visibilityCheck = Runnable { schedule() }
    private var hostActive = false
    private var failed = false
    private var ready = false
    private var viewport = 0 to 0
    private var identity: Any? = requestIdentity(source)
    private var bindingCache = options.cachePolicy
    init {
        require(options.iterations >= 0 && options.speed > 0 && options.speed.isFinite())
        require(!options.staticImage || options.onResourceReady == null) {
            "staticImage owns presentation; use bindings and onReady instead of onResourceReady"
        }
        view.setTag(R.id.svga_glide_request, this)
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) startLoad()
    }
    private fun startLoad() {
        if (closed || loadStarted) return
        loadStarted = true
        ready = false; failed = false
        scope.launch {
            var pendingSound: SvgaAudioSession? = null
            var pendingDynamic: SVGADynamicEntity? = null
            var pendingDrawable: SVGADrawable? = null
            try {
                while (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) delay(16)
                val decodeWidth = ((view.width + 63) / 64) * 64
                val decodeHeight = ((view.height + 63) / 64) * 64
                viewport = decodeWidth to decodeHeight
                val requestSource = source
                val baseRequest = options.requestFactory?.let { factory ->
                    withContext(Dispatchers.IO) { factory(decodeWidth, decodeHeight) }
                } ?: if (requestSource is SvgaRequest) requestSource.copy(
                    width = requestSource.width.takeIf { it > 0 } ?: decodeWidth,
                    height = requestSource.height.takeIf { it > 0 } ?: decodeHeight
                ) else SvgaRequest(SvgaSource.from(requestSource), decodeWidth, decodeHeight, options.cachePolicy)
                val request = baseRequest.copy(
                    // CENTER draws at source scale even when its viewport is smaller.
                    width = if (decodeScaleType == android.widget.ImageView.ScaleType.CENTER) 0 else baseRequest.width,
                    height = if (decodeScaleType == android.widget.ImageView.ScaleType.CENTER) 0 else baseRequest.height,
                    memoryCache = options.memoryCache ?: baseRequest.memoryCache,
                    weakMemoryCache = options.weakMemoryCache ?: baseRequest.weakMemoryCache,
                    bitmapConfig = options.bitmapConfig ?: baseRequest.bitmapConfig ?: decodeDefaults.bitmapConfig,
                    skipInvisibleImages = options.skipInvisibleImages ?: baseRequest.skipInvisibleImages ?: decodeDefaults.skipInvisibleImages,
                    inBitmap = useInBitmap,
                )
                val loaded = loader.load(request) { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        if (options.requestFactory != null) request.onDownloadProgress?.invoke(progress)
                        else (source as? SvgaRequest)?.onDownloadProgress?.invoke(progress)
                        options.onDownloadProgress(progress)
                    }
                }
                ensureActive()
                if (options.useViewControls && !options.staticImage) {
                    resource = loaded
                    val bind = options.onResourceReady
                    if (bind == null) {
                        val bindings = options.bindingsFactory?.invoke(loaded) ?: options.bindings
                        val dynamic = bindings.prepare(loader.imageLoader, view.context, request.cachePolicy, loaded,
                            view.width, view.height, view.scaleType)
                        pendingDynamic = dynamic
                        ensureActive()
                        view.setVideoItem(loaded.newVideoEntity(), dynamic)
                        pendingDynamic = null
                        if (playing) view.startAnimation()
                    } else {
                        val entity = loaded.newVideoEntity()
                        val accepted = try { bind(entity) } catch (e: Throwable) { entity.clear(); throw e }
                        if (!accepted) entity.clear()
                    }
                    ensureActive()
                    ready = true
                    options.onReady()
                    return@launch
                }
                val playback = if (options.staticImage) null else SvgaPlayback(loaded.frames, loaded.fps, options.startFrame, options.endFrame ?: loaded.frames - 1, options.reverse)
                bindingCache = request.cachePolicy
                resource = loaded
                if (!options.staticImage) {
                    val sound = SvgaAudioSession(view.context, loaded)
                    pendingSound = sound
                    withContext(Dispatchers.IO) { sound.prepare() }
                }
                // An update during preparation must not publish an obsolete binding snapshot.
                while (true) {
                    val bindings = options.bindingsFactory?.invoke(loaded) ?: options.bindings
                    val dynamic = bindings.prepare(loader.imageLoader, view.context, bindingCache, loaded,
                        view.width, view.height, view.scaleType)
                    pendingDynamic = dynamic
                    ensureActive()
                    val candidate = SVGADrawable(loaded.newVideoEntity(), dynamic)
                    pendingDrawable = candidate
                    pendingDynamic = null // The drawable now owns the dynamic entity.
                    candidate.scaleType = view.scaleType
                    candidate.advanceExternalClock(playback?.frame ?: 0, 0)
                    val width = view.width; val height = view.height
                    withContext(Dispatchers.Default) { candidate.prepare(width, height) }
                    if (options.bindingsFactory != null || bindings === options.bindings) break
                    candidate.clear(); pendingDrawable = null
                }
                val prepared = checkNotNull(pendingDrawable)
                drawable = prepared
                pendingDrawable = null
                audio = pendingSound
                pendingSound = null
                view.setImageDrawable(prepared)
                if (options.staticImage) prepared.showStaticFrame() else view.stepToFrame(playback?.frame ?: 0, false)
                prepared.setPlaybackActive(playing)
                clock = playback
                ready = true
                options.onReady()
                schedule()
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) {
                failed = true; ready = false
                clearPresentation()
                options.onError(e)
            } finally {
                // withContext waits for its worker before unwinding: never clear while prepare runs.
                pendingDrawable?.clear()
                pendingDynamic?.clearDynamicObjects()
                pendingSound?.close()
            }
        }
    }
    private fun schedule() { if (!closed && !options.staticImage && view.isAttachedToWindow) {
        Choreographer.getInstance().removeFrameCallback(this)
        Choreographer.getInstance().postFrameCallback(this)
    } }
    private fun isHostActive() = view.isAttachedToWindow && view.isShown && view.getGlobalVisibleRect(visibleRect) &&
        (view.findViewTreeLifecycleOwner()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false)
    override fun doFrame(frameTimeNanos: Long) {
        if (closed || !view.isAttachedToWindow) return
        val clock = clock ?: return
        val visible = isHostActive()
        if (playing && visible) {
            if (!hostActive) { hostActive = true; drawable?.setPlaybackActive(true) }
            if (frameJob?.isActive == true) { clock.pause(); audio?.pause(); schedule(); return }
            pendingCompletion = clock.tick(frameTimeNanos, options.iterations, options.speed) || pendingCompletion
            val frame = clock.frame
            val presentation = drawable
            if (presentation != null && !presentation.isFrameReady(frame)) {
                clock.pause(); audio?.pause()
                prepareFrame(presentation, frame) {
                    if (playing && this.clock === clock && clock.frame == frame) presentFrame(clock)
                }
            } else presentFrame(clock)
        } else {
            if (!playing || options.hiddenBehavior != SvgaHiddenBehavior.CONTINUE_TIMELINE) clock.pause()
            if (!visible && options.hiddenBehavior == SvgaHiddenBehavior.STOP) pause()
            audio?.pause(); if (hostActive) { hostActive = false; drawable?.setPlaybackActive(false) }
        }
        if (playing) {
            if (visible) schedule() else view.postDelayed(visibilityCheck, 200)
        }
    }
    fun pause() { if (closed) return; if (options.useViewControls) view.pauseAnimation(); playing = false; clock?.pause(); audio?.pause(); drawable?.setPlaybackActive(false); hostActive = false
        Choreographer.getInstance().removeFrameCallback(this); view.removeCallbacks(visibilityCheck) }
    fun resume() { if (!closed && !options.staticImage) { if (options.useViewControls) view.resumeAnimation(); playing = true; clock?.pause(); schedule() } }
    fun seekToProgress(progress: Float) {
        if (closed || options.staticImage) return
        frameJob?.cancel(); frameJob = null
        pendingCompletion = false
        if (options.useViewControls) view.stepToPercentage(progress.toDouble(), playing)
        clock?.seek(progress); audio?.pause()
        val playback = clock ?: return
        val presentation = drawable ?: return
        val frame = playback.frame
        if (presentation.isFrameReady(frame)) presentation.updateCurrentFrame(frame)
        else prepareFrame(presentation, frame) {
            if (clock === playback && playback.frame == frame) presentation.updateCurrentFrame(frame)
        }
    }
    private fun presentFrame(clock: SvgaPlayback) {
        // Decode completion can arrive after the host was hidden or stopped.
        if (!isHostActive()) { schedule(); return }
        drawable?.advanceExternalClock(clock.frame, clock.positionNanos)
        audio?.advance(clock.frame, clock.completedIterations, options.speed, options.reverse)
        if (pendingCompletion) { pendingCompletion = false; pause(); options.onFinished() }
    }
    private fun prepareFrame(presentation: SVGADrawable, frame: Int, onPrepared: () -> Unit) {
        frameJob?.cancel()
        frameJob = scope.launch {
            try {
                withContext(Dispatchers.Default) { presentation.prepareFrame(frame) }
                ensureActive()
                if (!closed && drawable === presentation) onPrepared()
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) {
                failed = true; ready = false; pause(); clearPresentation(); options.onError(e)
            }
        }
    }
    fun replay() { seekToProgress(0f); resume() }
    fun updateBindings(bindings: SvgaBindings) {
        if (closed) return
        options.bindings = bindings
        val generation = ++version
        updateJob?.cancel()
        if (!ready) return // The initial load reads the latest snapshot before publication.
        updateJob = scope.launch {
            var prepared: SVGADynamicEntity? = null
            try {
                prepared = bindings.prepare(loader.imageLoader, view.context, bindingCache, resource,
                    view.width, view.height, view.scaleType)
                if (closed || generation != version) return@launch
                val resource = resource ?: return@launch
                val useViewControls = options.useViewControls && !options.staticImage
                val old = if (useViewControls) view.drawable as? SVGADrawable else drawable
                val replacement = SVGADrawable(resource.newVideoEntity(), prepared)
                replacement.scaleType = view.scaleType
                replacement.advanceExternalClock(clock?.frame ?: old?.currentFrame ?: 0, clock?.positionNanos ?: 0)
                val width = view.width; val height = view.height
                try { withContext(Dispatchers.Default) { replacement.prepare(width, height) } }
                catch (e: Throwable) { replacement.clear(); throw e }
                drawable = replacement
                if (useViewControls) {
                    // Read the live View state after preparation; its clock can keep advancing while we wait.
                    val frame = old?.currentFrame ?: 0
                    val wasPlaying = view.isAnimating
                    view.setImageDrawable(replacement)
                    view.stepToFrame(frame, wasPlaying)
                } else {
                    view.setImageDrawable(replacement)
                    if (options.staticImage) replacement.showStaticFrame() else view.stepToFrame(clock?.frame ?: 0, false)
                    replacement.setPlaybackActive(playing && hostActive)
                }
                prepared = null
                clock?.let { drawable?.updateCurrentFrame(it.frame) }
                old?.clear()
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { options.onError(e) }
            finally { prepared?.clearDynamicObjects() }
        }
    }
    internal fun rebind(nextSource: Any, next: SvgaViewOptions): Boolean {
        if (decodeDefaults != SvgaDecodeOptions.defaults) return false
        if (!closed && decodeScaleType != view.scaleType) return false
        if (closed || failed || !next.reuseOnRebind || !options.reuseOnRebind || identity != requestIdentity(nextSource)) return false
        if ((nextSource as? SvgaRequest)?.refresh == true) return false
        if (useInBitmap != (next.inBitmap && !next.staticImage &&
                (if (next.useViewControls) view.loops == 1 else next.iterations == 1))) return false
        if (options.loader !== next.loader || options.requestFactory !== next.requestFactory ||
            options.bindingsFactory !== next.bindingsFactory ||
            options.onResourceReady !== next.onResourceReady || options.useViewControls != next.useViewControls ||
            options.cachePolicy != next.cachePolicy || options.memoryCache != next.memoryCache || options.weakMemoryCache != next.weakMemoryCache ||
            options.bitmapConfig != next.bitmapConfig || options.skipInvisibleImages != next.skipInvisibleImages || options.inBitmap != next.inBitmap ||
            options.iterations != next.iterations || options.autoPlay != next.autoPlay || options.staticImage != next.staticImage || options.speed != next.speed ||
            options.startFrame != next.startFrame || options.endFrame != next.endFrame || options.reverse != next.reverse ||
            options.hiddenBehavior != next.hiddenBehavior ||
            options.restartOnAttach != next.restartOnAttach) return false
        if (viewport.first > 0 && view.width > 0 && view.height > 0 &&
            viewport != (((view.width + 63) / 64) * 64 to ((view.height + 63) / 64) * 64)) return false
        if (ready && view.drawable == null) return false
        if (ready && !options.useViewControls && view.drawable !== drawable) return false
        val bindingsChanged = options.bindings !== next.bindings
        if (bindingsChanged && (drawable == null || options.useViewControls)) return false
        source = nextSource
        options = next
        if (bindingsChanged) updateBindings(next.bindings)
        return true
    }
    override fun onViewAttachedToWindow(v: View) {
        if (!closed) startLoad()
    }
    override fun onViewDetachedFromWindow(v: View) {
        if (!options.restartOnAttach) { close(); return }
        releaseSession()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    fun cancel() = close()
    private fun releaseSession() {
        loadStarted = false
        ready = false
        ++version
        scope.cancel(); Choreographer.getInstance().removeFrameCallback(this)
        view.removeCallbacks(visibilityCheck)
        updateJob = null
        frameJob = null
        clearPresentation()
    }
    private fun clearPresentation() {
        pendingCompletion = false
        audio?.close(); audio = null
        targetView?.let { view ->
            if (view.getTag(R.id.svga_glide_request) === this) { view.stopAnimation(false); view.clear() }
        }
        drawable?.clear()
        drawable = null; resource = null; clock = null; hostActive = false
    }
    override fun close() {
        if (closed) return
        closed = true
        releaseSession()
        view.removeOnAttachStateChangeListener(this)
        if (view.getTag(R.id.svga_glide_request) === this) {
            view.setTag(R.id.svga_glide_request, null)
        }
        // Closed handles may remain in application fields; drop captured bitmaps and callbacks.
        options = SvgaViewOptions()
        source = ""
        identity = null
        targetView = null
    }
}

fun SVGAImageView.loadSvga(source: Any, configure: SvgaViewOptions.() -> Unit = {}): SvgaViewHandle {
    check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { "loadSvga must run on the main thread" }
    val options = SvgaViewOptions().apply(configure)
    val current = getTag(R.id.svga_glide_request) as? SvgaViewHandle
    if (current?.rebind(source, options) == true) return current
    current?.close()
    stopAnimation(false)
    clear()
    return SvgaViewHandle(this, source, options)
}

/** Cancel loading and playback, including any retained reattach request. */
fun SVGAImageView.clearSvga() {
    (getTag(R.id.svga_glide_request) as? SvgaViewHandle)?.close()
    stopAnimation(false)
    clear()
}

private fun requestIdentity(source: Any): Any {
    val request = (source as? SvgaRequest) ?: runCatching { SvgaRequest(SvgaSource.from(source)) }.getOrElse { return source }
    val snapshot = request.copy(headers = request.headers.toMap(), onDownloadProgress = null)
    val file = (snapshot.source as? SvgaSource.LocalFile)?.file
    return snapshot to file?.let { Triple(it.absolutePath, it.length(), it.lastModified()) }
}
