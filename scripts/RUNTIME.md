# 手机私有 Linux 运行时

运行时由本项目独立组装，未复制 Buddy 的代码或资源。它只为 Claude Code 和手机 MCP 工具提供无界面的 Linux 用户空间；没有 Linux 桌面、终端入口或独立应用商店。

## 组成与来源

| 组件 | 固定版本 | 上游来源 |
|---|---|---|
| ARM64 Linux rootfs | Alpine 3.22.7 | [Alpine 官方下载](https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/) |
| PRoot 与两个 loader | 5.1.107.102 | [Termux 官方包](https://packages.termux.dev/apt/termux-main/)、[源码](https://github.com/termux/proot) |
| libtalloc | 2.5.0 | Termux 官方包、[上游源码](https://talloc.samba.org/) |
| libandroid-shmem | 0.7 | Termux 官方包、[源码](https://github.com/termux/libandroid-shmem) |
| Claude Code | 2.1.311 / linux-arm64-musl | [官方安装器](https://claude.ai/install.sh)、[官方固定版本清单](https://downloads.claude.ai/claude-code-releases/2.1.311/manifest.json) |
| Python 3 与运行库 | 见锁文件 | [Alpine 3.22 官方包索引](https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64/APKINDEX.tar.gz) |

固定 URL、版本和 SHA256 位于 [runtime-lock.json](runtime-lock.json)。初始清单从上述官方 HTTPS 源取得；PRoot 与 rootfs 校验值来自官方包索引，Claude 校验值来自官方 manifest，Alpine APK 的完整文件 SHA256 在首次下载后固定。后续构建逐文件校验锁定的 SHA256。运行库保留各自的上游许可，Claude Code 的使用遵循 Anthropic 条款；生成的原生二进制和包含 Claude 的安装资产仅用于本地构建，未纳入 Git。

## 准备资产

需要 Python 3.10+ 和 curl。Python 3.14 可使用标准库解压 Claude 官方 Zstandard 下载，减少首次下载量；较旧 Python 自动下载原始二进制。

```bash
python3 scripts/prepare-runtime.py
# 如开发机需要代理：
python3 scripts/prepare-runtime.py --proxy http://127.0.0.1:7897
```

下载缓存位于 `local/runtime-downloads/`。结果为：

- `app/src/main/assets/runtime/rootfs.tar.gz`
- `app/src/main/jniLibs/arm64-v8a/libproot.so`
- `app/src/main/jniLibs/arm64-v8a/libproot-loader.so`
- `app/src/main/jniLibs/arm64-v8a/libproot-loader32.so`
- `app/src/main/jniLibs/arm64-v8a/libtalloc.so`
- `app/src/main/jniLibs/arm64-v8a/libandroid-shmem.so`

生成文件的 SHA256 另写入本地 `local/runtime-payload.json`。手机首次准备运行时仅解包 App 资产，无须从手机下载这些组件。

## Android 适配

PRoot 和 loader 从 App 的 `nativeLibraryDir` 启动。构建时必须设置 `android:extractNativeLibs="true"` 并保留这些文件。Android 只把 `lib*.so` 形式的文件提取到原生库目录，因此将 PRoot 的 `libtalloc.so.2` 依赖和 talloc 的 SONAME 原位缩短为 `libtalloc.so`，以零字节补齐长度，保持 ELF 偏移和程序代码不变。

`LinuxRuntime` 用 PRoot 的 UID/GID 映射呈现普通用户 1000；它没有 root 权限。用户数据与 Linux 系统文件分开，保存于 App 私有目录。App 退出后台任务后可停止进程，卸载 App 则删除其私有运行时。

模型密钥仅通过进程环境传入 Claude，不写入 Linux 配置文件，也不放入进程命令行。Claude 内置工具关闭，只允许 `phone` MCP 工具，并以 `dontAsk` 权限模式运行；本实现不使用 `--dangerously-skip-permissions`。手机工具桥接由应用层校验随机令牌。

当前运行时是否能稳定启动，以华为真机上的 `claude --version`、Python MCP 连接和实际任务验证为准；构建成功本身不能证明兼容性。
