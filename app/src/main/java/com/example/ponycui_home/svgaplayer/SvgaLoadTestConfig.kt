package com.example.ponycui_home.svgaplayer

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.widget.ImageView
import com.opensource.svgaplayer.SvgaHiddenBehavior
import com.opensource.svgaplayer.coil3.SvgaViewOptions
import com.opensource.svgaplayer.loader.SvgaCachePolicy
import com.opensource.svgaplayer.loader.SvgaRequest
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class LoadTestField(
    val key: String,
    val label: String,
    val default: String,
    val choices: List<String> = emptyList(),
    val toggle: Boolean = false,
) {
    URL("url", "SVGA 网络地址", "https://raw.githubusercontent.com/yyued/SVGA-Samples/master/posche.svga"),
    BITMAP("bitmapConfig", "Bitmap 格式", "ARGB_8888", listOf("ARGB_8888", "RGB_565")),
    SKIP_INVISIBLE("skipInvisibleImages", "跳过不可见图片", "false", toggle = true),
    IN_BITMAP("inBitmap", "inBitmap（仅单次播放生效）", "false", toggle = true),
    WIDTH("width", "解码宽度 px（0 自动）", "0"),
    HEIGHT("height", "解码高度 px（0 自动）", "0"),
    SCALE("scaleType", "显示缩放（CENTER 按原图解码）", "FIT_CENTER", ImageView.ScaleType.values().map { it.name }),
    CACHE_POLICY("cachePolicy", "缓存策略", "ALL", SvgaCachePolicy.entries.map { it.name }),
    MEMORY_CACHE("memoryCache", "强内存缓存", "继承", listOf("继承", "开启", "关闭")),
    WEAK_MEMORY("weakMemoryCache", "弱内存缓存", "继承", listOf("继承", "开启", "关闭")),
    MEMORY_READ("memoryRead", "内存读取覆盖", "继承", listOf("继承", "开启", "关闭")),
    MEMORY_WRITE("memoryWrite", "内存写入覆盖", "继承", listOf("继承", "开启", "关闭")),
    DISK_READ("diskRead", "磁盘读取覆盖", "继承", listOf("继承", "开启", "关闭")),
    DISK_WRITE("diskWrite", "磁盘写入覆盖", "继承", listOf("继承", "开启", "关闭")),
    CACHE_ONLY("cacheOnly", "仅从缓存加载", "false", toggle = true),
    REFRESH("refresh", "强制刷新", "false", toggle = true),
    STALE("allowStaleOnError", "网络失败允许过期缓存", "false", toggle = true),
    RESUME("resumeDownloads", "断点续传", "true", toggle = true),
    NAMESPACE("namespace", "缓存 namespace", "default"),
    HEADERS("headers", "请求头（每行 名称: 值）", ""),
    ITERATIONS("iterations", "播放次数（0 无限循环）", "0"),
    AUTO_PLAY("autoPlay", "加载后自动播放", "true", toggle = true),
    STATIC_IMAGE("staticImage", "仅显示静态首帧（忽略播放参数）", "false", toggle = true),
    SPEED("speed", "播放速度（大于 0）", "1"),
    START_FRAME("startFrame", "起始帧（从 0 开始）", "0"),
    END_FRAME("endFrame", "结束帧（留空为末帧）", ""),
    REVERSE("reverse", "倒放", "false", toggle = true),
    HIDDEN("hiddenBehavior", "不可见时行为", "PAUSE", SvgaHiddenBehavior.entries.map { it.name }),
}

internal class LoadTestValidationException(val field: LoadTestField, message: String) : IllegalArgumentException(message)

/** Raw draft values are retained even while the user is entering an invalid number or URL. */
internal class SvgaLoadTestConfig(private val values: Map<LoadTestField, String>) {
    operator fun get(field: LoadTestField): String = values[field] ?: field.default

    fun save(preferences: SharedPreferences) {
        preferences.edit().apply {
            LoadTestField.entries.forEach { putString(it.key, this@SvgaLoadTestConfig[it]) }
        }.apply()
    }

    fun validate(): LoadTestRun {
        fun invalid(field: LoadTestField, message: String): Nothing = throw LoadTestValidationException(field, message)
        fun integer(field: LoadTestField): Int = this[field].trim().toIntOrNull()?.takeIf { it >= 0 }
            ?: invalid(field, "${field.label}必须是非负整数")
        fun flag(field: LoadTestField) = this[field] == "true"
        fun cacheOverride(field: LoadTestField): Boolean? = when (this[field]) { "开启" -> true; "关闭" -> false; else -> null }

        val url = this[LoadTestField.URL].trim().toHttpUrlOrNull()
            ?: invalid(LoadTestField.URL, "请输入有效的 HTTP(S) 网络地址")
        val cacheOnly = flag(LoadTestField.CACHE_ONLY)
        val refresh = flag(LoadTestField.REFRESH)
        if (cacheOnly && refresh) invalid(LoadTestField.REFRESH, "仅缓存与强制刷新不能同时开启")
        val speed = this[LoadTestField.SPEED].trim().toFloatOrNull()?.takeIf { it.isFinite() && it > 0 }
            ?: invalid(LoadTestField.SPEED, "播放速度必须是大于 0 的有限数字")
        val start = integer(LoadTestField.START_FRAME)
        val end = this[LoadTestField.END_FRAME].takeIf { it.isNotBlank() }?.let { integer(LoadTestField.END_FRAME) }
        if (end != null && end < start) invalid(LoadTestField.END_FRAME, "结束帧不能小于起始帧")
        val headers = linkedMapOf<String, String>()
        this[LoadTestField.HEADERS].lineSequence().filter { it.isNotBlank() }.forEachIndexed { index, line ->
            val separator = line.indexOf(':')
            if (separator <= 0) invalid(LoadTestField.HEADERS, "请求头第 ${index + 1} 行应为 名称: 值")
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            try { Headers.Builder().add(name, value) } catch (_: IllegalArgumentException) {
                invalid(LoadTestField.HEADERS, "请求头第 ${index + 1} 行包含非法字符")
            }
            if (headers.keys.any { it.equals(name, ignoreCase = true) })
                invalid(LoadTestField.HEADERS, "请求头名称不能重复：$name")
            headers[name] = value
        }
        val options = SvgaViewOptions().apply {
            reuseOnRebind = false // Each button press must execute the loader, including warm-cache runs.
            iterations = integer(LoadTestField.ITERATIONS)
            autoPlay = flag(LoadTestField.AUTO_PLAY)
            staticImage = flag(LoadTestField.STATIC_IMAGE)
            inBitmap = flag(LoadTestField.IN_BITMAP)
            this.speed = speed
            startFrame = start
            endFrame = end
            reverse = flag(LoadTestField.REVERSE)
            hiddenBehavior = SvgaHiddenBehavior.valueOf(this@SvgaLoadTestConfig[LoadTestField.HIDDEN])
        }
        val request = SvgaRequest(url.toString()).copy(
            width = integer(LoadTestField.WIDTH), height = integer(LoadTestField.HEIGHT),
            cachePolicy = SvgaCachePolicy.valueOf(this[LoadTestField.CACHE_POLICY]),
            namespace = this[LoadTestField.NAMESPACE].trim().ifEmpty { "default" },
            headers = headers.toMap(), cacheOnly = cacheOnly, refresh = refresh,
            memoryCache = cacheOverride(LoadTestField.MEMORY_CACHE), weakMemoryCache = cacheOverride(LoadTestField.WEAK_MEMORY),
            memoryRead = cacheOverride(LoadTestField.MEMORY_READ), memoryWrite = cacheOverride(LoadTestField.MEMORY_WRITE),
            diskRead = cacheOverride(LoadTestField.DISK_READ), diskWrite = cacheOverride(LoadTestField.DISK_WRITE),
            allowStaleOnError = flag(LoadTestField.STALE), resumeDownloads = flag(LoadTestField.RESUME),
            bitmapConfig = Bitmap.Config.valueOf(this[LoadTestField.BITMAP]),
            skipInvisibleImages = flag(LoadTestField.SKIP_INVISIBLE),
        )
        return LoadTestRun(request, options, ImageView.ScaleType.valueOf(this[LoadTestField.SCALE]))
    }

    companion object {
        fun read(preferences: SharedPreferences) = SvgaLoadTestConfig(LoadTestField.entries.associateWith { field ->
            val value = preferences.getString(field.key, field.default) ?: field.default
            if (field.choices.isNotEmpty() && value !in field.choices) field.default else value
        })
    }
}

internal data class LoadTestRun(val request: SvgaRequest, val options: SvgaViewOptions, val scaleType: ImageView.ScaleType) {
    fun requestForViewport(width: Int, height: Int): SvgaRequest = request.copy(
        width = if (scaleType == ImageView.ScaleType.CENTER) 0 else request.width.takeIf { it > 0 } ?: ((width + 63) / 64) * 64,
        height = if (scaleType == ImageView.ScaleType.CENTER) 0 else request.height.takeIf { it > 0 } ?: ((height + 63) / 64) * 64,
    )

    fun summary(width: Int, height: Int): String = buildString {
        val effective = requestForViewport(width, height)
        val size = if (scaleType == ImageView.ScaleType.CENTER) "原图尺寸" else "${effective.width}×${effective.height}px"
        append("${request.bitmapConfig} · $size · $scaleType · ${request.cachePolicy}")
        append("\n强缓存读/写=${request.readsMemory}/${request.writesMemory} · 弱缓存=${request.usesWeakMemory}")
        append("\n磁盘读/写=${request.readsDisk}/${request.writesDisk} · 仅缓存=${request.cacheOnly} · 刷新=${request.refresh}")
        append("\n跳过不可见=${request.skipInvisibleImages} · inBitmap=${options.inBitmap && options.iterations == 1 && !options.staticImage}")
        append(" · ${if (options.staticImage) "静态首帧" else "${options.speed}倍 / ${options.iterations}次（0无限）"}")
    }
}
