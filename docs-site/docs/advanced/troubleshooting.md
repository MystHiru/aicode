# 常见问题

收录界面上没有直接入口、容易卡住的问题。

## 该下载哪个安装包

真机选 `armsolo`（arm64），模拟器或 Chromebook 选 `x86solo`（x86_64），不确定就用 `universal`（双架构，体积较大）。

架构不匹配会导致内置容器无法运行。

## 如何让 AI 访问手机里的文件

AI 跑在内置的 Linux 容器里，默认只能看到工作区，看不到手机的公共存储。要让它访问，需要把宿主目录挂载进容器：

1. 先确认存储权限已授权。首次使用时会弹权限请求；如果之前拒绝过，去系统设置 → 应用 → AiCode → 权限 → 存储 里开启。
2. 打开「设置 → 容器与镜像」，编辑当前正在用的容器。
3. 在「挂载」里加一条：**本地目录**填宿主路径（例如 `/sdcard/Download`），**容器目录**填容器内路径（例如 `/mnt/Download`）。
4. 保存后重启终端会话，AI 就能通过容器内路径访问了。

::: warning 按需挂载
只挂你需要的子目录，不要图省事直接挂整个 `/sdcard`——那等于把手机里所有文件都暴露给 AI，有隐私泄露风险。
:::

**例子：让 AI 看 App 自己的日志。** 日志在 `/storage/emulated/0/Android/data/com.aicode/files/logs/`。把本地目录 `/storage/emulated/0/Android/data/com.aicode/` 挂到容器 `/mnt/aicode`，AI 就能读 `/mnt/aicode/files/logs/` 下的日志。

注意 `Android/data/` 下只能访问 AiCode 自己的目录（`com.aicode`），其他应用的目录受分区存储限制访问不了。

如果只是想处理某一个文件，更简单的办法是把它复制进工作区（`projects/`），AI 默认就能访问。

## 如何访问 App 的私有目录

项目文件和 AI 配置都放在应用私有目录里，普通文件管理器看不到。有几种办法：

**用 MT 管理器挂载**（以 MT 为例）：

1. 打开 MT 管理器，点左上角菜单（三横线）。
2. 侧边栏右上角的三个点 → 「添加本地存储」。
3. 系统文件选择器打开后，点左上角三个点，选 `aicode`。
4. 点底部「使用此文件夹」完成挂载。

挂载后能看到两个目录：`projects/` 是工作区项目，`aicode/` 是 AI 配置（对应容器内的 `~/.aicode`，里面有 `skills/`、`docs/`、`mcp.json` 等）。

**用内置终端**：切到终端页，执行 `cd ~/.aicode` 直接看。

**直接让 AI 做**：AI 本来就跑在容器里，你可以让它读或改 `~/.aicode` 下的文件。

**Root 设备**：直接访问 `/data/data/com.aicode/files/`，其中 `projects/` 是工作区、`aicode/` 是配置、`rootfs/` 是容器系统。

## 支持哪些模型服务

支持 OpenAI、Anthropic、Gemini 三种协议，以及绝大多数 OpenAI 兼容的第三方中转服务。配置方法见[AI 提供商与模型](/guide/providers)。
