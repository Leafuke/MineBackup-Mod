# 后端能力、连接诊断与恢复取消（MineBackup 3.4.0 / API v2）

八个主模组维护目标统一实现这些方法；`API_VERSION` 保持 2。接口默认实现返回 `UNSUPPORTED`，旧 API 实现仍可加载。所有公共类型位于 `com.leafuke.minebackup.api.v2`，不引用 Minecraft 或加载器类型。异步回调不保证游戏线程，UI 和游戏操作须自行调度。

## 能力声明

`backendCapabilities(BackendCapabilitiesRequest.create("addon:capabilities"))` 通过现有 KnotLink 查询 `GET_CAPABILITIES`，解析 `specVersion=1.0` 的 `func_list`，返回不可变的命令/参数清单。限制清单大小并拒绝规范化参数冲突；相同命令别名的兼容声明会合并。它不占用当前世界操作句柄。

`SUCCESS` 只证明声明，不证明插件启用、世界绑定、组合合法或归档可恢复。断线、旧协议、缺少清单和解析失败返回 `FAILED`；无活动服务器或原世界关闭返回 `UNAVAILABLE`。详细通信分类由 `backendStatus` 提供，真实提交仍须处理拒绝结果。

## 后端状态

```java
api.backendStatus(BackendStatusRequest.create("time_machine:diagnostics"))
    .thenAccept(status -> {
        // These are separate observations, not an operation readiness guarantee.
        renderQueryChannel(status.queryChannel());
        renderSignalChannel(status.signalChannel());
        renderResponder(status.responder());
        status.failure().ifPresent(failure -> renderReason(failure.code()));
        status.activeTasks().ifPresent(count -> renderTaskCount(count));
        // Schedule Minecraft UI work on the current game's executor.
    });
```

上例 `render*` 方法为调用方实现的界面占位方法。

`BackendStatusResult` 的 `outcome` 为 `SUCCESS/UNSUPPORTED/UNAVAILABLE/FAILED`。查询和信号通道分别为 `UNKNOWN/CONNECTED/UNREACHABLE`；主程序响应端为 `UNKNOWN/ONLINE/OFFLINE`。`enabled`、`initialized`、`activeTasks` 和 `activeAutomaticBackups` 为可选字段，缺失时保持未知。主程序拒绝状态请求也能证明响应端在线；畸形通信数据或超时不能证明主程序在线或离线。

状态查询使用只读 `GET_STATUS`，兼容 MineBackup 的 `data` 和 FolderRewind 的 `message/data` 载荷。它不抢占操作、不修改绑定、不自动重试世界操作；无活动服务器或查询过程中原世界关闭返回 `UNAVAILABLE`。查询成功不证明信号通道连接，不证明主程序中的 Minecraft 插件启用或当前世界可操作。快照返回后状态可能变化。

| 原因代码 | 观察与用户处理 |
| --- | --- |
| `KNOTLINK_UNREACHABLE` | 连接服务端口失败；检查 KnotLink 安装和启动情况，无法仅据此区分未安装与未运行。 |
| `BACKEND_OFFLINE` | KnotLink 明确返回 `offline`；启动主程序并检查其 KnotLink 开关，无法仅据此断定主程序进程未启动。 |
| `RESPONSE_TIMEOUT` | 连接后未及时收到响应；检查主程序是否忙碌或通信异常。 |
| `CONNECTION_CLOSED` | 建立查询连接后发生中断；检查服务与主程序。 |
| `PROTOCOL_ERROR` | 帧、编码、v2 字段或状态载荷异常；检查版本兼容性及日志。 |
| `CLIENT_CLOSED` / `QUERY_QUEUE_FULL` | 通信组件已关闭 / 本地请求过多；重新进入世界或稍后重试。 |

`OperationFailure` 继续使用原有 `(code, message)` 构造器，新增代码可供调用方本地化；`message` 是技术详情，不适合直接显示给用户。后台重连只写日志，不增加聊天提醒或 `/mb status`。

## 取消倒计时

```java
OperationHandle<RestoreResult> handle = api.restoreCurrent(
    RestoreRequest.latest("time_machine:restore"));
RestoreCancelResult cancellation = api.cancelRestore(
    RestoreCancelRequest.create("time_machine:ui", handle.id()));
```

`callerId` 规范化后用于记录；拥有 UUID 即可取消其他调用方的恢复倒计时。调用方应保存真实句柄 UUID，不使用全局 `/mb stop` 代替此 API。

- `CANCELLED`：原子匹配 UUID 并取消 `COUNTING_DOWN`，原句柄以 `CANCELLED` 完成，计时器和操作占用释放。
- `NOT_PENDING`：UUID 不匹配、不是恢复或操作已结束；不会影响其他操作。
- `ALREADY_SUBMITTED`：匹配的恢复已开始提交；不发送后端取消，不中断恢复。
- `UNSUPPORTED`：实现未提供该 API。

取消为同步本地状态操作，不执行阻塞网络或文件等待；与倒计时提交共用原子状态检查。管理员 `/mb stop` 保留现有权限和行为。返回 `CANCELLED` 的一次调用之后，重复调用返回 `NOT_PENDING`。

## 保存与依赖

本轮保持严格 flush 保存，没有引入保存策略配置或保存策略 API。JEA、DeathRewind、Time-Machine 可继续使用原来的请求和句柄；新功能需声明 MineBackup >=3.4.0 或处理缺失类型/方法的 `LinkageError`。接口默认方法不能让新增类型在旧 JAR 中凭空存在。
