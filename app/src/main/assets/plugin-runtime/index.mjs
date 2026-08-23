#!/usr/bin/env node
/**
 * AiCode 插件运行时（Sidecar）主入口
 *
 * 运行于 PRoot 容器内 Node.js 运行时，通过 Unix Domain Socket 与 Android 宿主双向通信。
 * 协议：NDJSON 上的 JSON-RPC 2.0。
 *
 * 启动流程：
 *   1. 读取环境变量 AICODE_SOCK / AICODE_WORKSPACE / AICODE_HOME
 *   2. 加载 sdk.mjs 注入 globalThis（供 @opencode-ai/plugin shim 引用）
 *   3. 确保 @opencode-ai/plugin 在全局/项目 node_modules 可解析（symlink 到 plugin-shim）
 *   4. 加载插件（npm 全局 → npm 项目 → 本地全局 → 本地项目，同名本地优先）
 *   5. 注册插件到 hook 注册表 + auth 注册表
 *   6. 监听 UDS，stdout 输出 AICODE_PLUGIN_READY 就绪信号
 *   7. 处理宿主请求（tools.list / tool.call / hook.dispatch / plugins.list / dispose / auth.*）
 */
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const AICODE_SOCK = process.env.AICODE_SOCK;
const AICODE_WORKSPACE = process.env.AICODE_WORKSPACE || '/root/workspace';
const AICODE_HOME = process.env.AICODE_HOME || '/root/.aicode';

if (!AICODE_SOCK) {
  console.error('AICODE_SOCK 环境变量缺失，插件运行时退出');
  process.exit(1);
}

// ── 1. 加载 SDK ──
const sdk = await import(pathToFileURL(path.join(__dirname, 'sdk.mjs')).href);

// ── 2. 注入 @opencode-ai/plugin shim ──
const { ensureShim, hasLocalPluginFiles, loadPlugins } = await import('./plugin-loader.mjs');
const shimDir = path.join(__dirname, 'plugin-shim');
ensureShim(path.join(AICODE_HOME, 'node_modules'), shimDir);
// 项目级 shim 仅当项目 .aicode/plugins 下存在本地插件时才创建
if (AICODE_WORKSPACE && hasLocalPluginFiles(AICODE_WORKSPACE)) {
  ensureShim(path.join(AICODE_WORKSPACE, '.aicode', 'node_modules'), shimDir);
}

// ── 3. 加载插件 ──
const { createClient } = await import('./client-api.mjs');
const { createHookRegistry } = await import('./hook-dispatcher.mjs');
const { createAuthRegistry } = await import('./auth-handler.mjs');
const { createTransport } = await import('./transport.mjs');

// hostRequest 闭包：向宿主发 JSON-RPC 请求。socket 未就绪时等待连接建立。
let hostSocketWaiters = [];
let hostSocketReady = false;
function waitForHostSocket() {
  if (hostSocketReady) return Promise.resolve();
  return new Promise((resolve) => hostSocketWaiters.push(resolve));
}
async function hostRequest(method, params = {}, pluginName) {
  await waitForHostSocket();
  return transport.hostRequest(method, params, pluginName);
}

// 创建注册表
const authRegistry = createAuthRegistry({ hostRequest });
const hookRegistry = createHookRegistry({
  workspaceDir: AICODE_WORKSPACE,
  createClient: (name) => createClient(name, hostRequest, AICODE_WORKSPACE),
  hostRequest,
});

// 加载插件
const { plugins, failed: failedPlugins, disabled: disabledPlugins, missing: missingPlugins, invalidConfigs } = await loadPlugins({
  homeDir: AICODE_HOME,
  workspaceDir: AICODE_WORKSPACE,
  createClient: (name) => createClient(name, hostRequest, AICODE_WORKSPACE),
  sdk,
});

// 注册插件到 hook + auth 注册表
for (const p of plugins) {
  hookRegistry.registerPlugin(p);
  authRegistry.registerAuthMethods(p);
}

// ── 4. 启动 UDS 传输 ──
const transport = createTransport(AICODE_SOCK, {
  onRequest: async (request, plugin) => {
    const method = request.method || '';
    const params = request.params || {};
    try {
      switch (method) {
        case 'tools.list':
          return { result: { tools: hookRegistry.listTools() } };
        case 'tool.call': {
          const r = await hookRegistry.callTool(params.name, params.args, params.sessionID);
          return { result: { result: r } };
        }
        case 'hook.dispatch': {
          const { hook, input, output } = params;
          if (hookRegistry.RETURN_HOOKS.has(hook)) {
            const { results, errors } = await hookRegistry.dispatchReturnHook(hook, input);
            // 序列化前剔除 fetch 函数（UDS 只能传 JSON），以 hasFetch 标记告知宿主可走代理
            const serializable = results.map((r) => {
              if (r && typeof r === 'object' && typeof r.fetch === 'function') {
                const { fetch, ...rest } = r;
                // auth.loader 特殊处理：注册代理目标
                if (hook === 'auth.loader' && input?.provider) {
                  authRegistry.registerAuthFetchProxy(input.provider, plugin, r);
                }
                return { ...rest, hasFetch: true };
              }
              return r;
            });
            return { result: { output: output || {}, results: serializable, errors } };
          }
          const r = await hookRegistry.dispatchHook(hook, input, output);
          return { result: r };
        }
        case 'plugins.list': {
          const list = hookRegistry.listPlugins(plugins);
          for (const f of failedPlugins) {
            list.push({ name: f.name, source: f.source, error: f.error, tools: [], hooks: [] });
          }
          for (const d of disabledPlugins) {
            list.push({ name: d.name, source: d.source, disabled: true, tools: [], hooks: [] });
          }
          for (const m of missingPlugins) {
            list.push({ name: m.name, source: m.source, missing: true, tools: [], hooks: [] });
          }
          return { result: { plugins: list, invalidConfigs } };
        }
        case 'dispose':
          await hookRegistry.disposeAll(plugins);
          return { result: {} };
        case 'auth.methods.list':
          return { result: authRegistry.handleAuthMethodsList() };
        case 'auth.authorize':
          return { result: await authRegistry.handleAuthAuthorize(params) };
        case 'auth.callback':
          return { result: await authRegistry.handleAuthCallback(params) };
        case 'auth.proxy':
          return { result: await authRegistry.handleAuthProxy() };
        default:
          return { error: { code: -32601, message: `未知方法 ${method}` } };
      }
    } catch (e) {
      return { error: { code: -32603, message: String(e?.message || e) } };
    }
  },
  onNotification: async (msg) => {
    // 当前协议无服务端通知场景
  },
});

await transport.start();
hostSocketReady = true;
for (const w of hostSocketWaiters) w();
hostSocketWaiters = [];

process.on('SIGTERM', () => {
  transport.close();
  process.exit(0);
});
