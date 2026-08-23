/**
 * AiCode 插件加载器：从 npm + 本地目录加载插件，规范化插件模块导出。
 *
 * 加载顺序：本地全局 → 本地项目 → npm 全局 → npm 项目（同名本地优先，npm 包自动跳过）。
 * 加载结果包含成功列表与失败列表，失败信息供设置页展示。
 */
import fs from 'fs';
import path from 'path';
import { pathToFileURL } from 'url';

/**
 * 加载所有插件。
 * @param {Object} opts
 * @param {string} opts.homeDir - 全局配置目录（~/.aicode）
 * @param {string|null} opts.workspaceDir - 当前工作区绝对路径（无则跳过项目级）
 * @param {(name: string) => object} opts.createClient - 为插件创建 SDK client
 * @param {object} opts.sdk - sdk.mjs 导出（含 $）
 * @returns {Promise<{plugins: Array, failed: Array}>}
 */
export async function loadPlugins(opts) {
  const { homeDir, workspaceDir, createClient, sdk } = opts;
  const globalCfg = readPluginsJson(path.join(homeDir, 'plugins.json'));
  const projectCfg = workspaceDir
    ? readPluginsJson(path.join(workspaceDir, '.aicode', 'plugins.json'))
    : null;

  // 先加载本地插件（决定同名 npm 包跳过名单）
  const localGlobal = await loadLocalPlugins(path.join(homeDir, 'plugins'), globalCfg, 'global-local', createClient, sdk);
  const localProject = workspaceDir
    ? await loadLocalPlugins(path.join(workspaceDir, '.aicode', 'plugins'), projectCfg, 'project-local', createClient, sdk)
    : { loaded: [], failed: [] };
  const localNames = new Set([...localGlobal.loaded, ...localProject.loaded].map((p) => p.name));

  // npm 全局 → npm 项目（与本地同名者跳过）
  const npmGlobal = await loadNpmPlugins(globalCfg, 'global-npm', localNames, createClient, sdk);
  const npmProject = projectCfg
    ? await loadNpmPlugins(projectCfg, 'project-npm', localNames, createClient, sdk)
    : { loaded: [], failed: [], missing: [] };

  // 禁用的插件：不加载（无工具/hook），但返回条目供设置页展示与重新启用
  const disabled = [
    ...collectDisabledNpm(globalCfg, 'global-npm'),
    ...collectDisabledNpm(projectCfg, 'project-npm'),
    ...collectDisabledLocal(path.join(homeDir, 'plugins'), globalCfg, 'global-local'),
    ...(workspaceDir
      ? collectDisabledLocal(path.join(workspaceDir, '.aicode', 'plugins'), projectCfg, 'project-local')
      : []),
  ];

  // 配置文件解析失败（JSON 语法错误等）：供设置页展示警告
  const invalidConfigs = [];
  if (globalCfg.invalid) invalidConfigs.push({ scope: 'global', error: globalCfg.invalid });
  if (projectCfg?.invalid) invalidConfigs.push({ scope: 'project', error: projectCfg.invalid });

  return {
    plugins: [...npmGlobal.loaded, ...npmProject.loaded, ...localGlobal.loaded, ...localProject.loaded],
    failed: [...npmGlobal.failed, ...npmProject.failed, ...localGlobal.failed, ...localProject.failed],
    disabled,
    missing: [...npmGlobal.missing, ...npmProject.missing],
    invalidConfigs,
  };
}

/** npm 来源中声明且被禁用的插件条目。 */
function collectDisabledNpm(cfg, source) {
  if (!cfg) return [];
  return cfg.plugins
    .filter((pkg) => cfg.disabled.includes(pkg))
    .map((pkg) => ({ name: pkg, source, disabled: true }));
}

/** 本地目录中存在且被禁用的插件条目（文件名/目录名带扩展名，与 loadLocalPlugins 的 name 一致）。 */
function collectDisabledLocal(dir, cfg, source) {
  if (!cfg || !fs.existsSync(dir)) return [];
  const names = new Set(
    fs.readdirSync(dir, { withFileTypes: true })
      .filter((e) => (e.isFile() && /\.(mjs|js|cjs)$/.test(e.name)) || e.isDirectory())
      .map((e) => e.name)
  );
  return cfg.disabled.filter((n) => names.has(n)).map((n) => ({ name: n, source, disabled: true }));
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
    // 文件不存在视为无配置（正常）；存在但解析失败才是配置无效
    if (e?.code === 'ENOENT') return { plugins: [], disabled: [] };
    return { plugins: [], disabled: [], invalid: e?.message || String(e) };
  }
}

async function loadNpmPlugins(cfg, source, skipNames, createClient, sdk) {
  const loaded = [];
  const failed = [];
  const missing = [];
  for (const pkg of cfg.plugins) {
    if (cfg.disabled.includes(pkg)) continue;
    if (skipNames.has(pkg)) continue;
    // 依赖未安装：跳过 import（会失败且无意义），标记未安装供设置页展示
    if (!isNpmPluginInstalled(pkg)) {
      missing.push({ name: pkg, source });
      continue;
    }
    try {
      const mod = await import(pkg);
      const initialized = await initPluginModule(mod, pkg, source, createClient, sdk);
      if (initialized) {
        initialized.version = readNpmPluginVersion(pkg);
        loaded.push(initialized);
      } else {
        failed.push({ name: pkg, source, error: '插件模块没有导出插件函数' });
      }
    } catch (e) {
      console.error(`[${pkg}] npm 插件加载失败: ${e?.message || e}`);
      failed.push({ name: pkg, source, error: e?.message || String(e) });
    }
  }
  return { loaded, failed, missing };
}

/** npm 插件是否已安装（全局或项目 node_modules 下存在 package.json）。 */
function isNpmPluginInstalled(pkg) {
  const candidates = [
    path.join(process.env.AICODE_HOME || '/root/.aicode', 'node_modules', pkg, 'package.json'),
    process.env.AICODE_WORKSPACE
      ? path.join(process.env.AICODE_WORKSPACE, '.aicode', 'node_modules', pkg, 'package.json')
      : null,
  ];
  return candidates.some((f) => f && fs.existsSync(f));
}

function readNpmPluginVersion(pkg) {
  const candidates = [
    path.join(process.env.AICODE_HOME || '/root/.aicode', 'node_modules', pkg, 'package.json'),
    process.env.AICODE_WORKSPACE
      ? path.join(process.env.AICODE_WORKSPACE, '.aicode', 'node_modules', pkg, 'package.json')
      : null,
  ];
  for (const file of candidates) {
    if (!file) continue;
    try {
      const pkgJson = JSON.parse(fs.readFileSync(file, 'utf-8'));
      if (pkgJson.version) return String(pkgJson.version);
    } catch { /* 忽略 */ }
  }
  return null;
}

async function loadLocalPlugins(dir, cfg, source, createClient, sdk) {
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
        mod = await import(pathToFileURL(full).href);
      } else if (entry.isDirectory()) {
        const indexFile = resolveDirEntry(full);
        if (indexFile) mod = await import(pathToFileURL(indexFile).href);
      } else {
        continue;
      }
      const initialized = await initPluginModule(mod, name, source, createClient, sdk);
      if (initialized) {
        initialized.version = entry.isDirectory() ? readLocalPluginVersion(full) : null;
        loaded.push(initialized);
      } else {
        failed.push({ name, source, error: '插件模块没有导出插件函数' });
      }
    } catch (e) {
      console.error(`[${name}] 本地插件加载失败: ${e?.message || e}`);
      failed.push({ name, source, error: e?.message || String(e) });
    }
  }
  return { loaded, failed };
}

function readLocalPluginVersion(dir) {
  try {
    const pkgJson = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf-8'));
    return pkgJson.version ? String(pkgJson.version) : null;
  } catch {
    return null;
  }
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
    } catch { /* 忽略 */ }
  }
  for (const idx of ['index.mjs', 'index.js', 'index.cjs']) {
    const f = path.join(dir, idx);
    if (fs.existsSync(f)) return f;
  }
  return null;
}

/**
 * 把插件模块导出规范化为插件对象 { name, source, hooks, dispose }。
 * 支持同步返回与 async 初始化。
 */
async function initPluginModule(mod, name, source, createClient, sdk) {
  const candidates = [];
  const modObj = mod?.default ?? mod;
  if (typeof modObj === 'function') {
    candidates.push(modObj);
  } else if (modObj && typeof modObj === 'object') {
    if (typeof modObj.server === 'function') candidates.push(modObj.server);
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
  const workspaceDir = process.env.AICODE_WORKSPACE || '/root/workspace';
  const projectInfo = {
    id: projectHash(workspaceDir),
    name: path.basename(workspaceDir || ''),
    directory: workspaceDir,
  };
  for (const fn of candidates) {
    const result = fn({
      project: projectInfo,
      client: createClient(name),
      $: sdk.$,
      directory: workspaceDir,
      worktree: workspaceDir,
    });
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

function projectHash(dir) {
  let h = 0;
  for (let i = 0; i < dir.length; i++) h = ((h << 5) - h + dir.charCodeAt(i)) | 0;
  return Math.abs(h).toString(36);
}

/**
 * 在指定 node_modules 目录注入 @opencode-ai/plugin shim 符号链接。
 * 插件 `import '@opencode-ai/plugin'` 时 Node 从插件文件位置向上解析 node_modules，
 * 必须在全局/项目 node_modules 下放 shim 才能被解析。
 */
export function ensureShim(nodeModulesDir, shimDir) {
  try {
    if (!fs.existsSync(nodeModulesDir)) fs.mkdirSync(nodeModulesDir, { recursive: true });
    const scopedDir = path.join(nodeModulesDir, '@opencode-ai');
    if (!fs.existsSync(scopedDir)) fs.mkdirSync(scopedDir, { recursive: true });
    const pkgLink = path.join(scopedDir, 'plugin');
    if (!fs.existsSync(pkgLink)) {
      fs.symlinkSync(shimDir, pkgLink, 'dir');
    }
  } catch (e) {
    console.error(`无法注入 @opencode-ai/plugin shim: ${e?.message || e}`);
  }
}

/**
 * 项目 .aicode/plugins 下是否存在可加载的本地插件。
 */
export function hasLocalPluginFiles(workspace) {
  const dir = path.join(workspace, '.aicode', 'plugins');
  if (!fs.existsSync(dir)) return false;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isFile() && /\.(mjs|js|cjs)$/.test(entry.name)) return true;
    if (entry.isDirectory()) return true;
  }
  return false;
}
