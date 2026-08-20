#!/usr/bin/env node
/**
 * AiCode 插件运行时（Sidecar）
 *
 * 运行于 PRoot 容器内 Node.js 运行时，通过 Unix Domain Socket 与 Android 宿主双向通信。
 * 协议：NDJSON 上的 JSON-RPC 2.0（报文结构与宿主侧 McpJsonRpc 对齐）：
 *   - 请求（带 id）：tools.list / tool.call / hook.dispatch / plugins.list / dispose
 *   - 通知（无 id）：event（宿主派发工作流事件，fire-and-forget）
 *
 * 启动流程：
 *   1. 读取环境变量 AICODE_SOCK（UDS 路径）/ AICODE_WORKSPACE（工作区绝对路径）
 *   2. 加载 sdk.mjs 注入 globalThis（供 @opencode-ai/plugin shim 引用）
 *   3. 确保 @opencode-ai/plugin 在全局/项目 node_modules 可解析（symlink 到 plugin-shim）
 *   4. 加载插件（npm 全局 → npm 项目 → 本地全局 → 本地项目，同名本地优先）
 *   5. 监听 UDS，stdout 输出 AICODE_PLUGIN_READY 就绪信号
 */
import net from 'net';
import fs from 'fs';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
import readline from 'readline';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const AICODE_SOCK = process.env.AICODE_SOCK;
const AICODE_WORKSPACE = process.env.AICODE_WORKSPACE || '/root/workspace';
const AICODE_HOME = process.env.AICODE_HOME || '/root/.aicode';

// ── 1. 加载 SDK 并注入 globalThis ──
const sdk = await import(pathToFileURL(path.join(__dirname, 'sdk.mjs')).href);
globalThis.__aicode_sdk = sdk;

// ── 2. 确保 @opencode-ai/plugin shim 可解析 ──
ensureShim(path.join(AICODE_HOME, 'node_modules'));
if (AICODE_WORKSPACE) ensureShim(path.join(AICODE_WORKSPACE, '.aicode', 'node_modules'));

// ── 3. 加载插件 ──
const { plugins, failed: failedPlugins } = await loadPlugins();

// ── 4. Hook 与工具注册表 ──
/** hookName -> [{ plugin, fn }]；按加载顺序执行，后执行者覆盖前者的 output 字段。 */
const hookHandlers = new Map();
/** toolName -> { plugin, def }；同名工具后者覆盖前者（本地插件优先于 npm 插件）。 */
const tools = new Map();
/** 已加载插件名（去重用，同名时新加载的替换旧的）。 */
const loadedNames = new Map();

for (const p of plugins) {
  registerPlugin(p);
}

// ── 5. 监听 UDS ──
if (!AICODE_SOCK) {
  console.error('AICODE_SOCK 环境变量缺失，插件运行时退出');
  process.exit(1);
}
if (fs.existsSync(AICODE_SOCK)) {
  try { fs.unlinkSync(AICODE_SOCK); } catch (e) { /* 忽略 */ }
}
const server = net.createServer((socket) => {
  setHostSocket(socket);
  const rl = readline.createInterface({ input: socket, crlfDelay: Infinity });
  rl.on('line', (line) => {
    if (!line.trim()) return;
    let msg;
    try {
      msg = JSON.parse(line);
    } catch (e) {
      writeLine(socket, { id: null, error: { code: -32700, message: 'Parse error' } });
      return;
    }
    handleMessage(msg, socket);
  });
});
server.listen(AICODE_SOCK, () => {
  console.log('AICODE_PLUGIN_READY');
});

function writeLine(socket, obj) {
  socket.write(JSON.stringify(obj) + '\n');
}

/** 提取工具参数 schema：兼容 AiCode shim 的 parameters 与真实 opencode 的 zod args。 */
function toolParams(def) {
  if (def.parameters) return def.parameters;
  const args = def.args;
  if (args && typeof args === 'object') {
    const properties = {};
    const required = [];
    for (const [name, schema] of Object.entries(args)) {
      properties[name] = zodToJsonSchema(schema);
      const inner = schema?._def;
      const optional = inner && (inner.typeName === 'ZodOptional' || inner.typeName === 'ZodDefault' || inner.type === 'optional' || inner.type === 'default');
      if (!optional) required.push(name);
    }
    return { type: 'object', properties, required };
  }
  return { type: 'object', properties: {}, required: [] };
}

/** 轻量 zod → JSON Schema（兼容 zod 3 的 typeName 与 zod 4 的 type 两种内部结构）。 */
function zodToJsonSchema(type) {
  if (!type || typeof type !== 'object' || !type._def) return { type: 'string' };
  const def = type._def;
  const desc = typeof type.description === 'string' ? { description: type.description } : {};
  const t = def.typeName ? def.typeName.replace(/^Zod/, '').toLowerCase() : def.type;
  switch (t) {
    case 'string': return { type: 'string', ...desc };
    case 'number':
    case 'integer': return { type: 'number', ...desc };
    case 'boolean': return { type: 'boolean', ...desc };
    case 'enum':
    case 'nativeenum': {
      const entries = def.entries || def.values;
      const values = Array.isArray(entries) ? entries : Object.values(entries || {});
      return { type: 'string', enum: values, ...desc };
    }
    case 'optional':
    case 'nullable':
    case 'default':
    case 'catch': return zodToJsonSchema(def.innerType);
    case 'array': return { type: 'array', items: zodToJsonSchema(def.element || def.type), ...desc };
    case 'object': {
      const shape = typeof def.shape === 'function' ? def.shape() : def.shape;
      const properties = {};
      for (const [k, v] of Object.entries(shape || {})) properties[k] = zodToJsonSchema(v);
      return { type: 'object', properties, ...desc };
    }
    default: return { type: 'string', ...desc };
  }
}

async function handleMessage(msg, socket) {
  const { id, method, params } = msg;
  // 响应：Kotlin 宿主对 plugin client.* 请求（hostRequest）的应答。带 id、无 method、含 result/error。
  if (method === undefined && id !== undefined && id !== null && (msg.result !== undefined || msg.error !== undefined)) {
    const pendingReq = hostPending.get(id);
    if (pendingReq) {
      hostPending.delete(id);
      if (msg.error) {
        pendingReq.reject(new Error(`[AiCode] host 请求失败 [${msg.error.code}] ${msg.error.message}`));
      } else {
        pendingReq.resolve(msg.result || {});
      }
    } else {
      console.error(`收到无匹配的 host 响应 id=${id}`);
    }
    return;
  }
  try {
    if (id === undefined || id === null) {
      // 通知：event 派发
      if (method === 'event') {
        dispatchEvent(params?.event);
      }
      return;
    }
    switch (method) {
      case 'tools.list': {
        const list = [...tools.entries()].map(([name, t]) => ({
          name,
          description: t.def.description,
          parameters: toolParams(t.def),
        }));
        writeLine(socket, { id, result: { tools: list } });
        break;
      }
      case 'tool.call': {
        const { name, args, sessionID } = params || {};
        const t = tools.get(name);
        if (!t) {
          writeLine(socket, { id, result: { result: { status: 'error', message: `工具 ${name} 不存在`, code: 'TOOL_NOT_FOUND' } } });
          break;
        }
        try {
          const raw = await t.def.execute(args || {}, {
            directory: AICODE_WORKSPACE,
            worktree: AICODE_WORKSPACE,
            sessionID,
            client: createClient(),
            // opencode 工具上下文兼容：AiCode 无交互确认/进度机制，ask 自动允许（工具
            // 执行仍受宿主权限层管控）、metadata/push 为 no-op；abort 给永不中断的信号。
            abort: new AbortController().signal,
            ask: async () => true,
            metadata: () => {},
            push: () => {},
          });
          writeLine(socket, { id, result: { result: { status: 'success', data: normalizeResult(raw) } } });
        } catch (e) {
          writeLine(socket, { id, result: { result: { status: 'error', message: String(e?.message || e), code: 'PLUGIN_TOOL_ERROR' } } });
        }
        break;
      }
      case 'hook.dispatch': {
        const { hook, input, output } = params || {};
        const handlers = hookHandlers.get(hook) || [];
        const errors = [];
        let currentOutput = output || {};
        if (RETURN_HOOKS.has(hook)) {
          // 返回型 hook（provider.models / auth.loader / small_model 等）：收集各插件返回值
          const results = [];
          for (const h of handlers) {
            try {
              const r = await h.fn(input || {});
              if (r != null) results.push(r);
            } catch (e) {
              errors.push({ plugin: h.plugin, error: String(e?.message || e) });
            }
          }
          writeLine(socket, { id, result: { output: currentOutput, results, errors } });
          break;
        }
        // 修改型 hook：fn(input, output) 就地修改 output
        for (const h of handlers) {
          try {
            await h.fn(input || {}, currentOutput);
          } catch (e) {
            errors.push({ plugin: h.plugin, error: String(e?.message || e) });
          }
        }
        writeLine(socket, { id, result: { output: currentOutput, errors } });
        break;
      }
      case 'plugins.list': {
        const list = plugins.map((p) => ({
          name: p.name,
          source: p.source,
          tools: [...tools.entries()].filter(([, t]) => t.plugin === p.name).map(([n]) => n),
          hooks: Object.keys(p.hooks),
        }));
        for (const f of failedPlugins) {
          list.push({ name: f.name, source: f.source, error: f.error, tools: [], hooks: [] });
        }
        writeLine(socket, { id, result: { plugins: list } });
        break;
      }
      case 'dispose': {
        for (const p of plugins) {
          try {
            await p.dispose?.();
          } catch (e) {
            console.error(`[${p.name}] dispose 失败: ${e?.message || e}`);
          }
        }
        writeLine(socket, { id, result: {} });
        break;
      }
      default:
        writeLine(socket, { id, error: { code: -32601, message: `未知方法 ${method}` } });
    }
  } catch (e) {
    writeLine(socket, { id, error: { code: -32603, message: String(e?.message || e) } });
  }
}

/** 返回型 hook：fn(input) 直接返回结果，不走 output 修改语义。 */
const RETURN_HOOKS = new Set([
  'provider.models',
  'auth.loader',
  'experimental.provider.small_model',
  'experimental.text.complete',
]);

async function dispatchEvent(event) {
  const handlers = hookHandlers.get('event') || [];
  for (const h of handlers) {
    try {
      await h.fn({ event: event || {} });
    } catch (e) {
      console.error(`[${h.plugin}] event 处理失败: ${e?.message || e}`);
    }
  }
}

// ── 宿主反向请求（plugin client.* API → Kotlin）──

/** 当前已建立的宿主连接（server.listen 后在连接回调中赋值；插件初始化可能早于连接建立）。 */
let hostSocket = null;
/** 等待宿主连接就绪的回调队列：插件在初始化阶段（socket 未建立）调用 client.* 时先挂起。 */
let hostWaiters = [];
/** 已发出、等待宿主响应的请求：id → { resolve, reject }。 */
const hostPending = new Map();
let hostIdCounter = 0;
/** 宿主单次请求响应超时（工具/hook 执行中可能久置，给足时间）。 */
const HOST_REQUEST_TIMEOUT_MS = 30_000;

function setHostSocket(socket) {
  hostSocket = socket;
  for (const w of hostWaiters) w();
  hostWaiters = [];
}

function getHostSocket() {
  if (hostSocket) return Promise.resolve(hostSocket);
  return new Promise((resolve) => hostWaiters.push(() => resolve(hostSocket)));
}

/** 向 Kotlin 宿主发送 JSON-RPC 请求并等待响应。socket 未就绪时等待连接建立。 */
function hostRequest(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++hostIdCounter;
    const timer = setTimeout(() => {
      if (hostPending.has(id)) {
        hostPending.delete(id);
        reject(new Error(`[AiCode] host 请求 ${method} 超时`));
      }
    }, HOST_REQUEST_TIMEOUT_MS);
    hostPending.set(id, {
      resolve: (v) => { clearTimeout(timer); resolve(v); },
      reject: (e) => { clearTimeout(timer); reject(e); },
    });
    getHostSocket().then((sock) => {
      if (!sock) {
        hostPending.delete(id);
        clearTimeout(timer);
        reject(new Error(`[AiCode] host 连接不可用，请求 ${method} 失败`));
        return;
      }
      const msg = { jsonrpc: '2.0', id, method };
      if (params && typeof params === 'object') msg.params = params;
      sock.write(JSON.stringify(msg) + '\n');
    }).catch((e) => {
      hostPending.delete(id);
      clearTimeout(timer);
      reject(e);
    });
  });
}

/** 明确不支持的 client.* API：reject 带原因，插件可用 try/catch 降级，而非 TypeError 崩溃。 */
function unsupported(name, reason) {
  return () => Promise.reject(new Error(`[AiCode] client.${name} 不支持：${reason}`));
}

/** 构造插件可见的 SDK client：形状对齐 @opencode-ai/sdk（fields 风格 { data } 包装）。 */
function createClient() {
  const c = {};
  // app：结构化日志 / 列出 agent（不支持）
  c.app = {
    log: async ({ body } = {}) => {
      await hostRequest('client.app.log', { body: body || {} });
      return { data: true };
    },
    agents: unsupported('app.agents', 'AiCode 无 agent 概念'),
  };
  // global：健康检查（本地实现）
  c.global = {
    health: async () => ({ data: { healthy: true, version: 'aicode' } }),
  };
  // project：本地实现（project 参数已有同样信息）
  c.project = {
    get: async () => ({
      data: {
        id: AICODE_WORKSPACE ? projectHash(AICODE_WORKSPACE) : 'global',
        name: path.basename(AICODE_WORKSPACE || ''),
        directory: AICODE_WORKSPACE,
        vcs: fs.existsSync(path.join(AICODE_WORKSPACE || '', '.git')) ? 'git' : undefined,
      },
    }),
  };
  // session：会话信息与消息
  c.session = {
    get: async ({ path } = {}) => ({
      data: await hostRequest('client.session.get', { id: path?.id }),
    }),
    list: async () => ({
      data: await hostRequest('client.session.list', {}),
    }),
    messages: async ({ path } = {}) => ({
      data: await hostRequest('client.session.messages', { id: path?.id }),
    }),
    prompt: unsupported('session.prompt', '插件不能代发 AI 对话（安全边界）'),
    delete: unsupported('session.delete', '插件不能删除会话'),
    update: unsupported('session.update', '插件不能修改会话'),
  };
  // files：工作区文件读写。runner 与工作区同容器，Node fs 即最终事实，本地实现
  // （Sandbox 与宿主工具权限并不保护不可信插件读写容器文件系统，这里限定在工作区内防越界）。
  c.files = {
    read: async ({ path: p } = {}) => {
      const full = safeWorkspacePath(p?.filePath);
      if (full === null) throw new Error(`[AiCode] client.files.read 路径超出工作区: ${p?.filePath}`);
      const stat = await fs.promises.stat(full);
      return {
        data: {
          type: stat.isDirectory() ? 'directory' : 'file',
          content: stat.isDirectory() ? '' : await fs.promises.readFile(full, 'utf-8'),
          filePath: p?.filePath,
        },
      };
    },
    write: async ({ path: p, body } = {}) => {
      const full = safeWorkspacePath(p?.filePath);
      if (full === null) throw new Error(`[AiCode] client.files.write 路径超出工作区: ${p?.filePath}`);
      await fs.promises.mkdir(path.dirname(full), { recursive: true });
      await fs.promises.writeFile(full, body?.data ?? '', 'utf-8');
      return { data: true };
    },
    list: async ({ path: p } = {}) => {
      const full = safeWorkspacePath(p?.dirPath || '.');
      if (full === null) throw new Error(`[AiCode] client.files.list 路径超出工作区: ${p?.dirPath}`);
      const entries = await fs.promises.readdir(full, { withFileTypes: true });
      return {
        data: entries.map((e) => ({
          name: e.name,
          type: e.isDirectory() ? 'directory' : 'file',
          path: path.join(p?.dirPath || '.', e.name),
        })),
      };
    },
    edit: unsupported('files.edit', '请用 files.read + files.write 组合完成替换'),
    search: unsupported('files.search', '请使用内置 SearchCode 工具'),
  };
  // config：读宿主配置（只读）
  c.config = {
    get: async () => ({ data: await hostRequest('client.config.get', {}) }),
    set: unsupported('config.set', '插件不能改写宿主配置'),
  };
  // auth：凭据管理（安全边界：不开放给插件写入/读取）
  c.auth = {
    set: unsupported('auth.set', '插件不能修改 API Key 等凭据'),
    list: unsupported('auth.list', '插件不能读取凭据'),
  };
  // tui：Android 无 TUI，仅场边通知（toast/消息）有价值
  c.tui = {
    showToast: async ({ body } = {}) => {
      await hostRequest('client.tui.toast', { body: body || {} });
      return { data: true };
    },
    appendPrompt: unsupported('tui.appendPrompt', 'AiCode 无命令行输入框'),
    executeCommand: unsupported('tui.executeCommand', '插件不能触发 Slash 命令'),
    openHelp: unsupported('tui.openHelp', 'AiCode 无 TUI'),
    openSessions: unsupported('tui.openSessions', 'AiCode 无 TUI'),
    openThemes: unsupported('tui.openThemes', 'AiCode 无 TUI'),
    openModels: unsupported('tui.openModels', 'AiCode 无 TUI'),
    submitPrompt: unsupported('tui.submitPrompt', 'AiCode 无 TUI'),
    clearPrompt: unsupported('tui.clearPrompt', 'AiCode 无 TUI'),
  };
  // event：已有 event hook，无需额外订阅通道
  c.event = {
    subscribe: unsupported('event.subscribe', '请直接使用 event hook 监听事件'),
  };
  return c;
}

/** 工作区路径的稳定短 hash，作为 project.id（对齐 opencode 的 git hash 语义）。 */
function projectHash(dir) {
  let h = 0;
  for (let i = 0; i < dir.length; i++) h = ((h << 5) - h + dir.charCodeAt(i)) | 0;
  return Math.abs(h).toString(36);
}

/** 把工作区相对/绝对路径解析到工作区内；越界或不存在返回 null。 */
function safeWorkspacePath(p) {
  if (!p) return AICODE_WORKSPACE;
  const resolved = path.resolve(AICODE_WORKSPACE, p);
  const rel = path.relative(AICODE_WORKSPACE, resolved);
  if (rel.startsWith('..') || path.isAbsolute(rel)) return null;
  return resolved;
}

function registerPlugin(p) {
  // 同名插件：移除旧注册（工具与 hook），再注册新的（本地优先语义）
  if (loadedNames.has(p.name)) {
    const old = loadedNames.get(p.name);
    for (const [toolName, t] of tools) {
      if (t.plugin === old.name) tools.delete(toolName);
    }
    for (const [, handlers] of hookHandlers) {
      for (let i = handlers.length - 1; i >= 0; i--) {
        if (handlers[i].plugin === old.name) handlers.splice(i, 1);
      }
    }
  }
  loadedNames.set(p.name, p);
  const hooks = p.hooks || {};
  for (const [name, value] of Object.entries(hooks)) {
    if (name === 'tool') {
      for (const [toolName, def] of Object.entries(value || {})) {
        if (def && typeof def.execute === 'function') {
          tools.set(toolName, { plugin: p.name, def });
        }
      }
    } else if (typeof value === 'function' || (value && typeof value === 'object')) {
      if (!hookHandlers.has(name)) hookHandlers.set(name, []);
      hookHandlers.get(name).push({ plugin: p.name, fn: value });
    }
  }
}

// ── 插件加载 ──

async function loadPlugins() {
  const globalCfg = readPluginsJson(path.join(AICODE_HOME, 'plugins.json'));
  const projectCfg = AICODE_WORKSPACE ? readPluginsJson(path.join(AICODE_WORKSPACE, '.aicode', 'plugins.json')) : null;

  // 1. npm 全局 → 2. npm 项目 → 3. 本地全局 → 4. 本地项目
  const npmGlobal = await loadNpmPlugins(globalCfg, 'global-npm');
  const npmProject = projectCfg ? await loadNpmPlugins(projectCfg, 'project-npm') : { loaded: [], failed: [] };
  const localGlobal = await loadLocalPlugins(path.join(AICODE_HOME, 'plugins'), globalCfg, 'global-local');
  const localProject = AICODE_WORKSPACE
    ? await loadLocalPlugins(path.join(AICODE_WORKSPACE, '.aicode', 'plugins'), projectCfg, 'project-local')
    : { loaded: [], failed: [] };

  const loaded = [];
  for (const batch of [npmGlobal, npmProject, localGlobal, localProject]) {
    for (const p of batch.loaded) if (p) loaded.push(p);
  }
  const failed = [...npmGlobal.failed, ...npmProject.failed, ...localGlobal.failed, ...localProject.failed];
  return { plugins: loaded, failed };
}

function readPluginsJson(file) {
  try {
    const raw = fs.readFileSync(file, 'utf-8');
    const cfg = JSON.parse(raw);
    return {
      plugins: Array.isArray(cfg.plugins) ? cfg.plugins : [],
      disabled: Array.isArray(cfg.disabled) ? cfg.disabled : [],
    };
  } catch (e) {
    return { plugins: [], disabled: [] };
  }
}

async function loadNpmPlugins(cfg, source) {
  const loaded = [];
  const failed = [];
  for (const pkg of cfg.plugins) {
    if (cfg.disabled.includes(pkg)) continue;
    try {
      const mod = await awaitImport(pkg);
      const initialized = await initPluginModule(mod, pkg, source);
      if (initialized) loaded.push(initialized);
      else failed.push({ name: pkg, source, error: '插件模块没有导出插件函数' });
    } catch (e) {
      console.error(`[${pkg}] npm 插件加载失败: ${e?.message || e}`);
      failed.push({ name: pkg, source, error: e?.message || String(e) });
    }
  }
  return { loaded, failed };
}

async function loadLocalPlugins(dir, cfg, source) {
  const loaded = [];
  const failed = [];
  if (!fs.existsSync(dir)) return { loaded, failed };
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    const name = entry.name;
    if (cfg.disabled.includes(name)) continue;
    const full = path.join(dir, name);
    try {
      let mod = null;
      if (entry.isFile() && /\.(mjs|js|cjs)$/.test(name)) {
        mod = await awaitImport(pathToFileURL(full).href);
      } else if (entry.isDirectory()) {
        const indexFile = resolveDirEntry(full);
        if (indexFile) mod = await awaitImport(pathToFileURL(indexFile).href);
      } else {
        continue;
      }
      const initialized = await initPluginModule(mod, name, source);
      if (initialized) loaded.push(initialized);
      else failed.push({ name, source, error: '插件模块没有导出插件函数' });
    } catch (e) {
      console.error(`[${name}] 本地插件加载失败: ${e?.message || e}`);
      failed.push({ name, source, error: e?.message || String(e) });
    }
  }
  return { loaded, failed };
}

function resolveDirEntry(dir) {
  const pkgFile = path.join(dir, 'package.json');
  if (fs.existsSync(pkgFile)) {
    try {
      const pkg = JSON.parse(fs.readFileSync(pkgFile, 'utf-8'));
      if (pkg.main) {
        const mainPath = path.join(dir, pkg.main);
        if (fs.existsSync(mainPath)) return mainPath;
      }
    } catch (e) { /* 忽略 */ }
  }
  for (const idx of ['index.mjs', 'index.js', 'index.cjs']) {
    const f = path.join(dir, idx);
    if (fs.existsSync(f)) return f;
  }
  return null;
}

/** 动态 import：裸包名（npm 插件）或 file:// URL（本地插件）。 */
function awaitImport(specifier) {
  if (specifier.startsWith('file:')) return import(specifier);
  return import(specifier);
}

/** 把插件模块导出规范化为插件对象 { name, source, hooks, dispose }。 */
function initPluginModule(mod, name, source) {
  const candidates = [];
  const modObj = mod?.default ?? mod;
  if (typeof modObj === 'function') {
    candidates.push(modObj);
  } else if (modObj && typeof modObj === 'object') {
    // opencode PluginModule 形态：{ server: Plugin } 或 { default: Plugin }
    if (typeof modObj.server === 'function') candidates.push(modObj.server);
    // 命名导出多个插件函数
    for (const v of Object.values(modObj)) {
      if (typeof v === 'function' && v !== modObj.server) candidates.push(v);
    }
  }
  if (candidates.length === 0) {
    console.error(`[${name}] 插件模块没有导出插件函数，跳过`);
    return null;
  }
  const hooks = {};
  let disposeFn = null;
  for (const fn of candidates) {
    const result = fn({
      project: { id: '', name: '', directory: AICODE_WORKSPACE },
      client: createClient(),
      $: sdk.$,
      directory: AICODE_WORKSPACE,
      worktree: AICODE_WORKSPACE,
    });
    // 支持同步返回与 async 初始化
    if (result && typeof result.then === 'function') {
      // 异步初始化：先占位，await 后补注册
      return result.then((r) => {
        if (!r) return null;
        Object.assign(hooks, r);
        if (typeof r.dispose === 'function') disposeFn = r.dispose;
        return { name, source, hooks, dispose: disposeFn };
      });
    }
    if (result) {
      Object.assign(hooks, result);
      if (typeof result.dispose === 'function') disposeFn = result.dispose;
    }
  }
  return { name, source, hooks, dispose: disposeFn };
}

function ensureShim(nodeModulesDir) {
  try {
    if (!fs.existsSync(nodeModulesDir)) fs.mkdirSync(nodeModulesDir, { recursive: true });
    const scopedDir = path.join(nodeModulesDir, '@opencode-ai');
    if (!fs.existsSync(scopedDir)) fs.mkdirSync(scopedDir, { recursive: true });
    const pkgLink = path.join(scopedDir, 'plugin');
    if (!fs.existsSync(pkgLink)) {
      fs.symlinkSync(path.join(__dirname, 'plugin-shim'), pkgLink, 'dir');
    }
  } catch (e) {
    console.error(`无法注入 @opencode-ai/plugin shim: ${e?.message || e}`);
  }
}

function normalizeResult(raw) {
  if (typeof raw === 'string') return raw;
  if (raw === undefined || raw === null) return '';
  if (typeof raw === 'object') {
    try {
      return JSON.stringify(raw);
    } catch (e) {
      return String(raw);
    }
  }
  return String(raw);
}

process.on('SIGTERM', () => {
  server.close();
  process.exit(0);
});