package com.aicode.feature.agent.presentation.component

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.presentation.AgentAttachment
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Image
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MessageAttachmentPreviewRow(
    attachments: List<AgentAttachment>,
    onClick: ((AgentAttachment) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(top = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        attachments.forEach { attachment ->
            MessageAttachmentPreviewItem(attachment = attachment, onClick = onClick)
        }
    }
}

@Composable
private fun MessageAttachmentPreviewItem(
    attachment: AgentAttachment,
    onClick: ((AgentAttachment) -> Unit)? = null
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable { onClick(attachment) }
    } else {
        Modifier
    }
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = clickModifier.size(76.dp)
    ) {
        if (attachment.isImage) {
            MessageImagePreview(attachment = attachment)
        } else {
            MessageFilePreview(attachment = attachment)
        }
    }
}

/**
 * 用系统对应 app 打开附件文件（FileProvider 授权 URI）。
 * 文件不存在或无匹配 app 时 toast 提示。
 */
internal fun openAttachment(context: Context, attachment: AgentAttachment) {
    val file = File(attachment.localPath)
    if (!file.exists() || !file.isFile) {
        Toast.makeText(context, context.getString(R.string.chat_open_file_failed), Toast.LENGTH_SHORT).show()
        return
    }
    // APK 安装包：系统安装器要求「允许安装未知应用」授权，未授权时引导用户去设置页开启，
    // 否则点击只会弹出「没有权限安装」的拒绝提示。
    if (isApk(attachment)) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, context.getString(R.string.chat_open_apk_permission), Toast.LENGTH_LONG).show()
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.chat_open_file_no_app), Toast.LENGTH_SHORT).show()
            }
            return
        }
    }
    val uri: Uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.chat_open_file_failed), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, attachment.mimeType.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.chat_open_file_no_app), Toast.LENGTH_SHORT).show()
    }
}

private fun isApk(attachment: AgentAttachment): Boolean {
    if (attachment.mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)) return true
    val name = attachment.fileName.lowercase()
    return name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk")
}

@Composable
private fun MessageImagePreview(attachment: AgentAttachment) {
    // 读盘 + 解码放 IO 线程：以前在 remember 里同步 decodeFile，滚到带图消息时会卡主线程。
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, attachment.localPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(attachment.localPath, bounds)
                val sampleSize = calculateMessageAttachmentSampleSize(bounds.outWidth, bounds.outHeight, 180, 180)
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                BitmapFactory.decodeFile(attachment.localPath, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        ComposeImage(
            bitmap = loaded,
            contentDescription = attachment.fileName.ifBlank { stringResource(R.string.common_image_preview) },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                FeatherIcons.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun MessageFilePreview(attachment: AgentAttachment) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            FeatherIcons.FileText,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = attachment.fileName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatBytes(attachment.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun calculateMessageAttachmentSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    var sampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / sampleSize >= reqHeight && halfWidth / sampleSize >= reqWidth) {
            sampleSize *= 2
        }
    }
    return sampleSize.coerceAtLeast(1)
}