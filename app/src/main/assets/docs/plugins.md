# 插件（Plugins）

插件是扩展 AI 能力的一种方式：通过一段 JavaScript 脚本向 AI 注入自定义工具、拦截或改写工具执行、给 LLM 请求加签、注入环境变量、监听会话事件等。插件兼容 OpenCode 插件规范（`@opencode-ai/plugin`），跑在容器内的 Node.js 运行时中。

## 1. 插件入口

进入「设置」→「插件」，进入插件管理页：

*   页面采用与 MCP 一致的 iOS 分组列表样式：顶部为**插件状态**概览（运行中 / 启动中 / 启动失败 / 未启用），显示实际运行插件的运行时（`bun` 或 `node`）、已加载的插件数与工具数、当前 UDS socket 路径；加载失败时额外显示失败数量与错误信息；**配置文件解析失败**（`plugins.json` JSON 语法错误等）时显示「配置文件无效」警告（可点击复制错误详情）。
*   下方分组列出**插件**，每个插件一行，显示：插件图标、插件名称、来源 Pill（`npm` / `本地`）、工具数量 Pill、Hook 数量 Pill，以及右侧状态标记（`已加载` / `加载失败`）。
*   **左滑删除**：列表支持左滑手势露出红色删除按钮，点击可一键删除插件（包括清理对应配置文件或本地插件文件）。
*   **插件列表**：列出全部已加载插件；**声明但未安装**（npm 依赖缺失）的插件显示「未安装」状态，**被禁用**的插件显示「插件已禁用」状态并保留在列表中可随时重新启用。
*   **插件详情弹窗**：点击插件行可打开底部详情弹窗，展示插件名称、来源、版本号（npm 包读 `package.json`，本地目录型插件读其 `package.json`，单文件插件无版本）与加载状态，分类查看该插件注册的 **Tools 工具列表**（工具名、描述与参数列表）以及 **Hooks 钩子列表**（支持的所有 Hook 功能说明）；弹窗内提供**启用/禁用开关**（切换后自动重载运行时，禁用的插件不加载）；声明了 `auth` 的插件额外显示**插件认证**卡片（provider 与登录状态，点击进入登录弹窗）；详情弹窗不提供删除/关闭按钮，下滑或点击遮罩关闭，删除请使用列表左滑。
*   右上角**重载**按钮可手动重新加载所有插件。
*   未配置任何插件时，页面会提示如何放置插件脚本。

## 2. 插件存放位置（作用域）

插件分**全局**与**项目级**两级作用域，加载时取并集：

| 作用域 | 本地插件目录 | 配置/依赖文件 |
|---|---|---|
| 全局（所有项目共享） | `~/.aicode/plugins/` | `~/.aicode/plugins.json`、`~/.aicode/package.json` |
| 项目级（仅当前工作区，可随 git 提交） | `<工作区>/.aicode/plugins/` | `<工作区>/.aicode/plugins.json`、`<工作区>/.aicode/package.json` |

本地插件支持单文件（`.mjs` / `.js` / `.cjs`）或**含 `index` 的目录**（多文件插件）。

## 3. 配置方式

插件有两种来源：

**① 本地文件插件**：直接把插件脚本放入 `plugins/` 目录（全局或项目级），启动时自动扫描加载。

**② npm 包插件**：在对应作用域的 `plugins.json` 的 `plugins` 数组声明包名，启动时自动 `npm install` 并加载：

```json
{
  "plugins": ["opencode-wakatime", "@my-org/custom-plugin"],
  "disabled": ["my-local-plugin.js"]
}
```

*   `plugins`：要加载的 npm 包名（支持 scoped 包）。
*   `disabled`：被禁用的插件名（本地插件用文件名/目录名，npm 插件用包名），禁用的插件不加载。
*   本地插件与 npm 插件同名时**本地优先**，npm 包自动跳过。

**依赖管理**：插件需要外部 npm 包时，在对应作用域的 `package.json` 声明依赖。**依赖需手动安装**：在容器终端执行 `cd /root/.aicode && npm install`（全局）或 `cd .aicode && npm install`（项目级）后重载插件。App 不会自动执行安装，仅检测缺失并在日志中提示。

## 4. 加载与重载

*   **App 启动**自动加载当前工作区的插件。
*   **工作区切换**时自动重载（插件目录与项目级 `plugins.json` 随工作区变化）。
*   **手工重载**：设置 → 插件 → 右上角「重载」。
*   **外部编辑自动重载**：在容器内/外部直接编辑 `plugins.json` 或 `package.json` 后，约 2 秒内自动重载（npm 依赖变化时先 `npm install`）。

## 5. 插件能做什么（已支持能力）

插件导出一个异步函数，返回注册的 Hooks 与 Tools。以下能力已实现、可直接使用：

| Hook | 用途 |
|---|---|
| `tool` | 向模型注入**自定义工具**，模型发起调用时在 Node 容器中执行 |
| `tool.execute.before` | 工具执行前拦截：审查参数、阻止高危操作或改写参数 |
| `tool.execute.after` | 工具执行后改写输出、捕获数据落盘、过滤敏感日志 |
| `tool.definition` | 工具定义发送给模型前动态改写描述、隐藏参数（已实现） |
| `chat.headers` | LLM 请求发出前注入鉴权 Token、签名、私有网关头（input 对齐 opencode：sessionID/agent/model/provider/message） |
| `chat.params` | LLM 请求发出前动态调整推理参数（temperature/topP/topK/maxOutputTokens 等；input 对齐 opencode：sessionID/agent/model/provider/message） |
| `chat.message` | 用户消息落库前改写输入（对齐 opencode：output 为 `{message: UserMessage, parts: Part[]}`，插件改写 parts 中的 text part；input 含 sessionID/messageID/agent/model） |
| `auth.loader` | 请求时动态提供认证信息：`headers`/`apiKey` 合并进请求头；返回自定义 `fetch` 时该 provider 的 LLM 请求（含流式）经容器内本地代理转发，支持 OAuth 刷新/多账户/限流类插件 |
| `experimental.provider.small_model` | 未配置压缩/标题专用模型时提供小模型建议 |
| `experimental.chat.system.transform` | 系统提示词组装后注入额外上下文（⚠️ 会打断隐式前缀缓存，慎用） |
| `experimental.chat.messages.transform` | 模型请求发送前裁剪历史消息、脱敏敏感数据（对齐 opencode：output 为 `{info: Message, parts: Part[]}[]`，插件改写后回写） |
| `experimental.session.compacting` | 上下文压缩生成摘要前注入需跨压缩保留的上下文 |
| `shell.env` | Bash 工具/终端执行命令前注入环境变量 |
| `permission.ask` | 高危工具权限弹窗前自动化允许/拒绝 |
| `command.execute.before` | Slash 命令执行前拦截（对齐 opencode：output 为 `{parts: Part[]}`，改写 text part 或置空阻止执行） |
| `provider.models` | 为指定 provider 提供模型目录（如私有网关模型清单） |
| `event` | 监听会话与工作流事件（见下方列表） |
| `dispose` | 插件卸载/会话销毁时清理资源 |

**`event` 可监听的事件**（已实现）：`session.created`（普通会话与子代理会话创建均触发，子代理额外带 `parentID`/`subagentType`）、`session.updated`、`session.deleted`、`session.status`（会话开始/结束运行，`status.type` 为 `busy`/`idle`，由 SessionActivityRegistry 自动派发）、`session.idle`（AI 回复完成）、`session.error`（AI 回复失败）、`message.created`、`mode.changed`、`tool.started`、`tool.finished`、`permission.asked` / `permission.replied`、`file.edited`、`todo.updated`、`command.executed`、`compaction.started` / `compaction.finished` / `compaction.failed`、`llm.retrying`。

### plugin 入参中的 `client`（SDK Client）

插件入口函数与工具 `execute` 上下文的 `client` 对象提供对宿主的编程式访问（对齐 `@opencode-ai/sdk` 的 fields 风格，返回值统一为 `{ data: ... }`）：

| API | 状态 | 说明 |
|---|---|---|
| `client.app.log()` | ✅ 已支持 | 结构化日志（service/level/message/extra），写入宿主日志 |
| `client.app.agents()` | ✅ 已支持 | 列出可用 agent（对齐 opencode `Agent[]`：name/description/mode/builtIn/permission/tools/options；内置 general/build/plan 子代理类型，name 记录为子会话 `subagentType`） |
| `client.global.health()` | ✅ 已支持 | 健康检查（本地实现，opencode 无此端点） |
| `client.project.get()` | ✅ 已支持 | 项目信息（对齐 opencode `Project`：id/worktree/vcsDir/vcs/time） |
| `client.session.get()` | ✅ 已支持 | 按 id 查会话（对齐 opencode `Session`：id/slug/projectID/directory/title/version/time/model/agent/parentID 等） |
| `client.session.list()` | ✅ 已支持 | 当前工作区会话列表（对齐 opencode `Session[]` 直接数组） |
| `client.session.children()` | ✅ 已支持 | 指定会话的全部子会话（子代理）列表（对齐 `Session[]`） |
| `client.session.create()` | ✅ 已支持 | 创建会话（返回 `Session`）；`body.parentID` 存在时创建**子代理会话**（继承父会话模型配置，禁止嵌套） |
| `client.session.prompt()` | ✅ 已支持 | 向会话发送消息触发 AI 回复（对齐 opencode `prompt_async`，立即返回 void）；`body.noReply=true` 仅注入上下文不触发回复；`body.model` 可选覆盖会话模型；`parts` 中 text 部分拼接为消息，**subtask 部分（`{type:"subtask",prompt,description,agent}`）创建子代理会话并自动启动执行**（每个 subtask 一个子代理，受 5 个并发上限约束） |
| `client.session.promptAsync()` | ✅ 已支持 | `prompt` 的别名（AiCode 的 prompt 本就异步派发、立即返回，语义一致） |
| `client.session.status()` | ✅ 已支持 | 运行中会话（对齐 opencode `Record<sessionID, {type:"busy"}>`） |
| `client.session.delete()` | ✅ 已支持 | 删除会话（含全部子代理会话与消息，运行中先停止；返回 boolean） |
| `client.session.update()` | ✅ 已支持 | 更新会话元数据（目前仅 `title`，返回 `Session`） |
| `client.session.messages()` | ✅ 已支持 | 指定会话最近 100 条消息（对齐 opencode `{info, parts}[]`：USER 行→User+text part、ASSISTANT 行→Assistant+text/reasoning part、TOOL 行→合并进最近 assistant 消息的 ToolPart） |
| `client.files.read()` / `write()` / `list()` | ✅ 已支持 | 工作区文件读写列目录（限定在工作区内；read 对齐 `FileContent`（type 为 text/binary，二进制 base64 编码），list 对齐 `FileNode[]`） |
| `client.config.get()` | ✅ 已支持 | 宿主核心配置（对齐 opencode Config 字段命名的最小子集：`model`/`small_model` 为 `provider/model` 字符串） |
| `client.tui.showToast()` | ✅ 已支持 | 映射为 Android Toast 提示 |
| `client.auth.set()` | ✅ 已支持 | 写入/更新/删除插件认证凭据（宿主 `auth.json`，对齐 opencode）；`body` 传 `null` 删除 |
| `client.auth.list()` | ✅ 已支持 | 列出已配置凭据的 provider id |
| `client.auth.get()` | ✅ 已支持 | 读取指定 provider 的凭据（供 `auth.loader` 的 `getAuth()` 实时取用） |
| `client.auth.logout()` | ✅ 已支持 | 删除指定 provider 的凭据（等价 `set(..., null)`） |
| `client.config.set` / `client.tui.openXxx` / `client.event.subscribe` 等 | ❌ 不支持 | 调用返回明确错误（不抛 TypeError），插件可 try/catch 降级 |

插件能力以本文档与插件详情弹窗中的 Hooks/Tools 列表为准。

### 插件认证（登录）

声明 `auth` 的插件（如 OAuth 订阅类插件 `opencode-antigravity-auth`）可在「设置 → 插件 → 点击插件 → 插件认证」卡片中完成登录：

*   卡片显示插件声明的 `auth.provider` 与登录状态（已登录 / 未登录），点击进入认证弹窗。
*   弹窗列出插件声明的登录方法（`auth.methods`，OAuth 或 API Key 类型）。
*   **OAuth 流程**：点击方法后插件返回授权链接与操作说明 → 点击链接在系统浏览器中完成授权 → 按提示「输入授权码」提交或点「已完成授权」；成功后凭据（access/refresh token）由插件经 `client.auth.set` 写入宿主 `auth.json`。
*   **API Key 流程**：直接输入 key 保存；插件声明了 `authorize` 时先执行其校验/转换逻辑。
*   **退出登录**：删除该 provider 的凭据。

认证后的请求行为取决于插件 `auth.loader` 的返回：

*   返回 `headers` / `apiKey`：合并进该 provider 的 LLM 请求头（`apiKey` 按 provider 类型转 `Authorization: Bearer` / `x-api-key` / `x-goog-api-key`）。
*   返回自定义 `fetch`（多账户轮换、OAuth 刷新、限流、响应转换等）：该 provider 的 LLM 请求（含流式 SSE）经容器内本地代理转发给插件处理，provider 的 API Key 可留空。

凭据存储位置：宿主私有目录 `aicode/auth.json`（容器内 `/root/.aicode/auth.json`），明文存储，与 AiCode 用户 API Key（设置 → 模型）相互独立。

### 插件 provider 自动注册（虚拟 provider）

声明 `auth` 的插件加载后，会自动注册一个以 `auth.provider` 为 id 的**虚拟 provider**（如 `opencode-xai-oauth` 的 `xai`、`opencode-antigravity-auth` 的 `google`），随插件加载/卸载自动出现/消失，不写入用户提供商配置：

*   **模型选择**：主页聊天输入框的模型选择弹窗与「设置 → 默认模型」中会出现该虚拟 provider（按插件名分组），模型列表来自内置模型目录（models.dev 数据）中同 id 提供商的对话模型（如 `xai` 下的 Grok 系列）；插件若声明 `provider.models` 则以其为准。
*   **设置页提供商列表**：虚拟 provider 会显示在「设置 → AI 提供商」列表中（带 **插件认证** 标签），可点击进入编辑页管理模型：默认列出 models.dev 目录中的模型，可手动添加/删除模型，「拉取模型」展示该提供商的目录模型列表供勾选添加；凭据由插件提供（编辑页不显示 API Key 输入，Base URL 只读展示自动解析值）；不支持左滑删除（生命周期归插件）。
*   **对话使用**：会话绑定该 provider 后，请求自动匹配插件 `auth.loader`（返回自定义 `fetch` 时经本地代理转发），API Key 无需填写；类型（OpenAI/Anthropic/Gemini）用 models.dev 的 `npm` 字段判断（`@ai-sdk/anthropic` → Anthropic，`@ai-sdk/google` → Gemini，其他 → OpenAI 兼容），兜底为 OpenAI 兼容。
*   若用户在设置中手动添加了同 id 的提供商，以用户配置为准（覆盖虚拟 provider）。

## 6. 不支持

以下 OpenCode 能力 **AiCode 明确不支持**（无对应机制）：`config`（插件配置的读写，即 `client.config.set`）、`experimental.text.complete`、`experimental_workspace` / `serverUrl`。社区插件若依赖上述能力需改造或放弃。
*   插件认证：`auth.methods`（OAuth / API Key 登录）与 `client.auth.*`（凭据读写）已支持，见上文「插件认证（登录）」；插件无法读写 AiCode 用户配置的 API Key（`ai_providers` 表），凭据存独立 `auth.json`。
*   `client.session.prompt` 已开放（插件可驱动普通会话与子代理会话，产生 token 费用），`agent`/`system`/`tools` 等 opencode 字段暂不生效；`parts` 支持 `text`（发消息）与 `subtask`（派发子代理，opencode 子代理协议）。

## 7. 简单示例

在 `~/.aicode/plugins/` 下新建 `my-tools.mjs`：

```javascript
import { tool } from '@opencode-ai/plugin';
import { createHash } from 'node:crypto';

export const MyTools = async () => {
  return {
    tool: {
      md5: tool({
        description: "计算字符串的 MD5 值",
        args: {
          text: tool.schema.string({ description: "要计算的明文" })
        },
        async execute(args) {
          return createHash('md5').update(args.text).digest('hex');
        }
      })
    }
  };
};
```

保存后到「设置 → 插件」点「重载」，运行时即加载该插件，模型便可通过 `md5` 工具计算哈希。

更多 Hook 签名与示例可参考插件详情弹窗中的 Hooks 说明。

## 8. 日志与排查

插件相关日志统一写入宿主日志（设置 → 日志），可通过以下特征定位问题：

*   **插件名标识**：`runner` 进程输出以 `plugin-runner:` 前缀进入宿主日志，其中插件名以 `[插件名]` 标记（如 `[test-plugin] event: session.created`、`[test-plugin] 执行工具 md5 args=...`），可区分日志来自哪个插件。
*   **宿主侧请求日志**：插件调用 `client.*` API 时，宿主日志会记录 `← [client.session.create] id=... plugin=插件名`，可确认是哪个插件发起的会话创建/查询/删除等操作。
*   **工具调用日志**：AI 调用插件工具时记录 `调用插件工具 工具名 [plugin=插件名] args=...`，并附执行耗时与成功/失败状态。
*   **插件自身输出**：插件代码中的 `console.log` / `console.error` 会以 `plugin-runner:` 前缀进入宿主日志，排查时可直接在插件脚本中添加输出。
*   **加载失败**：插件加载失败时，日志会列出失败插件名与原因（`plugins.list` 与运行时就绪日志均包含明细）。