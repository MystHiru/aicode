/**
 * AiCode 插件 Auth 处理：管理 auth.methods 声明、authorize/callback 流程、auth.loader 自定义 fetch 的 HTTP 代理。
 *
 * 协议：
 * - auth.methods.list：列出所有 provider 的登录方法
 * - auth.authorize：执行登录授权（OAuth 返回 url+callback，api 返回 requiresKey）
 * - auth.callback：提交 OAuth 回调（code 模式传 code，auto 模式无参）
 * - auth.proxy：启动 HTTP 代理，返回 provider → 代理地址映射（供 Kotlin 把命中插件认证的请求转发过来）
 */
import http from 'http';
import { Readable } from 'stream';

/**
 * 创建 auth 注册表实例。
 * @param {Object} opts
 * @param {(plugin: string, method: string, params: object) => Promise<any>} opts.hostRequest - 向宿主发请求（client.auth.set）
 * @returns {Object} auth 注册表 + 处理函数
 */
export function createAuthRegistry(opts) {
  const { hostRequest } = opts;
  /** provider id -> [{ plugin, provider, label, type, authorize, index }]：插件 auth.methods 声明。 */
  const authMethodsByProvider = new Map();
  /** provider id -> { plugin, fetch, baseURL, headers }：auth.loader 返回自定义 fetch 时注册的代理目标。 */
  const authFetchProxies = new Map();
  /** provider id -> { callback, method }：authorize 后等待 callback 的挂起登录。 */
  const pendingAuthorizes = new Map();
  /** HTTP 代理服务器与端口（127.0.0.1 随机端口）。 */
  let authProxyServer = null;
  let authProxyPort = 0;

  /**
   * 从插件 hooks 中提取 auth.methods 注册到 authMethodsByProvider。
   * auth.loader 由 hook-dispatcher.mjs 单独处理（带 provider 绑定的返回型 hook）。
   */
  function registerAuthMethods(plugin) {
    const auth = plugin.hooks?.auth;
    if (!auth || typeof auth !== 'object') return;
    const provider = auth.provider;
    if (!provider || !Array.isArray(auth.methods)) return;
    if (!authMethodsByProvider.has(provider)) authMethodsByProvider.set(provider, []);
    auth.methods.forEach((m, i) => {
      if (!m || typeof m !== 'object') return;
      authMethodsByProvider.get(provider).push({
        plugin: plugin.name,
        provider,
        label: m.label || '',
        type: m.type === 'api' ? 'api' : 'oauth',
        authorize: typeof m.authorize === 'function' ? m.authorize : null,
        index: i,
      });
    });
  }

  /** 同名插件重载时清理旧 auth 声明。 */
  function unregisterAuthMethods(pluginName) {
    for (const [provider, list] of authMethodsByProvider) {
      for (let i = list.length - 1; i >= 0; i--) {
        if (list[i].plugin === pluginName) list.splice(i, 1);
      }
      if (list.length === 0) authMethodsByProvider.delete(provider);
    }
    for (const [provider, entry] of authFetchProxies) {
      if (entry.plugin === pluginName) authFetchProxies.delete(provider);
    }
    for (const [provider, pending] of pendingAuthorizes) {
      if (pending.plugin === pluginName) pendingAuthorizes.delete(provider);
    }
  }

  /** auth.loader hook 返回自定义 fetch 时注册代理目标。 */
  function registerAuthFetchProxy(provider, plugin, result) {
    if (result && typeof result.fetch === 'function') {
      authFetchProxies.set(provider, { plugin, fetch: result.fetch, baseURL: result.baseURL || null, headers: result.headers || null });
    } else if (authFetchProxies.has(provider)) {
      authFetchProxies.delete(provider);
    }
  }

  /** auth.methods.list：列出所有 provider 的登录方法。 */
  function handleAuthMethodsList() {
    const providers = [];
    for (const [provider, list] of authMethodsByProvider) {
      providers.push({
        provider,
        methods: list.map((m) => ({ label: m.label, type: m.type, plugin: m.plugin })),
      });
    }
    return { providers };
  }

  /** auth.authorize：执行登录授权。 */
  async function handleAuthAuthorize({ provider, methodIndex, inputs }) {
    const list = authMethodsByProvider.get(provider) || [];
    const method = list[methodIndex];
    if (!method) {
      return { error: `provider ${provider} 无第 ${methodIndex} 个登录方法` };
    }
    try {
      if (method.type === 'api' && typeof method.authorize !== 'function') {
        // opencode 语义：无 authorize 的 api 方法直接提示输入 key，宿主侧自行收集。
        return { type: 'api', requiresKey: true, label: method.label };
      }
      if (typeof method.authorize !== 'function') {
        return { error: `登录方法 ${method.label} 未实现 authorize` };
      }
      // 不传 inputs（undefined）而非空对象：部分插件（如 antigravity）用 `if (inputs)` 区分
      // CLI 交互菜单（等 stdin，AiCode 无人输入会永久阻塞）与 TUI/宿主驱动流程（返回 url+callback）。
      // 同时加超时保护：authorize 应在数秒内返回授权 URL，网络异常/插件 bug 时给出明确错误而非挂到宿主 30s 超时。
      let result;
      try {
        result = await Promise.race([
          Promise.resolve().then(() => method.authorize(inputs !== undefined ? inputs : undefined)),
          new Promise((_, reject) => setTimeout(() => reject(new Error('AUTHORIZE_TIMEOUT')), 25000)),
        ]);
      } catch (e) {
        if (String(e?.message) === 'AUTHORIZE_TIMEOUT') {
          return { error: `authorize 执行超时（25s）：${method.label} 可能要求交互式输入，当前 AiCode 不支持该插件的命令行登录流程` };
        }
        return { error: `authorize 失败: ${String(e?.message || e)}` };
      }
      if (result && typeof result === 'object') {
        // api 型 authorize 直接返回 success：凭据立即落盘
        if (result.type === 'success') {
          const auth = result.refresh !== undefined || result.access !== undefined
            ? { type: 'oauth', refresh: result.refresh ?? '', access: result.access ?? '', expires: result.expires ?? 0 }
            : { type: 'api', key: result.key ?? '', metadata: result.metadata };
          await hostRequest('client.auth.set', { id: provider, body: auth }, method.plugin);
          return { type: 'success', completed: true };
        }
        if (result.type === 'failed') {
          return { error: (result.error) || '登录失败' };
        }
        pendingAuthorizes.set(provider, {
          plugin: method.plugin,
          methodIndex,
          callback: typeof result.callback === 'function' ? result.callback : null,
          mode: result.method === 'code' ? 'code' : 'auto',
        });
        return {
          url: result.url || '',
          instructions: result.instructions || '',
          method: result.method === 'code' ? 'code' : 'auto',
        };
      }
      return { error: 'authorize 返回无效结果' };
    } catch (e) {
      return { error: String(e?.message || e) };
    }
  }

  /** auth.callback：提交 OAuth 回调。 */
  async function handleAuthCallback({ provider, code }) {
    const pending = pendingAuthorizes.get(provider);
    if (!pending) {
      return { type: 'failed', error: '没有挂起的登录流程，请先执行 auth.authorize' };
    }
    try {
      const cb = pending.callback;
      const result = cb
        ? await cb(code !== undefined ? code : undefined)
        : { type: 'failed' };
      pendingAuthorizes.delete(provider);
      if (result && result.type === 'success') {
        const auth = result.refresh !== undefined || result.access !== undefined
          ? { type: 'oauth', refresh: result.refresh ?? '', access: result.access ?? '', expires: result.expires ?? 0 }
          : { type: 'api', key: result.key ?? '', metadata: result.metadata };
        await hostRequest('client.auth.set', { id: provider, body: auth }, pending.plugin);
        return { type: 'success' };
      }
      return { type: 'failed', error: (result && result.error) || '登录失败' };
    } catch (e) {
      pendingAuthorizes.delete(provider);
      return { type: 'failed', error: String(e?.message || e) };
    }
  }

  /** auth.proxy：启动 HTTP 代理并返回 provider → 代理地址映射。 */
  async function handleAuthProxy() {
    await startAuthProxy();
    const providers = {};
    for (const [provider] of authFetchProxies) {
      providers[provider] = { baseUrl: `http://127.0.0.1:${authProxyPort}` };
    }
    return { providers, port: authProxyPort };
  }

  // ── auth.loader 自定义 fetch 的 HTTP 代理 ──
  // Kotlin 把命中插件认证的 provider 请求指向 127.0.0.1:<port>，真实目标 URL 放 X-Aicode-Real-Url 头，
  // 本代理调用插件 loader 返回的 fetch 转发，响应流直接 pipe（支持 SSE / chunked）。
  // 返回 Promise<port>：等 listen 完成才 resolve，避免调用方拿到尚未绑定的端口。
  // 注意：请求/响应头必须剔除传输层头（host/content-length/connection/accept-encoding/content-encoding 等）——
  // 这些头由 fetch 自行管理；bun 环境下保留 host+content-length 会触发 TLS 校验错乱（ERR_TLS_CERT_ALTNAME_INVALID），实测确认。
  const PROXY_FORBIDDEN_REQ_HEADERS = new Set([
    'host', 'connection', 'keep-alive', 'proxy-connection', 'transfer-encoding', 'te', 'upgrade',
    'content-length', 'accept-encoding',
  ]);
  const PROXY_FORBIDDEN_RESP_HEADERS = new Set(['content-length', 'content-encoding', 'transfer-encoding']);

  /** auth 代理详细日志开关：AICODE_AUTH_PROXY_DEBUG=1 时打印完整 headers/请求体/响应体（Authorization 脱敏）。 */
  const AUTH_PROXY_DEBUG = process.env.AICODE_AUTH_PROXY_DEBUG === '1';

  /** 脱敏敏感头：authorization 只留前 8 字符。 */
  function redactHeaders(headers) {
    const out = {};
    for (const [k, v] of Object.entries(headers)) {
      out[k] = k.toLowerCase() === 'authorization'
        ? `${String(v).slice(0, 8)}…(len=${String(v).length})`
        : String(v);
    }
    return out;
  }

  function startAuthProxy() {
    if (authProxyServer) return Promise.resolve(authProxyPort);
    return new Promise((resolve, reject) => {
      const server = http.createServer(async (req, res) => {
        const realUrl = req.headers['x-aicode-real-url'];
        const provider = req.headers['x-aicode-provider'];
        const entry = provider ? authFetchProxies.get(provider) : null;
        if (!realUrl || !entry) {
          res.writeHead(400, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ error: 'auth proxy 未注册该 provider（插件 auth.loader 未返回 fetch）' }));
          return;
        }
        try {
          const chunks = [];
          for await (const chunk of req) chunks.push(chunk);
          const body = Buffer.concat(chunks);
          // 组装 fetch init：剔除 X-Aicode-* 私有头与传输层头（host/content-length/accept-encoding 等，由 fetch 自行管理），
          // 合并插件 loader 返回的自定义 headers（如 quota/路由头）。
          const initHeaders = {};
          for (const [k, v] of Object.entries(req.headers)) {
            if (k.startsWith('x-aicode-')) continue;
            if (PROXY_FORBIDDEN_REQ_HEADERS.has(k.toLowerCase())) continue;
            initHeaders[k] = v;
          }
          const extra = entry.headers;
          if (extra && typeof extra === 'object') {
            for (const [k, v] of Object.entries(extra)) {
              if (v != null && !(k.toLowerCase() in initHeaders)) initHeaders[k] = String(v);
            }
          }
          const init = {
            method: req.method || 'POST',
            headers: initHeaders,
            // body 传字符串而非 Buffer：部分插件（如 opencode-antigravity-auth）以 typeof body === 'string' 判断并转换请求体，
            // Buffer 会跳过转换（URL 已改写但 body 保持原始格式 → 上游 400 Unknown name）。实测确认。
            body: (req.method === 'GET' || req.method === 'HEAD' || body.length === 0) ? undefined : body.toString('utf-8'),
          };
          const t0 = Date.now();
          console.log(`[auth-proxy] → ${req.method || 'POST'} ${realUrl} (provider=${provider}, body=${body.length}B)`);
          if (AUTH_PROXY_DEBUG) {
            console.log(`[auth-proxy] req headers: ${JSON.stringify(redactHeaders(initHeaders))}`);
            if (body.length > 0) console.log(`[auth-proxy] req body: ${body.toString('utf-8').slice(0, 2048)}`);
          }
          const upstream = await entry.fetch(realUrl, init);
          const elapsedMs = Date.now() - t0;
          const respHeaders = {};
          for (const [k, v] of Object.entries(upstream.headers || {})) {
            if (PROXY_FORBIDDEN_RESP_HEADERS.has(k.toLowerCase())) continue;
            respHeaders[k] = v;
          }
          console.log(`[auth-proxy] ← ${upstream.status} in ${elapsedMs}ms`);
          if (AUTH_PROXY_DEBUG) {
            console.log(`[auth-proxy] resp headers: ${JSON.stringify(redactHeaders(respHeaders))}`);
          }
          res.writeHead(upstream.status || 200, respHeaders);
          const upBody = upstream.body;
          if (upBody && typeof upBody.getReader === 'function') {
            const nodeStream = typeof Readable.fromWeb === 'function'
              ? Readable.fromWeb(upBody)
              : webStreamToNode(upBody);
            nodeStream.on('error', () => res.destroy());
            nodeStream.pipe(res);
          } else if (upBody && typeof upBody.pipe === 'function') {
            upBody.on('error', () => res.destroy());
            upBody.pipe(res);
          } else if (upBody != null) {
            const text = Buffer.isBuffer(upBody) ? upBody.toString('utf-8') : String(upBody);
            if (AUTH_PROXY_DEBUG) console.log(`[auth-proxy] resp body: ${text.slice(0, 2048)}`);
            res.end(text);
          } else {
            res.end();
          }
        } catch (e) {
          res.writeHead(502, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ error: String(e?.message || e) }));
        }
      });
      server.on('error', (e) => console.error(`[auth-proxy] 代理错误: ${e?.message || e}`));
      server.listen(0, '127.0.0.1', () => {
        authProxyPort = server.address().port;
        authProxyServer = server;
        console.log(`[auth-proxy] listening on 127.0.0.1:${authProxyPort}`);
        resolve(authProxyPort);
      });
    });
  }

  /** Web ReadableStream → Node Readable 的兼容回退（旧 Node 无 Readable.fromWeb）。 */
  function webStreamToNode(webStream) {
    const reader = webStream.getReader();
    return new Readable({
      async read() {
        try {
          const { done, value } = await reader.read();
          if (done) this.push(null);
          else this.push(Buffer.from(value));
        } catch (e) {
          this.destroy(e);
        }
      },
    });
  }

  return {
    registerAuthMethods,
    unregisterAuthMethods,
    registerAuthFetchProxy,
    handleAuthMethodsList,
    handleAuthAuthorize,
    handleAuthCallback,
    handleAuthProxy,
  };
}
