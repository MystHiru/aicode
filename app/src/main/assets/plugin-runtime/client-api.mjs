/**
 * AiCode 插件 SDK Client 实现：构造插件可见的 client 对象，形状对齐 @opencode-ai/sdk（fields 风格 { data } 包装）。
 *
 * 用法：
 *   const client = createClient('plugin-name', hostRequest);
 *   await client.app.log({ body: { level: 'info', message: 'hi' } });
 *   const sessions = await client.session.list();
 */
import fs from 'fs';
import path from 'path';

/**
 * 构造插件可见的 SDK client。
 * @param {string} pluginName - 发起请求的插件名（随 hostRequest 传给宿主用于日志区分）
 * @param {(method: string, params: object) => Promise<any>} hostRequest - 向宿主发请求
 * @param {string} workspaceDir - 工作区绝对路径（client.files 限定访问范围）
 * @returns {object} SDK client
 */
export function createClient(pluginName, hostRequest, workspaceDir) {
  const c = {};
  const host = (method, params = {}) => hostRequest(method, params, pluginName);

  // app：结构化日志 / 列出可用 agent（对齐 opencode GET /agent，只读列表）
  c.app = {
    log: async ({ body } = {}) => {
      await host('client.app.log', { body: body || {} });
      return { data: true };
    },
    agents: async () => {
      const r = await host('client.app.agents.list', {});
      return { data: r };
    },
  };

  // global：健康检查（本地实现）
  c.global = {
    health: async () => ({ data: { healthy: true, version: 'aicode' } }),
  };

  // project：本地实现（project 参数已有同样信息），形状对齐 opencode SDK Project
  c.project = {
    get: async () => {
      const dir = workspaceDir;
      const hasGit = fs.existsSync(path.join(dir || '', '.git'));
      return {
        data: {
          id: dir ? projectHash(dir) : 'global',
          worktree: dir || '',
          vcsDir: hasGit ? path.join(dir, '.git') : undefined,
          vcs: hasGit ? 'git' : undefined,
          time: { created: 0 },
        },
      };
    },
  };

  // session：会话信息与消息（只读 + 子代理相关写操作，签名对齐 opencode SDK）
  c.session = {
    get: async ({ path } = {}) => ({
      data: await host('client.session.get', { id: path?.id }),
    }),
    list: async () => ({
      data: await host('client.session.list', {}),
    }),
    messages: async ({ path } = {}) => ({
      data: await host('client.session.messages', { id: path?.id }),
    }),
    children: async ({ path } = {}) => ({
      data: await host('client.session.children', { id: path?.id }),
    }),
    create: async ({ body } = {}) => ({
      data: await host('client.session.create', { body: body || {} }),
    }),
    prompt: async ({ path, body } = {}) => {
      await host('client.session.prompt', { id: path?.id, body: body || {} });
      return { data: undefined };
    },
    promptAsync: async ({ path, body } = {}) => {
      await host('client.session.prompt', { id: path?.id, body: body || {} });
      return { data: undefined };
    },
    status: async () => ({
      data: await host('client.session.status', {}),
    }),
    delete: async ({ path } = {}) => ({
      data: await host('client.session.delete', { id: path?.id }),
    }),
    update: async ({ path, body } = {}) => ({
      data: await host('client.session.update', { id: path?.id, body: body || {} }),
    }),
  };

  // files：工作区文件读写。runner 与工作区同容器，Node fs 即最终事实，本地实现
  // （Sandbox 与宿主工具权限并不保护不可信插件读写容器文件系统，这里限定在工作区内防越界）。
  // 返回形状对齐 opencode：FileContent（read）/ FileNode[]（list）。
  c.files = {
    read: async ({ path: p } = {}) => {
      const full = safeWorkspacePath(workspaceDir, p?.filePath);
      if (full === null) throw new Error(`[AiCode] client.files.read 路径超出工作区: ${p?.filePath}`);
      const stat = await fs.promises.stat(full);
      if (stat.isDirectory()) {
        return { data: { type: 'text', content: '' } };
      }
      const buf = await fs.promises.readFile(full);
      // 二进制检测：内容含 NUL 字节视为 binary（对齐 opencode FileContent.type）
      const isBinary = buf.includes(0);
      return {
        data: isBinary
          ? { type: 'binary', content: buf.toString('base64'), encoding: 'base64' }
          : { type: 'text', content: buf.toString('utf-8') },
      };
    },
    write: async ({ path: p, body } = {}) => {
      const full = safeWorkspacePath(workspaceDir, p?.filePath);
      if (full === null) throw new Error(`[AiCode] client.files.write 路径超出工作区: ${p?.filePath}`);
      await fs.promises.mkdir(path.dirname(full), { recursive: true });
      await fs.promises.writeFile(full, body?.data ?? '', 'utf-8');
      return { data: true };
    },
    list: async ({ path: p } = {}) => {
      const full = safeWorkspacePath(workspaceDir, p?.dirPath || '.');
      if (full === null) throw new Error(`[AiCode] client.files.list 路径超出工作区: ${p?.dirPath}`);
      const entries = await fs.promises.readdir(full, { withFileTypes: true });
      const rel = p?.dirPath || '.';
      return {
        data: entries.map((e) => ({
          name: e.name,
          path: path.join(rel, e.name),
          absolute: path.join(full, e.name),
          type: e.isDirectory() ? 'directory' : 'file',
          ignored: false,
        })),
      };
    },
    edit: unsupported('files.edit', '请用 files.read + files.write 组合完成替换'),
    search: unsupported('files.search', '请使用内置 SearchCode 工具'),
  };

  // config：读宿主配置（只读）
  c.config = {
    get: async () => ({ data: await host('client.config.get', {}) }),
    set: unsupported('config.set', '插件不能改写宿主配置'),
  };

  // auth：凭据管理（读写宿主 auth.json，对齐 opencode SDK：set/list/get）。
  // 安全边界：仅插件自己的 provider 凭据可写（由宿主侧校验 id 归属），不暴露 AiCode 用户 API Key。
  c.auth = {
    set: async ({ path, body } = {}) => {
      await host('client.auth.set', { id: path?.id, body: body || null });
      return { data: true };
    },
    list: async () => ({
      data: await host('client.auth.list', {}),
    }),
    get: async ({ path } = {}) => ({
      data: await host('client.auth.get', { id: path?.id }),
    }),
    logout: async ({ path } = {}) => {
      await host('client.auth.set', { id: path?.id, body: null });
      return { data: true };
    },
  };

  // tui：Android 无 TUI，仅场边通知（toast/消息）有价值
  c.tui = {
    showToast: async ({ body } = {}) => {
      await host('client.tui.toast', { body: body || {} });
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

/** 明确不支持的 client.* API：reject 带原因，插件可用 try/catch 降级，而非 TypeError 崩溃。 */
function unsupported(name, reason) {
  return () => Promise.reject(new Error(`[AiCode] client.${name} 不支持：${reason}`));
}

/** 工作区路径的稳定短 hash，作为 project.id（对齐 opencode 的 git hash 语义）。 */
function projectHash(dir) {
  let h = 0;
  for (let i = 0; i < dir.length; i++) h = ((h << 5) - h + dir.charCodeAt(i)) | 0;
  return Math.abs(h).toString(36);
}

/** 把工作区相对/绝对路径解析到工作区内；越界或不存在返回 null。 */
function safeWorkspacePath(workspaceDir, p) {
  if (!p) return workspaceDir;
  const resolved = path.resolve(workspaceDir, p);
  const rel = path.relative(workspaceDir, resolved);
  if (rel.startsWith('..') || path.isAbsolute(rel)) return null;
  return resolved;
}
