package com.aicode.feature.settings.presentation.component

/*
 * [二开] 动态背景渲染层（GIF / MP4）。
 *
 * 上游只支持静态图片背景，本文件补上动图与视频背景的绘制，供 MainActivity 的全局背景层按
 * BackgroundSettingsRepository.Kind 分发调用。刻意独立成文件，不修改上游既有渲染逻辑，
 * 便于后续 rebase/merge 上游代码（冲突面为 0）。
 *
 * 实现要点：
 *  - 动图（GIF / 动图 WebP）：ImageDecoder 解成 AnimatedImageDrawable，用帧回调驱动 Compose 重绘。
 *  - MP4：不在本文件绘制，由 MainActivity 的 window 层 TextureView 承担（见 setWallpaperVideo）。
 */

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 动图背景（GIF / 动图 WebP）。
 *
 * 不用 android.graphics.Movie：Movie.draw() 画在硬件加速画布上不可靠，
 * setTime() 之后画出来的仍是首帧，表现就是「动图完全静止」。
 * 改用 ImageDecoder 解出的 AnimatedImageDrawable：解码器自己按帧调度，
 * 并通过 AnimationCallback 在每帧回调里递增计数，Canvas 读到该计数就重绘。
 *
 * @param path 应用私有目录下的动图绝对路径。
 * @param alpha 不透明度（与静态图共用同一套水印透明度）。
 * @param modifier 外层修饰符，通常无需传入。
 */
@Composable
internal fun GifBackgroundView(
    path: String,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    val drawable by produceState<Drawable?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeAnimated(path) }
    }

    val current = drawable ?: return

    // 每帧递增一次；Canvas 在绘制阶段读它，从而持续重绘。
    // AnimatedImageDrawable 自己按时间推进内部帧，这里只负责把「该重绘了」告诉 Compose。
    // （Animatable2.AnimationCallback 只有开始/结束回调，没有逐帧回调，所以用 withFrameNanos。）
    var frameTick by remember(current) { mutableIntStateOf(0) }

    DisposableEffect(current) {
        (current as? AnimatedImageDrawable)?.start()
        onDispose { (current as? AnimatedImageDrawable)?.stop() }
    }

    LaunchedEffect(current) {
        while (true) {
            withFrameNanos { frameTick++ }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .alpha(alpha)
    ) {
        // 读一下让绘制阶段订阅帧计数。
        @Suppress("UNUSED_EXPRESSION")
        frameTick
        val w = size.width.toInt().coerceAtLeast(1)
        val h = size.height.toInt().coerceAtLeast(1)
        if (current.bounds.width() != w || current.bounds.height() != h) {
            current.setBounds(0, 0, w, h)
        }
        current.draw(drawContext.canvas.nativeCanvas)
    }
}

/**
 * 解出动图。API 28+ 用 ImageDecoder（GIF 与动图 WebP 都支持）；
 * 更低版本退回静态首帧，保证不崩、至少能显示画面。
 */
private fun decodeAnimated(path: String): Drawable? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(File(path))
        ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
            // 软件位图：避开硬件位图在自定义画布上的抽样问题。
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        BitmapDrawable(null, BitmapFactory.decodeFile(path))
    }
}.getOrNull()

/**
 * MP4 视频背景：静音 + 循环播放，覆盖全屏。
 *
 * 用 TextureView（而非 SurfaceView）是为了能参与 Compose 的图层合成，透明度才能生效。
 *
 * @param path 应用私有目录下的 MP4 绝对路径。
 * @param alpha 不透明度（与静态图共用同一套 0~0.2 的水印透明度）。
 * @param modifier 外层修饰符，通常无需传入。
 */
@Composable
internal fun VideoBackgroundView(
    path: String,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    // Surface 由 TextureView 的回调产出，MediaPlayer 依赖它才能出画面。
    var surface by remember(path) { mutableStateOf<Surface?>(null) }
    // [二开] 纹理重建期间（如抽屉弹出导致重新布局）TextureView 会短暂空白，
    // 用销毁前抓到的最后一帧兜底，避免整屏闪白。
    var lastFrame by remember(path) { mutableStateOf<ImageBitmap?>(null) }

    DisposableEffect(path, surface) {
        val target = surface
        if (target == null) {
            // 纹理还没就绪：先不建播放器，等回调把 surface 填进来后本效应会重跑。
            onDispose { }
        } else {
            val player = MediaPlayer()
            runCatching {
                player.setDataSource(path)
                player.setSurface(target)
                player.isLooping = true
                player.setVolume(0f, 0f)
                player.setOnPreparedListener { it.start() }
                // 解码失败时吞掉错误，避免 MediaPlayer 抛 ANR 级异常拖垮界面。
                player.setOnErrorListener { _, _, _ -> true }
                player.prepareAsync()
            }
            onDispose {
                runCatching {
                    player.stop()
                    player.release()
                }
            }
        }
    }

    DisposableEffect(path) {
        onDispose {
            surface?.release()
            surface = null
        }
    }

    // alpha 会给父节点加硬件层，TextureView 画进硬件层会闪白。
    // 背景视频始终按原亮度画，可见度只由上面的遮罩控制。
    if (alpha <= 0f) return
    Box(modifier = modifier.fillMaxSize()) {
        lastFrame?.let { frame ->
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        AndroidView(
            factory = { context ->
                val view = TextureView(context)
                view.apply {
                    // 背景层只负责显示，不抢焦点、不拦截触摸，正文区域依旧可正常滚动点击。
                    isFocusable = false
                    isFocusableInTouchMode = false
                    // 纹理空白时让下方的最后一帧透出来
                    isOpaque = false
                    var lastCaptureMs = 0L
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            surface = Surface(surfaceTexture)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            surface?.release()
                            surface = null
                            // true 表示由系统回收 SurfaceTexture，这里已先释放自建的 Surface。
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                            // 销毁回调里 bitmap 常常已经是空的，播放中间隔抓一帧才能兜住闪白。
                            val now = SystemClock.uptimeMillis()
                            if (lastFrame != null && now - lastCaptureMs < 1500L) return
                            lastCaptureMs = now
                            lastFrame = runCatching { view.bitmap?.asImageBitmap() }.getOrNull() ?: lastFrame
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
