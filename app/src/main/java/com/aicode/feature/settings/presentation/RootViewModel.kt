package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.root.SuRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Root 通道的设置页状态：探测 su 能否拿到 uid 0。 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val suRunner: SuRunner
) : ViewModel() {

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    init {
        refresh()
    }

    /** 重新探测（如授权后返回页面）。 */
    fun refresh() {
        viewModelScope.launch { _available.value = suRunner.isAvailable() }
    }
}
