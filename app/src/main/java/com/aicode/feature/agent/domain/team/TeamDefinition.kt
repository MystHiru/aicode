package com.aicode.feature.agent.domain.team

/**
 * 团队里的一个成员。
 *
 * 两种来源：
 * - [agent] 非空：引用 `agents/<name>.md` 的现成子代理定义，模型与提示词都跟着定义走；
 * - [agent] 为空：直接用 [providerId] + [model] 拉一个已配置的模型，人设由 [prompt] 给出。
 *
 * 这样组队时不限于已经写好的子代理，任何配好的模型都能直接拉进来当成员。
 *
 * @param name 群聊里的显示名（可与 agent 名不同）
 * @param avatar 头像："emoji:🦊" 或本地图片的容器路径；空表示用名字首字自动生成
 */
data class TeamMember(
    val name: String,
    val avatar: String = "",
    val agent: String = "",
    val providerId: String = "",
    val model: String = "",
    val prompt: String = "",
    val isLead: Boolean = false
) {
    /** 是否引用现成的子代理定义。 */
    val usesAgent: Boolean get() = agent.isNotBlank()

    /** 群成员列表里展示的模型/来源标签。 */
    fun sourceLabel(): String = when {
        usesAgent -> "子代理 · $agent"
        model.isNotBlank() -> "模型 · $model"
        else -> "未配置模型"
    }
}

/**
 * 一个 agent 团队（群）的定义，来自 `teams/<name>.md`。
 *
 * @param name 群名
 * @param description 团队的职责
 * @param ownerName 群聊里「用户本人」的显示名
 * @param ownerAvatar 用户本人的头像："emoji:🙂" 或图片容器路径
 * @param members 群成员
 * @param strategy 群规（正文），每轮发言时拼进成员的上下文
 * @param filePath 定义文件的容器路径
 */
data class TeamDefinition(
    val name: String,
    val description: String,
    val ownerName: String = DEFAULT_OWNER_NAME,
    val ownerAvatar: String = "",
    val members: List<TeamMember>,
    val strategy: String,
    val filePath: String? = null,
    val key: String = ""
) {
    /** 保存表单提交的快照。 */
    data class Form(
        val name: String,
        val description: String,
        val ownerName: String,
        val ownerAvatar: String,
        val members: List<TeamMember>,
        val strategy: String
    )

    enum class SaveError { INVALID_NAME, NO_MEMBERS, NAME_CONFLICT, MEMBER_LIMIT, IO_FAILED }

    companion object {
        const val MAX_DESC_CHARS = 500
        const val MAX_MEMBERS = 12
        const val DEFAULT_OWNER_NAME = "你"
    }
}
