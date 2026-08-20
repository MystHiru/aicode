/**
 * AiCode 插件类型声明：对齐 @opencode-ai/plugin 的公开 API 表面。
 * 与官方类型存在差异处（model/provider 为字符串、无 Part 概念等）已在
 * docs/plugin-system-specification.md 的兼容性总览中标注。
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

export interface ClientResponse<T> {
  data: T
}

export interface AppClient {
  log(body: { body: { service: string; level: "debug" | "info" | "warn" | "error"; message: string; extra?: Record<string, unknown> } }): Promise<ClientResponse<boolean>>
  agents(): Promise<unknown>
}

export interface GlobalClient {
  health(): Promise<ClientResponse<{ healthy: boolean; version: string }>>
}

export interface ProjectClient {
  get(): Promise<ClientResponse<{ id: string; name: string; directory: string; vcs?: string }>>
}

export interface SessionClient {
  get(body: { path: { id: string } }): Promise<ClientResponse<Record<string, unknown>>>
  list(): Promise<ClientResponse<{ sessions: Record<string, unknown>[] }>>
  messages(body: { path: { id: string } }): Promise<ClientResponse<{ messages: Record<string, unknown>[] }>>
  prompt(...args: unknown[]): Promise<never>
  delete(...args: unknown[]): Promise<never>
  update(...args: unknown[]): Promise<never>
}

export interface FilesClient {
  read(body: { path: { filePath: string } }): Promise<ClientResponse<{ type: string; content: string; filePath: string }>>
  write(body: { path: { filePath: string }; body: { data?: string } }): Promise<ClientResponse<boolean>>
  list(body: { path: { dirPath: string } }): Promise<ClientResponse<{ name: string; type: string; path: string }[]>>
  edit(...args: unknown[]): Promise<never>
  search(...args: unknown[]): Promise<never>
}

export interface ConfigClient {
  get(): Promise<ClientResponse<Record<string, unknown>>>
  set(...args: unknown[]): Promise<never>
}

export interface AuthClient {
  set(...args: unknown[]): Promise<never>
  list(...args: unknown[]): Promise<never>
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
    input: { sessionID: string; messageID?: string },
    output: { message: { role: string; content: string } },
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
    output: { args: string },
  ) => Promise<void> | void
  provider?: {
    id: string
    models?: (provider: string, ctx: { auth?: unknown }) => Promise<Record<string, unknown>>
  }
  auth?: {
    provider: string
    loader?: (auth: () => Promise<unknown>, provider: string) => Promise<Record<string, unknown>>
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