# 自定义容器镜像

AiCode 默认用内置的 Alpine 容器。如果你需要别的发行版（Debian、Ubuntu 等），或者想要预装特定工具链的环境，可以添加自定义镜像。

::: tip 版本说明
1.9.0 起提供统一的初始化脚本，1.10.0 起内置镜像下载功能。手动导入方式一直可用。
:::

## 方式一：内置镜像下载（推荐）

1. 打开「设置 → 容器与镜像」，点右上角的下载图标。
2. 页面会列出可下载的镜像（Alpine、Ubuntu、Debian 等），点一行看详情并下载。
3. 右上角可以切换下载源（官方源 / 华为云 / 阿里云 / 腾讯云），国内网络建议选国内源。
4. 下载完成后点「导入」，镜像就加入容器列表了，选中即可使用。

已下载的镜像可以重复导入；左滑可以删除下载好的镜像文件。

## 方式二：手动导入镜像文件

需要特定版本或特定发行版时，可以自己准备 rootfs 镜像导入。

**镜像要求**：

- 压缩格式：`.tar.gz`、`.tgz`、`.tar.xz`、`.txz`
- 内容必须是完整的 Linux rootfs（文件系统根目录，里面有 `/bin/sh` 或其它 shell）
- 架构要和设备匹配：ARM64 设备用 aarch64 镜像，x86 设备用 x86_64 镜像

**从哪里拿镜像**：

- Alpine（体积小、启动快）：`https://alpine.linuxhub.cn/alpine/edge/releases/`，在 aarch64 目录下选 `alpine-minirootfs-*-aarch64.tar.gz`
- Ubuntu：`https://cdimage.ubuntu.com/ubuntu-base/releases/`，选 `ubuntu-base-*-base-arm64.tar.gz`
- Debian：可以从 Docker Hub 的官方镜像（如 `arm64v8/debian`）导出

有 Docker 环境的话，从任意镜像导出 rootfs：

```bash
docker pull debian:bookworm
docker create --name temp-rootfs debian:bookworm
docker export temp-rootfs | gzip > debian-rootfs.tar.gz
docker rm temp-rootfs
```

也可以在已有的 Linux 环境里用 `debootstrap`（Debian / Ubuntu）或 `alpine-make-rootfs`（Alpine）自己做。

**导入步骤**：

1. 打开「设置 → 容器与镜像」，点右上角 +，选「本地镜像」。
2. 填配置：
   - **名称**：这个镜像配置的别名。
   - **shell 路径**：容器内的 shell，如 `/bin/sh` 或 `/bin/bash`。
   - **镜像文件**：选择准备好的 rootfs 文件。保存时会复制一份到 App 私有目录，之后重置容器都从这份副本重新解压，不依赖原始文件；删除镜像时副本一并清理。
   - **挂载**（可选）：把宿主目录挂进容器，逐项填本地目录与容器目录，如 `/sdcard` 对应 `/mnt/sdcard`。
   - **proot 参数**（可选）：逐项添加，原样追加到 PRoot 启动参数。
   - **环境变量**（可选）：注入容器内进程的键值对。
3. 保存后在列表里选中这个配置，就切换成了自定义容器。

首次使用自定义镜像时 App 会解压 rootfs 到私有目录，耗时取决于镜像大小。

## 遇到问题

### 装软件报错

容器内硬链接不可用导致的。编辑镜像 → proot 参数 → 添加下面这个参数 → 保存，重进终端：

```
--link2symlink
```

用内置下载功能导入的镜像已经默认带了这个参数。

### 方向键显示成 ^[[A

当前 shell 是 `sh`，没有行编辑能力。编辑容器，把 Shell 改成：

```
/bin/bash
```

bash 自带方向键、历史记录和补全。

## 其它注意事项

- 自定义镜像默认不配置国内软件源，可以在首次进终端的初始化菜单里自动换源。
- 删除自定义镜像会一并清理它解压出来的 rootfs，释放存储空间。
- 内置 Alpine 镜像即使删掉也能一键恢复，随时可以切回。
