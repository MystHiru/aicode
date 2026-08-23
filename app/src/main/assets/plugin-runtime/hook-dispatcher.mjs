/**
 * AiCode 插件 Hook 分发器：管理 hook/tool/插件名注册表，提供修改型/返回型 hook 分发。
 *
 * 修改型 hook（chat.headers / tool.execute.before 等）：fn(input, output) 就地修改 output。
 * 返回型 hook（provider.models / auth.loader / small_model 等）：fn(input) 直接返回结果，收集各插件返回值。
 * 事件 hook（event）：dispatchEvent({type, ...}) 派发到所有 event 订阅者。
 */
import fs from 'fs';

/** 返回型 hook：fn(input) 直接返回结果，不走 output 修改语义。 */
const RETURN_HOOKS = new Set([
  'provider.models',
  'auth.loader',
  'experimental.provider.small_model',
  'experimental.text.complete',
]);

/**
 * 创建 hook 注册表实例。
 * @param {Object} opts
 * @param {string} opts.workspaceDir - 工作区绝对路径（用于 tool.execute 的 context.directory）
 * @param {(name: string) => object} opts.createClient - 为插件工具创建 SDK client
 * @param {(plugin: string, method: string, params: object) => Promise<any>} opts.hostRequest - 向宿主发请求
 * @returns {Object} 注册表 + 分发函数
 */
export function createHookRegistry(opts) {
  const { workspaceDir, createClient, hostRequest } = opts;
  /** hookName -> [{ plugin, fn, provider }]：按加载顺序执行，后执行者覆盖前者的 output 字段。 */
  const hookHandlers = new Map();
  /** toolName -> { plugin, def }：同名工具后者覆盖前者（本地插件优先于 npm 插件）。 */
  const tools = new Map();
  /** 已加载插件名（去重用，同名时新加载的替换旧的）。 */
  const loadedNames = new Map();

  function registerPlugin(plugin) {
    // 同名插件：移除旧注册（工具与 hook），再注册新的（本地优先语义）
    if (loadedNames.has(plugin.name)) {
      const old = loadedNames.get(plugin.name);
      for (const [toolName, t] of tools) {
        if (t.plugin === old.name) tools.delete(toolName);
      }
      for (const [, handlers] of hookHandlers) {
        for (let i = handlers.length - 1; i >= 0; i--) {
          if (handlers[i].plugin === old.name) handlers.splice(i, 1);
        }
      }
    }
    loadedNames.set(plugin.name, plugin);
    const hooks = plugin.hooks || {};
    for (const [name, value] of Object.entries(hooks)) {
      if (name === 'tool') {
        for (const [toolName, def] of Object.entries(value || {})) {
          if (def && typeof def.execute === 'function') {
            tools.set(toolName, { plugin: plugin.name, def });
          }
        }
      } else if (name === 'auth' && value && typeof value === 'object') {
        // auth 声明由 auth-handler.mjs 单独处理（需要 provider 绑定 + methods 列表）
        // 这里只处理 auth.loader（返回型 hook）
        const provider = value.provider;
        if (provider && typeof value.loader === 'function') {
          if (!hookHandlers.has('auth.loader')) hookHandlers.set('auth.loader', []);
          hookHandlers.get('auth.loader').push({ plugin: plugin.name, fn: value.loader, provider });
        }
      } else if (name === 'provider' && value && typeof value === 'object') {
        // provider.models（返回型 hook，带 provider id 绑定）
        if (typeof value.models === 'function') {
          if (!hookHandlers.has('provider.models')) hookHandlers.set('provider.models', []);
          hookHandlers.get('provider.models').push({ plugin: plugin.name, fn: value.models, provider: value.id });
        }
      } else if (typeof value === 'function' || (value && typeof value === 'object')) {
        if (!hookHandlers.has(name)) hookHandlers.set(name, []);
        hookHandlers.get(name).push({ plugin: plugin.name, fn: value });
      }
    }
  }

  /**
   * 分发修改型 hook：fn(input, output) 就地修改 output，返回合并后的 output 与各插件错误。
   */
  async function dispatchHook(hook, input, output) {
    const handlers = hookHandlers.get(hook) || [];
    const errors = [];
    let currentOutput = output || {};
    for (const h of handlers) {
      try {
        await h.fn(input || {}, currentOutput);
      } catch (e) {
        errors.push({ plugin: h.plugin, error: String(e?.message || e) });
      }
    }
    return { output: currentOutput, errors };
  }

  /**
   * 分发返回型 hook（provider.models / auth.loader / small_model 等），收集各插件返回值。
   * auth.loader 特殊处理：注入 getAuth()（实时从宿主取凭据）与 provider 配置（input.providerConfig），
   * 且只调用声明了该 provider 的插件（input.provider 匹配），避免多 auth 插件串扰。
   */
  async function dispatchReturnHook(hook, input) {
    const handlers = hookHandlers.get(hook) || [];
    const errors = [];
    const results = [];
    const loaderHandlers = hook === 'auth.loader'
      ? handlers.filter((h) => !(input || {}).provider || h.provider === (input || {}).provider)
      : handlers;
    for (const h of loaderHandlers) {
      try {
        let r;
        if (hook === 'auth.loader') {
          // getAuth/providerConfig 对齐 opencode 宿主语义：永远返回对象（无凭据时为空对象），
          // 避免插件 `current.type` 直接访问 null 崩溃（auth 插件共性模式）。
          const providerConfig = (input || {}).providerConfig || {};
          const getAuth = async () => {
            const res = await hostRequest('client.auth.get', { id: h.provider }, h.plugin);
            return (res && typeof res === 'object') ? res : {};
          };
          r = await h.fn(getAuth, providerConfig);
        } else {
          r = await h.fn(input || {});
        }
        if (r != null) results.push(r);
      } catch (e) {
        errors.push({ plugin: h.plugin, error: String(e?.message || e) });
      }
    }
    return { results, errors };
  }

  /** 派发事件到所有 event 订阅者。 */
  async function dispatchEvent(event) {
    const handlers = hookHandlers.get('event') || [];
    for (const h of handlers) {
      try {
        console.log(`[${h.plugin}] event: ${event?.type || ''}`);
        await h.fn({ event: event || {} });
      } catch (e) {
        console.error(`[${h.plugin}] event 处理失败: ${e?.message || e}`);
      }
    }
  }

  /** 列出所有已注册工具（供 tools.list）。 */
  function listTools() {
    return [...tools.entries()].map(([name, t]) => ({
      name,
      description: t.def.description,
      parameters: toolParams(t.def),
      plugin: t.plugin,
    }));
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

  /** 执行插件工具（供 tool.call）。 */
  async function callTool(name, args, sessionID) {
    const t = tools.get(name);
    if (!t) {
      return { status: 'error', message: `工具 ${name} 不存在`, code: 'TOOL_NOT_FOUND' };
    }
    try {
      const start = Date.now();
      console.log(`[${t.plugin}] 执行工具 ${name} args=${JSON.stringify(args || {}).slice(0, 200)}`);
      const raw = await t.def.execute(args || {}, {
        directory: workspaceDir,
        worktree: workspaceDir,
        sessionID,
        client: createClient(t.plugin),
        // opencode 工具上下文兼容：AiCode 无交互确认/进度机制，ask 自动允许（工具
        // 执行仍受宿主权限层管控）、metadata/push 为 no-op；abort 给永不中断的信号。
        abort: new AbortController().signal,
        ask: async () => true,
        metadata: () => {},
        push: () => {},
      });
      console.log(`[${t.plugin}] 工具 ${name} 执行成功 耗时=${Date.now() - start}ms`);
      return { status: 'success', data: normalizeResult(raw) };
    } catch (e) {
      console.error(`[${t.plugin}] 工具 ${name} 执行失败: ${e?.message || e}`);
      return { status: 'error', message: String(e?.message || e), code: 'PLUGIN_TOOL_ERROR' };
    }
  }

  function normalizeResult(raw) {
    if (typeof raw === 'string') return raw;
    if (raw === undefined || raw === null) return '';
    if (typeof raw === 'object') {
      try { return JSON.stringify(raw); } catch { return String(raw); }
    }
    return String(raw);
  }

  /** 列出所有已加载插件（供 plugins.list）。 */
  function listPlugins(plugins) {
    const list = plugins.map((p) => ({
      name: p.name,
      source: p.source,
      version: p.version || null,
      tools: [...tools.entries()].filter(([, t]) => t.plugin === p.name).map(([n]) => n),
      hooks: Object.keys(p.hooks),
      auth: p.hooks && p.hooks.auth && typeof p.hooks.auth === 'object'
        ? {
            provider: p.hooks.auth.provider || null,
            methods: Array.isArray(p.hooks.auth.methods)
              ? p.hooks.auth.methods.map((m) => ({
                  label: (m && m.label) || '',
                  type: (m && m.type === 'api') ? 'api' : 'oauth',
                }))
              : [],
          }
        : null,
    }));
    return list;
  }

  /** 通知所有插件执行 dispose。 */
  async function disposeAll(plugins) {
    for (const p of plugins) {
      try {
        await p.dispose?.();
      } catch (e) {
        console.error(`[${p.name}] dispose 失败: ${e?.message || e}`);
      }
    }
  }

  return {
    registerPlugin,
    dispatchHook,
    dispatchReturnHook,
    dispatchEvent,
    listTools,
    callTool,
    listPlugins,
    disposeAll,
    RETURN_HOOKS,
  };
}
