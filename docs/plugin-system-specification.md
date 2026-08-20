# AiCode 通用插件系统设计与开发规范（兼容 OpenCode 插件规范）

## 一、系统概述与设计哲学

AiCode 插件系统是一套**运行于 PRoot Linux 容器内 Node.js 运行时**、通过 **Unix Domain Socket (UDS)** 与 Android App 宿主双向通信的通用扩展框架。

### 核心定位
1. **兼容 OpenCode 插件生态**：API 契约、Hook 签名与上下文定义对齐 OpenCode 插件规范，社区现有插件（npm 包与本地脚本）经少量适配即可复用。兼容以 AiCode 实际能力为准，详见「二、兼容性总览」中的支持矩阵——**未列出的能力视为不支持，社区插件依赖这些能力时需要改造**。
2. **零 APK 包体积负担**：依托容器内置的 Node.js 运行时执行，App 端无需内嵌重型 JS 引擎。
3. **安全与进程级隔离**：插件作为独立的伴生进程（Sidecar）运行，其异常崩溃与内存波动不会波及宿主 App。
4. **全生命周期拦截与扩展**：覆盖从用户消息输入、Prompt 拼装、请求头加签、Token 流式处理到工具调用前后的全链路。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          AiCode 宿主工作流引擎 (Kotlin)                      │
│                                                                             │
│  [用户输入] ──► chat.message ──► chat.headers/params ──► [LLM 流式调用]     │
│                                                               │             │
│  [Agent 循环] ◄── tool.execute.after ◄── 执行工具 ◄── tool.execute.before   │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                    PluginManager (Kotlin 宿主)                        │  │
│  └───────────────────────────────────▲───────────────────────────────────┘  │
└──────────────────────────────────────┼──────────────────────────────────────┘
                                       │ (Unix Domain Socket / HTTP JSON-RPC)
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                    PRoot Linux 容器 / Node.js 运行时 (Sidecar)               │
│                                                                             │
│  ┌───────────────────────── PluginRuntimeHub ──────────────────────────────┐  │
│  │  - 插件加载器 (Loader): 动态扫描并加载全局与工作区插件                   │  │
│  │  - 兼容层 (Compat Layer): 提供 @opencode-ai/plugin & SDK 模拟实现        │  │
│  │  - 钩子执行管道 (Hook Pipeline): 链式分发与执行各插件注册的 Hook         │  │
│  └───────────────────────────────────┬───────────────────────────────────┘  │
│                                      ▼                                      │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                     用户编写 / 社区 OpenCode 插件 (.js / .ts)          │  │
│  │  - 自定义工具插件 (如 generate_image 绘图与文件落盘)                     │  │
│  │  - 协议加签与网关适配 (如 chat.headers 动态签名)                         │  │
│  │  - 安全审查与行为拦截 (如 env-protection, shell.env)                     │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 二、兼容性总览

兼容原则：**签名对齐 OpenCode，落点对应 AiCode**。凡 AiCode 工作流中存在等价阶段的能力，按官方签名提供；官方签名依赖 AiCode 不存在的概念（Part、Model/Provider 对象、OAuth 等）时，按 AiCode 实际模型适配并在本文档标注差异；AiCode 完全没有对应机制的能力，明确列为不支持。

### 1. 能力支持矩阵

| 能力 | 兼容级别 | 说明 |
|---|---|---|
| `tool` 自定义工具注入 | ✅ 对齐 | 工具注册进 ToolRegistry，随内置工具一起发给模型 |
| `tool.execute.before` | ✅ 对齐 | 工具执行前拦截，可改参数或抛错阻止 |
| `tool.execute.after` | ✅ 对齐 | 工具执行后改写结果 |
| `tool.definition` | ✅ 对齐 | 工具 schema 发送前改写描述/参数 |
| `chat.message` | ✅ 适配 | 无 Part 概念，output 为 `{ message: { role, content } }`，可改 content；图片经 `message.images` 单独携带 |
| `chat.headers` | ✅ 适配 | input 中 `model`/`provider` 为字符串（AiCode 模型即字符串）；落点在 provider adapter 请求构建处 |
| `chat.params` | ✅ 适配 | 同上，`model` 为字符串；`options` 原样并入 provider 请求体 |
| `experimental.chat.system.transform` | ✅ 对齐 | system prompt 组装后触发，`model` 为字符串 |
| `experimental.chat.messages.transform` | ✅ 适配 | output 为 AiCode 消息模型数组（见 §五.9），非 opencode 的 `{info, parts}` 结构 |
| `shell.env` | ✅ 对齐 | Bash 工具/终端命令执行前注入环境变量 |
| `permission.ask` | ✅ 对齐 | 权限弹窗前触发，input 补充 AiCode 的 `mode` 字段 |
| `event` | ✅ 适配 | 事件集映射 AiCode 的 AgentEvent，见 §五.12 |
| `experimental.session.compacting` | ✅ 适配 | 映射 AiCode 的 ContextCompactor：压缩摘要生成前注入上下文 |
| `dispose` | ✅ 对齐 | 插件卸载/会话销毁时清理 |
| `provider`（模型列表扩展） | ✅ 适配 | 插件返回的模型目录合并进模型列表（见 §五.15） |
| `auth`（动态认证信息） | ✅ 适配 | 仅支持 `auth.loader`（请求时动态提供认证头）；OAuth 交互流程不支持（AiCode 为手动填 Key） |
| `command.execute.before` | ✅ 适配 | 拦截/改写 SlashCommand 执行（见 §五.16） |
| `experimental.provider.small_model` | ✅ 适配 | 为压缩/标题等轻量任务提供小模型建议（见 §五.17） |
| `config` | ❌ 不支持 | AiCode 配置结构不同于 opencode Config |
| `experimental.text.complete` | ❌ 不支持 | AiCode 无文本补全 UI 路径 |
| `experimental_workspace` / `serverUrl` | ❌ 不支持 | AiCode 无工作区适配器注册机制，插件入口不注入这两个字段 |

### 2. 事件支持矩阵（`event` hook 内可监听到的事件）

| 事件 | 兼容级别 | 触发时机 |
|---|---|---|
| `session.created` | ✅ | 新会话建立 |
| `session.idle` | ✅ | 一轮 Agent 循环正常结束（对应 AgentEvent.Completed） |
| `session.error` | ✅ | 一轮循环失败（对应 AgentEvent.Failed） |
| `session.updated` | ✅ | 会话元数据变更（标题、绑定 provider 等） |
| `message.created` | ✅ | 新消息落库（AiCode 特有，opencode 无同名事件） |
| `tool.started` | ✅ | 工具开始执行（对应 AgentEvent.ToolCallStarted） |
| `tool.finished` | ✅ | 工具执行完成（对应 AgentEvent.ToolCallFinished） |
| `mode.changed` | ✅ | 计划/构建/自动模式切换（AiCode 特有） |
| `compaction.started` / `compaction.finished` / `compaction.failed` | ✅ | 上下文压缩生命周期（AiCode 特有） |
| `llm.retrying` | ✅ | 网络请求重试（AiCode 特有） |
| `todo.updated` | ✅ | TodoTool 执行后派发（AiCode 有 todo 工具） |
| `permission.asked` / `permission.replied` | ✅ | 权限弹窗前/用户答复后派发 |
| `file.edited` | ✅ | editFile/writeFile 成功修改后派发 |
| `command.executed` | ✅ | SlashCommand 执行后派发 |
| `lsp.*`、`tui.*`、`server.connected`、`installation.updated` | ❌ 不支持 | AiCode 无对应系统，不派发 |

---

## 三、目录结构与作用域规范

统一收敛于 `.aicode` 规范目录下，分全局与项目两级作用域，加载时取并集（项目级同名项优先）：

### 1. 目录结构
```text
# 全局作用域（跨项目通用，保存在容器持久化根目录）
~/.aicode/
├── plugins/                     # 全局插件目录
│   ├── my-global-plugin.js      # 单文件插件
│   └── custom-tools/            # 多文件/目录型插件
│       └── index.js
├── plugins.json                 # 全局插件配置（启停名单 + npm 插件声明）
├── package.json                 # 全局 npm 依赖声明
└── node_modules/                # npm install 产物（自动生成）

# 项目级作用域（当前工作区专属，可随 Git 仓库提交）
<workspace>/.aicode/
├── plugins/                     # 项目插件目录
│   ├── local-hook.js
│   └── project-adapter/
│       └── index.js
├── plugins.json                 # 项目插件配置（启停名单 + npm 插件声明）
├── package.json                 # 项目 npm 依赖声明
└── node_modules/                # npm install 产物（自动生成）
```

### 2. 插件来源与安装机制

插件支持两种来源：

**① 本地文件插件**：直接放入 `plugins/` 目录，启动时自动扫描加载。

**② npm 包插件**：在 `plugins.json` 的 `plugins` 数组声明包名，启动时自动 `npm install` 到对应作用域的 `node_modules/` 并加载：

```json
{
  "plugins": ["opencode-wakatime", "@my-org/custom-plugin"],
  "disabled": ["my-global-plugin.js"]
}
```

- `plugins`：要加载的 npm 包名（支持 scoped 包）。
- `disabled`：被禁用的插件名（本地插件用文件名/目录名，npm 插件用包名），禁用的插件不加载。
- 本地插件与 npm 插件同名时按「本地优先」处理，npm 包自动跳过。

**依赖管理**：插件需要外部 npm 包时，在对应作用域的 `package.json` 声明依赖。App 启动插件运行时或检测到 `package.json` / `plugins.json` 变动时，自动在容器内执行 `npm install`。

### 3. 加载顺序与冲突规则

1. 全局 `plugins.json` 声明的 npm 插件
2. 项目 `plugins.json` 声明的 npm 插件
3. 全局 `~/.aicode/plugins/` 目录下的本地插件
4. 项目 `<workspace>/.aicode/plugins/` 目录下的本地插件

规则：
- 同名 npm 包只加载一次；本地插件与 npm 插件同名时本地优先，npm 跳过。
- 同一 Hook 被多个插件注册时，按上述加载顺序依次执行、后执行者覆盖前者的 output 字段（已由前者写入的字段不再被后者覆盖为 undefined；后者显式写入的字段覆盖前者）。
- 工具同名冲突：插件工具与内置工具同名时**插件工具优先**（与 OpenCode 一致）；多个插件声明同名工具时按加载顺序后者覆盖前者。

---

## 四、插件入口与上下文注入规范

插件模块支持 ES Module (`.mjs`, `.js` 带 `"type": "module"`) 与 CommonJS。标准插件导出为一个或多个异步初始化函数。

### 1. 插件入口函数签名
```typescript
import type { Plugin } from '@opencode-ai/plugin';

export const MyPlugin: Plugin = async ({
  project,    // 项目元数据信息 { id, name, directory }
  client,     // AiCode API Client 实例
  $,          // 命令执行器（Node 简化实现，见下方说明）
  directory,  // 当前工作区物理绝对路径 (如 /root/workspace)
  worktree    // Git 仓库工作树物理路径
}) => {
  return {
    // 返回注册的 Hooks 与 Tools 字典
  };
};
```

**字段说明**：
- `project`、`client`、`$`、`directory`、`worktree` 与 OpenCode 对齐。
- ⚠️ OpenCode 的 `serverUrl`、`experimental_workspace` 字段 **AiCode 不提供**（无对应机制），社区插件若依赖这两个字段需改造。

### 2. `$` 命令执行器兼容说明

OpenCode 的 `$` 是 **Bun Shell API**（模板字符串语法、管道、重定向、内嵌 JS 表达式等）。AiCode 容器运行时为 **Node.js（v20+，无 Bun）**，兼容层提供 `$` 的**简化实现**：

- 支持：`` $\`command arg1 arg2\` `` 模板字符串执行命令、`$\`cmd1 | cmd2\`` 管道、`cwd`/`env` 选项、`quiet()`/`nothrow()` 链式调用。
- 不支持：Bun Shell 的高级特性（`$\`echo ${expr}\`` 内嵌 JS 表达式求值、`$.cwd()` 动态切换、`onStdout` 流式回调等）。社区插件若重度依赖这些特性需改为显式 `child_process` 或 `execSync`。

### 3. TypeScript 类型支持

兼容层在容器内提供 `@opencode-ai/plugin` 名称解析（shim 包，含 `tool`、`tool.schema` 导出与 `Plugin`/`ToolDefinition` 类型声明）。插件可直接 `import type { Plugin } from '@opencode-ai/plugin'`。类型声明以本文档为准，与官方类型存在差异处已在「二、兼容性总览」标注。

---

## 五、Hooks 规范与 API 签名参考

### 1. 自定义工具注入 (`tool`) ✅
允许插件向模型动态注入自定义工具，大模型发起调用时直接在 Node 容器中执行。工具会注册进宿主 `ToolRegistry`，与内置工具一同随每次请求发给模型。

```typescript
tool: {
  [toolName: string]: ToolDefinition;
}
```

**定义工具示例：**
```javascript
import { tool } from '@opencode-ai/plugin';

export const CustomToolsPlugin = async () => {
  return {
    tool: {
      calculate_hash: tool({
        description: "计算指定字符串的哈希值",
        args: {
          text: tool.schema.string({ description: "要计算的明文" }),
          algorithm: tool.schema.enum(["md5", "sha256"], { description: "哈希算法" })
        },
        async execute(args, context) {
          // args: 模型传入的参数对象
          // context: { directory, worktree } 与官方一致
          const hash = crypto.createHash(args.algorithm).update(args.text).digest('hex');
          return `Hash (${args.algorithm}): ${hash}`;
        }
      })
    }
  };
};
```

**签名说明**：`execute(args, context)` 与 OpenCode 一致，`context` 提供 `{ directory, worktree, sessionID }`（`sessionID` 为当前会话 ID，插件可用作 MCP 会话隔离等用途）；返回字符串即工具结果，返回对象会被 JSON 序列化后回填模型。

### 2. 工具执行前拦截 (`tool.execute.before`) ✅
在内置或扩展工具执行前触发，可审查参数、阻止高危操作或改写参数。执行顺序位于宿主权限评估通过之后、工具真正执行之前。

```typescript
"tool.execute.before"?: (
  input: { tool: string; sessionID: string; callID: string },
  output: { args: any }
) => Promise<void>;
```

**注**：抛出的异常会使该工具执行失败（结果以 `TOOL_EXECUTION_FAILED` 形式回填模型），这是拦截类插件的标准做法（见案例三）。

### 3. 工具执行后拦截与结果加工 (`tool.execute.after`) ✅
在工具执行完毕后触发，可改写输出、捕获二进制数据落盘、过滤敏感日志。

```typescript
"tool.execute.after"?: (
  input: { tool: string; sessionID: string; callID: string; args: any },
  output: { title: string; output: string; metadata: any }
) => Promise<void>;
```

### 4. 发送给模型的工具定义改写 (`tool.definition`) ✅
在向大模型发送工具列表前触发，可动态修改工具描述或隐藏某些参数。

```typescript
"tool.definition"?: (
  input: { toolID: string },
  output: { description: string; parameters: any }
) => Promise<void>;
```

### 5. 用户消息接收拦截 (`chat.message`) ✅ 适配
用户发送新消息时触发，可改写用户提示词、拦截特定 Slash 命令。**落点在 `AIAgentViewModel.executeAgentRequestStream`（消息落库之前）**——消息落库发生在 ViewModel，改写须在 persist 之前才有意义。

```typescript
"chat.message"?: (
  input: { sessionID: string; messageID?: string },
  output: { message: { role: string; content: string } }
) => Promise<void>;
```

**差异说明**：OpenCode 的 output 为 `{ message: UserMessage; parts: Part[] }`，AiCode 无 Part 概念（消息模型为 `content` + 可选 `images`），故只提供 `message` 字段，修改 `content` 即改写用户输入。社区插件若依赖 `parts` 需改造。

### 6. 请求头与鉴权加签 (`chat.headers`) ✅ 适配
向 LLM Provider 发送 HTTP 请求前触发，用于注入鉴权 Token、签名、私有网关头。落点在 provider adapter（OpenAI/Anthropic/Gemini）的请求构建处。

```typescript
"chat.headers"?: (
  input: { sessionID: string; model: string; provider: string },
  output: { headers: Record<string, string> }
) => Promise<void>;
```

**差异说明**：`model`/`provider` 为字符串（AiCode 的模型即字符串、provider 为配置名），OpenCode 中为 `Model`/`ProviderContext` 对象。社区插件若访问 `input.model.id`、`input.provider.info` 等对象字段需改造为字符串比较。

### 7. 模型推理参数改写 (`chat.params`) ✅ 适配
向 LLM Provider 发送 HTTP 请求前触发，用于动态调整推理参数。落点同 `chat.headers`。

```typescript
"chat.params"?: (
  input: { sessionID: string; model: string },
  output: {
    temperature?: number;
    topP?: number;
    topK?: number;
    maxOutputTokens?: number;
    options?: Record<string, any>
  }
) => Promise<void>;
```

**差异说明**：output 字段均为可选——插件未写入的字段保持 AiCode 现状（OpenAI/Gemini 请求体不携带 temperature/topP，用服务端默认；Anthropic 硬编码 temperature=0.7，thinking 模式下为 null）。插件写入的字段会**新增注入**到 provider 请求体（temperature/topP/topK/maxOutputTokens 按各协议字段名映射，OpenAI 为 `temperature`/`top_p`/`max_tokens`，Anthropic 为 `temperature`/`top_p`/`max_tokens`，Gemini 为 `generationConfig.temperature` 等）；`options` 中的键值对原样并入请求体（用于私有字段、路由参数等）。

⚠️ **Anthropic thinking 模式限制**：Anthropic API 要求 thinking 模式下 `temperature` 必须为 null，插件改写 temperature 时若目标模型开启了 extended thinking，需自行判断并跳过，否则请求会 400。

### 8. 系统提示词转换 (`experimental.chat.system.transform`) ✅
组装 System Prompt 时触发，可向模型注入动态项目规范或额外上下文。落点在 `SystemPromptProvider.build` 之后。

```typescript
"experimental.chat.system.transform"?: (
  input: { sessionID?: string; model: string },
  output: { system: string[] }
) => Promise<void>;
```

**注**：`system` 数组元素按顺序拼接为最终 system prompt。⚠️ 修改 system 会改变请求前缀、打断 AiCode 的隐式前缀缓存（影响缓存命中与费用），插件应仅在确有需要时注入。

### 9. 历史消息列表转换 (`experimental.chat.messages.transform`) ✅ 适配
在模型请求发送前触发，可用于历史消息裁剪、敏感数据脱敏。

```typescript
"experimental.chat.messages.transform"?: (
  input: { sessionID?: string },
  output: { messages: AiCodeMessage[] }
) => Promise<void>;
```

**差异说明**：output 为 **AiCode 消息模型**数组，非 OpenCode 的 `{ info, parts }[]`。AiCode 消息模型 JSON 形态：

```json
{ "type": "user", "id": "", "content": "文本内容", "images": [] }
{ "type": "assistant", "id": "", "content": "正文", "toolCalls": [], "reasoning": "", "signature": "" }
{ "type": "toolResult", "id": "", "toolName": "readFile", "result": "{\"status\":\"success\",...}", "images": [] }
```

### 10. Shell 执行环境变量注入 (`shell.env`) ✅
在 Bash 工具或终端执行命令前触发，用于向子进程注入动态环境变量。落点在 `ExecuteCommandTool` 及终端命令执行路径。

```typescript
"shell.env"?: (
  input: { cwd: string; sessionID?: string; callID?: string },
  output: { env: Record<string, string> }
) => Promise<void>;
```

### 11. 权限拦截与自动化决策 (`permission.ask`) ✅
在触发高危工具权限确认弹窗前调用，可自动化允许或拒绝特定操作。落点在宿主管权限评估（`ToolPermissionPolicyEngine` 判定 + 弹窗）之前。

```typescript
"permission.ask"?: (
  input: { tool: string; args: any; mode: "PLAN" | "BUILD" | "AUTO" },
  output: { status: "ask" | "deny" | "allow" }
) => Promise<void>;
```

**差异说明**：input 在官方 `{ tool, args }` 基础上补充 AiCode 的 `mode`（当前 Agent 模式，PLAN 模式下写操作工具默认被策略拒绝）。`status` 语义：`allow` 直接放行（跳过弹窗与策略拒绝）、`deny` 直接拒绝、`ask` 走正常弹窗流程。多个插件注册时按加载顺序执行，后执行者覆盖前者的 status。

### 12. 工作流事件总线监听 (`event`) ✅ 适配
监听会话与工作流内部事件。事件集映射 AiCode 的 AgentEvent，完整列表见「二、兼容性总览」的事件支持矩阵。

```typescript
"event"?: (
  input: { event: { type: string; properties?: any } }
) => Promise<void>;
```

示例：监听会话结束发送通知。

```javascript
export const NotificationPlugin = async () => {
  return {
    event: async ({ event }) => {
      if (event.type === "session.idle") {
        // 发送系统通知
      }
    }
  };
};
```

### 13. 压缩上下文注入 (`experimental.session.compacting`) ✅ 适配
上下文压缩（ContextCompactor）生成摘要前触发，可注入跨压缩周期需要保留的额外上下文。

```typescript
"experimental.session.compacting"?: (
  input: { sessionID: string },
  output: { context: string[]; prompt?: string }
) => Promise<void>;
```

**差异说明**：`context` 数组中的字符串会追加到 AiCode 压缩摘要指令中；`prompt` 若设置则整体替换默认压缩指令（与 OpenCode 语义一致）。

### 14. 插件卸载清理 (`dispose`) ✅
插件被禁用、会话销毁或 App 退出时触发，用于释放资源、清理临时文件。

```typescript
dispose?: () => Promise<void>;
```

### 15. 模型列表扩展 (`provider`) ✅ 适配
插件可为指定 provider 提供模型目录（如私有网关的模型清单），返回的模型合并进 AiCode 模型列表（`ModelMetadataService` 的目录合并处），用户即可在设置中选择。

```typescript
provider?: {
  id: string;
  models?: (provider: string, ctx: { auth?: any }) => Promise<Record<string, ModelMeta>>;
}
```

其中 `ModelMeta` 为 AiCode 模型元数据的 JSON 形态：

```json
{
  "modelId": {
    "contextTokens": 128000,
    "supportsVision": false,
    "supportsReasoning": false
  }
}
```

**差异说明**：OpenCode 的 `provider` hook 同时承担认证与模型两个职责，AiCode 只适配**模型目录**部分；`provider.id` 匹配 AiCode 的 provider 配置名（字符串）。

### 16. Slash 命令执行前拦截 (`command.execute.before`) ✅ 适配
AiCode 内置 SlashCommand 系统（`/status`、`/compress` 等），本 hook 在命令执行前触发，可改写参数或阻止执行。

```typescript
"command.execute.before"?: (
  input: { command: string; sessionID: string; arguments: string },
  output: { args: string }
) => Promise<void>;
```

**差异说明**：OpenCode 的 output 为 `{ parts: Part[] }`（命令展开为消息分片），AiCode 无 Part 概念，适配为改写命令参数字符串；`output.args` 置空字符串可阻止命令执行。

### 17. 动态认证信息 (`auth`) ✅ 适配
AiCode 为手动填写 API Key，**不支持** OpenCode 的 OAuth/API Key 授权交互流程（`auth.methods`）；但支持 `auth.loader`：在每次 LLM 请求构建时动态提供/刷新认证信息（临时凭证、动态签名等），与 `chat.headers` 共用 provider adapter 的请求构建注入点。

```typescript
auth?: {
  provider: string;
  loader?: (auth: () => Promise<any>, provider: string) => Promise<Record<string, any>>;
}
```

**差异说明**：返回的键值对合并进请求头（`Authorization`、`X-Api-Key` 等），优先级低于用户手动配置的 API Key；`auth.methods`（OAuth 弹浏览器授权、回调）不支持。

### 18. 轻量任务小模型建议 (`experimental.provider.small_model`) ✅ 适配
AiCode 有压缩模型与标题生成模型两类轻量任务模型（设置中独立配置）。本 hook 为 provider 提供默认小模型建议，作为这两处设置的可选候选来源。

```typescript
"experimental.provider.small_model"?: (
  input: { provider: string },
  output: { model?: string }
) => Promise<void>;
```

**差异说明**：AiCode 模型即字符串，output 直接给模型 id（OpenCode 中为 `ModelV2` 对象）；仅当用户未手动指定压缩/标题模型时，建议值才生效。

### 19. 明确不支持的 Hooks

以下 OpenCode 能力 **AiCode 不支持**（无对应机制或适配价值低），社区插件若依赖需改造或放弃：

| Hook | 原因 |
|---|---|
| `config` | AiCode 配置结构不同于 opencode Config，适配意义低 |
| `experimental.text.complete` | AiCode 无文本补全 UI 路径 |
| `experimental_workspace` / `serverUrl` | 工作区适配器为 opencode 架构概念，AiCode 远程模式为内置 SSH，插件入口不注入这两个字段 |

---

## 六、核心实战案例

### 案例一：自定义生图工具与工作区自动落盘
```javascript
import fs from 'fs';
import path from 'path';
import { tool } from '@opencode-ai/plugin';

export const ImageGenPlugin = async () => {
  return {
    tool: {
      generate_image: tool({
        description: "根据提示词生成图片并自动保存到当前工作区",
        args: {
          prompt: tool.schema.string({ description: "绘图英文提示词" })
        },
        async execute(args, context) {
          // 1. 调用第三方生图 API (返回 Base64)
          const res = await fetch("https://api.example.com/v1/draw", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ prompt: args.prompt })
          });
          const { image_base64 } = await res.json();

          // 2. 将图片写入工作区 generated-images/ 目录（directory 来自 context）
          const saveDir = path.join(context.directory, 'generated-images');
          if (!fs.existsSync(saveDir)) fs.mkdirSync(saveDir, { recursive: true });

          const fileName = `img_${Date.now()}.png`;
          const filePath = path.join(saveDir, fileName);
          fs.writeFileSync(filePath, Buffer.from(image_base64, 'base64'));

          // 3. 返回 Markdown 相对路径，前端聊天界面自动渲染图片
          return `图片生成成功：\n\n![Generated Image](generated-images/${fileName})`;
        }
      })
    }
  };
};
```

### 案例二：网关加签与参数拦截
```javascript
import crypto from 'crypto';

export const CustomAuthPlugin = async () => {
  return {
    "chat.headers": async (input, output) => {
      const timestamp = Date.now().toString();
      const signature = crypto.createHmac('sha256', 'my-secret-key')
        .update(timestamp)
        .digest('hex');

      output.headers['X-Auth-Timestamp'] = timestamp;
      output.headers['X-Auth-Signature'] = signature;
      output.headers['X-Client-Type'] = 'AiCode-Plugin-Runner';
    },
    "chat.params": async (input, output) => {
      // 限制最大输出 token 并在 options 中携带私有字段
      output.maxOutputTokens = 8192;
      output.options = { ...output.options, custom_route: "internal-fast" };
    }
  };
};
```

### 案例三：敏感文件 (.env) 读取防护
```javascript
export const EnvProtectionPlugin = async () => {
  return {
    "tool.execute.before": async (input, output) => {
      // 拦截读文件操作（AiCode readFile 工具参数名为 path）
      if (input.tool === "readFile" && output.args.path?.includes(".env")) {
        throw new Error("安全拦截：禁止通过 Agent 工具直接读取 .env 敏感配置文件！");
      }
    }
  };
};
```

---

## 七、宿主与容器 UDS 通信及生命周期保障

### 1. 通信流程
1. **启动伴生进程**：App 启动或发起会话时，通过 `LinuxContainerEngine` 启动 Node 运行时：
   ```bash
   node ~/.aicode/runtime/plugin-runner.mjs
   ```
   环境变量注入：`AICODE_SOCK=/root/.aicode/plugin-<UUID>.sock`、`AICODE_WORKSPACE=/root/workspace`。
2. **防残留与自愈**：App 启动前先检查并删除可能残留的旧 `.sock` 文件；
3. **就绪确认**：Node 启动并成功监听 UDS 后，在 stdout 输出 `AICODE_PLUGIN_READY`，App 侧轮询 socket 文件就绪后建立连接；
4. **工具注册与 Hook 分发**：
   - App 启动时调用 `tools.list`，将插件返回的动态工具同步至 `ToolRegistry`；
   - 插件启停或重载时重新调用，增量同步（新增注册、移除注销，同名覆盖内置工具并在重载时恢复）；
   - 在工作流各个阶段调用 `hook.dispatch` 触发对应 Hook。
5. **优雅退出**：会话销毁或 App 退出时，发送 `SIGTERM`，随后执行各插件 `dispose`，由 App 兜底清理 socket 文件。

**传输层说明**：UDS 上的协议为 **NDJSON 上的 JSON-RPC 2.0**（报文模型与宿主侧 `McpJsonRpc` 对齐），而非 HTTP——Android 端用 `android.net.LocalSocket` 连接（minSdk 26 无 `java.net.UnixDomainSocketAddress`，且 OkHttp 4 不支持 UDS）。带 id 的请求等待响应、按 id 路由；不带 id 的通知（`event`）不回包。

### 2. 异常与隔离策略
- **插件异常隔离**：单个插件初始化失败或 Hook 执行抛错，不影响其它插件与宿主工作流。Hook 抛错时：`tool.execute.before` 的异常按「工具执行失败」处理（见 §五.2）；其余 Hook 的异常记入日志并跳过该插件本轮执行。
- **插件崩溃自愈**：Sidecar 进程崩溃后，App 检测到连接断开，自动重启伴生进程并重放已加载插件的注册信息。
- **超时保护**：每个 Hook 调用设置超时（默认 10s），超时按异常处理，防止插件挂起阻塞工作流。

---

## 八、实施落地规划清单

### 1. 运行时 SDK 资源 (`app/src/main/assets/plugin-runtime/`)
- `runner.mjs`：常驻 UDS 服务端，负责动态加载、`@opencode-ai/plugin` 兼容层、Hook 管道执行与工具调用分发。
- `sdk.mjs`：提供与 `@opencode-ai/plugin` 对齐的 `tool()`、`tool.schema`、`$` 简化 Shell API 及文件操作工具函数。
- `@opencode-ai/plugin` shim 包：容器内 `node_modules` 名称解析 + 类型声明（.d.ts）。

### 2. 宿主核心层 (`feature/agent/domain/plugin/`)
- `PluginManager.kt`：管理 Node 伴生子进程生命周期、UDS 连接池与心跳、加载状态持久化、`plugins.json` 变更监听与 npm 安装触发。
- `PluginHookDispatcher.kt`：封装工作流各阶段的 Hook 分发与返回值合并逻辑（含超时与异常隔离）。
- `PluginToolBridge.kt`：实现 `AgentTool` 接口，将 JS 插件声明的工具代理为原生 Agent 工具。

### 3. 工作流接入 (`feature/agent/domain/workflow/`)
- `StatefulAgentWorkflow.kt`：在 Prompt 组装、消息发送前、权限评估、工具执行前后完整埋设 Hook 点；`chat.message` 在 `AIAgentViewModel`（消息落库前）、`experimental.session.compacting` 在 `ContextCompactor`（压缩指令构建处）。
- provider adapter（OpenAI/Anthropic/Gemini）：在请求构建处埋设 `chat.headers` / `chat.params`，并作为 `auth.loader` 的注入点。
- `ExecuteCommandTool.kt`：埋设 `shell.env`（以 export 前缀拼入命令，兼容本地/远程后端）。
- `ModelMetadataService`：模型目录合并处埋设 `provider.models`（插件模型目录优先于 models.dev 拉取结果、仍低于用户自定义）。
- `SlashCommandRegistry`/`AIAgentViewModel`：命令执行前埋设 `command.execute.before`，执行后派发 `command.executed` 事件。
- 事件派发点：`AIAgentViewModel` 收集 AgentEvent 流时转发（session.idle/error、tool.started/finished、mode.changed、compaction.*、llm.retrying）；`createSession` 后派发 `session.created`。

### 4. 设置与管理界面 (`feature/settings/presentation/`)
- 插件管理列表页：支持展示已加载插件、查看声明的 Tools 与 Hooks、插件启停切换与重载、npm 插件安装入口。
---

## 九、Plugin SDK Client API 兼容性

插件入口函数与工具 `execute` 上下文均注入 `client` 对象（对齐 `@opencode-ai/sdk` 的 **fields 风格**，返回值统一为 `{ data: ... }` 包装）。不支持的 API 调用返回 `Promise.reject`（带明确 `[AiCode]` 错误信息），不抛 TypeError，插件可 try/catch 降级。

### 1. 支持矩阵

| 方法 | 状态 | 说明 |
|---|---|---|
| `client.app.log({ body })` | ✅ 真实现 | 结构化日志写入宿主 FileLogger（service/level/message/extra） |
| `client.global.health()` | ✅ 真实现 | runner 本地实现，version = `"aicode"` |
| `client.project.get()` | ✅ 真实现 | runner 本地实现（id/name/directory/vcs） |
| `client.session.get({ path })` | ✅ 真实现 | 会话详情（Room DB，字段对齐 opencode Session：modelID/providerID 等） |
| `client.session.list()` | ✅ 真实现 | 当前工作区会话列表 |
| `client.session.messages({ path })` | ✅ 真实现 | 指定会话最近 100 条消息 |
| `client.files.read/write/list` | ✅ 真实现 | runner 本地 Node fs 实现，限定工作区内（`safeWorkspacePath`） |
| `client.config.get()` | ✅ 真实现 | 宿主核心配置（当前工作区、默认模型） |
| `client.tui.showToast({ body })` | ✅ 真实现 | 映射为 Android Toast 提示 |
| `client.session.create/prompt/status/delete/update` | ❌ 不支持 | 调用返回 `Promise.reject`（明确错误信息）；子代理相关 API 规划中，届时改为降级响应 |
| `client.auth.*` / `config.set` / `tui.openXxx` / `event.subscribe` / `app.agents` / `files.edit/search` | ❌ 不支持 | 调用返回 `Promise.reject`（明确错误信息），不抛 TypeError，插件可 try/catch 降级 |

### 2. 协议实现

- runner → Kotlin 反向请求基于同一 UDS 的 JSON-RPC：runner 发带 id+method 的请求，Kotlin 侧 `UdsTransport.onRequest` 回调处理并回写响应。
- Kotlin 侧由 `PluginHostApiHandler`（`feature/agent/domain/plugin/`）实现宿主能力（session/config/app.log/toast），Hilt 注入 Room DAO 与设置仓库。
- hostRequest 超时 30s（`HOST_REQUEST_TIMEOUT_MS`），与宿主侧工具调用超时一致；超时返回错误响应，不阻塞 tool.call。
