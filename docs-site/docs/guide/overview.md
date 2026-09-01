# 功能总览

AiCode 的设置页按五个分组组织，这一页对照说明每个入口对应的文档。

## AI 配置

| 入口 | 说明 |
| --- | --- |
| AI 提供商 | 接入模型服务、管理模型列表、多 Key、思考强度 → [文档](/guide/providers) |
| 默认模型 | 新会话默认模型，以及识图、标题总结的专用模型 → [文档](/guide/default-models) |
| MCP 服务器 | 接入外部工具，全局与项目两级配置 → [文档](/guide/mcp) |
| 技能 | 按需加载的专项操作指令 → [文档](/guide/skills) |
| 子代理 | 派独立会话并行干活，可自定义模型与工具集 → [文档](/guide/subagent) |

## 运行环境

| 入口 | 说明 |
| --- | --- |
| 容器与镜像 | 本地 Linux 容器与远程 SSH 后端 → [文档](/guide/container) |
| 网络代理 | 全局代理与提供商级代理 → [文档](/guide/proxy) |
| 连接与同步 | SFTP / FTP 通道、工作区同步、内置 FTP 服务端 → [文档](/guide/sync) |

## 工具与权限

| 入口 | 说明 |
| --- | --- |
| 工具授权 | AI 调用工具的授权规则 → [文档](/guide/permissions) |
| 软件权限 | 保活、通知、存储、电池优化等系统权限 → [文档](/guide/app-permissions) |
| 日志 | 查看运行日志、崩溃报告 → [文档](/guide/logs) |

## 外观与语言

外观主题、背景图、语言 → [文档](/guide/appearance)

## 系统

| 入口 | 说明 |
| --- | --- |
| Token 统计 | 用量、费用估算、调用明细 → [文档](/guide/token-stats) |
| 备份与还原 | 加密导出导入配置与工作区 → [文档](/guide/backup) |
| 关于 | 版本信息与检查更新 → [文档](/guide/about) |

## 不在设置里的功能

| 功能 | 说明 |
| --- | --- |
| 聊天界面 | 标题栏、侧边栏、工具栏、消息队列、工作区切换 → [文档](/guide/chat) |
| 三种模式 | Build / Plan / Auto 的权限区别 → [文档](/guide/modes) |
| 检查点与撤销 | AI 改代码前自动快照，可一键回滚 → [文档](/guide/checkpoint) |
| 终端 | 多标签、辅助按键栏、配色与字体 → [文档](/guide/terminal) |
| 文件浏览与编辑 | 文件树、代码编辑器、语法高亮 → [文档](/guide/files) |
| Git | 状态、分支、提交、标签 → [文档](/guide/git) |
| 自定义提示词 | 覆盖 AI 的系统提示词片段 → [文档](/guide/custom-prompts) |

## 进阶内容

| 主题 | 说明 |
| --- | --- |
| 在容器中编译 Android 应用 | 搭 JDK 与 Android SDK，从源码出 APK → [文档](/advanced/build-android-app) |
| 自定义容器镜像 | 换 Debian / Ubuntu，导入自己的 rootfs → [文档](/advanced/custom-image) |
| 自定义面板 | 用脚本在输入框上方画余额或用量卡片 → [文档](/advanced/dashboard-cards) |
| 常见问题 | 界面上没有直接入口的那些问题 → [文档](/advanced/troubleshooting) |
