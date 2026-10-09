# SVGA 加载与 Compose 接入

该版本把下载、缓存、解码和播放实例分开。Android View 和原生 Compose 默认共用进程内的 `SvgaEngine`；同来源、兼容缓存策略的请求合并下载，同尺寸的资源准备进一步合并。Compose 直接绘制 Canvas，通过 `withFrameNanos` 驱动，不创建 Android View 或 Drawable。

## Bitmap 解码与内存

基础图片支持进程级默认配置和单次加载覆盖。建议在 Application 初始化时设置全局默认：

```kotlin
SvgaDecodeOptions.defaults = SvgaDecodeOptions(
    bitmapConfig = Bitmap.Config.RGB_565,
    skipInvisibleImages = true,
)
```

默认值为 `ARGB_8888`、`skipInvisibleImages = false`。配置对象不可变，整体赋值是线程安全的；每次加载捕获一次配置，修改全局默认不修改已经解码的资源。Compose 在下一次重组时读取新默认。

```kotlin
// 单次请求覆盖；null 继承全局默认。
val request = SvgaRequest(url).copy(
    bitmapConfig = Bitmap.Config.ARGB_8888,
    skipInvisibleImages = false,
)
loader.load(request)

// View：优先级为 View 显式配置 > SvgaRequest 配置 > 全局默认。
svgaView.loadSvga(url) {
    bitmapConfig = Bitmap.Config.RGB_565
    skipInvisibleImages = true
}

// Compose 使用相同优先级。
SvgaView(url, bitmapConfig = Bitmap.Config.ARGB_8888, skipInvisibleImages = true)
```

颜色配置支持 `ARGB_8888`、`RGB_565` 两种首选格式。`RGB_565` 只适用于无需透明通道的图片，会降低颜色精度；带透明通道的图片保留透明度，不能保证内存减半。其他格式会报参数错误。基础图片始终使用软件 Bitmap，以兼容遮罩绘制；业务动态图片仍由对应图片加载器决定格式。这些配置均进入强缓存、弱缓存和解码合并的身份；不同配置仍共享兼容的下载和磁盘缓存。

图片采样按图层 layout、旋转/镜像/缩放矩阵，以及实际请求像素尺寸计算，合并同一图片所有可见帧和图层的最大需求。缩小变换不会再被固定为至少 1 倍。Android 28+ 使用 ImageDecoder 指定输出尺寸，旧系统使用 BitmapFactory 采样与密度缩放，避免只按 2 的幂采样造成的尺寸浪费。未知请求尺寸保留原图，不放大原图；非等比视口保守采用较大缩放比例。View 的 CENTER、Compose 的 None 和自定义 ContentScale 保留原图尺寸。

开启 `skipInvisibleImages` 后，未使用图片、普通图层全程 alpha <= 0 的图片会跳过解码。判断覆盖素材整个时间轴，不因 `startFrame/endFrame`、动态 hidden 或替换图片裁剪资源，保持 seek、倒放、重播和动态填充的能力；matte 图片保守保留。过滤不会删除图层元数据或改变帧数。它减少解码分配和资源常驻像素，不是播放结束自动释放或按帧延迟加载。

旧 Parser 产生的实体也使用全局默认；在 `onComplete` 中、交给 View 之前可设置该实体的配置：

```kotlin
videoItem.decodeOptions = SvgaDecodeOptions.defaults.copy(
    bitmapConfig = Bitmap.Config.ARGB_8888,
    skipInvisibleImages = false,
)
svgaView.setVideoItem(videoItem)
```

旧 Parser 的内置解码器使用相同尺寸优化。若业务通过 `SVGAParser.setBitmapDecoder` 接管解码，颜色与尺寸仍由业务解码器决定，库只应用不可见图片过滤。直接调用 `SvgaResource.decode` 可传 `decodeOptions`。基础资源像素预算仍先保守估算，再核对实际 `allocationByteCount`。

## 模块

| 模块 | JitPack artifactId | 内容 |
| --- | --- | --- |
| `library` | `svga-core` | 旧 API、只读资源、渲染器、播放时钟、实例音频 |
| `svga-loader` | `svga-loader` | URL / File / Assets、并发去重、缓存、取消、预加载 |
| `svga-coil3` | `svga-coil3` | Coil 3.3.0 Fetcher、独立 ImageLoader、View 扩展、填充 |
| `svga-glide5` | `svga-glide5` | Glide 5.0.7 ModelLoader、View 扩展、动态图片填充 |
| `svga-compose` | `svga-compose` | 原生 Compose 组件、状态、生命周期与交互 |

3.0.0 通过 JitPack 远程接入。以下以发布 Tag `3.0.0-beta1` 为例，版本号需与实际 Git Tag 完全一致（包括可能的 `v` 前缀）。当前构建使用 Kotlin 2.2.0、AGP 8.12.0、compileSdk 35、minSdk 21；库字节码目标为 Java 17。

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// app/build.gradle.kts，一次引入全部模块
implementation("com.github.qqnp1100:SVGAPlayer-Android:3.0.0-beta1")
```

上述仓库坐标会聚合全部已发布模块，包括 Compose。按需接入时，使用 [JitPack 多模块坐标](https://docs.jitpack.io/building/#multi-module-projects)，与聚合依赖二选一：

```kotlin
implementation("com.github.qqnp1100.SVGAPlayer-Android:svga-coil3:3.0.0-beta1") // View
// 或
implementation("com.github.qqnp1100.SVGAPlayer-Android:svga-compose:3.0.0-beta1") // Compose
```

仅使用 View 时选择 `svga-coil3` 或 `svga-glide5`，不会引入 Compose；Compose 项目选择 `svga-compose` 并启用宿主的 Compose 编译插件。所需下层模块会自动引入。JitPack 构建并发布对应 Tag 后即可远程解析；本仓库内示例仍使用 `implementation(project(":svga-coil3"))` 或 `implementation(project(":svga-compose"))`。

### Glide 5 接入

```kotlin
implementation("com.github.qqnp1100.SVGAPlayer-Android:svga-glide5:3.0.0-beta1")
// 仓库内使用 implementation(project(":svga-glide5"))。
```

模块使用 Glide 5.0.7，保留 Android 21 支持；Glide 5.0.8/5.0.9 要求 Android 23。

```kotlin
import com.opensource.svgaplayer.glide5.SvgaImageLoader
import com.opensource.svgaplayer.glide5.loadSvga
import com.opensource.svgaplayer.glide5.clearSvga
import com.opensource.svgaplayer.glide5.svgaBindings

val handle = svgaView.loadSvga(giftUrl) {
    iterations = 1
    bindings = svgaBindings {
        text("user_name", "小明")
        image("avatar", avatarUrl, circleCrop = true)
    }
    onReady = { }
    onError = { error -> /* 处理错误 */ }
}
handle.pause()
handle.resume()
svgaView.clearSvga()

// 在协程中加载或预下载。
val loader = SvgaImageLoader.get(context)
val resource = loader.load(SvgaRequest(giftUrl))
loader.preDownload(SvgaRequest(giftUrl), parseAfterDownload = false)
```

Glide View 入口提供与 Coil View 入口同名的选项和 handle，包含静态首帧、动态填充更新、下载进度、取消、detach 清理和重新 attach。按所用模块导入对应包，避免同名扩展冲突。Compose 入口继续使用 `svga-compose`。

SVGA 请求通过 Glide ModelLoader 进入共享 `SvgaEngine`，遵守 `SvgaRequest` 的缓存、请求头、尺寸与解码选项；每次订阅独立取消和接收进度。SVGA 外层 Glide 缓存关闭，缓存与请求合并由引擎负责。普通动态图片使用宿主的 Glide 配置，无需额外声明 AppGlideModule 或注解处理器；库仅注册自己的模型类型。

动态图片按图层实际区域请求，保持来源比例，最长边上限 1024 px，`size` 可指定更小上限，支持圆形裁剪与可选失败。Glide 结果复制为实例独占的软件 Bitmap，替换绑定或释放实例时回收；业务直接传入的 Bitmap 仍为借用。`loader.close()` 取消该加载器的加载和预下载，不关闭共享引擎或宿主 Glide。

## View

```kotlin
val handle = svgaView.loadSvga(giftUrl) {
    iterations = 1 // 0 表示无限循环
    bindings = svgaBindings {
        text("user_name", "小明", textSizeSp = 16f)
        image("avatar", avatarUrl, circleCrop = true)
        hidden("debug_layer")
    }
    onReady = { /* 基础图片、填充和音频已准备 */ }
    onFinished = { /* 自然结束 */ }
    onError = { error -> /* 处理错误 */ }
}

handle.pause()
handle.resume()
handle.seekToProgress(.5f)
handle.replay()
handle.updateBindings(svgaBindings { text("user_name", "小红") })
handle.cancel()
```

`updateBindings` 替换该实例的整个填充快照，不重新请求基础资源。新快照准备完成后替换显示，旧请求取消；过期更新不能覆盖新结果。Bitmap 参数为借用，库不回收业务传入的 Bitmap。URL 图片由私有 Coil ImageLoader 加载；基础 SVGA 仍由引擎下载，二者不会重复下载 SVGA 文件。

Coil 动态图片默认按图层实际显示区域请求解码尺寸：合并同 key 所有帧和图层的宽高、缩放/旋转，再应用 View 的 ScaleType 或 Compose 的 ContentScale。普通图片根据原图比例和 EXIF 方向计算覆盖区域两个方向所需的像素，避免宽高比不同时先缩得过小、再被图层拉伸；圆形图片以区域最长边作为直径，居中裁剪结果由 Coil 缓存。解码最长边限制为 1024 px，`size` 可指定更小的上限，超出上限时保留原图比例缩小，不放大低分辨率原图。手动调用 `bindings.prepare` 可传入展示宽高和 ScaleType；不传时按素材坐标计算，找不到图层时回退到 `size`（未指定则为 1 px）。直接传入 Bitmap 的重载保留借用行为。

View API 在主线程调用。不同请求替换时取消并清理会话；相同请求重复绑定会保留播放状态。detach 时立即释放；可用 `restartOnAttach` 配置重新 attach 后自动重载。暂停保留进度，不可见时停止逐帧工作，只保留低频可见性检查。新的 View 适配器用 Choreographer 驱动，滚动文字和音频随同一会话推进。

## 原生 Compose

```kotlin
val state = rememberSvgaState()
val bindings = rememberSvgaBindings(userName, avatarUrl) {
    text("user_name", userName, textSizeSp = 16f)
    image("avatar", avatarUrl, circleCrop = true)
}

SvgaView(
    source = giftUrl,
    modifier = Modifier.size(200.dp),
    state = state,
    bindings = bindings,
    iterations = 1,
    visible = isItemVisible,
    contentScale = ContentScale.Fit,
    alignment = Alignment.Center,
    contentDescription = "礼物动画",
    onLayerClick = { key -> /* 命中最上方的图层 */ },
    onFinished = { },
    onError = { },
    placeholder = { /* 加载占位 */ },
    error = { error -> /* 错误内容 */ },
)
```

支持 Fit、Crop、FillBounds、None 等 ContentScale 和 Alignment。传入布局真实尺寸，按 64 px 分档；尺寸缩小复用现有资源，放大稳定 120 ms 后后台准备更高规格，保留播放进度。新渲染器准备完成前继续显示旧画面，尺寸升级复用音频会话，不重复触发 `onReady`。帧状态在绘制阶段读取，不要求父组件逐帧重组。

`state` 提供 `loadState`、`error`、`isPlaying`、`currentFrame`、`progress`、`completedIterations`，以及 pause / resume / seekToProgress / replay。同一个 state 只能绑定一个组件。换回调不会重新加载；离开组合释放会话，页面进入后台停止帧循环。列表屏幕外可见性由业务传 `visible`。

两种入口都支持 `speed`、`startFrame`、`endFrame`、`reverse`，以及 `SvgaHiddenBehavior`：PAUSE 恢复原进度，CONTINUE_TIMELINE 按经过时间定位，STOP 等待显式恢复。倒放静音；Android 21/22 的非 1 倍速播放静音，Android 23+ 音频跟随正向倍速。

文字支持颜色、sp 字号、滚动速度；Android 23+ 支持 StaticLayout 多行、省略和对齐。Compose 模块另有接受 `Color` 和 `TextUnit` 的 `text` 扩展。图片支持 Bitmap、Coil 支持的来源、必需/可选失败策略和圆形裁剪。填充需使用素材实际图层 key，可通过 `resource.layerKeys` 查看。

## 来源与缓存

```kotlin
SvgaSource.Remote("https://example.com/gift.svga", version = "v2")
SvgaSource.LocalFile(file)
SvgaSource.Asset("gift.svga")
```

字符串只接受 HTTP(S)、`file://` 和 `file:///android_asset/`；普通相对路径请显式使用 Asset。View 和 Compose 的 `source` 也接受完整请求：

```kotlin
val request = SvgaRequest(
    source = SvgaSource.Remote(giftUrl),
    cachePolicy = SvgaCachePolicy.ALL,
    namespace = "account-123",
    headers = mapOf("Authorization" to token),
    // width / height 为 0 时，由展示入口提供实际像素尺寸。
)
```

| 策略 | 内存 | 磁盘 |
| --- | --- | --- |
| NONE | 关闭 | 关闭 |
| MEMORY | 开启 | 关闭 |
| DISK | 关闭 | 开启 |
| ALL | 开启 | 开启 |

`memoryRead` / `memoryWrite` 分别覆盖强引用 LRU 的读写，`diskRead` / `diskWrite` 分别覆盖磁盘读写；弱引用索引由独立的 `weakMemoryCache` 控制。`cacheOnly` 禁止网络，可读取已有缓存；`refresh` 绕过内存快速命中并重新获取来源；`allowStaleOnError` 显式允许网络失败时使用已有磁盘正文，默认关闭。

请求头快照、完整 URL、版本、namespace 纳入来源身份；文件使用规范路径、长度和修改时间。内容 SHA-256 和解码规格区分资源缓存。HTTP 使用 Cache-Control、ETag、Last-Modified、Expires 和 Age，过期条件验证；no-store 不写可复用缓存。没有新鲜度的 HTTP 响应不会无限从内存返回。NONE 允许解压/音频所需临时文件，不持久复用。

引擎默认内存 32 MiB、磁盘 128 MiB、下载并发 4、解码并发 2、单次解码像素预算 128 MiB；可在构造时修改。内存计费包含实际 Bitmap 分配量、音频字节及帧对象估算。提供 `clearMemory()`、挂起的 `clearDisk()`、`preload(request)`（准备完整资源）和诊断计数器。

### 预下载

`preDownload` 默认仅流式下载原始 SVGA 文件到磁盘，不解压、不解析、不解码图片，也不生成或登记解码资源到强缓存或弱索引。下载过程使用小块缓冲，不把整个文件保存在内存中。引擎和 `SvgaImageLoader` 均提供挂起接口：

```kotlin
val engine = SvgaEngine(
    context,
    downloadConcurrency = 4,    // 正常加载与预下载共享的总下载上限
    preDownloadConcurrency = 2, // 预下载发起的独立来源任务上限，默认 2
)
val request = SvgaRequest(giftUrl).copy(cachePolicy = SvgaCachePolicy.DISK)

// 默认只下载并保存原始文件，展示时再解析。
engine.preDownload(request)

// 下载完成后准备资源；DISK 策略仍不缓存解码资源到内存。
engine.preDownload(request, parseAfterDownload = true)

// 如需同时预热资源内存缓存，使用 ALL；尺寸应与展示请求一致。
engine.preDownload(request.copy(cachePolicy = SvgaCachePolicy.ALL), parseAfterDownload = true)

// 使用现有 loader，无需经过 Coil 的图片解码流程。
loader.preDownload(request, parseAfterDownload = false) { progress -> /* 下载进度 */ }
```

下载完成后的解析默认关闭；开启后使用与正常加载相同的解码流程，受 `decodeConcurrency` 限制，内存缓存读写遵循请求的强/弱缓存配置。磁盘始终保存原始文件和 HTTP 元数据，不序列化 Bitmap 或已解码资源；`preload` 保持原有准备完整资源的行为。

同一个引擎内，预下载与正常加载沿用相同来源身份和共享任务。完整 URL、版本、namespace、请求头以及网络/磁盘读写策略兼容时，共用一次下载；不同尺寸或内存缓存配置不额外下载。同解码规格且兼容策略的解析进一步合并。并发额度由实际来源生产任务占用，重复订阅不会占用额外额度；超出上限的任务挂起排队。正常加载新来源不受预下载单独上限约束，但加入已排队的同来源预下载时等待其共享任务。取消一个订阅者不会中断其他订阅者，仅最后一个离开时取消生产任务；符合条件的部分正文仍可用于断点续传。

预下载遵循 `diskRead` / `diskWrite`、磁盘预算和 HTTP 新鲜度规则：禁止磁盘写入、`no-store` 或超出缓存容量时，不保证留存可复用文件；过期文件在正常加载时仍需条件验证，`cacheOnly = true` 可禁止网络。只下载的文件标记为尚未解析验证，首次成功解析后升级标记，解析失败则清理对应未验证缓存，防止后续加载反复命中坏文件。并发的晚到预下载结果不会把已拒绝的正文重新写回缓存。

自定义实例通过 `SvgaImageLoader(context, engine)` 注入两种入口；默认使用共享实例，不覆盖宿主 Coil SingletonImageLoader。基础资源使用有容量上限的强引用 LRU、弱引用索引和 GC 所有权，不放入 Coil 通用内存缓存；会话 clear 和缓存淘汰不会 recycle 仍被另一个实例使用的基础 Bitmap。引擎不保证跨进程下载去重。

## 核心改动

- zlib 限长流式 Protobuf 解码，避免完整解压 ByteArray 的复制；ZIP 在暂存目录完整解压后发布。
- 新链路在后台完成图片采样、路径和首帧文字缓存准备，尺寸来自真实布局。
- 修复旧默认缓存对 zlib 文件的漏命中、ZIP 就绪判断与等待者锁身份问题，去掉缓存命中的重复入队。
- 旧线程池满载时返回错误，不在提交线程执行下载/解析；取消下载会断开连接。
- 按旋转/镜像矩阵统计采样缩放，修复缩放因子在采样比例中抵消；预构建路径无全局可变 Path。
- 复用每帧 sprite 列表，遮罩分组边界不再逐帧分配数组；遮罩缺失也会正确恢复 Canvas。
- 旧 View 修正 FPS 整数截断；新时钟覆盖暂停、seek、跳帧、多循环、倒放和倍速。

复杂 matte 仍复用已有软件遮罩合成算法，未声称更换为 GPU 遮罩或取得固定提速比例。图层点击是逆变换后的布局矩形命中，不是逐像素透明度/路径命中。旧 Parser 保留兼容入口；新业务建议使用统一加载器。

## 验证

```shell
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :library:testDebugUnitTest :svga-loader:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.example.ponycui_home.svgaplayer.test/androidx.test.runner.AndroidJUnitRunner
```

示例首页新增 Native Compose / Coil 3 和 View / Coil 3 两项。真机测试覆盖并发合并、单个订阅者取消、缓存隔离、304、失败重试、ZIP 安全、旧 Parser 缓存、原生与 View 逐像素一致性、音频会话隔离、Compose 生命周期及 View 释放。

2026-10-02 最终验证：15 项 JVM 单元测试和 13 项真机 instrumentation 测试全部通过；四模块 Release AAR、sources JAR、POM 和 Gradle module metadata 已生成。

验证设备为 Samsung SM-A125N。用户提供的 head_bg_vip10 / head_bg_vip9 均为 750×400、24 帧、12 FPS，已在两种入口显示；第一轮联网加载观测约 2.40 s / 1.12 s。该数据包含当时网络和设备状态，不是与旧版本的性能对照。截图和原始运行输出位于被 Git 忽略的 `verification`、`device-tests.log`、`build-check.log`。

## 下载进度

View 与 Compose 都支持 `onDownloadProgress`，在主线程回调；Compose 还可读取 `state.downloadProgress`。原有 `state.progress` 仍然是播放进度。

```kotlin
svgaView.loadSvga(url) {
    onDownloadProgress = { progress ->
        val downloaded = progress.bytesRead
        val percent = progress.fraction?.let { (it * 100).toInt() }
        // percent 为 null 时显示已下载字节数或不定进度条。
    }
}

SvgaView(url, onDownloadProgress = { progress -> /* 更新下载 UI */ })

// 引擎 / Coil 包装器 / 预加载请求也支持回调：
engine.acquire(SvgaRequest(url)) { progress -> /* 下载进度 */ }
loader.load(SvgaRequest(url)) { progress -> /* 下载进度 */ }
engine.preload(SvgaRequest(url).copy(onDownloadProgress = { progress -> /* 下载进度 */ }))
```

`bytesRead` 是已读取的 HTTP 响应体字节数，`totalBytes` 在 Content-Length 未知或透明 gzip 解压时为 null；`fraction` 为 0～1，未知长度或零长度时为 null。`completed` 表示响应体下载完成，资源仍需解码，播放准备完成请使用 `onReady`。失败和取消不产生虚假的下载完成事件；下载完成后解码仍可能失败。

下载期间约每 100ms 更新一次，慢订阅者会合并中间进度。共享同一次下载的订阅者各自收到更新，后来加入的订阅者收到最新进度；取消一个订阅不会影响其他订阅。内存/磁盘缓存命中、HTTP 304、Assets 和本地文件不会产生下载进度。Compose 更换来源会清空下载状态。

底层 `SvgaRequest.onDownloadProgress`、`engine.acquire` 和 `loader.load` 接受 suspend 回调；引擎在调用方协程上下文串行派发，Coil Fetcher 上下文由 Coil 决定。直接使用底层回调更新 UI 时请切换主线程。View/Compose 入口已处理主线程切换。回调异常会使当前订阅加载失败，回调应保持轻量。


## 旧 View 业务迁移

`loader.load(request).newVideoEntity()` 生成互相独立、借用只读资源的展示实体。将其交给旧 `SVGAImageView.setVideoItem` 后，startAnimation / stopAnimation / pauseAnimation / stepToFrame 自动走新版 Choreographer 与 SvgaPlayback 时钟，并拥有独立音频会话。保留 loops、帧区间、倒放、FillMode 和 SVGACallback；不可见或宿主生命周期暂停时停止推进，detach 取消准备并释放音频。动态图片及首帧绘制缓存准备后才开始播放。旧 Parser 产生的实体仍保留原播放链路。

应用启动时可设置 `SVGAImageView.sourceLoader`，把 XML 的 `source` 转给统一业务请求入口；回调收到 View、来源和 autoPlay。`SvgaBindings.Builder.text` 另支持 typeface 与 scrollSpacing，迁移定制文字时无需丢失字体和滚动间距。

`SvgaPlayback.seekFrame` 用整数纳秒精确定位，避免帧号经浮点百分比换算落到前一帧。

## 中断与断点续传

现代加载链路（`SvgaEngine`、Coil 3、View 扩展、原生 Compose）默认启用 `resumeDownloads = true`。下载中断、最后一个订阅者取消或引擎关闭时，会保留符合条件的已下载片段。下次加载同一来源时，包括引擎或应用重建后，会携带 `Range: bytes=<已下载长度>-` 和 `If-Range: <强 ETag>` 请求剩余部分。一个共享订阅者取消时，其他订阅者的下载继续。

```kotlin
// 默认开启，View 和 Compose 可直接传入 URL，无需另外配置。
val request = SvgaRequest(url)
engine.acquire(request) { progress ->
    // 续传时 bytesRead 包含之前保存的字节，totalBytes 是整个文件的长度。
}

// 按请求关闭续传，或强制从头刷新。
engine.acquire(request.copy(resumeDownloads = false))
engine.acquire(request.copy(refresh = true))

// 同时清除完整磁盘缓存和续传片段（会等待正在写片段的下载释放文件）。
engine.clearDisk()
```

片段写入遵守 `diskWrite`，复用遵守 `diskRead`；`NONE`、`MEMORY` 缓存策略不会保留片段。`cacheOnly` 不把不完整片段视为可用资源。响应为 `no-store` / `Vary: *`、缺少强 ETag（包括只有弱 ETag 或 Last-Modified）、非 identity 编码时不保留片段，下一次从头下载。自动续传请求使用 `Accept-Encoding: identity`，避免压缩响应与本地字节偏移不一致。手动提供 Range / If-Range 或非 identity Accept-Encoding 时不启用自动续传。

服务端返回 206 时校验偏移、完整长度、ETag、最终 URL 和响应编码；不匹配的 206、416 或异常 304 会丢弃片段并从头重试一次。服务端忽略 Range 或资源更新而返回 200 时，直接用完整新响应替换旧片段，不拼接。此处理遵循 [HTTP Range / If-Range 语义](https://www.rfc-editor.org/rfc/rfc9110.html#name-if-range)。

片段按 URL/资源版本、namespace 和请求头隔离，保存在应用私有缓存 `svga-engine/partials`，不会进入正式缓存或直接交给解码器。下载完成后移动为独立文件，成功解码后才发布完整缓存。片段最长保留 24 小时；独立片段池预算为 `min(diskBytes, 64 MiB)`，清理跳过活动写入，下载结束后再次清理。系统清理缓存或片段过期后会正常从头下载。旧 `SVGAParser` 下载接口不使用此现代引擎。
## 应用直接使用 loadSvga

View 请求现在保存在 View 的库 tag 中。默认 detach 后关闭；开启 `restartOnAttach` 后，detach 取消当前任务并清理展示，重新 attach 时由库重新加载保留的请求。`clearSvga()` 同时清除保留请求，之后 attach 不会复活旧内容。

迁移既有动态实体业务时，可设置 `useViewControls = true` 和 `onResourceReady`：回调在主线程收到独立 `SVGAVideoEntity`，返回 true 表示业务已接管展示；返回 false 时库释放该实体。未提供回调时库自动 setVideoItem，并依据 autoPlay 调用资源版播放控制。`requestFactory` 在后台接收布局像素尺寸并生成 SvgaRequest，供业务解析资源包路径等来源。下载、缓存、替换、取消和 attach 生命周期仍全部由库处理。

## 弱引用索引与强引用内存缓存

同来源、同网络和磁盘策略的并发请求，即使强/弱内存缓存开关不同，也共享下载；同尺寸进一步共享解码。各订阅者独立执行内存缓存读写策略。`clear()` 会断开展示实体对共享资源的引用，不回收其他实例仍在使用的基础 Bitmap。清空引擎缓存后，没有其他持有者的基础资源交由 GC 回收。

`updateBindings()` 会替换当前请求保留的填充快照，重新 attach 使用最新快照。最终 `cancel()` / `close()` 会释放 handle 对 View、请求配置和回调的引用；借用的业务 Bitmap 不会被库 recycle。解析或动态图片加载在 clear 后才完成时，不再把过期结果写回已清理的实体。

默认请求同时开启强引用 LRU 和弱引用资源索引。弱索引不设置资源字节容量上限，不持有资源强引用；播放器或业务仍持有资源时，即使该资源已被 LRU 淘汰（包括资源超过 LRU 容量），后续相同身份、版本和解码规格的请求仍可复用它。

```kotlin
// 每次加载独立控制：只用弱缓存，不写入或读取强引用 LRU。
val request = SvgaRequest(url).copy(
    memoryCache = false,
    weakMemoryCache = true,
)
loader.load(request) // engine.acquire / preload 同样适用

// View
svgaView.loadSvga(url) {
    memoryCache = false
    weakMemoryCache = true
}

// 原生 Compose
SvgaView(url, memoryCache = false, weakMemoryCache = true)

// 同时关闭两种资源内存缓存，仍可使用磁盘缓存。
loader.load(SvgaRequest(url).copy(memoryCache = false, weakMemoryCache = false))

// 引擎级总开关：关闭后，该引擎内任何请求都不会使用弱索引。
val engine = SvgaEngine(context, weakMemoryCacheEnabled = false)
```

两个请求开关均为 nullable：null 继承 `cachePolicy.memory`。默认 ALL（以及 MEMORY）启用两层，NONE / DISK 默认关闭两层；显式 true / false 可独立覆盖。例如 `memoryCache = false` 不会关闭默认弱缓存。既有 `memoryRead` / `memoryWrite` 优先于 `memoryCache`，仅控制强缓存读写；若要禁用所有资源内存缓存，还需设置 `weakMemoryCache = false`。View/Compose 未设置开关时保留传入 SvgaRequest 的配置；设置后覆盖对应字段。

`loadSvga` 可为单次播放启用按需图片解码，默认 `inBitmap = false`：

```kotlin
svgaView.loadSvga(url) {
    iterations = 1
    inBitmap = true
}
```

开启后按可见图层/帧引用总次数分类：同一图片被多个帧或图层引用时仍预解码；只有一次可见引用的图片保留压缩数据，播放到对应帧时在后台解码。按实际解码宽、高统计这些单次图片：同分辨率至少有两张时，使用 `BitmapFactory.Options.inBitmap` 复用已退出绘制的可变 Bitmap；同分辨率只有一张时，仅使用普通解码器按需解码，不取用也不进入复用池。不可见且未使用的图片不解码，遮罩引用保守计入。每个展示实例拥有独立的像素和有容量限制的复用池，压缩数据计入共享资源缓存大小。首个展示帧需要的图片准备完成后才启动播放；后续帧等待解码时暂停播放时钟与音频，不在主线程等待解码。

仅 `iterations = 1` 且非 `staticImage` 时生效，循环及静态展示保持全量预解码。使用 `useViewControls = true` 时以加载前的 `svgaView.loops = 1` 为准。Compose `SvgaView` 始终使用全量预解码，即使传入的 `SvgaRequest` 设置了 `inBitmap = true`，以免在绘制阶段等待解码。开关参与资源缓存和 View 重绑定身份，默认资源与按需资源不会混用。跳转或再次播放会重新解码已被覆盖的单次图片；保留最后显示帧，直到展示被替换或清理。Android 28+ 参与复用的单次图片使用 BitmapFactory，分辨率唯一的单次图片及重复图片继续使用原有解码器。

查询顺序是强 LRU → 弱索引 → 磁盘/网络；弱命中允许按当前请求配置提升到强 LRU。弱索引与强缓存都检查来源、尺寸和新鲜度，refresh 绕过并失效两层，no-store 不登记可复用资源。`clearMemory()` 清空两层索引，不回收播放实例正在使用的资源。`memoryHits` 记录强命中，`weakMemoryHits` 单独记录弱命中。

通过 `ReferenceQueue` 在缓存操作时清理已回收资源对应的 key 和引用；队列中的旧引用不会误删同 key 的新资源。索引不持有 View、Context、回调或播放实例。弱引用非空只代表资源仍存活，不能作为正在播放的判断，也不保证预加载资源在下一次请求时仍然存在。32 MiB 的默认预算仅约束强引用 LRU，并不是所有活动资源的总内存上限。

## RecyclerView 刷新不重播

`loadSvga` 默认 `reuseOnRebind = true`：同一个 View 上来源、版本、请求配置和播放参数未变时，复用当前 handle、Drawable 和播放时钟，不先清空、不重新下载或解码、不回到第一帧。正在加载的相同请求也复用，下载进度及完成/错误回调更新为最近一次绑定的回调；已准备好的复用不会再次触发 onReady。手动暂停、播放进度和已完成次数都保留。

```kotlin
// onBindViewHolder 中可以重复调用；不要在调用前 clear()/stopAnimation()。
holder.svga.loadSvga(item.url) {
    reuseOnRebind = true       // 默认
}

// 真正回收 ViewHolder 时立即释放，避免池中持有画面与回调。
override fun onViewRecycled(holder: Holder) {
    holder.svga.clearSvga()
    super.onViewRecycled(holder)
}
```

只修改动态填充时保留旧画面，准备新填充后在当前帧替换，不重置播放时钟。来源、文件版本/长度/修改时间、请求头、namespace、解码尺寸或播放配置变化时正常替换。要明确重新播放可调用 handle.replay()；要强制重新创建请求可设置 reuseOnRebind = false，或传入 refresh = true 的 SvgaRequest。

detach、clearSvga()/cancel() 均立即释放，不保留离屏会话；重新 attach 是否自动加载由 restartOnAttach 控制。自定义 requestFactory / onResourceReady 更换函数实例时保守地重新加载，避免忽略业务填充或资源变更；若需复用，应使用稳定函数实例并将业务版本体现在 source/SvgaRequest 中。

库只能复用同一 View 的播放。Adapter 若清空数据后重新创建 ViewHolder，或 ItemAnimator 对整个条目做淡入淡出，仍可能出现视觉变化。列表应保持稳定的条目身份并做差量更新；不需要内容变更动画时可设置 `(recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false`，依据 [Android 官方 API](https://developer.android.com/reference/androidx/recyclerview/widget/SimpleItemAnimator#setSupportsChangeAnimations(boolean)) 关闭该类动画。无需关闭全部移动/插入动画。

## loadSvga 静态首帧

```kotlin
svgaView.loadSvga(url) {
    staticImage = true
    bindings = svgaBindings { text("name", "小明") }
    onReady = { /* 首帧已准备并展示 */ }
}
```

staticImage 默认 false。为 true 时固定显示文件第 0 帧，优先于 autoPlay、startFrame、endFrame 和 reverse；不创建播放时钟、不准备音频、不启动逐帧或滚动文字动画，handle.resume()/replay()/seekToProgress() 不改变首帧。动态填充更新仍显示第 0 帧。此配置不会减少 SVGA 基础资源的解析量，可照常复用已解码缓存。

与 autoPlay = false 不同，后者只是初始暂停，仍可 resume。需要从静态切回动画时，再次 loadSvga 并设置 staticImage = false，会创建正常播放会话。相同静态请求重复绑定继续复用画面；detach 仍立即释放。

静态模式由 loader 管理展示，包括 useViewControls = true 时；因此不能同时设置接管展示的 onResourceReady。使用 bindings 定制首帧，并用 onReady 获取就绪通知。直接调用 SVGAImageView 自身的播放方法属于业务主动接管，不受 handle 的静态播放限制。
