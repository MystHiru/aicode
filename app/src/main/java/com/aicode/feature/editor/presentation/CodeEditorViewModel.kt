package com.aicode.feature.editor.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.editor.domain.TextMateSetup
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 编辑器页状态。一期只读，故没有脏标记与保存态。 */
sealed interface EditorUiState {
    data object Loading : EditorUiState
    data class Success(val content: String, val scopeName: String?) : EditorUiState
    data class TooLarge(val sizeBytes: Long) : EditorUiState
    data class Error(val detail: String?) : EditorUiState
}

@HiltViewModel
class CodeEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var loadedPath: String? = null

    /** 重复调用同一路径不会重复读盘，供 Compose 重组时安全调用。 */
    fun load(path: String) {
        if (loadedPath == path) return
        loadedPath = path
        _uiState.value = EditorUiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = runCatching {
                if (!fileAccess.exists(path) || !fileAccess.isFile(path)) {
                    return@runCatching EditorUiState.Error(null)
                }
                val size = fileAccess.fileSize(path)
                if (size > MAX_EDITABLE_BYTES) {
                    return@runCatching EditorUiState.TooLarge(size)
                }
                // 语法包解析放在这里，确保 AndroidView factory 在主线程创建编辑器时 registry 已就绪。
                TextMateSetup.ensureInitialized(context)
                val scope = TextMateSetup.scopeNameFor(path)
                if (scope != null) {
                    // 预热 grammar：某种语法首次构造要编译大量正则，不在这里做就会压到主线程并拖后首次上色。
                    // 不将对象传给 UI：Language 绑编辑器生命周期，editor.release() 会连带销毁它，
                    // 跨重建复用已销毁实例会出问题——此处仅为把编译结果缓进 registry。
                    runCatching { TextMateLanguage.create(scope, false).destroy() }
                }
                EditorUiState.Success(
                    content = fileAccess.readFile(path),
                    scopeName = scope
                )
            }.getOrElse { e ->
                FileLogger.w(TAG, "打开文件失败: $path", e)
                EditorUiState.Error(e.message)
            }
        }
    }

    private companion object {
        const val TAG = "CodeEditorViewModel"

        /** 全量读入内存，超过该体积拒绝打开以避免 OOM 与长时间卡顿。 */
        const val MAX_EDITABLE_BYTES = 2L * 1024 * 1024
    }
}
