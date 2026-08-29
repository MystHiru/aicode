package com.aicode.feature.editor.presentation

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.editor.domain.TextMateSetup
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * 独立全屏代码查看页。一期只读：不提供保存，避免与 AI 的 editFile 写冲突。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    path: String,
    onBack: () -> Unit,
    viewModel: CodeEditorViewModel = hiltViewModel()
) {
    BackHandler { onBack() }
    LaunchedEffect(path) { viewModel.load(path) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = path.substringAfterLast('/'),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = path,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    Text(
                        text = stringResource(R.string.editor_readonly),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = Spacing.lg)
                    )
                }
            )
        }
    ) { padding ->
        val content = Modifier
            .fillMaxSize()
            .padding(padding)
        when (val s = state) {
            is EditorUiState.Loading -> CenterBox(content) { CircularProgressIndicator() }
            is EditorUiState.Success -> EditorSurface(s, content)
            is EditorUiState.TooLarge -> CenterBox(content) {
                HintText(
                    stringResource(
                        R.string.editor_file_too_large,
                        s.sizeBytes / 1024 / 1024
                    )
                )
            }
            is EditorUiState.Error -> CenterBox(content) {
                HintText(s.detail ?: stringResource(R.string.editor_load_failed))
            }
        }
    }
}

@Composable
private fun EditorSurface(state: EditorUiState.Success, modifier: Modifier) {
    // 与实际渲染出的 Compose 主题保持一致，而非跟随系统设置——应用内可单独切换主题。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // sora 的语法分析在后台线程跑，首帧必然还没上色。用一次渐显护住这段窗口，
    // 把「先纯文本后突然上色」的跳变变成内容渐现；分析超过该窗口的大文件仍会看到跳变，
    // 那是库的异步设计，无法彻底消除。
    var revealed by remember(state.content) { mutableStateOf(false) }
    LaunchedEffect(state.content) { revealed = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = HIGHLIGHT_REVEAL_MS),
        label = "editor-reveal"
    )
    // 语法主题是 ThemeRegistry 全局单态，深浅切换后需重建编辑器才能整体换色。
    key(dark) {
        AndroidView(
            modifier = modifier.graphicsLayer { alpha = contentAlpha },
            factory = { ctx ->
                TextMateSetup.applyTheme(dark)
                CodeEditor(ctx).apply {
                    isEditable = false
                    typefaceText = Typeface.MONOSPACE
                    colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                    state.scopeName?.let { scope ->
                        setEditorLanguage(TextMateLanguage.create(scope, false))
                    }
                    setText(state.content)
                }
            },
            onRelease = { it.release() }
        )
    }
}

@Composable
private fun CenterBox(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = Spacing.xl)
    )
}

/** 内容渐显时长：给后台语法分析留出窗口，同时不致于让用户觉得打开变慢。 */
private const val HIGHLIGHT_REVEAL_MS = 200
