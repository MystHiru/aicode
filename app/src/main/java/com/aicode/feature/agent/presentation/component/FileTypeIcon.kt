package com.aicode.feature.agent.presentation.component

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.aicode.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.Code
import compose.icons.feathericons.FileText

/**
 * 文件浏览行的图标来源，分两类以适配不同的着色方式：
 * - [Colored]：彩色矢量图（如 php 大象），显示时不染色，保留原色；
 * - [Mono]：线性图标（Feather），显示时随主题着色。
 */
sealed interface FileTypeIcon {
    data class Colored(@DrawableRes val res: Int) : FileTypeIcon
    data class Mono(val vector: ImageVector) : FileTypeIcon
}

/**
 * 按文件名（主要看扩展名）挑选图标，分层回退：
 * 图片各格式共用一个图标 → 主流语言用各自的彩色图标 → 其它可识别的代码/标记/配置文件
 * 用统一的“代码”图标 → 其余用默认文件图标。
 */
fun fileTypeIconFor(name: String): FileTypeIcon {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in IMAGE_EXTS -> FileTypeIcon.Colored(R.drawable.filetype_image)
        "php" -> FileTypeIcon.Colored(R.drawable.filetype_php)
        "java" -> FileTypeIcon.Colored(R.drawable.filetype_java)
        "py", "pyw", "pyi" -> FileTypeIcon.Colored(R.drawable.filetype_python)
        "js", "mjs", "cjs", "jsx" -> FileTypeIcon.Colored(R.drawable.filetype_javascript)
        "ts", "mts", "cts", "tsx" -> FileTypeIcon.Colored(R.drawable.filetype_typescript)
        "go" -> FileTypeIcon.Colored(R.drawable.filetype_go)
        "rs" -> FileTypeIcon.Colored(R.drawable.filetype_rust)
        "c", "h" -> FileTypeIcon.Colored(R.drawable.filetype_c)
        "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> FileTypeIcon.Colored(R.drawable.filetype_cpp)
        "kt", "kts" -> FileTypeIcon.Colored(R.drawable.filetype_kotlin)
        in CODE_EXTS -> FileTypeIcon.Mono(FeatherIcons.Code)
        else -> FileTypeIcon.Mono(FeatherIcons.FileText)
    }
}

/** 图片：各格式共用一个图标。 */
private val IMAGE_EXTS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico",
    "heic", "heif", "avif", "tif", "tiff"
)

/** 可识别为代码/标记/配置，但未单独配彩色图标的扩展名，统一用默认“代码”图标。 */
private val CODE_EXTS = setOf(
    "cs", "rb", "swift", "dart", "lua", "scala", "groovy", "gradle",
    "sh", "bash", "zsh", "ksh", "html", "htm", "css", "scss", "less",
    "json", "jsonc", "json5", "yaml", "yml", "xml", "sql",
    "md", "markdown", "toml", "ini", "cfg", "conf", "properties", "env", "vue"
)
