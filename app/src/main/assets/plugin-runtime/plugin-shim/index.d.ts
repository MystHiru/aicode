/**
 * AiCode 插件类型声明：对齐 @opencode-ai/plugin 的公开 API 表面。
 * 与官方类型存在差异处（model/provider 为字符串、无 Part 概念等）见
 * app/src/main/assets/docs/plugins.md。
 */

export interface Project {
  id: string
  name: string
  directory: string
}

export interface ToolSchema {
  type: string
  [key: string]: unknown
}

export interface ToolDefinition {
  description: string
  parameters: {
    type: "object"
    properties: Record<string, ToolSchema>
    required: string[]
  }
  execute(args: Record<string, unknown>, context: ToolContext): unknown | Promise<unknown>
}

export interface ToolHelper {
  (def: {
    description: string
    args?: Record<string, ToolSchema>
    execute(args: Record<string, unknown>, context: ToolContext): unknown | Promise<unknown>
  }): ToolDefinition
  schema: {
    string(params?: Record<string, unknown>): ToolSchema
    number(params?: Record<string, unknown>): ToolSchema
    integer(params?: Record<string, unknown>): ToolSchema
    boolean(params?: Record<string, unknown>): ToolSchema
    enum(values: string[], params?: Record<string, unknown>): ToolSchema
    object(properties: Record<string, ToolSchema>, params?: Record<string, unknown>): ToolSchema
    array(items: ToolSchema, params?: Record<string, unknown>): ToolSchema
  }
}

export const tool: ToolHelper

export interface ShellResult {
  stdout: string
  stderr: string
  exitCode: number
}

export interface Shell {
  (strings: TemplateStringsArray, ...values: unknown[]): Promise<ShellResult> & {
    quiet(): Shell
    nothrow(): Shell
    cwd(dir: string): Shell
    env(env: Record<string, string>): Shell
  }
}

export const $: Shell
export const shell: Shell

// ── SDK Client API（对齐 @opencode-ai/sdk fields 风格，{ data } 包装）──
// 返回结构对齐 opencode v1 契约（Session / {info, parts} 消息 / FileNode / FileContent / Agent / Project / Config）。

export interface ClientResponse<T> {
  data: T
}

/** opencode v1 Session（AiCode 映射：slug=id、projectID=工作区 hash、version="1"）。 */
export interface Session {
  id: string
  slug: string
  projectID: string
  directory: string
  parentID?: string
  title: string
  version: string
  time: {
    created: number
    updated: number
    compacting?: number
  }
  model?: { id: string; providerID: string; variant?: string }
  agent?: string
  cost?: number
  tokens?: {
    input: number
    output: number
    reasoning: number
    cache: { read: number; write: number }
  }
  metadata?: Record<string, unknown>
}

/** opencode 消息 info（User | Assistant 的并集，AiCode 映射见 plugins.md）。 */
export interface MessageInfo {
  id: string
  sessionID: string
  role: "user" | "assistant"
  time: { created: number; completed?: number }
  parentID?: string
  agent?: string
  model?: { providerID: string; modelID: string; variant?: string }
  modelID?: string
  providerID?: string
  mode?: string
  path?: { cwd: string; root: string }
  cost?: number
  tokens?: {
    input: number
    output: number
    reasoning: number
    cache: { read: number; write: number }
  }
}

export interface TextPart {
  id: string
  sessionID: string
  messageID: string
  type: "text"
  text: string
}

export interface ReasoningPart {
  id: string
  sessionID: string
  messageID: string
  type: "reasoning"
  text: string
  time: {
    start: number
    end?: number
  }
}

export interface ToolPart {
  id: string
  sessionID: string
  messageID: string
  type: "tool"
  callID: string
  tool: string
  state:
    | {
        status: "completed"
        input: Record<string, unknown>
        output: string
        title: string
        metadata: Record<string, unknown>
        time: { start: number; end: number }
      }
    | {
        status: "error"
        input: Record<string, unknown>
        error: string
        time: { start: number; end: number }
      }
}

export type Part = TextPart | ReasoningPart | ToolPart

/** opencode session.messages 的元素：{ info, parts }。 */
export interface MessageWithParts {
  info: MessageInfo
  parts: Part[]
}

/** session.prompt 的 parts 输入（AiCode 支持 text 与 subtask 两类）。 */
export type PartInput =
  | { type: "text"; text: string }
  | { type: "subtask"; prompt: string; description?: string; agent?: string }

export interface FileNode {
  name: string
  path: string
  absolute: string
  type: "file" | "directory"
  ignored: boolean
}

export interface FileContent {
  type: "text" | "binary"
  content: string
  diff?: string
  patch?: unknown
  encoding?: "base64"
  mimeType?: string
}

export interface Agent {
  name: string
  description?: string
  mode: "subagent" | "primary" | "all"
  builtIn: boolean
  permission: {
    edit: "ask" | "allow" | "deny"
    bash: Record<string, "ask" | "allow" | "deny">
  }
  tools: Record<string, boolean>
  options: Record<string, unknown>
}

export interface Project {
  id: string
  worktree: string
  vcsDir?: string
  vcs?: "git"
  time: { created: number; initialized?: number }
}

/** opencode Config 最小子集（AiCode 仅提供 model/small_model）。 */
export interface Config {
  model?: string
  small_model?: string
  [key: string]: unknown
}

export interface AppClient {
  log(body: { body: { service: string; level: "debug" | "info" | "warn" | "error"; message: string; extra?: Record<string, unknown> } }): Promise<ClientResponse<boolean>>
  agents(): Promise<ClientResponse<Agent[]>>
}

export interface GlobalClient {
  health(): Promise<ClientResponse<{ healthy: boolean; version: string }>>
}

export interface ProjectClient {
  get(): Promise<ClientResponse<Project>>
}

export interface SessionClient {
  get(body: { path: { id: string } }): Promise<ClientResponse<Session>>
  list(): Promise<ClientResponse<Session[]>>
  children(body: { path: { id: string } }): Promise<ClientResponse<Session[]>>
  create(body: { body?: { title?: string; parentID?: string } }): Promise<ClientResponse<Session>>
  messages(body: { path: { id: string } }): Promise<ClientResponse<MessageWithParts[]>>
  prompt(body: { path: { id: string }; body: { parts?: PartInput[]; noReply?: boolean; model?: { providerID: string; modelID: string } } }): Promise<ClientResponse<undefined>>
  promptAsync(body: { path: { id: string }; body: { parts?: PartInput[]; noReply?: boolean; model?: { providerID: string; modelID: string } } }): Promise<ClientResponse<undefined>>
  status(): Promise<ClientResponse<Record<string, { type: "busy" | "idle" }>>>
  delete(body: { path: { id: string } }): Promise<ClientResponse<boolean>>
  update(body: { path: { id: string }; body: { title?: string } }): Promise<ClientResponse<Session>>
}

export interface FilesClient {
  read(body: { path: { filePath: string } }): Promise<ClientResponse<FileContent>>
  write(body: { path: { filePath: string }; body: { data?: string } }): Promise<ClientResponse<boolean>>
  list(body: { path: { dirPath: string } }): Promise<ClientResponse<FileNode[]>>
  edit(...args: unknown[]): Promise<never>
  search(...args: unknown[]): Promise<never>
}

export interface ConfigClient {
  get(): Promise<ClientResponse<Config>>
  set(...args: unknown[]): Promise<never>
}

/** 凭据（对齐 opencode Auth）：oauth 刷新/访问令牌或 api key。 */
export type Auth =
  | { type: "oauth"; refresh: string; access: string; expires: number; accountId?: string; enterpriseUrl?: string }
  | { type: "api"; key: string; metadata?: Record<string, string> }
  | { type: "wellknown"; key: string; token: string }

/** 登录方法输入提示（对齐 opencode Prompt）。 */
export type AuthPrompt =
  | { type: "text"; key: string; message: string; placeholder?: string; validate?: (value: string) => string | undefined }
  | { type: "select"; key: string; message: string; options: Array<{ label: string; value: string; hint?: string }> }

/** authorize/callback 的成功或失败结果（对齐 opencode AuthOAuthResult callback 返回值）。 */
export type AuthSuccessResult =
  | ({ type: "success"; provider?: string } & (
      | { refresh: string; access: string; expires: number; accountId?: string; enterpriseUrl?: string }
      | { key: string; metadata?: Record<string, string> }
    ))
  | { type: "failed"; error?: string }

/** OAuth 授权结果（对齐 opencode）：url + instructions + auto/code 回调。 */
export type AuthOAuthResult = {
  url: string
  instructions: string
} & (
  | { method: "auto"; callback(): Promise<AuthSuccessResult> }
  | { method: "code"; callback(code: string): Promise<AuthSuccessResult> }
)

/** 登录方法：oauth（授权流程）或 api（key 输入，可选 authorize 校验）。 */
export interface AuthMethod {
  type: "oauth" | "api"
  label: string
  prompts?: AuthPrompt[]
  authorize?(inputs?: Record<string, string>): Promise<AuthOAuthResult | AuthSuccessResult>
}

export interface AuthClient {
  set(body: { path: { id: string }; body: Auth | null }): Promise<ClientResponse<boolean>>
  list(): Promise<ClientResponse<Record<string, Auth>>>
  get(body: { path: { id: string } }): Promise<ClientResponse<Auth>>
  logout(body: { path: { id: string } }): Promise<ClientResponse<boolean>>
}

export interface TuiClient {
  showToast(body: { body: { message: string; variant?: string; duration?: number; title?: string } }): Promise<ClientResponse<boolean>>
  appendPrompt(...args: unknown[]): Promise<never>
  executeCommand(...args: unknown[]): Promise<never>
  openHelp(...args: unknown[]): Promise<never>
  openSessions(...args: unknown[]): Promise<never>
  openThemes(...args: unknown[]): Promise<never>
  openModels(...args: unknown[]): Promise<never>
  submitPrompt(...args: unknown[]): Promise<never>
  clearPrompt(...args: unknown[]): Promise<never>
}

export interface EventClient {
  subscribe(...args: unknown[]): Promise<never>
}

export interface OpencodeClient {
  app: AppClient
  global: GlobalClient
  project: ProjectClient
  session: SessionClient
  files: FilesClient
  config: ConfigClient
  auth: AuthClient
  tui: TuiClient
  event: EventClient
}

// ── 工具执行上下文（含 client 与 sessionID）──

export interface ToolContext {
  directory: string
  worktree: string
  sessionID?: string
  client: OpencodeClient
  abort: AbortSignal
  ask: (params: { message: string }) => Promise<boolean>
  metadata: (params: Record<string, unknown>) => void
  push: (params: { title: string; output: string }) => void
}

export interface PluginInput {
  project: Project
  client: OpencodeClient
  $: Shell
  directory: string
  worktree: string
  /** AiCode 不提供（无对应机制），仅在类型上声明以兼容官方插件编译。 */
  serverUrl?: URL
  experimental_workspace?: never
}

export type Plugin = (input: PluginInput) => Promise<Hooks> | Hooks

export interface Hooks {
  dispose?: () => Promise<void> | void
  event?: (input: { event: { type: string; properties?: Record<string, unknown> } }) => Promise<void> | void
  tool?: Record<string, ToolDefinition>
  "chat.message"?: (
    input: {
      sessionID: string
      agent?: string
      model?: { providerID: string; modelID: string }
      messageID?: string
      variant?: string
    },
    output: { message: MessageInfo; parts: Part[] },
  ) => Promise<void> | void
  "chat.headers"?: (
    input: { sessionID: string; model: string; provider: string },
    output: { headers: Record<string, string> },
  ) => Promise<void> | void
  "chat.params"?: (
    input: { sessionID: string; model: string },
    output: {
      temperature?: number
      topP?: number
      topK?: number
      maxOutputTokens?: number
      options?: Record<string, unknown>
    },
  ) => Promise<void> | void
  "experimental.chat.system.transform"?: (
    input: { sessionID?: string; model: string },
    output: { system: string[] },
  ) => Promise<void> | void
  "experimental.chat.messages.transform"?: (
    input: { sessionID?: string },
    // AiCode 扩展：output.messages 为 AiCode 消息模型数组（非 opencode {info, parts}[]，避免有损转换）
    output: { messages: unknown[] },
  ) => Promise<void> | void
  "tool.execute.before"?: (
    input: { tool: string; sessionID: string; callID: string },
    output: { args: Record<string, unknown> },
  ) => Promise<void> | void
  "tool.execute.after"?: (
    input: { tool: string; sessionID: string; callID: string; args: Record<string, unknown> },
    output: { title: string; output: string; metadata: Record<string, unknown> },
  ) => Promise<void> | void
  "tool.definition"?: (
    input: { toolID: string },
    output: { description: string; parameters: Record<string, unknown> },
  ) => Promise<void> | void
  "shell.env"?: (
    input: { cwd: string; sessionID?: string; callID?: string },
    output: { env: Record<string, string> },
  ) => Promise<void> | void
  "permission.ask"?: (
    input: { tool: string; args: Record<string, unknown>; mode: "PLAN" | "BUILD" | "AUTO" },
    output: { status: "ask" | "deny" | "allow" },
  ) => Promise<void> | void
  "experimental.session.compacting"?: (
    input: { sessionID: string },
    output: { context: string[]; prompt?: string },
  ) => Promise<void> | void
  "command.execute.before"?: (
    input: { command: string; sessionID: string; arguments: string },
    output: { parts: Part[] },
  ) => Promise<void> | void
  provider?: {
    id: string
    models?: (provider: string, ctx: { auth?: unknown }) => Promise<Record<string, unknown>>
  }
  auth?: {
    provider: string
    loader?: (auth: () => Promise<Auth>, provider: Record<string, unknown> | null) => Promise<Record<string, unknown>>
    methods: AuthMethod[]
  }
  "experimental.provider.small_model"?: (
    input: { provider: string },
    output: { model?: string },
  ) => Promise<void> | void
}

export interface PluginModule {
  id?: string
  server: Plugin
  tui?: never
}