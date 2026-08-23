# AiCode 插件 auth hook 完整实现 · 交接文档（2026-08-21）

> 目标：让 `opencode-antigravity-auth` 这类 OAuth 订阅插件在 AiCode 可用。
> 状态：**核心实现完成 + 编译通过 + runner smoke 19/19 通过 + APK 已发用户验证**；「虚拟 provider 热插拔」**已定方案未实施**。

## 一、已实现能力（对照 opencode 官方 `packages/plugin/src/index.ts` AuthHook）

| opencode 能力 | AiCode 现状 |
|---|---|
| `auth.loader`（返回 headers/apiKey/**自定义 fetch**） | ✅ 已实现（含 fetch 代理） |
| `auth.methods`（OAuth code/auto + API Key 登录） | ✅ 已实现（设置页插件详情 → 插件认证弹窗） |
| `client.auth.set/list/get/logout`（凭据落盘 auth.json） | ✅ 已实现 |
| `getAuth()`（loader 第一参，实时读凭据） | ✅ 已实现（UDS 反向请求） |
| `provider.models` | ✅ 已实现（**顺带修了死钩子**，此前从未派发到插件） |

## 二、关键文件

### JS（`app/src/main/assets/plugin-runtime/`）
- **`runner.mjs`**（1080+ 行）：核心运行时
  - `registerPlugin`（~673 行）：**展开注册** `auth`→`auth.loader`/`auth.methods`、`provider`→`provider.models`（修复死钩子：旧版把整个对象注册为 `auth`/`provider` 钩子名，分发用 `auth.loader`/`provider.models` 永远匹配不到）
  - `client.auth.*`（~617 行）：set/list/get/logout → `hostRequest('client.auth.set'|'client.auth.list'|'client.auth.get')`
  - `hook.dispatch` 的 RETURN_HOOKS 分支（~222 行）：`auth.loader` 特殊分发——注入 `getAuth()`（内部 `hostRequest('client.auth.get', {id})`）与 provider 配置（`input.providerConfig`）；loader 返回含 `fetch` 函数时注册 `authFetchProxies[provider]`，序列化时剔除 fetch 改标 `hasFetch: true`
  - `auth.methods.list` / `auth.authorize` / `auth.callback` / `auth.proxy` RPC（~297 行起）：
    - `auth.authorize`：**不传 inputs 时调 `authorize(undefined)`**（不能传 `{}`，antigravity 用 `if(inputs)` 区分 CLI 交互菜单 vs 宿主驱动流程）；api 型 authorize 直接返回 success 时立即 `client.auth.set` 落盘并回 `completed: true`；**25s 超时保护**（AUTHORIZE_TIMEOUT 明确报错）
    - `auth.callback`：执行挂起 callback，成功后自动 `client.auth.set` 存凭据（oauth→{type:'oauth',refresh,access,expires} / api→{type:'api',key}）
    - `auth.proxy`：`await startAuthProxy()` 后返回 `{providers: {pluginProviderId: {baseUrl: "http://127.0.0.1:<port>"}}, port}`
  - `startAuthProxy`（~755 行）：**返回 Promise**，等 `server.listen` 完成才 resolve（否则返回端口 0 导致 ECONNREFUSED）；请求带 `X-Aicode-Real-Url`（真实目标 URL）+ `X-Aicode-Provider` 头；转发时剔除 `X-Aicode-*` 私有头、合并 loader 返回的自定义 headers（如 quota 头）；响应流 pipe（支持 SSE/chunked），Web ReadableStream 用 `Readable.fromWeb` 或 `webStreamToNode` 回退
- **`plugin-shim/index.d.ts`**：补全 `Auth`/`AuthPrompt`/`AuthSuccessResult`/`AuthOAuthResult`/`AuthMethod` 类型、`AuthClient` 签名（set/list/get/logout）、`Hooks.auth.methods`

### Kotlin 新增
- `feature/agent/domain/plugin/PluginAuthStore.kt`：宿主 `filesDir/aicode/auth.json`（容器内 `/root/.aicode/auth.json`）读写；`{providerId: {type,refresh,access,expires,key,metadata}}`；Mutex + 临时文件原子写（rename 失败回退直写）；缓存 Map
- `feature/agent/domain/plugin/PluginAuthModels.kt`：`PluginAuth`（hasCredentials/accessTokenExpired）、`PluginAuthMethod`、`PluginAuthorizeResult`（含 requiresKey/completed）、`PluginAuthCallbackResult`
- `feature/settings/presentation/component/PluginAuthDialog.kt`：登录弹窗（OAuth url 点击开浏览器 + code 输入/auto 完成 + API Key 输入；busy 状态；错误/成功提示）

### Kotlin 修改
- `PluginHostApiHandler.kt`：+ `client.auth.set/list/get`（set 的 body 为 null 删除；get 返回凭据给 loader）
- `PluginClient.kt`：+ `authMethodsList`/`authAuthorize`/`authCallback`/`authProxyInfo` RPC；`PluginDescriptor` + `auth: PluginAuthDeclaration?`（provider + methods label/type）；新增 `ProviderAuthMethods` 等数据类
- `PluginManager.kt`：实现 gateway 新接口（转发 client，无运行时返回空/错误）
- `PluginHookGateway.kt`：接口 + `authMethods`/`authAuthorize`/`authCallback`/`authProxy`/`hasPluginAuth(providerId)`
- `PluginRequestHooks.kt`：`applyChatHeaders` 加可选 `providerId/baseUrl/providerType`（命中时合并 auth.loader 认证头）；`applyAuthLoader` 重构返回 `AuthLoaderResult(headers/baseURL/hasFetch)`，入参带 `providerConfig`（{id,baseURL,type}），apiKey 按类型转认证头（OPENAI→Bearer / ANTHROPIC→x-api-key / GEMINI→x-goog-api-key）；新增 `resolveProviderProxy(providerId)`
- 三个 Adapter（OpenAI/Anthropic/Gemini，各 4/2/2 处调用点）：`realUrl` → `pluginProxy` 命中时代理地址 + `X-Aicode-Real-Url`/`X-Aicode-Provider` 头
- `StatefulAgentWorkflow.kt`：`getEffectiveProvider`/`compactSession` 空 key 校验放宽（`hasPluginAuth(config.id)`）；`resolveProviderConfig` 两处过滤同样放宽
- `SettingsViewModel.kt`：+ `refreshPluginAuth`/`pluginAuthAuthorize`/`pluginAuthSubmit`/`pluginAuthSaveApiKey`/`pluginAuthLogout` + `pluginAuthMethods`/`pluginAuthStatus`/`pluginAuthBusy` 状态流；注入 `PluginAuthStore`
- `SettingsScreen.kt`：插件详情 onOpenDetail 时 refreshPluginAuth；PluginDetailDialog 传认证参数
- `PluginDetailDialog.kt`：+ 认证卡片（provider + 登录状态，点击开 PluginAuthDialog）
- `strings.xml`/`values-en/strings.xml`：+ 22 条 `plugins_auth_*` 文案

### 文档
- `app/src/main/assets/docs/plugins.md`：auth.loader 语义、client.auth 支持、插件认证使用说明、不支持章节修正

## 三、UDS 协议（runner ↔ Kotlin，NDJSON JSON-RPC）

```
Kotlin → runner：auth.methods.list / auth.authorize {provider, methodIndex, inputs?} / auth.callback {provider, code?} / auth.proxy
runner → Kotlin（client.auth.* 反向）：client.auth.set {id, body|null} / client.auth.list / client.auth.get {id}
```

## 四、已验证的坑（全部实测确认）

1. **死钩子**：`auth`/`provider` 对象不能整对象注册，必须展开子钩子（auth.loader/provider.models）——旧实现这两个 hook 从未派发到插件
2. **inputs 空对象陷阱**：`authorize(inputs || {})` 会把 undefined 变 `{}`（truthy），antigravity 走 CLI 交互菜单等 stdin → 30s 超时（日志特征：`=== Antigravity OAuth (Account 1) ===` 后无下文）。**必须传 `authorize(inputs !== undefined ? inputs : undefined)`**
3. **代理端口异步**：`server.listen(0)` 是异步的，`auth.proxy` 必须 `await startAuthProxy()`（返回 Promise）后再读端口，否则返回 0 → ECONNREFUSED
4. **本地插件名带扩展名**：`loadLocalPlugins` 的 name 是 `test-auth.mjs`（非 `test-auth`），测试断言要 startsWith
5. **FeatherIcons 引用**：图标用 `FeatherIcons.ChevronRight`（object 属性），不能用包全限定名
6. **kotlinx add(it)**：`buildJsonArray { add(String) }` 编译错，需 `add(JsonPrimitive(it))`
7. **File import**：PluginAuthStore 需 `import java.io.File`

## 五、验证方式

- **runner smoke**：`/tmp/auth-smoke-test.mjs`（容器内 `node /tmp/auth-smoke-test.mjs`，需先有 `/tmp/antigravity/` 无关，独立）：模拟宿主 client 连 runner，19 项断言全过（methods 列表/authorize api+oauth/callback 落盘/loader+getAuth/fetch 代理转发含 SSE 流与自定义头/私有头剥除/plugins.list）
- **编译**：`./gradlew :app:assembleArmsoloDebug`（挂后台 notify，**输出不要 tail**——用户明确要求）
- **单测**：`./gradlew :app:testUniversalDebugUnitTest`（push 前跑；用户曾拒绝过一次，勿擅自跑）
- **真机**：用户已登录成功（antigravity OAuth），**对话调用尚未验证**

## 六、关键决策与现状

- **provider id 匹配**：插件认证按 `AIProviderConfig.id == 插件 auth.provider`（antigravity 为 **"google"**，见其 constants.js `ANTIGRAVITY_PROVIDER_ID`）。**但 AiCode 新建 provider 的 id 是时间戳自动生成（ProviderEditorScreen.kt:155），用户无法设为 "google"** → 登录成功但对话时 `resolveProviderProxy("时间戳")` 查不到代理、空 key 放行也失效
- **用户已拍板方案（未实施）**：**不改原有提供商，做「插件 provider 热插拔」**——插件加载时自动注册虚拟 provider（id=插件 auth.provider），随插件生命周期出现/消失；模型同样热插拔（插件 `provider.models` hook 或手动添加）
- 用户拒绝过：修改 ProviderEditorScreen 加自定义 id 输入框的方案（「没必要动原有提供商」）

## 七、待办（按序）

1. **虚拟 provider 热插拔**（用户已确认方案）：
   - `PluginHookGateway`/`PluginManager`：`fun pluginProviders(): List<AIProviderConfig>`——从 `currentPlugins().auth` 生成（id=provider、name=插件名、type=GEMINI、baseUrl 默认 antigravity 端点、models=空或插件 provider.models 合并）
   - antigravity 端点（constants.js）：`https://cloudcode-pa.googleapis.com`（Gemini CLI）；`isGenerativeLanguageRequest` 判断的 host 需确认（generativelanguage.googleapis.com 还是 cloudcode-pa）
   - providers 数据流合并：`SettingsViewModel.providers`（Room getAll + 插件 provider，Room 记录覆盖虚拟默认，用户保存后落 Room 成为普通 provider）
   - `StatefulAgentWorkflow.resolveProviderConfig`：`getProviderById` 查不到时查插件 provider（session 绑定 "google" 可解析）
   - 编辑入口：虚拟 provider 可打开 ProviderEditorScreen（保存即落 Room）
   - 主页/会话模型选择：基于 providers 流自动生效
2. 真机验证对话链路（用户已登录，选 google + gemini-2.5-pro 等模型对话，确认流式经代理转发）
3. 容器网络：`authorizeAntigravity` 生成 Google OAuth URL 是容器内网络请求，不继承宿主代理；DNS 污染环境下可能失败——需要时容器内配 HTTP(S)_PROXY 或确认 runner 进程 env 继承
4. push 前单测 + git 提交（用户验证通过后；Conventional Commits，`feat(agent): 插件 auth hook 完整支持`）
5. 交付后更新项目记忆 `plugin-system-implementation-status`（补 auth 部分）并清理 `/tmp/auth-smoke-test.mjs` 是否保留待定（建议留容器内供回归）

## 八、antigravity 插件信息（npm 1.6.0，已解包 /tmp/antigravity/）

- provider id：`google`；支持模型：gemini-2.5-pro/flash（-low/-medium/-high thinking tier）、gemini-3-pro、claude-opus-4-6/sonnet-4-6 等（transform/model-resolver.js）
- loader 返回 `{apiKey:"", fetch}`，fetch 内做账户轮换/限流/刷新/响应转换；`isGenerativeLanguageRequest` 非 Gemini 请求直接透传
- authorize 无 inputs 时（TUI 流程）：`startOAuthListener()`（本地回调 server）→ `authorizeAntigravity()` 生成 URL → 容器无 DISPLAY 时 `openBrowser` 失败返回 false（listener 保留）→ 返回 `{url, instructions, method:"auto", callback}`——**AiCode 弹窗点开浏览器授权后自动回调完成**
- 登录后凭据：`{type:"oauth", refresh(含 projectId 打包), access, expires}`，存 auth.json

## 九、给下一会话的冷启动提示

1. 先读本文件 + `memory(action=read, name="plugin-system-implementation-status")`
2. 用户正在真机验证 antigravity 登录；**下一动作是实施「虚拟 provider 热插拔」**（第七节第 1 条），或先等用户对话链路验证结果
3. 若用户报告对话请求 404/无法连接，先查：provider id 是否为 "google"（当前不可能）、代理是否命中（日志 `X-Aicode-Real-Url` 头、runner 的 `[auth-proxy]` 与 `[test-auth] fetch proxy` 日志）
4. 改动编译型代码后冒烟：`assembleArmsoloDebug` 挂后台 notify、**不 tail**；APK 路径 `app/build/outputs/apk/armsolo/debug/app-armsolo-debug.apk`，构建通过后直接 sendFile 给用户（用户验证通过才能 git 提交）
