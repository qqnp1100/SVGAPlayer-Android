package com.opensource.svgaplayer.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.opensource.svgaplayer.*
import com.opensource.svgaplayer.coil3.*
import com.opensource.svgaplayer.loader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

enum class SvgaLoadState { EMPTY, LOADING, READY, ERROR }

@Stable
class SvgaState {
    var loadState by mutableStateOf(SvgaLoadState.EMPTY)
        internal set
    var downloadProgress by mutableStateOf<SvgaDownloadProgress?>(null)
        internal set
    var error by mutableStateOf<Throwable?>(null)
        internal set
    var isPlaying by mutableStateOf(true)
        private set
    var currentFrame by mutableIntStateOf(0)
        internal set
    var progress by mutableFloatStateOf(0f)
        internal set
    var completedIterations by mutableIntStateOf(0)
        internal set
    internal var clock: SvgaPlayback? = null
    internal var owner: Any? = null
    internal var revision by mutableIntStateOf(0)
    fun pause() { isPlaying = false; clock?.pause() }
    fun resume() { clock?.pause(); isPlaying = true }
    fun seekToProgress(progress: Float) { clock?.seek(progress); currentFrame = clock?.frame ?: 0; this.progress = progress.coerceIn(0f, 1f); revision++ }
    fun replay() { seekToProgress(0f); resume() }
}

@Composable fun rememberSvgaState() = remember { SvgaState() }
@Composable fun rememberSvgaBindings(vararg keys: Any?, block: SvgaBindings.Builder.() -> Unit): SvgaBindings =
    remember(*keys) { svgaBindings(block) }

/** Native Compose: no AndroidView, Drawable, ValueAnimator or hidden View. */
@Composable
fun SvgaView(
    source: Any,
    modifier: Modifier = Modifier,
    state: SvgaState = rememberSvgaState(),
    bindings: SvgaBindings = SvgaBindings.Empty,
    cachePolicy: SvgaCachePolicy = SvgaCachePolicy.ALL,
    iterations: Int = 0,
    autoPlay: Boolean = true,
    visible: Boolean = true,
    hiddenBehavior: SvgaHiddenBehavior = SvgaHiddenBehavior.PAUSE,
    speed: Float = 1f,
    startFrame: Int = 0,
    endFrame: Int? = null,
    reverse: Boolean = false,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    contentDescription: String? = null,
    loader: SvgaImageLoader? = null,
    onReady: () -> Unit = {},
    onFinished: () -> Unit = {},
    onError: (Throwable) -> Unit = {},
    onLayerClick: ((String) -> Unit)? = null,
    placeholder: @Composable () -> Unit = {},
    error: @Composable (Throwable) -> Unit = {},
    onDownloadProgress: (SvgaDownloadProgress) -> Unit = {},
    memoryCache: Boolean? = null,
    weakMemoryCache: Boolean? = null,
) {
    require(iterations >= 0 && speed > 0 && speed.isFinite())
    val context = LocalContext.current
    val imageLoader = loader ?: remember(context) { SvgaImageLoader.get(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val direction = LocalLayoutDirection.current
    val request = remember(source, cachePolicy, memoryCache, weakMemoryCache) {
        val base = (source as? SvgaRequest) ?: SvgaRequest(SvgaSource.from(source), cachePolicy = cachePolicy)
        base.copy(memoryCache = memoryCache ?: base.memoryCache, weakMemoryCache = weakMemoryCache ?: base.weakMemoryCache)
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var decodeSize by remember(request) { mutableStateOf(IntSize.Zero) }
    var loadedRequest by remember { mutableStateOf<SvgaRequest?>(null) }
    var resource by remember { mutableStateOf<SvgaResource?>(null) }
    var renderer by remember { mutableStateOf<SvgaRenderer?>(null) }
    var audio by remember { mutableStateOf<SvgaAudioSession?>(null) }
    var drawTime by remember { mutableLongStateOf(0L) }
    val downloadCallback by rememberUpdatedState(onDownloadProgress)
    val readyCallback by rememberUpdatedState(onReady)
    val errorCallback by rememberUpdatedState(onError)
    val finishedCallback by rememberUpdatedState(onFinished)
    val clickCallback by rememberUpdatedState(onLayerClick)
    val drawnOffset = remember { intArrayOf(0, 0) }
    val token = remember { Any() }
    DisposableEffect(state) {
        check(state.owner == null) { "A SvgaState can only drive one SvgaView" }; state.owner = token
        onDispose { state.owner = null; state.clock = null }
    }
    // Only stable growth requests larger images. Shrinking reuses the prepared resource.
    LaunchedEffect(size, request) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val next = IntSize(((size.width + 63) / 64) * 64, ((size.height + 63) / 64) * 64)
        if (next.width > decodeSize.width || next.height > decodeSize.height) {
            if (decodeSize != IntSize.Zero) delay(120)
            decodeSize = IntSize(maxOf(next.width, decodeSize.width), maxOf(next.height, decodeSize.height))
        }
    }
    val width = decodeSize.width
    val height = decodeSize.height
    LaunchedEffect(request, width, height, imageLoader, state) {
        if (width <= 0 || height <= 0) return@LaunchedEffect
        if (loadedRequest != request) {
            resource = null; state.clock = null; state.currentFrame = 0; drawTime = 0L
            state.loadState = SvgaLoadState.LOADING; state.error = null; state.downloadProgress = null
        }
        try {
            resource = imageLoader.load(request.copy(width = request.width.takeIf { it > 0 } ?: width,
                height = request.height.takeIf { it > 0 } ?: height)) { progress ->
                withContext(Dispatchers.Main.immediate) {
                    state.downloadProgress = progress
                    request.onDownloadProgress?.invoke(progress)
                    downloadCallback(progress)
                }
            }
            loadedRequest = request
        } catch (e: CancellationException) { throw e }
        catch (e: Throwable) { state.error = e; state.loadState = SvgaLoadState.ERROR; errorCallback(e) }
    }
    LaunchedEffect(resource, bindings, imageLoader, state, startFrame, endFrame, reverse) {
        val loaded = resource ?: return@LaunchedEffect
        var dynamic: SVGADynamicEntity? = null
        var sound: SvgaAudioSession? = null
        var drawing: SvgaRenderer? = null
        try {
            dynamic = bindings.prepare(imageLoader.imageLoader, context, request.cachePolicy, loaded)
            sound = SvgaAudioSession(context, loaded)
            withContext(Dispatchers.IO) { sound.prepare() }
            drawing = loaded.newRenderer(dynamic)
            dynamic = null
            val preparedScale = contentScale.computeScaleFactor(Size(loaded.width.toFloat(), loaded.height.toFloat()),
                Size(size.width.toFloat(), size.height.toFloat()))
            val preparedWidth = (loaded.width * preparedScale.scaleX).roundToInt().coerceAtLeast(1)
            val preparedHeight = (loaded.height * preparedScale.scaleY).roundToInt().coerceAtLeast(1)
            withContext(Dispatchers.Default) { drawing.prepare(preparedWidth, preparedHeight) }
            renderer = drawing; audio = sound
            if (state.clock == null || state.clock?.frames != loaded.frames || state.clock?.fps != loaded.fps ||
                state.clock?.startFrame != startFrame || state.clock?.endFrame != (endFrame ?: loaded.frames - 1) || state.clock?.reverse != reverse) {
                state.clock = SvgaPlayback(loaded.frames, loaded.fps, startFrame, endFrame ?: loaded.frames - 1, reverse)
                if (autoPlay) state.resume() else state.pause()
            }
            state.loadState = SvgaLoadState.READY; readyCallback()
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (e: Throwable) { state.error = e; state.loadState = SvgaLoadState.ERROR; errorCallback(e) }
        finally {
            if (renderer === drawing) renderer = null
            if (audio === sound) audio = null
            drawing?.close(); sound?.close(); dynamic?.clearDynamicObjects()
        }
    }
    val canPlay = visible && size.width > 0 && size.height > 0
    LaunchedEffect(renderer, canPlay, lifecycle, iterations, state, hiddenBehavior, speed, state.revision) {
        val drawing = renderer ?: return@LaunchedEffect
        if (!canPlay) {
            if (hiddenBehavior != SvgaHiddenBehavior.CONTINUE_TIMELINE) state.clock?.pause()
            if (hiddenBehavior == SvgaHiddenBehavior.STOP) state.pause()
            audio?.pause(); drawing.pause(); return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (isActive) {
                    if (!state.isPlaying) { state.clock?.pause(); audio?.pause(); drawing.pause()
                        snapshotFlow { state.isPlaying }.first { it }
                    }
                    withFrameNanos { now ->
                        val clock = state.clock ?: return@withFrameNanos
                        val done = clock.tick(now, iterations, speed)
                        state.currentFrame = clock.frame
                        state.progress = clock.frame.toFloat() / (clock.frames - 1).coerceAtLeast(1)
                        state.completedIterations = clock.completedIterations
                        if (drawing.hasActiveScrollingText) drawTime = clock.positionNanos
                        audio?.advance(clock.frame, clock.completedIterations, speed, reverse)
                        if (done) { state.pause(); audio?.pause(); finishedCallback() }
                    }
                }
            } finally {
                if (hiddenBehavior != SvgaHiddenBehavior.CONTINUE_TIMELINE) state.clock?.pause()
                if (hiddenBehavior == SvgaHiddenBehavior.STOP) state.pause()
                audio?.pause(); drawing.pause()
            }
        }
    }
    Box(modifier.onSizeChanged { size = it }.clipToBounds().pointerInput(renderer, visible) {
        detectTapGestures { point -> if (visible) renderer?.hitTest(point.x - drawnOffset[0], point.y - drawnOffset[1])?.let { clickCallback?.invoke(it) } }
    }.semantics {
        if (contentDescription != null) this.contentDescription = contentDescription
    }) {
        Canvas(Modifier.matchParentSize()) {
            val loaded = resource
            val drawing = renderer
            state.revision // Seeking invalidates only drawing.
            val frame = state.currentFrame
            val time = if (drawTime > 0) drawTime else state.clock?.positionNanos ?: 0L
            if (visible && loaded != null && drawing != null) {
                val scale = contentScale.computeScaleFactor(Size(loaded.width.toFloat(), loaded.height.toFloat()), this.size)
                val scaled = IntSize((loaded.width * scale.scaleX).roundToInt(), (loaded.height * scale.scaleY).roundToInt())
                val offset = alignment.align(scaled, IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), direction)
                drawnOffset[0] = offset.x; drawnOffset[1] = offset.y
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas; val save = native.save()
                    try { native.translate(offset.x.toFloat(), offset.y.toFloat()); drawing.draw(native, scaled.width, scaled.height, frame, time) }
                    finally { native.restoreToCount(save) }
                }
            }
        }
        when (state.loadState) {
            SvgaLoadState.LOADING -> placeholder()
            SvgaLoadState.ERROR -> state.error?.let { error(it) }
            else -> Unit
        }
    }
}
