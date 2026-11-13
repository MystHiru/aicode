package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.root.RootShell
import com.aicode.feature.agent.domain.root.RootState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Root 执行通道的设置页状态。探测是耗时操作，结果缓存在 [RootShell]。 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val rootShell: RootShell
) : ViewModel() {

    private val _state = MutableStateFlow(RootState.UNAVAILABLE)
    val state: StateFlow<RootState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 重新探测 su 是否可用（如授权后返回页面）。 */
    fun refresh() {
        viewModelScope.launch { _state.value = rootShell.refresh() }
    }
}
