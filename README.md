<p align="center">
  <h1 align="center">AiCode</h1>
  <p align="center">
    Android 端 AI 编程工具 · 内置 Linux 终端 · AI Agent · MCP 协议 · Git 集成
    <br />
    <a href="README.md">中文</a> · <a href="README.en.md">English</a>
  </p>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License GPL-3.0" /></a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin-purple.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg" alt="Jetpack Compose UI" />
  <img src="https://img.shields.io/badge/MinSDK-26-orange.svg" alt="Min SDK 26 (Android 8.0)" />
  <a href="https://github.com/jieapi/aicode/releases/latest"><img src="https://img.shields.io/github/v/release/jieapi/aicode?display_name=tag&include_prereleases" alt="Latest Release" /></a>
  <a href="https://github.com/jieapi/aicode/releases"><img src="https://img.shields.io/github/downloads/jieapi/aicode/total" alt="Total Downloads" /></a>
</p>

<p align="center">
  <table>
    <tr>
      <td align="center"><img src="docs/screenshots/home.png" alt="AiCode 主页 - AI 对话界面，支持代码生成与 Markdown 渲染" width="270"/></td>
      <td align="center"><img src="docs/screenshots/git.png" alt="AiCode Git 集成 - 可视化提交记录与分支管理" width="270"/></td>
    </tr>
    <tr>
      <td align="center">主页 · AI 对话</td>
      <td align="center">Git · 提交历史</td>
    </tr>
    <tr>
      <td align="center"><img src="docs/screenshots/container.png" alt="AiCode 容器设置 - 容器镜像管理" width="270"/></td>
      <td align="center"><img src="docs/screenshots/models.png" alt="AiCode 模型列表 - 多提供商模型管理" width="270"/></td>
    </tr>
    <tr>
      <td align="center">容器 · 镜像管理</td>
      <td align="center">模型 · 列表配置</td>
    </tr>
  </table>
</p>

---

## 简介

AiCode 是一款在 Android 手机上运行的 AI 编程工具，将大语言模型与本地 Linux 开发环境深度集成。它内置 Alpine Linux 容器和终端模拟器，让 AI 能直接读写文件、执行 Shell 命令、运行构建工具；同时支持远程 SSH 服务器作为执行后端，把手机变成远程项目的移动工作站。

## 广告

| 图标 | 描述 |
|------|------|
| <img src="https://opencode.ai/favicon-96x96-v3.png" width="24" alt="OpenCode" /> | **[OpenCode Go](https://opencode.ai/go?ref=8Q5GA5B1NY)** — 低价订阅，提供最强大开源模型的慷慨额度与可靠访问 |
| <img src="https://www.rainyun.com/favicon.ico" width="24" alt="RainYun" /> | **[雨云](https://www.rainyun.com/logins_)** — 高性价比云服务器，新用户优惠 |

## 功能特性

- **AI Agent** — 支持 OpenAI / Anthropic / Gemini 兼容协议，多提供商切换；内置文件读写、Shell 执行、后台终端、代码与网页搜索、图片识别、向用户提问等工具；流式输出，长对话自动上下文压缩
- **检查点与撤销** — Agent 修改代码前自动记录文件快照，对话中可一键回滚，支持仅恢复代码、仅恢复对话或两者同时恢复
- **内置终端与容器** — 基于 Termux 组件与 PRoot 的本地 Linux 容器，内置 Alpine 镜像，支持导入自定义 rootfs、挂载宿主目录；终端可后台常驻
- **远程 SSH 模式** — 以远程服务器作为执行后端：命令走 exec channel、文件读写走 SFTP、终端走 shell channel，在手机上直接操作远程项目
- **MCP 协议** — 支持连接本地（stdio）与远程（HTTP）MCP 服务器，动态扩展 AI 工具能力
- **技能与自动记忆** — 支持全局/项目级技能（Skills）与长期记忆，AI 可跨会话复用经验与约定
- **Git 集成** — 内置 Git 状态、分支、提交、差异查看与标签管理，支持配置署名与凭据
- **工作区同步** — 支持 SFTP / FTP 同步，内置 FTP 服务器方便电脑端管理文件
- **备份与还原** — 加密导出/导入提供商配置、凭据、聊天历史与工作区文件
- **Markdown 渲染** — AI 回复实时渲染 Markdown，支持代码高亮
- **自定义提示词** — 系统提示词支持用户自定义覆盖，App 升级不丢失

## 快速开始

| 项目 | 说明 |
|------|------|
| 系统要求 | Android 8.0+（API 26），arm64-v8a / x86_64 |
| 下载地址 | [GitHub Releases](https://github.com/jieapi/aicode/releases/latest)：真机选 `armsolo`、模拟器选 `x86solo`、通用选 `universal` 包 |
| 快速上手 | 「设置 → AI 提供商」配模型 →「容器与镜像」选本地或 SSH → 新建会话开始对话 |
| 更新记录 | [Releases](https://github.com/jieapi/aicode/releases)（历史版本与更新说明） |
| 使用指南 | [GitHub Wiki](https://github.com/jieapi/aicode/wiki) |

## Star

如果 AiCode 对你有帮助，欢迎 [Star](https://github.com/jieapi/aicode) 支持，让更多开发者看到这个项目。

## 反馈与贡献

- **Bug 反馈**：遇到问题请到 [Issues](https://github.com/jieapi/aicode/issues) 提交，附上复现步骤、设备型号与系统版本，便于快速定位
- **功能建议**：想加新功能或改进，欢迎先在 [Issues](https://github.com/jieapi/aicode/issues) 讨论
- **贡献代码**：欢迎提交 [Pull Request](https://github.com/jieapi/aicode/pulls)，我们会及时 review

## 致谢

- [OpenCode](https://github.com/anomalyco/opencode) — 终端 AI 编码工具，本项目的核心灵感来源
- [Termux](https://github.com/termux/termux-app) — Android 终端模拟器，提供了终端组件与 PRoot 方案
- [Kelivo](https://github.com/Chevey339/kelivo) — 跨平台 LLM 聊天客户端，AI 对话界面设计参考

## 开源协议

本项目基于 [GPL-3.0](LICENSE) 协议开源。
