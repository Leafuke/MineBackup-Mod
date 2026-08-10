# Fabric 26.1 配置与权限参考

本文仅适用于 Minecraft 26.1-26.1.2 的 MineBackup-Mod Fabric 版本。
MineBackup/FolderRewind 桌面程序拥有独立配置。

## 文件位置与加载时机

- 全局配置：`config/minebackup.properties`。
- 世界自动化：`config/minebackup/worlds/<SHA-256>.properties`。
- 专服还原状态与辅助文件：`config/minebackup/restart/`。

Minecraft 服务端启动时加载全局配置和当前世界配置；单机打开存档时也会启动集成服务端。
手动修改全局文件后需要重启服务端或重新打开存档。`/mb auto start` 和
`/mb auto stop` 会立即写入并应用世界配置。

MineBackup 加载时会把已知全局配置重写成标准形式，因此不要在
`minebackup.properties` 中存放无关的自定义键。

## 全局配置

| 配置键 | 默认值 | 合法值 | 适用环境 | 作用 |
|---|---:|---|---|---|
| `restore.countdownSeconds` | `10` | 整数 `0..300` | 全部 | 提交当前世界还原前的倒计时；`0` 表示关闭倒计时。 |
| `lan.hostReopen.enabled` | `true` | `true` 或 `false` | LAN 房主 | 集成服务端还原后重新开放局域网。 |
| `lan.hostReopen.retryCount` | `6` | 整数 `1..30` | LAN 房主 | 尝试重新使用原 LAN 端口的次数。 |
| `lan.hostReopen.retryIntervalTicks` | `40` | 整数 `10..200` | LAN 房主 | 两次重试之间的服务端 tick 数。 |
| `lan.hostReopen.allowRandomPortFallback` | `true` | `true` 或 `false` | LAN 房主 | 原端口不可用时允许改用随机可用端口。 |
| `lan.clientReconnect.enabled` | `true` | `true` 或 `false` | LAN 客户端 | 房主还原并重新开放 LAN 后自动重连。 |
| `lan.clientReconnect.initialDelayTicks` | `200` | 整数 `40..600` | LAN 客户端 | 首次重连前的等待 tick 数。 |
| `lan.clientReconnect.retryIntervalTicks` | `100` | 整数 `20..200` | LAN 客户端 | 重连尝试间隔。 |
| `lan.clientReconnect.maxDurationTicks` | `1800` | 整数 `200..7200` | LAN 客户端 | 自动重连的最长尝试时间。 |
| `updateCheck.enabled` | `true` | `true` 或 `false` | 客户端和服务端 | 检查 MineBackup-Mod 新版本。 |
| `dedicatedRestore.mode` | `SIDECAR` | `SIDECAR` 或 `DISABLED` | 专用服务端 | 启用内置 Sidecar 的安全还原交接。 |
| `dedicatedRestore.restartScript` | 空 | 路径字符串 | 专用服务端 | 明确指定重启脚本；留空时从服务端工作目录自动查找。 |
| `dedicatedRestore.sidecarStartTimeoutSeconds` | `5` | 整数 `1..60` | 专用服务端 | 停服前等待 Sidecar 成功订阅的时间。 |
| `dedicatedRestore.worldReleaseTimeoutSeconds` | `8` | 整数 `1..120` | 专用服务端 | 等待世界文件可释放的时间。 |
| `dedicatedRestore.operationTimeoutSeconds` | `3600` | 整数 `30..86400` | 专用服务端 | 已交接还原操作的最长等待时间。 |

越界整数会被限制到合法范围；缺失、空白或格式错误的值使用默认值。重启脚本和进程管理器要求见
[专用服务端还原](DEDICATED-RESTORE.md)。

## 世界自动化

使用以下指令配置当前世界：

```text
/mb auto start <分钟>
/mb auto start <分钟> backup
/mb auto start <分钟> remind
/mb auto status
/mb auto stop
```

第一种写法默认选择 `BACKUP`。间隔必须是 `1..525600` 的整数分钟。
`BACKUP` 到期后提交当前世界热备份；`REMIND` 不调用 KnotLink，只向全部在线玩家发出提醒，
拥有备份权限的玩家还会看到可点击的“立即备份”。无人在线时只写日志。

每个世界文件由 MineBackup 管理，包含：

| 配置键 | 值 | 编辑说明 |
|---|---|---|
| `world.identity` | 标准化的相对或绝对世界路径 | 自动生成的身份，不应手改。 |
| `world.displayName` | 世界名称 | 仅用于诊断显示，不参与身份判断。 |
| `automation.mode` | `OFF`、`BACKUP` 或 `REMIND` | 建议通过 `/mb auto` 修改。 |
| `automation.intervalMinutes` | 整数 `1..525600` | 仅在 `BACKUP` 或 `REMIND` 时存在。 |

游戏目录内的世界使用标准化相对路径，因此整体移动实例仍能保留计划；外部世界使用真实绝对路径。
仅重命名或移动世界会产生新身份，新世界默认关闭自动化，旧文件保留。损坏或身份不匹配的文件会被禁用。

调度器只运行当前世界的计划。每次服务端启动都从启动时刻重新计算，不保存绝对下次执行时间。
任何当前世界备份返回成功或 `NO_CHANGES` 后，都从完成时刻重新计时；失败、取消、忙碌或拒绝不重置。

提醒模式适合存在不抗卸载红石机器的世界，可避免自动备份恰好发生在不合适时刻。
MineBackup 无法识别机器的安全状态，而还原世界必然涉及卸载；提醒模式并不保证机器回档安全，
它只是把实际备份时机交给玩家选择。

## 权限

Fabric 26.1 使用 Minecraft 原生权限 Atom。集成服务端的存档房主和服务端控制台始终直通。
其他调用者依次通过 `minebackup:command/admin`、具体能力节点或原版管理员权限
（OP 等级 2）授权，以保持升级兼容。

| 权限节点 | 对应指令 |
|---|---|
| `minebackup:command/backup` | `/mb save`、`/mb backup` |
| `minebackup:command/restore` | `/mb restore`、`/mb confirm`、`/mb stop` |
| `minebackup:command/browse` | `/mb list configs`、`/mb list folders`、`/mb list backups` |
| `minebackup:command/target_backup` | `/mb target backup` |
| `minebackup:command/target_restore` | `/mb target restore` |
| `minebackup:command/automation` | `/mb auto start`、`/mb auto stop`、`/mb auto status` |
| `minebackup:command/admin` | 全部 MineBackup 指令 |

帮助、指令发现和补全会按能力过滤。备份列表仅向拥有当前世界还原权限的调用者显示还原按钮。
MineBackup 不内置玩家角色表或 ACL 编辑器，权限管理仍由服务端负责。

## 升级迁移

旧全局键 `auto.currentWorld.intervalMinutes` 会在首次成功打开世界后迁移一次。
目标世界没有计划时创建 `BACKUP` 计划；已有世界计划时保留现有计划。只有世界文件写入成功后才删除旧键。
更早的 `auto.configId`、`auto.folder` 等目标式自动备份继续保持禁用，并显示迁移提示。

如果 `config/minebackup.properties` 不存在、但旧的
`config/minebackup-auto.properties` 存在，加载前会把旧文件移动为新名称。

## 人工验收清单

- 验证单机房主无需 OP 即可使用指令。
- 分别验证 LAN 房主、LAN OP 和 LAN 普通玩家的能力范围。
- 分别验证专服控制台、OP2 和非 OP 玩家。
- 在世界 A 启动计划，打开世界 B 确认其默认关闭，再重开 A 确认计划恢复。
- 分别验证 `BACKUP`、`REMIND` 和无人在线时的提醒行为。
- 手动完成一次备份后，用 `/mb auto status` 确认下次时间已重新计算。
