# 插件（Plugins）

插件是扩展 AI 能力的一种方式：通过一段 JavaScript 脚本向 AI 注入自定义工具、拦截或改写工具执行、给 LLM 请求加签、注入环境变量、监听会话事件等。插件兼容 OpenCode 插件规范（`@opencode-ai/plugin`），跑在容器内的 Node.js 运行时中。

## 1. 插件入口

进入「设置」→「插件」，进入插件管理页：

*   页面采用与 MCP 一致的 iOS 分组列表样式：顶部为**插件运行时状态**概览（运行中 / 启动中 / 启动失败 / 未启用），显示已加载的插件数与工具数；加载失败时额外显示失败数量与错误信息。
*   下方分组列出**插件**，每个插件一行，显示：插件图标、插件名称、来源 Pill（`npm（全局）` / `npm（项目）` / `本地文件（全局）` / `本地文件（项目）`）、工具数量 Pill、Hook 数量 Pill，以及右侧状态标记（`已加载` / `加载失败`）。
*   **左滑删除**：列表支持左滑手势露出红色删除按钮，点击可一键删除插件（包括清理对应配置文件或本地插件文件）。
*   **插件详情弹窗**：点击插件行可打开底部详情弹窗，分类查看该插件注册的 **Tools 工具列表**（工具名、描述与参数列表）以及 **Hooks 钩子列表**（支持的所有 Hook 功能说明），并提供删除插件操作。
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

**依赖管理**：插件需要外部 npm 包时，在对应作用域的 `package.json` 声明依赖。App 启动插件运行时或检测到 `package.json` / `plugins.json` 变动时，会自动在容器内执行 `npm install`。

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
| `chat.headers` | LLM 请求发出前注入鉴权 Token、签名、私有网关头 |
| `chat.params` | LLM 请求发出前动态调整推理参数（temperature/topP/topK/maxOutputTokens 等） |
| `chat.message` | 用户消息落库前改写输入、拦截特定命令 |
| `auth.loader` | 请求时动态提供/刷新认证头（临时凭证、动态签名），合并进请求头 |
| `experimental.provider.small_model` | 未配置压缩/标题专用模型时提供小模型建议 |
| `experimental.chat.system.transform` | 系统提示词组装后注入额外上下文（⚠️ 会打断隐式前缀缓存，慎用） |
| `experimental.chat.messages.transform` | 模型请求发送前裁剪历史消息、脱敏敏感数据 |
| `experimental.session.compacting` | 上下文压缩生成摘要前注入需跨压缩保留的上下文 |
| `shell.env` | Bash 工具/终端执行命令前注入环境变量 |
| `permission.ask` | 高危工具权限弹窗前自动化允许/拒绝 |
| `command.execute.before` | Slash 命令执行前拦截：改写参数或置空阻止执行 |
| `provider.models` | 为指定 provider 提供模型目录（如私有网关模型清单） |
| `event` | 监听会话与工作流事件（见下方列表） |
| `dispose` | 插件卸载/会话销毁时清理资源 |

**`event` 可监听的事件**（已实现）：`session.created`、`session.idle`、`session.error`、`session.updated`、`message.created`、`mode.changed`、`tool.started`、`tool.finished`、`permission.asked` / `permission.replied`、`file.edited`、`todo.updated`、`command.executed`、`compaction.started` / `compaction.finished` / `compaction.failed`、`llm.retrying`。

### plugin 入参中的 `client`（SDK Client）

插件入口函数与工具 `execute` 上下文的 `client` 对象提供对宿主的编程式访问（对齐 `@opencode-ai/sdk` 的 fields 风格，返回值统一为 `{ data: ... }`）：

| API | 状态 | 说明 |
|---|---|---|
| `client.app.log()` | ✅ 已支持 | 结构化日志（service/level/message/extra），写入宿主日志 |
| `client.global.health()` | ✅ 已支持 | 健康检查（本地实现） |
| `client.project.get()` | ✅ 已支持 | 项目信息（id/name/directory） |
| `client.session.get()` | ✅ 已支持 | 按 id 查会话（含 modelID/providerID/mode 等） |
| `client.session.list()` | ✅ 已支持 | 当前工作区会话列表 |
| `client.session.messages()` | ✅ 已支持 | 指定会话最近 100 条消息 |
| `client.files.read()` / `write()` / `list()` | ✅ 已支持 | 工作区文件读写列目录（限定在工作区内） |
| `client.config.get()` | ✅ 已支持 | 宿主核心配置（当前工作区、默认模型） |
| `client.tui.showToast()` | ✅ 已支持 | 映射为 Android Toast 提示 |
| `client.auth.*` / `client.config.set` / `client.session.prompt` / `client.tui.openXxx` / `client.event.subscribe` 等 | ❌ 不支持 | 调用返回明确错误（不抛 TypeError），插件可 try/catch 降级 |

完整支持矩阵与升级指南见 `docs/plugin-system-specification.md`。

## 6. 不支持

以下 OpenCode 能力 **AiCode 明确不支持**（无对应机制）：`config`（插件配置的读写，即 `client.config.set`）、`experimental.text.complete`、`experimental_workspace` / `serverUrl`。社区插件若依赖上述能力需改造或放弃。
*   插件 Hook 侧 `auth.loader` 可动态提供认证头；`client.auth.set`（静默改凭据）不开放。
*   `client.session.prompt`（插件代发 AI 对话）不开放，属安全边界。

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

更多 Hook 签名与示例见项目内 `docs/plugin-system-specification.md`（开发规范）。