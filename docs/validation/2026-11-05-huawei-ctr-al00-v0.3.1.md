# 华为畅享 50 Pro：0.3.1 顶部导航与侧滑删除验证

日期：2026-11-05。应用：PhoneAgent 0.3.1（调试版，`versionCode 7`）。设备：华为畅享 50 Pro / CTR-AL00，HarmonyOS 4.2，Android 12 / API 31 / ARM64，无 root。

本次 `historyui`、`delete`、`taskstore` 实机测试均已通过。验证覆盖固定顶部入口、最近任务列表、侧滑显露删除按钮、真实点击删除、删除持久化、运行任务保护，以及删除最新记录后的重置恢复。

**测试使用独立缓存目录中的合成历史，没有删除真实用户会话，也没有调用真实外部模型。** `historyui` 不发送任务；`taskstore` 不启动模型进程；`delete` 的运行状态检查启动真实 Claude Code，但仅连接不可达的本机地址 `http://127.0.0.1:9`，使用一次性测试凭据，无需模拟服务或端口映射。

## 顶部导航与最近任务

[MainActivity.java](../../app/src/main/java/com/ds/phoneagent/MainActivity.java) 将“任务记录”“新任务”和“连接与设置”放在内容 ScrollView 之外。启动路径优先展示当前可用会话；没有可用会话时展示新任务页，最近任务列表由“任务记录”入口显式打开。

`PASS historyui` 临时将带有 5 个合成会话的独立 Controller 接入真实 Activity，确认：

- 默认视图与最近任务列表分开；点击“任务记录”进入列表并关闭展开的设置面板。
- “任务记录”和“连接与设置”具有明确的无障碍描述，内容滚动前后入口的屏幕位置不变。
- 关闭状态下普通点击会话行会打开详情；再次点击“任务记录”返回更新后的列表。
- 测试结束恢复原 Controller、选中页面、草稿、过程展开状态、设置面板及滚动位置。

本项不读取或修改模型连接字段。导航断言使用真实 Activity 的控件和布局，以下侧滑与删除则通过真实触摸注入完成。

## 侧滑显露与真实点击删除

列表行使用 API 26 可用的原生 [SwipeDeleteRow.java](../../app/src/main/java/com/ds/phoneagent/SwipeDeleteRow.java)。删除按钮初始不可点击且对无障碍隐藏；只有完成显露手势或使用“显示删除按钮”无障碍操作后才可操作。根页面在新行展开时关闭其他展开行。手势本身不调用删除，删除回调只来自按钮点击。

`historyui` 使用 `Instrumentation.sendPointerSync` 对隔离会话行执行实际 DOWN / MOVE / UP，已验证：

| 触摸路径 | 实际结果 |
|---|---|
| 关闭行上的主要纵向滑动 | 没有打开详情、显露按钮或删除记录；外层列表可接管滚动 |
| 向左滑动 | 仅露出行右侧红色“删除”按钮，记录数量不变，也没有打开详情 |
| 从已露出的删除按钮区域开始纵向滑动 | 取消潜在按钮点击，没有误删或打开详情 |
| 向右滑动 | 收起删除按钮，保留记录且不打开详情 |
| 再次左滑，然后点击删除按钮 | 仅删除被点击的那条合成会话，数量由 5 变为 4，仍停留列表页 |
| 删除后重建历史存储 | 被删记录不复活，对应 last-task 已清理，其他 4 条记录仍在 |

被动日志进一步记录了首行仍附着于窗口且仍为列表当前首行；纵向手势后收到 CANCEL、位移为 0；横向手势收到 DOWN / MOVE / UP，结束时内容位移为 -264 像素，`revealed=true`、`deleteEnabled=true`。这些均为测试行的几何和事件元数据，不包含页面文本或私人屏幕。

多指与 CANCEL 收起、触摸 slop、横竖方向判断，以及无障碍显露/收起入口属于本版实现；本报告不把所有这些分支都扩展为已经完成实机触摸覆盖。

## 删除持久化与运行任务保护

`PASS delete` 使用独立历史与 last-task 文件，验证 [AgentController.java](../../app/src/main/java/com/ds/phoneagent/AgentController.java)、[TaskHistoryStore.java](../../app/src/main/java/com/ds/phoneagent/TaskHistoryStore.java) 和 [LastTaskStore.java](../../app/src/main/java/com/ds/phoneagent/LastTaskStore.java) 的实际删除行为：

| 检查 | 已验证结果 |
|---|---|
| 删除当前保存的会话 | 只清理其关联 last-task；另一会话保持逐字段序列化等价，重建后删除仍有效 |
| 删除最后一条记录 | 历史保持为空，重建不恢复已删记录 |
| 删除迁移来的旧版记录 | 再次提供旧 last-task 快照也不会重新导入已删会话 |
| Linux 会话文件范围 | 隔离的合成 CLI 会话文件仍在；App 历史删除不删除 Claude 会话文件 |
| 正在运行的会话 | 实际本机 CLI 进程存在期间，删除活动会话被拒绝 |
| 运行时删除另一条旧会话 | 保留当前进程、活动会话 ID、运行状态、展示 epoch 和当前 last-task；停止该本机测试任务后再次检查删除持久化 |
| 删除最新记录后重置 | 只确认剩余会话；重建 Controller 后保持待命或待配置展示，不修改其历史内容，也不复活被删记录 |

`PASS taskstore` 同时回归完成记录恢复、已确认错误恢复待命/待配置，以及遗留运行记录恢复为一次中断记录；没有恢复活动进程。

测试观察模型设置变更键，确认删除路径没有触发设置改动。此项与最终升级前后配置文件 checksum 校验不同。运行保护测试可能在实际 Linux home 留下新的本机测试 CLI 会话；其最终清理由主流程另行确认。

## 测试过程中的环境问题

首个 `historyui` 测试在“左滑后应显露删除按钮”的断言处失败，当时日志没有记录完整的命中和滚动证据。主流程随后在实际 App 上手动横滑成功。测试程序补充了定位前向 ScrollView 自身发送 DOWN / CANCEL 以结束先前惯性滚动、用局部可见区域加屏幕位置生成注入坐标，以及返回 false、不消费事件的被动触摸日志，之后重跑得到 `PASS historyui`。

`scrollTo` 和 `waitForIdleSync` 本身不保证 Android OverScroller 已停止。上述调整和后续日志证明最终手势确实到达目标行并按预期完成，但不足以断言首次失败只有一个已确定的根因；本次没有为迁就该断言而修改侧滑控件行为。

`delete` 验证前还遇到华为无障碍服务的旧绑定状态。主流程通过系统设置界面恢复服务后再验证；最终日志记录服务在等待 601 毫秒后完成绑定，并给出 `PASS delete`。此前的绑定问题不计作删除功能已验证通过的证据。

## 本地证据与构建

- `local/historyui-v031.txt`：触摸命中和几何元数据、导航及删除结果，`PASS historyui`。
- `local/delete-v031.txt`：删除、重置恢复、运行任务保护，`PASS delete`。
- `local/taskstore-v031.txt`：最近任务持久化恢复回归，`PASS taskstore`。
- 测试入口及范围见 [实机测试说明](../../tests/android/README.md) 和 [TestInstrumentation.java](../../tests/android/java/com/ds/phoneagent/TestInstrumentation.java)。

报告生成时核对的主 APK：

- 路径：`build/PhoneAgent-debug.apk`。
- 大小：148,354,680 字节。
- SHA256：`2df730ee63487d5bbb8afddbce577764123315a8785e9b68c1e1a6b5b5c2cf36`。

摘要仅对应本次核对文件，后续重建需更新。本报告未附真实任务内容、模型配置或私人屏幕截图。

## 最终收尾

已核对手机安装版本 0.3.1 / versionCode 7。升级、手势检查和删除专项测试后，原有 5 条用户会话所在的 `task-history.json` 与关联 `last-task.json` 均与测试前逐字节一致；模型配置文件 SHA256 一致。手动滑动真实记录只显露删除按钮，没有点删除；所有删除动作只针对隔离测试数据。

测试 APK 已卸载，未残留 Claude/PRoot/Python 测试进程，没有新增需要删除的 CLI 会话日志。本轮未启动模拟服务或端口映射。最终系统状态确认 PhoneAgent 手机控制服务已绑定，Binding/Crashed 列表为空。
