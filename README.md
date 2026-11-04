# PhoneAgent

运行在 Android 手机上的轻量 Agent 原型，属于 AIResearch（AI 研究所）子项目。通过悬浮宠物「月薪喵」进入，输入文字任务，由手机后台的 Claude Code 调用工具操作手机。当前版本为 **0.2.0**。

本项目独立实现 Android App、工具桥接和运行时装配，没有复制 Buddy 的代码或界面。Linux 只提供后台运行环境，没有桌面或终端入口。

## 当前范围

- 中文任务界面、可拖动的月薪喵悬浮入口、前台通知和停止按钮。
- 复用用户本机已有的月薪喵动画原图，在主界面与悬浮入口显示待命、准备、思考、操作、完成、待配置、出错和停止状态。
- App 私有目录中的 Alpine Linux + PRoot，运行官方 Claude Code ARM64 二进制。
- 可配置兼容 Anthropic API 的外部模型地址、模型名和 API Key。
- 通过本机 MCP 工具读取界面、返回桌面、返回上一级、打开设置或应用、点击、滑动、输入文字。
- API Key 用 AndroidKeyStore 加密保存在手机，设备工具接口只监听本机，并使用每次任务独立的随机令牌。

当前使用无障碍界面节点进行观察，不包含截图视觉、语音、锁屏解锁或自动开机启动。执行任务会占用手机当前屏幕。用户已自行接入真实外部模型，并反馈“打开设置”任务成功；这一反馈与本项目的自动化联调结果分别记录。

0.2.0 针对华为设置页隐藏普通悬浮窗的问题，在无障碍服务连接后改用该服务自身的 `WindowManager` 创建 `TYPE_ACCESSIBILITY_OVERLAY`；未连接时回退为获得授权的普通悬浮窗。进入 PhoneAgent 主界面时隐藏悬浮入口，退出后恢复。

## 运行方式

```text
月薪喵悬浮入口 / 文字任务
    → Android App
    → 私有 Linux 用户空间中的 Claude Code ↔ 外部模型
    → Python stdio MCP
    → 带任务令牌的本机工具接口
    → Android 无障碍服务
    → 读取界面、执行动作、再次检查
```

任务规划与工具调用循环由真正的 Claude Code 执行。CLI 的内置工具关闭，仅允许本项目的手机 MCP 工具；单次任务最多 12 轮、5 分钟，可在 App 或通知中主动停止。

## 目标设备

首个适配对象是华为畅享 50 Pro（CTR-AL00，8 GB RAM，HarmonyOS 4.2），ADB 实测为 **Android 12 / API 31 / ARM64**，无 root。其他机型尚未验证。

此原型使用 `minSdk 26 / targetSdk 28` 和调试签名，面向个人设备侧载验证。后续面向新系统或公开发行时，需要重新处理目标 SDK、进程执行和后台限制。

## 构建与安装

需要 Python 3.10+、JDK、Android SDK（platform、build-tools、platform-tools）。macOS 默认使用 Android Studio 自带 JBR 和 `~/Library/Android/sdk`；其他安装位置可设置 `JAVA_HOME`、`ANDROID_SDK_ROOT`。

```bash
# 下载并校验固定版本的运行时组件，生成离线安装资产
python3 scripts/prepare-runtime.py

# 使用 Android SDK 工具链构建，不需要下载 Gradle 或 AndroidX
python3 scripts/build.py

# 安装到已连接的手机；按手机系统提示完成安装
adb install --no-incremental -r build/PhoneAgent-debug.apk
```

运行包约 140 MiB，首次准备会在 App 私有空间解包。构建产物、下载缓存和签名密钥位于被 Git 忽略的目录中。[运行时来源、固定版本和准备方式](scripts/RUNTIME.md)。

## 手机上使用

1. 打开 PhoneAgent，点“准备环境”，等待运行环境就绪。
2. 开启 PhoneAgent 无障碍服务，月薪喵即可悬浮显示；普通悬浮窗权限作为备用方式。
3. 在“连接模型”填写兼容 Anthropic API 的 HTTPS 地址、模型名和 API Key。
4. 输入“打开系统设置”等简单任务，点击“开始执行”。
5. App 会退到后台，手机执行操作；点月薪喵返回查看结果，或用通知里的“停止任务”。

华为系统可能限制后台进程。如果遇到任务被系统中断，在应用启动管理中允许 PhoneAgent 后台活动。锁屏时请先手动解锁，再执行需要界面的操作。

## 验证

0.2.0 已通过月薪喵状态与动画、滚动恢复、桌面及设置页悬浮入口、Controller 多步操作与停止回归测试。已通过实机截图和窗口信息确认设置页上的月薪喵可见，桌面图标完整显示，见 [0.2.0 实机验证记录](docs/validation/2026-11-04-huawei-ctr-al00-v0.2.0.md)。

[0.1.0 历史验证记录](docs/validation/2026-11-04-huawei-ctr-al00.md) 保留当时的运行环境、手机工具、中文输入、Claude Code 本地联调和停止流程结果。真实外部模型打开设置成功来自用户反馈，未将它记作固定响应测试的结果。

```bash
python3 -m unittest discover -s tests -v
python3 scripts/build_android_tests.py
```

测试 APK 的安装与真机运行步骤见 [实机测试说明](tests/android/README.md)。本地固定响应的模拟模型仅用于验证 Claude Code → MCP → 手机操作链路，不代表任何真实外部模型的兼容性或任务能力。

## 目录

- `app/src/main/`：Android App、MCP 脚本与运行时资产位置。
- `scripts/`：SDK 构建、运行时下载校验与固定依赖清单。
- `tests/`：MCP 协议测试、Android 实机测试和本地模拟模型。
- [早期方案调研](docs/research/2026-11-04-buddy-phone-agent.md)：保留最初参考与选型过程。

远端仓库：[DsDoctor/PhoneAgent](https://github.com/DsDoctor/PhoneAgent)。在本目录中通过 `git pull --ff-only` 拉取更新，提交后使用 `git push` 同步。
