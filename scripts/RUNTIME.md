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

模型密钥仅通过进程环境传入 Claude，不写入 Linux 配置文件，也不放入进程命令行。生产任务开放 Claude 原生 `Read`、`Write`、`Edit`、`Glob`、`Grep` 和 `AskUserQuestion`，同时保留 `phone` MCP 工具。文件和手机工具预授权；原生反问通过 `--permission-mode default --permission-prompt-tool stdio` 返回 App，由用户明确提交答案。未开放 Bash，也不使用 `--dangerously-skip-permissions`。手机工具桥接校验随机令牌和暂停代次。

任务采用 `--input-format stream-json --output-format stream-json` 双向协议；初始化完成才发送用户消息。暂停先关闭手机动作门，再发送原生 interrupt；等旧轮结束才允许纠正后继续。等待回答时不重复请求模型并释放任务 WakeLock。App 界面退出前不会重新放行手机动作。结束任务先发送原生 interrupt，再关闭输入；退出清理按 PID、启动时间及 UID 追踪本次进程子树，先退出子进程，再退出 PRoot，确认无活跃子进程后才显示结束。

Claude 的 HOME 和工作目录固定为 `/home/agent`，实际保存在手机 App 私有 `files/linux/home/`，与 rootfs 分离。沿用 Claude 原生 `CLAUDE.md` 和 `.claude/projects/-home-agent/memory/MEMORY.md` 自动加载机制，没有另外注入或同步记忆。相同包名与签名的覆盖升级保留该目录；卸载或清除应用数据会删除它。

当前运行时是否能稳定启动，以华为真机上的 `claude --version`、Python MCP 连接和实际任务验证为准；构建成功本身不能证明兼容性。

## 脚本化基础动作

`phone_mcp.py` 提供 `open_app_ready`：启动指定包后，在同一次工具调用中每 500 毫秒读取界面，等待目标包出现可读内容并稳定至少 1 秒。默认等待预算 15 秒，最多 30 秒，HTTP 请求与响应读取也受整体时限约束。这些本地轮询不需要再次请求模型。

已观测到的大麦开屏节点 `splash_layout`、`splash_image`、`homepage_advert_pb` 会阻止就绪判定。工具等待其自然结束，不自动点广告、同意权限或猜测点击坐标；超时返回 `blocked` 和最后界面，连接中断立即停止。`ready` 只表示界面短暂稳定，不代表业务任务完成。

`phone_routines.py` 提供 `list_routines`、`save_routine`、`run_routine`，保存的是受限手机动作 JSON，不执行任意 Shell 或 Python 代码。保存需要真实执行凭据：多步操作需两次完整成功记录；单步 `open_app_ready` 可使用跨任务累计的两次成功候选，再以当前界面验证。脚本执行前检查必要的前台包名，执行后核对最终包名和界面文字，遇到失败立即停止。

脚本和有界的成功候选计数默认保存在手机 Linux 用户的 `~/.phoneagent/routines/`。原始界面与单次执行凭据只在当前 MCP 进程内存中保存；候选计数不保存界面文字、输入内容或模型密钥。测试可通过 `PHONE_AGENT_ROUTINES_DIR` 指向独立目录，无须改变 `HOME` 或访问用户已有脚本。
