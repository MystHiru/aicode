/**
 * @opencode-ai/plugin 兼容 shim：从运行时注入的 globalThis.__aicode_sdk 转发 API。
 * 由 runner.mjs 在加载插件前注入，插件 import 本包即可获得 tool()/tool.schema/$。
 */
const sdk = globalThis.__aicode_sdk;
if (!sdk) {
  throw new Error('@opencode-ai/plugin shim 未被初始化：请通过 AiCode 插件运行时加载本插件');
}

export const tool = sdk.tool;
export const $ = sdk.$;
export const shell = sdk.$;