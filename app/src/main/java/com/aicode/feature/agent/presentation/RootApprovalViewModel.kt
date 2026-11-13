package com.aicode.feature.agent.presentation

import androidx.lifecycle.ViewModel
import com.aicode.feature.agent.domain.root.PendingRootApproval
import com.aicode.feature.agent.domain.root.RootApprovalChoice
import com.aicode.feature.agent.domain.root.RootApprovalManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** 只负责把 root 审批请求桥给 UI，不做任何策略判断。 */
@HiltViewModel
class RootApprovalViewModel @Inject constructor(
    private val manager: RootApprovalManager
) : ViewModel() {

    val pending: StateFlow<PendingRootApproval?> = manager.pending

    fun resolve(id: String, choice: RootApprovalChoice) = manager.resolve(id, choice)
}
