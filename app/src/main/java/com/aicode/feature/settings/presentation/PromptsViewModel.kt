package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.prompt.PromptFragment
import com.aicode.feature.agent.domain.prompt.PromptFragmentCatalog
import com.aicode.feature.agent.domain.prompt.PromptFragmentSource
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 提示词页状态：按编号列出最终生效的片段（含来源），以及内置开关。 */
data class PromptsUiState(
    val fragments: List<PromptFragment> = emptyList(),
    val builtinDisabled: Boolean = false,
    /** 被单独关闭的片段编号；关闭的片段不进入系统提示词。 */
    val disabledNumbers: Set<Int> = emptySet(),
    /** 没有选中工作区时项目层不可写，编辑/新建回落到全局层。 */
    val hasWorkspace: Boolean = false,
    val loading: Boolean = true
)

/**
 * 提示词页状态：四级来源（项目 > 全局 > 本地 > 内置）的生效片段列表 + 内置开关。
 *
 * 读写都是磁盘 IO，统一放 IO 线程。
 */
@HiltViewModel
class PromptsViewModel @Inject constructor(
    private val catalog: PromptFragmentCatalog,
    private val workspaceRepository: WorkspaceRepository,
    private val toggleRepository: com.aicode.feature.settings.data.repository.PromptFragmentToggleRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PromptsUiState())
    val state: StateFlow<PromptsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val loaded = withContext(Dispatchers.IO) {
                catalog.refreshDisabledCache()
                PromptsUiState(
                    fragments = catalog.list(projectRoot),
                    builtinDisabled = catalog.isBuiltinDisabled(),
                    disabledNumbers = toggleRepository.disabledNumbers(),
                    hasWorkspace = projectRoot.isNotBlank(),
                    loading = false
                )
            }
            _state.value = loaded
        }
    }

    /** 保存某编号的覆盖（新建与编辑同一入口）：写到所选作用域层；编辑改编号时清掉旧编号。 */
    fun saveFragment(
        number: Int,
        title: String,
        scope: PromptFragmentSource,
        content: String,
        previousNumber: Int? = null
    ) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) {
                catalog.saveOverride(
                    number,
                    title,
                    content,
                    projectRoot,
                    target = scope,
                    previousNumber = previousNumber
                )
            }
            refresh()
        }
    }

    /** 删除某编号的覆盖，自动回退到下一层。 */
    fun deleteFragment(number: Int) = deleteFragments(listOf(number))

    /**
     * 批量删除（列表多选批量操作）：一次 IO 里删完再刷新一次列表。
     * 逐条调 [deleteFragment] 会各自触发一轮全量重扫，编号多时列表会来回跳。
     */
    fun deleteFragments(numbers: List<Int>) {
        if (numbers.isEmpty()) return
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) {
                numbers.forEach { catalog.deleteOverride(it, projectRoot) }
            }
            refresh()
        }
    }

    /** 重置：清空项目层与全局层的所有自定义片段，各编号回落到内置提示词。 */
    fun resetCustomFragments() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) { catalog.deleteAllCustom(projectRoot) }
            refresh()
        }
    }

    /** 拖拽重排：按新顺序重新编号并落盘，改变注入顺序。乐观更新本地状态，避免重读导致列表跳动。 */
    fun reorderFragments(reordered: List<PromptFragment>) {
        val numbers = reordered.map { it.number }.sorted()
        val renumbered = reordered.mapIndexed { index, fragment -> fragment.copy(number = numbers[index]) }
        _state.update { it.copy(fragments = renumbered) }
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val refreshed = withContext(Dispatchers.IO) {
                if (catalog.reorder(renumbered, projectRoot)) catalog.list(projectRoot) else null
            }
            if (refreshed != null) {
                _state.update { it.copy(fragments = refreshed) }
            } else {
                refresh()
            }
        }
    }

    /** 单独开关某个片段：关闭后渲染系统提示词时跳过它。 */
    fun setFragmentEnabled(number: Int, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { toggleRepository.setEnabled(number, enabled) }
            refresh()
        }
    }

    fun setBuiltinDisabled(disabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { catalog.setBuiltinDisabled(disabled) }
            refresh()
        }
    }
}
