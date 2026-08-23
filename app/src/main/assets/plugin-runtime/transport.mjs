/**
 * AiCode 插件运行时传输层：Unix Domain Socket 服务端 + NDJSON 消息路由。
 *
 * 协议：每行一条 JSON-RPC 2.0 消息。带 id 的请求等待响应；不带 id 的通知不回包；
 * runner → Kotlin 方向的请求（plugin client.* API）由 [onRequest] 回调处理。
 *
 * 用法：
 *   const t = createTransport(socketPath, { onRequest, onNotification, onResponse });
 *   await t.start();  // 监听 socket 并输出 AICODE_PLUGIN_READY
 *   t.write({ id, method, params });  // 发送请求
 *   t.notify(method, params);  // 发送通知
 *   t.close();  // 关闭
 */
import net from 'net';
import fs from 'fs';
import path from 'path';
import readline from 'readline';

/**
 * @typedef {Object} TransportOptions
 * @property {(request: object, plugin: string|null) => Promise<{result?: any, error?: {code: number, message: string}}>} onRequest
 *   处理 runner → Kotlin 方向请求（plugin client.* API）。返回 JSON-RPC 响应。
 * @property {(notification: object) => void} [onNotification]
 *   处理 runner → Kotlin 方向通知（无 id 的消息）。当前协议无此场景。
 * @property {(response: object) => void} [onResponse]
 *   处理 Kotlin → runner 方向的响应（对 runner 发出的请求的应答）。由 [request] 内部消费。
 */

/**
 * @typedef {Object} Transport
 * @property {() => Promise<void>} start
 * @property {(method: string, params?: object) => Promise<{result?: any, error?: any}>} request
 * @property {(method: string, params?: object) => void} notify
 * @property {() => void} close
 * @property {boolean} ready
 */

/**
 * 创建 UDS 传输实例。
 * @param {string} socketPath - UDS 文件路径
 * @param {TransportOptions} options
 * @returns {Transport}
 */
export function createTransport(socketPath, options) {
  const { onRequest, onNotification, onResponse } = options;
  let server = null;
  let socket = null;
  let reader = null;
  let writer = null;
  let closed = false;
  /** Kotlin → runner 方向的 id 计数器（tools.list / hook.dispatch 等） */
  let idCounter = 0;
  /** runner → Kotlin 方向的 id 计数器（client.* API） */
  let hostIdCounter = 0;
  /** id → {resolve, reject}：Kotlin → runner 方向，等待 runner 响应的请求 */
  const pending = new Map();
  /** id → {resolve, reject}：runner → Kotlin 方向，等待 Kotlin 响应的请求 */
  const hostPending = new Map();

  function writeLine(obj) {
    if (!writer || closed) return;
    socket.write(JSON.stringify(obj) + '\n');
  }

  function handleIncomingRequest(request) {
    const id = request.id;
    const method = request.method || '';
    const plugin = request.plugin || null;
    if (!onRequest) {
      writeLine({ id, error: { code: -32601, message: `Method not found: ${method}` } });
      return;
    }
    Promise.resolve()
      .then(() => onRequest(request, plugin))
      .then((resp) => {
        writeLine({ id, result: resp?.result, error: resp?.error });
      })
      .catch((e) => {
        writeLine({ id, error: { code: -32603, message: String(e?.message || e) } });
      });
  }

  function handleLine(line) {
    if (!line.trim()) return;
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      return;
    }
    // 响应：Kotlin 对 runner 请求的应答（带 id、无 method、含 result/error）
    // 注意：runner → Kotlin 方向的响应（对 hostRequest 的应答）和 Kotlin → runner 方向的响应（对 request 的应答）
    // 都走这个分支，但用不同的 pending map。
    if (msg.method === undefined && msg.id !== undefined && msg.id !== null && (msg.result !== undefined || msg.error !== undefined)) {
      // 先查 hostPending（runner → Kotlin 方向）
      const hp = hostPending.get(msg.id);
      if (hp) {
        hostPending.delete(msg.id);
        if (msg.error) {
          hp.reject(Object.assign(new Error(`[AiCode] ${msg.error.message}`), { code: msg.error.code }));
        } else {
          hp.resolve(msg.result !== undefined ? msg.result : {});
        }
        onResponse?.(msg);
        return;
      }
      // 再查 pending（Kotlin → runner 方向）
      const p = pending.get(msg.id);
      if (p) {
        pending.delete(msg.id);
        if (msg.error) {
          p.reject(Object.assign(new Error(`[AiCode] ${msg.error.message}`), { code: msg.error.code }));
        } else {
          p.resolve(msg.result !== undefined ? msg.result : {});
        }
      }
      onResponse?.(msg);
      return;
    }
    // 通知：无 id 的消息
    if (msg.id === undefined || msg.id === null) {
      onNotification?.(msg);
      return;
    }
    // 请求：Kotlin → runner 方向（tools.list / hook.dispatch 等）
    handleIncomingRequest(msg);
  }

  return {
    get ready() {
      return !closed && socket !== null;
    },

    async start() {
      if (closed) throw new Error('transport 已关闭');
      if (server) throw new Error('transport 已启动');
      if (fs.existsSync(socketPath)) {
        try { fs.unlinkSync(socketPath); } catch { /* 忽略 */ }
      }
      // 确保 socket 父目录存在
      try { fs.mkdirSync(path.dirname(socketPath), { recursive: true }); } catch { /* 忽略 */ }

      server = net.createServer((s) => {
        socket = s;
        writer = s; // 连接建立时才设置 writer（server.listen 回调时还没有连接）
        reader = readline.createInterface({ input: s, crlfDelay: Infinity });
        reader.on('line', handleLine);
        s.on('error', () => { /* 读循环异常会自然退出 */ });
        s.on('close', () => {
          // socket 断开时让所有 pending 请求失败
          for (const [, p] of pending) {
            p.reject(new Error('[AiCode] UDS 连接已关闭'));
          }
          pending.clear();
          for (const [, p] of hostPending) {
            p.reject(new Error('[AiCode] UDS 连接已关闭'));
          }
          hostPending.clear();
          socket = null;
          reader = null;
          writer = null;
        });
      });
      server.on('error', (e) => console.error(`[transport] server error: ${e?.message || e}`));

      await new Promise((resolve, reject) => {
        server.listen(socketPath, () => {
          resolve();
        });
        server.once('error', reject);
      });
      // 等首个连接建立后才算 ready（writer 在连接建立时才设置）
      await new Promise((resolve) => {
        if (socket) return resolve();
        const check = setInterval(() => {
          if (socket) { clearInterval(check); resolve(); }
        }, 50);
      });
      console.log('AICODE_PLUGIN_READY');
    },

    /** Kotlin → runner 方向：发送请求并等待 runner 响应（tools.list / hook.dispatch 等）。 */
    request(method, params) {
      if (closed || !socket) return Promise.reject(new Error('[AiCode] transport 未连接'));
      const id = ++idCounter;
      return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        const msg = { jsonrpc: '2.0', id, method };
        if (params && typeof params === 'object') msg.params = params;
        socket.write(JSON.stringify(msg) + '\n');
      });
    },

    /** runner → Kotlin 方向：发送请求并等待 Kotlin 响应（client.* API）。 */
    hostRequest(method, params, pluginName) {
      if (closed || !socket) return Promise.reject(new Error('[AiCode] transport 未连接'));
      const id = ++hostIdCounter;
      return new Promise((resolve, reject) => {
        hostPending.set(id, { resolve, reject });
        const msg = { jsonrpc: '2.0', id, method };
        if (params && typeof params === 'object') msg.params = params;
        if (pluginName) msg.plugin = pluginName;
        socket.write(JSON.stringify(msg) + '\n');
      });
    },

    notify(method, params) {
      if (closed || !socket) return;
      const msg = { jsonrpc: '2.0', method };
      if (params && typeof params === 'object') msg.params = params;
      socket.write(JSON.stringify(msg) + '\n');
    },

    close() {
      if (closed) return;
      closed = true;
      try { reader?.close(); } catch { /* 忽略 */ }
      try { socket?.destroy(); } catch { /* 忽略 */ }
      try { server?.close(); } catch { /* 忽略 */ }
      try { fs.unlinkSync(socketPath); } catch { /* 忽略 */ }
      for (const [, p] of pending) p.reject(new Error('[AiCode] transport 已关闭'));
      pending.clear();
      for (const [, p] of hostPending) p.reject(new Error('[AiCode] transport 已关闭'));
      hostPending.clear();
    },
  };
}
