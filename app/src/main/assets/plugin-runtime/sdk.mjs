/**
 * AiCode 插件 SDK：对齐 @opencode-ai/plugin 的 tool() / tool.schema / $ API。
 *
 * 差异说明：
 * - $ 为简化实现（Node child_process），不支持 Bun Shell 的内嵌 JS 表达式、流式回调等高级语法。
 * - tool.schema 产出 JSON Schema（非 Zod 对象），插件侧按对象使用即可。
 */
import { exec } from 'child_process';

/** 构造一个自定义工具定义。def.args 为 { 参数名: schema } 映射。 */
export function tool(def) {
  if (!def || typeof def !== 'object') throw new Error('tool() 需要定义对象');
  if (typeof def.description !== 'string') throw new Error('tool() 缺少 description（字符串）');
  if (typeof def.execute !== 'function') throw new Error('tool() 缺少 execute 函数');
  const parameters = def.args ? objectSchema(def.args) : { type: 'object', properties: {}, required: [] };
  return { description: def.description, parameters, execute: def.execute };
}

function objectSchema(args) {
  const properties = {};
  const required = [];
  for (const [key, schema] of Object.entries(args)) {
    const s = schema && typeof schema === 'object' ? { ...schema } : { type: 'string' };
    delete s.required; // required 由外层统一收集
    properties[key] = s;
    if (!schema || schema.required !== false) required.push(key);
  }
  return { type: 'object', properties, required };
}

tool.schema = {
  string: (params) => ({ type: 'string', ...(params || {}) }),
  number: (params) => ({ type: 'number', ...(params || {}) }),
  integer: (params) => ({ type: 'integer', ...(params || {}) }),
  boolean: (params) => ({ type: 'boolean', ...(params || {}) }),
  enum: (values, params) => ({ type: 'string', enum: values, ...(params || {}) }),
  object: (properties, params) => ({ type: 'object', properties, ...(params || {}) }),
  array: (items, params) => ({ type: 'array', items, ...(params || {}) }),
};

/**
 * $：简化 Bun Shell。支持模板字符串命令、管道（交由 shell 解析）、
 * .quiet()/.nothrow()/.cwd()/.env() 链式调用。不支持内嵌 JS 表达式。
 */
export function shell(strings, ...values) {
  const cmd = strings.reduce(
    (acc, s, i) => acc + s + (i < values.length ? String(values[i]) : ''),
    '',
  ).trim();
  const state = { quiet: false, nothrow: false, cwd: undefined, env: undefined };
  const run = () => new Promise((resolve, reject) => {
    exec(cmd, {
      cwd: state.cwd,
      env: { ...process.env, ...(state.env || {}) },
      maxBuffer: 64 * 1024 * 1024,
    }, (err, stdout, stderr) => {
      if (err && !state.nothrow) return reject(err);
      resolve({ stdout, stderr, exitCode: err ? (err.code ?? 1) : 0 });
    });
  });
  const p = {
    then(onFulfilled, onRejected) {
      return run().then(onFulfilled, onRejected);
    },
    quiet() { state.quiet = true; return this; },
    nothrow() { state.nothrow = true; return this; },
    cwd(dir) { state.cwd = dir; return this; },
    env(env) { state.env = env; return this; },
  };
  return p;
}

export { shell as $ };