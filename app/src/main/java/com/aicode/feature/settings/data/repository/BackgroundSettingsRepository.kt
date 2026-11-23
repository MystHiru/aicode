package com.aicode.feature.settings.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backgroundDataStore by preferencesDataStore(
    name = "background_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 全局自定义背景：媒体文件拷贝到应用私有目录持久化（不依赖 content URI 授权），
 * 透明度、文件路径与背景类型存 DataStore。路径为 null 表示未设置背景。
 *
 * [二开] 在上游「静态图片」的基础上扩展出 GIF / MP4 两种动态背景（见 [Kind]），
 * 新增字段全部独立命名，不改动上游既有 key 的语义，便于后续同步上游代码。
 */
@Singleton
class BackgroundSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /**
     * 背景类型。上游只有静态图片，这里按文件内容把背景分成四类，渲染时各走各的实现：
     * [IMAGE] 位图直接绘制，[GIF] 走 ImageDecoder + AnimatedImageDrawable，
     * [MP4] 走 TextureView + MediaPlayer，
     * [MOTION_PHOTO] JPEG 容器里嵌了一段 MP4（手机相册的「动态照片」），提取后按 MP4 播放。
     */
    enum class Kind {
        IMAGE,
        GIF,
        MP4,
        MOTION_PHOTO
    }

    companion object {
        const val DEFAULT_ALPHA = 0.15f
        /**
         * 背景可见度上限。
         *
         * [二开] 上游原为 0.2（水印层，压在内容上方）。现在背景画在内容下方、由遮罩控制，
         * 文字不受影响，可以开到 0.6；滑条 100% 即 0.6 可见度。
         */
        const val MAX_ALPHA = 0.6f
        /** 最小透明度：0 表示完全透明（背景不可见）。 */
        const val MIN_ALPHA = 0f
        private val IMAGE_PATH_KEY = stringPreferencesKey("background_image_path")
        private val ALPHA_KEY = floatPreferencesKey("background_alpha")

        /** [二开] 背景类型键；历史数据没有该键时按 [Kind.IMAGE] 兜底，老用户无感升级。 */
        private val IMAGE_KIND_KEY = stringPreferencesKey("background_image_kind")

        /** 把 UI 百分比（0~100）线性映射为实际透明度（0~MAX_ALPHA）。 */
        fun sliderToAlpha(percent: Float): Float =
            MAX_ALPHA * (percent / 100f).coerceIn(0f, 1f)

        /** 把实际透明度反映射为 UI 百分比（0~100）。 */
        fun alphaToSlider(alpha: Float): Float =
            (alpha / MAX_ALPHA).coerceIn(0f, 1f) * 100f

        /**
         * [二开] MIME 转背景类型：只认 GIF 与 MP4 两种动态格式，其余一律按静态图片处理。
         * 部分来源（如某些文件管理器）给不出 MIME，此时 [mime] 为 null，同样按静态图片兜底。
         */
        fun mimeToKind(mime: String?): Kind = when (mime?.lowercase(Locale.ROOT)) {
            "image/gif" -> Kind.GIF
            "video/mp4" -> Kind.MP4
            else -> Kind.IMAGE
        }

        /**
         * [二开] 按文件头判定背景类型，不依赖 MIME。
         *
         * MIME 不可靠：部分文件管理器返回 null；动图 WebP 与静态 WebP 的 MIME 都是 image/webp，
         * 只看 MIME 会把动图当静态图（只画首帧，看起来就是「动图不动」）。
         *
         *  - GIF：文件头 GIF87a / GIF89a
         *  - 动图 WebP：RIFF....WEBP 且 VP8X 动画标志位为 1，或含 ANIM / ANMF 块
         *  - MP4：偏移 4 处为 ftyp
         */
        fun detectKind(file: File): Kind {
            val header = ByteArray(256)
            val read = runCatching {
                FileInputStream(file).use { it.read(header) }
            }.getOrDefault(0)
            if (read < 12) return Kind.IMAGE
            val ascii = String(header, 0, read, Charsets.ISO_8859_1)
            // JPEG（FF D8 FF）：可能是相册里的「动态照片」——JPEG 容器里嵌了一段 MP4。
            // 这种文件头与普通 JPEG 相同，只能靠内嵌的 ftyp box 识别。
            if (read >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() &&
                header[2] == 0xFF.toByte()
            ) {
                return if (findMotionPhotoVideo(file) != null) Kind.MOTION_PHOTO else Kind.IMAGE
            }
            return when {
                ascii.startsWith("GIF87a") || ascii.startsWith("GIF89a") -> Kind.GIF
                ascii.startsWith("RIFF") && ascii.startsWith("WEBP", 8) ->
                    if (isAnimatedWebp(header, read, ascii)) Kind.GIF else Kind.IMAGE
                // HEIC / HEIF（brand 为 heic/heix/mif1 等）不是视频，按图片处理。
                ascii.startsWith("ftyp", 4) ->
                    if (brandAt(ascii, 8) in HEIF_BRANDS) Kind.IMAGE else Kind.MP4
                else -> Kind.IMAGE
            }
        }

        /** HEIF / HEIC 图像容器的 brand，不能当视频处理。 */
        private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")

        /** 常见 MP4 / 3GP brand；内嵌视频的 ftyp brand 基本落在这个集合里。 */
        private val MP4_BRANDS = setOf(
            "mp41", "mp42", "mp71", "isom", "iso2", "iso3", "iso4", "iso5", "iso6",
            "avc1", "dash", "mmp4", "M4V ", "M4A ", "3gp4", "3gp5", "3g2a", "qt  "
        )

        private fun brandAt(text: String, offset: Int): String =
            if (text.length >= offset + 4) text.substring(offset, offset + 4) else ""

        /**
         * 在文件里找内嵌的 MP4，返回 (起始字节, 字节长度)；不是动态照片则返回 null。
         *
         * MP4 的第二个 box 固定是 ftyp（4 字节长度 + "ftyp"），在 JPEG 数据里几乎不可能出现，
         * 所以按它定位最通用——覆盖 Google / 小米 / OPPO / vivo 的 MicroVideo、
         * 三星的 MotionPhoto，以及新版 Container:Directory 写法。
         */
        fun findMotionPhotoVideo(file: File): Pair<Long, Long>? {
            val length = file.length()
            if (length < 4096) return null
            val chunk = 64 * 1024
            var carry = ByteArray(0)
            var position = 0L
            FileInputStream(file).use { input ->
                val buffer = ByteArray(chunk)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    val data = if (carry.isEmpty()) {
                        buffer.copyOf(n)
                    } else {
                        ByteArray(carry.size + n).also {
                            carry.copyInto(it)
                            buffer.copyInto(it, carry.size, 0, n)
                        }
                    }
                    val base = position - carry.size
                    var i = 0
                    while (i + 8 <= data.size) {
                        if (data[i] == 'f'.code.toByte() && data[i + 1] == 't'.code.toByte() &&
                            data[i + 2] == 'y'.code.toByte() && data[i + 3] == 'p'.code.toByte()
                        ) {
                            // 校验 brand：避免把图像容器（HEIC 等）或文本里的 "ftyp" 当成视频。
                            val brand = String(data, i + 4, 4, Charsets.ISO_8859_1)
                            val start = base + i - 4
                            if (brand !in HEIF_BRANDS && brand in MP4_BRANDS &&
                                start > 0 && start < length - 8
                            ) {
                                return Pair(start, length - start)
                            }
                        }
                        i++
                    }
                    // 保留尾部 8 字节，避免 ftyp 被切在块边界
                    carry = data.takeLast(8).toByteArray()
                    position += n
                }
            }
            return null
        }

        /**
         * 把动态照片里内嵌的 MP4 提取到 cache 目录，供 MediaPlayer 播放。
         * MediaPlayer 需要一个完整的 MP4 文件，无法直接读 JPEG 中间的一段。
         */
        fun extractMotionPhotoVideo(file: File, cacheDir: File): File? {
            val range = findMotionPhotoVideo(file) ?: return null
            val target = File(cacheDir, "motion_${file.nameWithoutExtension}_${file.length()}.mp4")
            if (target.length() == range.second) return target
            return runCatching {
                java.io.RandomAccessFile(file, "r").use { source ->
                    source.seek(range.first)
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = range.second
                        while (remaining > 0) {
                            val n = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            remaining -= n
                        }
                    }
                }
                if (target.length() > 0) target else null
            }.getOrNull()
        }

        /** 动图 WebP 判定：优先读 VP8X 扩展头的动画标志位（bit 1），退而查 ANIM / ANMF 块。 */
        private fun isAnimatedWebp(header: ByteArray, size: Int, ascii: String): Boolean {
            // VP8X chunk 固定在偏移 12：fourcc(4) + size(4) + flags(1) + 保留(3)
            if (size > 20 && ascii.startsWith("VP8X", 12) && header[20].toInt() and 0x02 != 0) {
                return true
            }
            return ascii.contains("ANIM") || ascii.contains("ANMF")
        }
    }

    /** 当前背景图文件绝对路径；null 表示未设置。 */
    val imagePathFlow: Flow<String?> = context.backgroundDataStore.data.map { it[IMAGE_PATH_KEY] }

    /** 背景图不透明度（0.05~0.2，顶层水印保证文字可读）。 */
    val alphaFlow: Flow<Float> = context.backgroundDataStore.data.map {
        (it[ALPHA_KEY] ?: DEFAULT_ALPHA).coerceIn(MIN_ALPHA, MAX_ALPHA)
    }

    /** [二开] 当前背景类型；键缺失或值无法识别时按静态图片处理。 */
    val kindFlow: Flow<Kind> = context.backgroundDataStore.data.map { prefs ->
        prefs[IMAGE_KIND_KEY]
            ?.let { name -> Kind.entries.firstOrNull { it.name == name } }
            ?: Kind.IMAGE
    }

    /**
     * 选择新背景：把 [uri] 拷贝到私有目录 backgrounds/ 后替换旧文件。
     * 先复制并检查可读性，再更新 DataStore；失败时保留旧背景。
     */
    suspend fun setBackgroundImage(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val extension = extensionFor(runCatching { resolver.getType(uri) }.getOrNull())
        val directory = File(context.filesDir, "backgrounds").apply { mkdirs() }
        val target = try {
            File.createTempFile("bg_", ".$extension", directory)
        } catch (_: IOException) {
            return@withContext false
        }
        var published = false
        try {
            val source = resolver.openInputStream(uri) ?: return@withContext false
            source.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            val kind = detectKind(target)
            if (!isReadableBackground(target, kind)) return@withContext false
            ensureActive()
            // 发布与标记必须一起完成，否则取消操作可能清理掉已生效的文件。
            withContext(NonCancellable) {
                var oldPath: String? = null
                context.backgroundDataStore.edit {
                    oldPath = it[IMAGE_PATH_KEY]
                    it[IMAGE_PATH_KEY] = target.absolutePath
                    it[IMAGE_KIND_KEY] = kind.name
                }
                published = true
                oldPath?.let { runCatching { File(it).delete() } }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            if (!published) target.delete()
        }
    }

    private fun isReadableBackground(file: File, kind: Kind): Boolean {
        if (kind == Kind.MP4) {
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(file.absolutePath)
                return metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" &&
                    metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) == "video/mp4"
            } finally {
                metadata.release()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    /** 移除背景：删媒体文件并清路径与类型。 */
    suspend fun clearBackground() {
        var oldPath: String? = null
        context.backgroundDataStore.edit {
            oldPath = it[IMAGE_PATH_KEY]
            it.remove(IMAGE_PATH_KEY)
            it.remove(IMAGE_KIND_KEY)
        }
        oldPath?.let { runCatching { File(it).delete() } }
    }

    suspend fun setBackgroundAlpha(alpha: Float) {
        context.backgroundDataStore.edit {
            it[ALPHA_KEY] = alpha.coerceIn(MIN_ALPHA, MAX_ALPHA)
        }
    }

    /**
     * 按 MIME 决定落盘扩展名。[二开] 比上游多了 gif / mp4 两种，
     * 播放器与 Movie 解码都依赖扩展名之外的容器头，这里取名只为可读性。
     */
    private fun extensionFor(mime: String?): String = when (mime?.lowercase(Locale.ROOT)) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "video/mp4" -> "mp4"
        else -> "jpg"
    }
}
