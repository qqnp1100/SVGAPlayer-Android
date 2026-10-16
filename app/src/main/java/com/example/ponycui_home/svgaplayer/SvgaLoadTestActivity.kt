package com.example.ponycui_home.svgaplayer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.opensource.svgaplayer.SvgaResource
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.coil3.SvgaBindings
import com.opensource.svgaplayer.coil3.SvgaViewHandle
import com.opensource.svgaplayer.coil3.clearSvga
import com.opensource.svgaplayer.coil3.loadSvga
import java.util.Locale

/** A manual comparison screen: form edits are drafts until Load is pressed. */
class SvgaLoadTestActivity : AppCompatActivity() {
    private val preferences by lazy { getSharedPreferences("svga_load_test", Context.MODE_PRIVATE) }
    private val inputs = linkedMapOf<LoadTestField, View>()
    private lateinit var form: LinearLayout
    private lateinit var formScroll: ScrollView
    private lateinit var preview: SVGAImageView
    private lateinit var status: TextView
    private lateinit var currentResult: TextView
    private lateinit var previousResult: TextView
    private lateinit var progress: ProgressBar
    private val playbackButtons = mutableListOf<Button>()
    private var handle: SvgaViewHandle? = null
    private var generation = 0
    private var completedResult: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SVGA 加载配置测试"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val root = column().apply { setPadding(dp(12), 0, dp(12), dp(8)); setBackgroundColor(Color.WHITE) }
        form = column()
        formScroll = ScrollView(this).apply { isFillViewport = true; addView(form) }
        root.addView(formScroll, LinearLayout.LayoutParams(-1, 0, 1.1f))
        buildForm(SvgaLoadTestConfig.read(preferences))

        val actions = row()
        actions.addView(Button(this).apply { text = "加载 SVGA"; tag = "load"; setOnClickListener { load() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Button(this).apply {
            text = "恢复默认"
            setOnClickListener {
                val defaults = SvgaLoadTestConfig(emptyMap())
                defaults.save(preferences)
                buildForm(defaults)
                formScroll.scrollTo(0, 0)
            }
        }, LinearLayout.LayoutParams(dp(112), dp(48)))
        root.addView(actions)
        status = text("等待加载", 13f).apply { tag = "load_status"; maxLines = 2 }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.INVISIBLE }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))

        val results = column()
        currentResult = text("加载后显示耗时、素材信息和应用参数；结果区可上下滚动。\n共享资源估算不含播放 Bitmap 池，不代表进程峰值内存。", 12f).apply { tag = "current_result"; setTextIsSelectable(true) }
        previousResult = text("", 12f).apply { tag = "previous_result"; setTextColor(Color.DKGRAY); setTextIsSelectable(true) }
        results.addView(currentResult)
        results.addView(previousResult)
        root.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(-1, dp(96)))
        preview = SVGAImageView(this).apply {
            tag = "svga_preview"
            contentDescription = "SVGA 播放展示"
            setBackgroundColor(0xffe9edf2.toInt())
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = row()
        listOf<Pair<String, () -> Unit>>(
            "暂停" to { handle?.pause(); status.text = "已暂停" },
            "继续" to { handle?.resume(); status.text = "播放中" },
            "重播" to { handle?.replay(); status.text = "播放中" },
        ).forEach { (label, action) ->
            val button = Button(this).apply { text = label; isEnabled = false; setOnClickListener { action() } }
            playbackButtons.add(button)
            controls.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        root.addView(controls)
        setContentView(root)
    }

    private fun buildForm(config: SvgaLoadTestConfig) {
        inputs.clear()
        form.removeAllViews()
        form.addView(text("修改自动保存；点击加载后应用。配置区可上下滚动。", 12f))
        LoadTestField.entries.forEach { field ->
            when (field) {
                LoadTestField.URL -> section("地址与解码")
                LoadTestField.CACHE_POLICY -> section("缓存与网络")
                LoadTestField.ITERATIONS -> section("播放参数")
                else -> Unit
            }
            val input: View = when {
                field.toggle -> CheckBox(this).apply {
                    text = field.label
                    textSize = 14f
                    isChecked = config[field] == "true"
                    setOnCheckedChangeListener { _, _ -> saveDraft() }
                }
                field.choices.isNotEmpty() -> Spinner(this).apply {
                    adapter = ArrayAdapter(this@SvgaLoadTestActivity, android.R.layout.simple_spinner_item, field.choices).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    setSelection(field.choices.indexOf(config[field]).coerceAtLeast(0))
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { saveDraft() }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }
                else -> EditText(this).apply {
                    textSize = 14f
                    inputType = when (field) {
                        LoadTestField.URL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        LoadTestField.HEADERS -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        LoadTestField.NAMESPACE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        LoadTestField.SPEED -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                        else -> InputType.TYPE_CLASS_NUMBER
                    }
                    setSingleLine(field != LoadTestField.HEADERS)
                    if (field == LoadTestField.HEADERS) { minLines = 2; maxLines = 4; hint = "Authorization: Bearer …" }
                    setText(config[field])
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { saveDraft() }
                        override fun afterTextChanged(s: Editable?) {}
                    })
                }
            }
            input.tag = field.key
            inputs[field] = input
            if (field.toggle) form.addView(input, LinearLayout.LayoutParams(-1, -2))
            else if (field == LoadTestField.URL || field == LoadTestField.HEADERS) {
                val label = text(field.label, 13f)
                input.id = View.generateViewId()
                label.labelFor = input.id
                form.addView(label)
                form.addView(input, LinearLayout.LayoutParams(-1, -2))
            } else {
                val fieldRow = row()
                val label = text(field.label, 13f)
                input.id = View.generateViewId()
                label.labelFor = input.id
                fieldRow.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                fieldRow.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
                form.addView(fieldRow)
            }
        }
        form.addView(text("inBitmap 需播放次数=1 且关闭静态首帧。RGB_565 的透明图片仍保留透明通道。\n缓存覆盖项“继承”遵循缓存策略；内存读写覆盖优先于强缓存开关。", 12f))
    }

    private fun draft() = SvgaLoadTestConfig(inputs.mapValues { (_, view) ->
        when (view) {
            is CheckBox -> view.isChecked.toString()
            is Spinner -> view.selectedItem.toString()
            is EditText -> view.text.toString()
            else -> error("Unsupported input")
        }
    })

    private fun saveDraft() { if (inputs.isNotEmpty()) draft().save(preferences) }

    private fun load() {
        val config = draft().also { it.save(preferences) }
        inputs.values.filterIsInstance<EditText>().forEach { it.error = null }
        val run = try { config.validate() } catch (e: LoadTestValidationException) {
            status.text = "配置错误：${e.message}"
            val field = inputs.getValue(e.field)
            (field as? EditText)?.error = e.message
            field.requestFocus()
            field.post { field.requestRectangleOnScreen(android.graphics.Rect(0, 0, field.width, field.height)) }
            return
        }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(preview.windowToken, 0)
        currentFocus?.clearFocus()
        val token = ++generation
        preview.clearSvga()
        handle = null
        playbackButtons.forEach { it.isEnabled = false }
        preview.scaleType = run.scaleType
        previousResult.text = completedResult?.let { "上次：$it" } ?: ""
        currentResult.text = "本次：${run.summary(preview.width, preview.height)}"
        status.text = "加载中…"
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        val started = SystemClock.elapsedRealtime()
        var metadata = ""
        var appliedSummary = run.summary(preview.width, preview.height)
        val options = run.options
        handle = preview.loadSvga(run.request) {
            reuseOnRebind = false
            requestFactory = { width, height ->
                appliedSummary = run.summary(width, height)
                run.requestForViewport(width, height)
            }
            iterations = options.iterations
            autoPlay = options.autoPlay
            staticImage = options.staticImage
            inBitmap = options.inBitmap
            speed = options.speed
            startFrame = options.startFrame
            endFrame = options.endFrame
            reverse = options.reverse
            hiddenBehavior = options.hiddenBehavior
            bindingsFactory = { resource ->
                metadata = resourceDescription(resource)
                SvgaBindings.Empty
            }
            onDownloadProgress = { download ->
                if (token == generation) {
                    val fraction = download.fraction
                    progress.isIndeterminate = fraction == null
                    progress.progress = ((fraction ?: 0f) * 1000).toInt()
                    status.text = "下载${if (download.completed) "完成，准备播放" else "中"}：${formatBytes(download.bytesRead)}" +
                        (download.totalBytes?.let { " / ${formatBytes(it)}" } ?: "")
                }
            }
            onReady = {
                if (token == generation) {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    status.text = "加载成功 · ${elapsed}ms · ${if (options.staticImage) "静态首帧" else if (options.autoPlay) "播放中" else "等待播放"}"
                    progress.visibility = View.INVISIBLE
                    val result = "${elapsed}ms · $metadata\n$appliedSummary"
                    currentResult.text = "本次：$result"
                    completedResult = result
                    playbackButtons.forEach { it.isEnabled = !options.staticImage }
                }
            }
            onError = { error ->
                if (token == generation) {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val message = "加载失败 · ${elapsed}ms · ${error.message ?: error.javaClass.simpleName}"
                    status.text = message
                    progress.visibility = View.INVISIBLE
                    currentResult.text = "$message\n$appliedSummary"
                    completedResult = currentResult.text.toString()
                    playbackButtons.forEach { it.isEnabled = false }
                }
            }
            onFinished = { if (token == generation) status.text = "播放结束" }
        }
    }

    private fun resourceDescription(resource: SvgaResource) =
        "${resource.width}×${resource.height} · ${resource.frames}帧 / ${resource.fps}fps · 共享资源约 ${formatBytes(resource.sizeBytes)}（不含播放 Bitmap 池；非进程峰值）"

    override fun onPause() { saveDraft(); super.onPause() }

    override fun onDestroy() {
        generation++
        if (::preview.isInitialized) preview.clearSvga()
        handle = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(0xff263238.toInt()); setPadding(0, dp(3), 0, dp(3)) }
    private fun section(value: String) { form.addView(text(value, 16f).apply { setTypeface(null, Typeface.BOLD); setPadding(0, dp(10), 0, dp(6)) }) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun formatBytes(bytes: Long) = if (bytes >= 1024 * 1024) String.format(Locale.US, "%.2f MiB", bytes / (1024.0 * 1024)) else String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
}
