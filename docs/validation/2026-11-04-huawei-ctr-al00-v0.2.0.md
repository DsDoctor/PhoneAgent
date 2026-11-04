# 华为畅享 50 Pro：月薪喵 0.2.0 验证

日期：2026-11-04。应用：PhoneAgent 0.2.0（调试版，`versionCode 3`）。

本记录覆盖月薪喵动画入口和华为设置页悬浮窗修复。[0.1.0 验证报告](2026-11-04-huawei-ctr-al00.md) 保持原样，作为早期版本的历史记录。

## 实机环境与本次变更

- 设备：华为畅享 50 Pro / CTR-AL00 / 8 GB RAM，HarmonyOS 4.2。
- 实测系统：Android 12 / API 31 / ARM64，无 root。
- 后台仍运行私有 Alpine Linux、PRoot、Python MCP 和官方 Claude Code 2.1.311。
- 普通悬浮球改为可拖动的月薪喵动画入口，主界面同步显示任务状态。
- 无障碍服务已连接时，使用该服务自身的 `WindowManager` 添加 `TYPE_ACCESSIBILITY_OVERLAY`。未连接时，在已有普通悬浮窗授权的条件下回退为 `TYPE_APPLICATION_OVERLAY`。

## 设置页悬浮修复

目标华为设备会在部分系统设置页面隐藏普通 `TYPE_APPLICATION_OVERLAY` 窗口。仅检查 View 已挂载或 `isShown()`，不足以证明用户在屏幕上能看到它。

本次将无障碍悬浮窗绑定到已连接的 `PhoneAccessibilityService` 提供的 `WindowManager`，避免使用普通 Service 的窗口上下文冒充无障碍窗口。无障碍服务连接或断开时重新创建窗口，避免沿用失效的窗口令牌；普通悬浮窗作为授权后的回退路径。

显示规则仍为：PhoneAgent 主界面可见时隐藏月薪喵，离开主界面后按用户的悬浮开关恢复。回归测试同时覆盖从手机控制设置直接返回桌面的路径。

## 月薪喵原图与状态映射

素材来源：用户本机已有的「月薪喵」宠物动画图集，直接复用原始 WebP 图像，没有重新生成角色或替换画风。

- 仓库素材：`app/src/main/assets/monthly_salary_cat.webp`。
- 图集尺寸：1536 × 2288；8 列 × 11 行。
- 每帧固定画布：192 × 208，按格取帧并等比绘制。
- 文件大小：1,684,774 字节。
- 原图 SHA256：`6128dab98f27bc6aa99594dc1a2bb27fb18475bc52885f5828e6812f817152e2`。
- 已检查当前 APK 中 `assets/monthly_salary_cat.webp` 与上述素材逐字节相同。

实际映射以 [MonthlyCatView.java](../../app/src/main/java/com/ds/phoneagent/MonthlyCatView.java) 为准。下表的行号从 0 开始；每个状态仅播放该行指定数量的帧。

| Controller 状态 | 显示文字 | 图集行 | 播放帧数 |
|---|---|---:|---:|
| `IDLE` | 待命 | 0 | 6 |
| `PREPARING` | 准备 | 7 | 6 |
| `THINKING` | 思考 | 6 | 6 |
| `ACTING` | 操作 | 7 | 6 |
| `SUCCESS` | 完成 | 4 | 5 |
| `NEEDS_INPUT` | 待配置 | 8 | 6 |
| `ERROR` | 出错 | 5 | 8 |
| `STOPPING` | 停止中 | 8 | 6 |
| `STOPPED` | 已停止 | 3 | 4 |

切换状态时从首帧开始；每次手机工具执行结束后回到 THINKING，等待模型的下一步。取消或结束后，延迟的工具回调不会覆盖终态。View 不可见、离开窗口或被完全裁剪时停止动画回调，滚回屏幕时恢复。主界面和悬浮入口复用同一图集缓存。

桌面图标取原图第 4 行第 0 列的完整角色，使用奶油色背景和按比例缩放的自适应图标留白，避免华为桌面放大裁掉角色。

## 验证结果

| 检查 | 状态与证据范围 |
|---|---|
| APK 元数据 | 已核对包名 `com.ds.phoneagent`、版本 0.2.0、`versionCode 3`、`targetSdk 28`、ARM64 |
| APK 内月薪喵素材 | 通过，与源图逐字节一致 |
| `pet` 实机测试 | 通过；接收全部 `VisualState`，THINKING 和 ACTING 帧推进正常；主界面滚出时暂停、滚回后恢复 |
| 主界面隐藏悬浮入口 | `overlay` 测试通过 |
| 桌面与设置页窗口类型 | `overlay` 测试通过，均挂载为 `TYPE_ACCESSIBILITY_OVERLAY` |
| 设置页点击月薪喵 | `overlay` 测试通过，真实无障碍点击返回主界面并隐藏悬浮入口 |
| 控制设置直接返回桌面 | `overlay` 测试通过，悬浮入口仍挂载 |
| 0.2.0 Controller 完整回归 | 通过；本地固定响应完成 Home → read_screen → open_settings → read_screen，并断言 THINKING → ACTING → THINKING → SUCCESS；停止路径 THINKING → STOPPING → STOPPED 和进程清理通过 |
| 设置页最终画面与合成可见性 | 通过；最终截图可见月薪喵，窗口类型 2032、`isOnScreen=true`、`isVisible=true`，未被强制隐藏 |
| 桌面图标 | 最终截图确认整只猫完整显示，没有裁掉头部或身体 |
| 原有模型配置 | 升级和回归后配置文件 SHA256 与升级前一致；没有打印或上传配置内容 |
| 真实外部模型打开设置 | 用户反馈已成功；与自动化测试结果分开记录 |

`pet` 和 `overlay` 测试未调用外部模型，也未读取或修改模型配置。Controller 的固定响应服务用于验证实际 Claude Code → MCP → 手机操作链路，不据此评价真实模型的推理质量。

用户已经自行接入真实模型并完成一次“打开设置”任务。本报告不将该反馈扩展为其他任务或长时间后台运行已通过验证。

一次早期 Controller 回归因测试启动落在既有设置页、随后设备休眠而超时。测试入口现使用 CLEAR_TOP，并确认主界面已恢复、可见且获得焦点后开始；最终回归期间用临时 ADB 唤醒保持设备亮屏，没有更改持久系统设置。最终三项测试全部通过，测试 APK、本地响应服务和端口转发均已清理，手机只保留空闲的 PhoneAgent 服务。

可复现测试入口见 [实机测试说明](../../tests/android/README.md)：

```bash
python3 scripts/test-device.py overlay
python3 scripts/test-device.py pet
# 先按实机测试说明启动本地响应服务和端口转发
python3 scripts/test-device.py controller
```

## 当前构建产物

- 本地构建输出：`build/PhoneAgent-debug.apk`。
- 大小：148,301,362 字节，约 141 MiB。
- SHA256：`9a85dcc3f93888d674bc456727fc2c8d0944fbc7a98f2cc2da5e28036f80650d`。

上述摘要对应本报告核对时的 0.2.0 构建；如后续修改代码并重建，需同步更新摘要。原始设备日志、截图、安装包和签名密钥保留在被忽略的本地目录中。
