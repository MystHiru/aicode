/**
 * 宿主模拟器：扮演 AiCode Android 宿主（Kotlin 侧）连接 runner.mjs，
 * 验证双向 JSON-RPC 协议：
 *   1. 收 tools.list 并回工具列表
 *   2. 收 tool.call（execute 内部调用 client.* API）并等待 runner 完成
 *   3. 对 runner 发来的 hostRequest（client.session.get 等）回响应
 * 每条全程带 20s 看门狗，超时打印并退出码 1。
 */
import net from 'net';
import fs from 'fs';

const SOCK = '/tmp/test-runner.sock';
const WATCHDOG_MS = 20000;

// runner 由外部启动（AICODE_SOCK=/tmp/test-runner.sock），这里仅扮演客户端
const client = net.createConnection(SOCK);
client.setEncoding('utf8');

let buffer = '';
let nextId = 100;
const pending = new Map();
let hostReplies = 0;

function send(method, params = {}, id = null) {
  if (id === null) id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    const msg = { jsonrpc: '2.0', id, method };
    if (Object.keys(params).length) msg.params = params;
    client.write(JSON.stringify(msg) + '\n');
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id);
        reject(new Error(`看门狗超时：${method} (id=${id})`));
      }
    }, WATCHDOG_MS);
  });
}

function replyHostRequest(id, result) {
  hostReplies++;
  const msg = { jsonrpc: '2.0', id, result };
  client.write(JSON.stringify(msg) + '\n');
}

function handleLine(raw) {
  const msg = JSON.parse(raw);
  // runner 主动发来的 hostRequest（带 method）：模拟宿主服务
  if (msg.method && msg.id !== undefined && msg.id !== null) {
    const m = msg.method;
    console.log(`[host] → 收到 hostRequest: ${m} id=${msg.id}`);
    if (m === 'client.session.get') {
      replyHostRequest(msg.id, { id: msg.params?.id ?? '', title: '测试会话', modelID: 'gpt-4o', providerID: 'openai' });
    } else if (m === 'client.session.list') {
      replyHostRequest(msg.id, { sessions: [{ id: 's1', title: 'A' }, { id: 's2', title: 'B' }] });
    } else if (m === 'client.session.messages') {
      replyHostRequest(msg.id, { messages: [{ id: 'm1', role: 'user', content: 'hi', timestamp: 1 }] });
    } else if (m === 'client.app.log') {
      console.log(`[host] app.log: ${JSON.stringify(msg.params?.body)}`);
      replyHostRequest(msg.id, true);
    } else if (m === 'client.config.get') {
      replyHostRequest(msg.id, { workspace: '/tmp', defaultModel: { providerID: 'x', model: 'y' } });
    } else if (m === 'client.tui.toast') {
      console.log(`[host] toast: ${JSON.stringify(msg.params?.body)}`);
      replyHostRequest(msg.id, true);
    } else {
      console.log(`[host] 未知 hostRequest ${m}，回错误`);
      replyHostRequest(msg.id, { error: `unknown method ${m}` });
    }
    return;
  }
  // 普通响应
  if (msg.id !== undefined && msg.id !== null) {
    const p = pending.get(msg.id);
    if (p) {
      pending.delete(msg.id);
      if (msg.error) p.reject(new Error(`RPC 错误 [${msg.error.code}] ${msg.error.message}`));
      else p.resolve(msg.result ?? {});
    } else {
      console.log(`[host] 收到无匹配响应 id=${msg.id} (method 缺失)`);
    }
  }
}

client.on('connect', async () => {
  console.log('[host] 已连接 UDS');
  try {
    // 1. tools.list
    const list = await send('tools.list');
    console.log('[host] tools.list OK:', JSON.stringify(list).slice(0, 400));
    const tools = list.tools || [];
    const name = tools[0]?.name;
    if (!name) throw new Error('tools.list 返回空');
    // 2. tool.call（execute 内部会调 client.* API → hostRequest 往返）
    const call = await send('tool.call', { name, args: {} });
    console.log(`[host] tool.call "${name}" 返回:`, JSON.stringify(call).slice(0, 2000));
    if (call.result?.status !== 'success') {
      throw new Error(`tool.call 失败: ${JSON.stringify(call.result)}`);
    }
    // 3. plugins.list
    const pl = await send('plugins.list');
    console.log('[host] plugins.list OK:', JSON.stringify(pl).slice(0, 300));
    console.log(`[host] 全部通过 ✓ hostRequest 往返 ${hostReplies} 次`);
    client.end();
    process.exit(0);
  } catch (e) {
    console.error(`[host] ✗ 失败: ${e.message}`);
    client.end();
    process.exit(1);
  }
});

client.on('data', (chunk) => {
  buffer += chunk;
  let idx;
  while ((idx = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, idx).trim();
    buffer = buffer.slice(idx + 1);
    if (!line) continue;
    try {
      handleLine(line);
    } catch (e) {
      console.error(`[host] 解析失败: ${e.message} line=${line.slice(0, 200)}`);
    }
  }
});

client.on('error', (e) => {
  console.error(`[host] 连接错误: ${e.message}`);
  process.exit(1);
});

setTimeout(() => {
  console.error('[host] 全局看门狗超时（runner 未完成交换）');
  process.exit(1);
}, WATCHDOG_MS * 4);