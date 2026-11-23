package com.aicode.core.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.aicode.feature.settings.presentation.component.ModelLogoIcon
import java.io.File

/**
 * 群成员头像：支持四种来源，统一渲染成同尺寸的圆形。
 *
 * - `emoji:🦊`：直接画表情；
 * - `logo:deepseek-v4-pro`：渲染该模型上游厂商的官方 logo（与模型列表里同一个图标）；
 * - 图片路径（容器路径 `~/.aicode/...` 或绝对路径）：读本地文件解码；
 * - 其他/空：用名字首字撑一个色块。
 *
 * 头像为空但给了 [modelName] 时也走 logo 分支——直接拉进群的模型成员默认就显示自家 logo，
 * 用户想换可以自己选 emoji 或相册图片。
 *
 * 图片按需异步解码，解码失败自动退回首字，不会因为头像坏掉拖垮整条消息。
 * 本地模式下 `~/.aicode` 映射到 `filesDir/aicode`；远程工作区的头像不在此解析，退回首字。
 */
@Composable
fun MemberAvatar(
    avatar: String,
    name: String,
    tint: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    /** 兜底：头像为空时按模型名显示上游 logo（如 deepseek / glm / kimi）。 */
    modelName: String? = null
) {
    val context = LocalContext.current
    var bitmap by remember(avatar) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    val emoji = remember(avatar) { avatar.removePrefix("emoji:").takeIf { avatar.startsWith("emoji:") } }
    // "logo:模型名" 或头像为空但有模型名：显示上游官方 logo
    val logoModel = remember(avatar, modelName) {
        when {
            avatar.startsWith("logo:") -> avatar.removePrefix("logo:")
            avatar.isBlank() -> modelName?.takeIf { it.isNotBlank() }
            else -> null
        }
    }
    val imageFile = remember(avatar) {
        if (avatar.isBlank() || avatar.startsWith("emoji:") || avatar.startsWith("logo:")) {
            null
        } else {
            resolveAvatarFile(context, avatar)
        }
    }

    LaunchedEffect(imageFile) {
        bitmap = imageFile?.let { file ->
            runCatching {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        when {
            emoji != null -> Text(
                text = emoji,
                fontSize = (size.value * 0.55f).sp
            )
            logoModel != null -> ModelLogoIcon(
                modelName = logoModel,
                size = size * 0.72f
            )
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
            else -> Text(
                text = name.trim().take(1).ifBlank { "?" },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
                color = tint
            )
        }
    }
}

/** 把头像路径解析成本地文件：`~/.aicode` 映射到私有 filesDir，其余按绝对路径处理。 */
fun resolveAvatarFile(context: Context, path: String): File? {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return null
    val file = when {
        trimmed == "~/.aicode" -> File(context.filesDir, "aicode")
        trimmed.startsWith("~/.aicode/") -> File(File(context.filesDir, "aicode"), trimmed.removePrefix("~/.aicode/"))
        trimmed.startsWith("/") -> File(trimmed)
        else -> return null
    }
    return file.takeIf { it.isFile }
}
