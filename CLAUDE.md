# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## General Rules
- 永远使用中文回复 (Always reply in Chinese).
- 优先使用已有工具进行文件操作，比如读取、修改文件 (Prioritize using existing tools for file operations, such as reading and modifying files).

## Asset Synchronization
项目中的 `app/src/main/assets/prompts/`（提示词）和 `docs-site/docs/`（用户文档）是 AI Agent 的核心知识来源，必须与代码保持同步：

- **AI 工作流相关改动 → 检查 prompts**：任何与 AI 工作流相关的改动（工具新增/删除/重命名/参数签名变化、agent 行为变化、提示词逻辑调整等），都必须检查 `app/src/main/assets/prompts/` 下的提示词是否需要同步更新，确保模型看到的工具定义与行为说明与实际一致。AI 应自行在 `prompts/` 目录中查找对应的提示词文件；若不存在则新建。
- **功能、工具变化 → 检查 docs**：任何功能新增/删除/行为变化或工具变更，还要检查 `docs-site/docs/` 下是否有对应使用文档需要更新（如新功能的使用说明、工具行为变化的提示）。
- **UI 变化 → 必须更新对应使用文档**：任何 UI 变化（新增页面、改交互、调布局、改文案）**必须**同步更新 `docs-site/docs/` 下对应的使用文档，确保用户可见的说明与实际界面一致。AI 应自行在 `docs-site/docs/` 中查找对应的文档；若不存在则新建，并同步把新页面加进 `docs-site/.vitepress/config.ts` 的侧栏与 `docs-site/docs/guide/overview.md` 索引。
- **文档目录约定**：`docs-site/docs/` 是文档的唯一事实源，一份 Markdown 同时供 VitePress 文档站与 App 内置 AI 文档使用。`guide/` 放 App 功能说明，`advanced/` 放环境搭建与进阶教程，`index.md` 是站点首页（不打进 APK）。构建时由 `app/build.gradle.kts` 的 `syncAiDocs` task 复制到 `assets/docs/`，AI 在容器内看到的是 `~/.aicode/docs/guide/*.md` 与 `~/.aicode/docs/advanced/*.md`。**面向用户书写**：讲清怎么做、会看到什么、出错怎么办；不要写变量名、错误码、内部实现路径这类只对代码有意义的细节（那些属于 `prompts/`）。
- **UI 文案 → 必须同步 strings.xml**：任何新增或修改用户可见的中文文案（按钮、标题、提示、Toast 等），**必须**将其提取为 string resource 写入 `app/src/main/res/values/strings.xml`（中文）和 `app/src/main/res/values-en/strings.xml`（英文翻译），并在 `.kt` 代码中用 `stringResource(R.string.xxx)` 或 `context.getString(R.string.xxx)` 引用。**禁止在 .kt 文件中硬编码中文 UI 文案。** 命名规范：语义化英文全小写下划线分隔，通用文案用 `common_` 前缀跨页面复用。

## Git 提交规范

项目采用 **Conventional Commits**，由 `.githooks/commit-msg` 在本地校验（启用见仓库根 `.githooks/`）。格式：

```
<type>(<scope>): <subject>

<可选正文，空行隔开>
```

- **type** ∈ `feat | fix | refactor | docs | style | chore | ci | build | perf | test`
- **scope** 可选，建议用功能模块：`agent | settings | terminal | workspace | git | ui | mcp | db | core | docs | build | deps`
- **subject** 一行简述，中英文均可，句末不加句号。
- 跳过校验（仅紧急）：`git commit --no-verify ...`

示例：`feat(agent): 支持流式工具调用` / `fix(settings): 修复 provider 保存时校验失败` / `ci: 删除签名校验步骤`

## 分支与改动工作流

**原则：大功能/复杂改动拉分支，轻量修改/单测/修 Bug 直接在 `main` 操作。** 本仓库已全面采用 Tag 驱动发版，平时在 `main` 上的提交不会影响发布包，仅打 Tag 时才触发 GitHub Release。

- **改动分档**：
  - **新功能 / 复杂多文件改动 / 架构重构**：新建分支 `feat/xxx` 或 `refactor/xxx`，改完验证通过后合回 `main` 并清理分支。
  - **日常 Bug 修复 / 补单元测试 / CI与构建配置 / 纯文档 / 资源文案**：直接在 `main` 分支提交，无需新建分支，避免分支过滥。
  - **预览版（RC）热修复**：已发 RC Tag 后发现问题，必须从**该 RC Tag** 拉 `hotfix/xxx` 分支修复（**勿从最新 `main` 或功能分支拉**，否则会把已合入的未发版功能带进修复包），修复验证后升 rc 序号打 Tag 发修复版，再合回 `main` 并清理分支（详见「发版流程」）。
- **改动前先定分支**：涉及新功能开发时，先确认分支命名（如 `feat/session-model`），避免不同主题混在同一分支。
- **提交前必跑冒烟**：改完编译型代码（`.kt` / `.gradle.kts` / `AndroidManifest.xml`）→ 提交前默认 `./gradlew :app:assembleUniversalDebug` 验证可编译（**勿跑 `assembleDebug`/`assembleRelease` 三 flavor**，详见 Build and Run）。
- **推送到远端前必跑单元测试**：任何 `git push` 到远端之前，必须先跑一次单元测试 `./gradlew :app:testUniversalDebugUnitTest`（单 flavor，勿跑聚合 `test`），确认测试全部通过后再推送。改动不涉及逻辑（纯文档 / 资源文案 / 纯 `.md`）时可跳过。
- **合并入 main**：本地合并并确认无冲突后，及时清理已被合并的本地分支（`git branch -d <branch_name>`，删前用 `git branch --merged main` 确认安全）；已推送过的分支同步删除远端（`git push origin --delete <branch_name>`），避免本地删了远端残留。分支删除不影响已打的 Tag，Tag 独立引用提交，可随时 `git show <tag>` 追溯。

## 版本号规范

- **唯一事实源**：由 Git Tag / Commit 动态推导解析，**彻底无需手写 `app/build.gradle.kts` 中的 `versionName`**。
  - **`versionName`**：由 `gitVersionName()` 在构建时动态解析（如 tag 为 `v1.7.0` 则为 `1.7.0`；tag 为 `v1.7.0-rc1` 则为 `1.7.0-rc1`；非 Tag 的平时提交为 `1.7.0-dev.N+<hash>`）。
  - **`versionCode`**：由 `gitCommitCount()` 从 git 提交数自动生成，随提交单调递增，无需手动维护。
- **与 Tag 绑定**：发版时只需直接在 `main` 节点上打 git tag，例如 `v1.7.0-rc1` 或 `v1.7.0`，CI 捕获后会自动将生成的 APK 与该版本进行匹配并发布 Release。**严禁在功能分支（`feat/*` / `refactor/*`）上打 Tag 发版**，必须先合入 `main` 再打 Tag，确保发版的代码在 `main` 主线上可追溯。**唯一例外：预览版热修复**——RC 已发出后发现问题时，允许在基于该 RC Tag 的 `hotfix/*` 分支上打 rc 序号 +1 的 Tag 发修复版，修复必须随后合回 `main`（见「发版流程」）。

## 发版流程（RC 判定）

本项目靠 GitHub Release 分发且无灰度，发出去即终态，RC 是主要兜底。发版前按改动面判断是否先发 RC：

- **必须先发 RC**：本发版周期含新功能 / 行为变化（定档 `x.Y.0`）；或构建链路 / 签名 / flavor / CI 改动；或容器镜像、PRoot、ABI 相关改动。
- **可直接发正式**：本发版周期仅纯文档 / typo / 资源文案（定档 `x.y.Z`，无行为变化）。
- **看改动面**：本发版周期仅纯 bug 修复（定档 `x.y.Z`）——小改直接正式，触碰启动/容器的仍先 RC。

### 操作步骤

0. **发版前更新内置模型数据（手动执行）**：运行 `python3 scripts/update-models-dev-assets.py`，从 models.dev 拉取最新数据更新 `app/src/main/assets/api.official.json`（仅保留内置 12 个官方 provider、不引入新 provider，现有 provider 下的新模型可扩充，含单价字段）。**网络拉取/解析失败时脚本以非零退出且不改动快照——此时跳过此步直接发版，不要强行重试或手动改文件**；更新成功后需将快照改动一并提交。
1. **零代码修改发版（必须在 `main` 分支）**：无需在代码或配置中修改版本号。所有功能/修补必须先合并到 `main` 分支，在 `main` 最新的提交节点上直接打 Tag（例如 `git tag v1.7.0-rc1`）并推送：`git push origin v1.7.0-rc1`。
2. CI 接收到 `v*` Tag 后，自动捕获 Tag 版本推导生成 APK，构建 Release 发出。
3. **真机装 rc 包**，至少跑通 AI 对话 + 终端 + 容器启动三条主线。
4. 有问题 -> 从该 RC Tag 拉 `hotfix/xxx` 分支修复（**勿从最新 `main` 拉**，否则会把已合入的未发版功能带进修复包）-> 升 rc 序号打 Tag（`v1.7.0-rc2`）推送重发 -> 将修复合回 `main` 并推送 -> 删除 hotfix 分支；无问题 -> 直接打正式 Tag（`v1.7.0`）推远端转正。

## Build and Run

This is an Android application built with Kotlin, Jetpack Compose, and Hilt. It uses Gradle as the build system.

- **Build the project:** `./gradlew build` —— 完整构建，含三 flavor 全量编译 + lint + 测试，耗时极长，日常开发不用。
- **单 flavor 冒烟（AI 改完代码默认跑这个）：** `./gradlew :app:assembleUniversalDebug` —— `assembleDebug`/`assembleRelease` 是 flavor 聚合任务，会把 universal/armsolo/x86solo 三个 APK 各构一遍（三倍 Kotlin 编译 + 资源处理，慢）。AI 改完**编译型代码**（`.kt` / `.gradle.kts` / `AndroidManifest.xml`）后、提交前，默认只构 **universal debug** 单个 APK 做冒烟验证，不要触发全量三 flavor。仅改文档/资源/纯 `.md` 时可跳过。完整发版才用 `assembleRelease` 构三个。
- **Assemble Release APK:** `./gradlew assembleRelease` —— 按容器镜像/CPU 拆三个 flavor，输出到 `app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`（flavor ∈ universal/armsolo/x86solo）
- **Assemble Release AAB:** `./gradlew bundleRelease` —— 输出到 `app/build/outputs/bundle/<flavor>/release/app-<flavor>-release.aab`
- **Run Unit Tests:** `./gradlew test` —— 跨 flavor 聚合测试任务，日常用 `./gradlew :app:testUniversalDebugUnitTest` 单 flavor 即可。

### Release Packaging & Signing
The release signing configuration is automatically handled in `app/build.gradle.kts`:
- **Keystore File:** 路径由 `app/keystore.properties` 的 `storeFile` 字段指定（文件名不固定为 `aicode.jks`）。本地通常不存放签名文件，CI 从 GitHub secret 还原到 `app/aicode.jks`。
- **Credentials:** Loaded from `app/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)。
- **Target ABI:** 按 flavor 拆分：`universal` 含 `arm64-v8a` + `x86_64`，`armsolo` 仅 `arm64-v8a`，`x86solo` 仅 `x86_64`。

*Note: The project locks `targetSdk = 28` intentionally to allow PRoot execution (W^X policy bypass on Android 10+).*

## Architecture

The application is structured using a feature-based architecture with Domain-Driven Design (DDD) principles. It relies heavily on Jetpack Compose for the UI, Hilt for Dependency Injection, and Kotlin Coroutines/Flows for asynchronous operations.

### Key Components

- **App Core:** `AIEditorApp` initializes core services like `FileLogger`, `TerminalKeepaliveService`, and `McpManager`.
- **Core Module:** `app/src/main/java/com/aicode/core/` hosts cross-feature infrastructure: `FileLogger`, `db/MigrationLoader.kt`, etc.
- **Feature Modules:** Code is organized by feature under `app/src/main/java/com/aicode/feature/`:
    - `agent`: The core AI agent system. Includes prompt management, MCP (Model Context Protocol) integration, tool registry (file tools, shell execution, etc.), permission handling, and adapters for different AI providers (Anthropic, OpenAI).
    - `git`: Git integration and operations.
    - `settings`: Application configuration, including AI provider setup, logging, and keepalive settings.
    - `terminal`: Terminal emulation and session management. Local mode leverages Termux components (`terminal-emulator`, `terminal-view`) and PRoot via `LinuxContainerEngine`; remote SSH mode uses sshj (`SshShellBackend`, `RemoteTerminalSessionManager`).
    - `workspace`: Workspace and document provider management. Remote SSH file access via `RemoteSftpFileAccess`.
- **Remote SSH Link:** `RemoteSshConnection`（共享 sshj `SSHClient`）+ `RemoteSshEngine`（exec channel 执行命令）+ `RemoteSftpFileAccess`（文件操作）+ `RemoteTerminalSessionManager`（终端会话），构成远程模式下的执行链路。

### Database

The app uses Room for local database storage, primarily found in `feature/agent/data/local/database/AgentDatabase.kt` and related DAOs (e.g., `ChatSessionDao`, `AgentMessageDao`).

**Database Migrations:**
We use a custom, lightweight file-based migration system (`MigrationLoader.kt`).
To update the database schema:
1. Increment the database version in `AgentDatabase.kt`.
2. Create a new SQL file in `app/src/main/assets/migrations/` named `{VERSION}_description.sql` (e.g., `8_add_remote_servers.sql`、`26_add_session_last_input_tokens.sql`).
3. Add the necessary DDL/SQL statements to this file. The system will automatically execute it on startup and record it in the `migration_history` table.
   - ⚠️ **Warning**: the migration file is split by `;` (see `MigrationLoader`), so **no `;` may appear inside SQL string literals** (e.g. don't write `';base64,'`) — it would split the statement and fail the whole migration. Use `char(59)` if you need a literal semicolon.

### AI Agent & Tools

The AI agent interacts with the environment through a tool system (`feature/agent/domain/tool/`). Available tools include file operations (`FileTools.kt`), shell execution (`ExecuteCommandTool.kt`), and asking user questions. Tools are registered and managed via `ToolRegistry`. Permission to execute certain tools (like shell commands) is governed by `ToolPermissionManager` and `ToolPermissionPolicyEngine`.

### MCP (Model Context Protocol)

The app implements an MCP client (`feature/agent/domain/mcp/`) to connect to remote servers and dynamically register tools provided by those servers.

### Dependency Injection

Hilt is used extensively. Feature modules define their own DI modules (e.g., `AgentModule.kt`, `RepositoryModule.kt`) to provide interfaces to their implementations.