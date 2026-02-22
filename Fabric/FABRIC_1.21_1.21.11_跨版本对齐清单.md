# Fabric 1.21 ↔ 1.21.11 跨版本对齐清单（MineBackup）

## 1. 目标与范围

本清单用于统一维护以下两个子项目的行为一致性：

- `Fabric/Fabric-1.21`（Yarn 映射）
- `Fabric/Fabric-1.21.11`（Mojang 官方映射）

重点保证：

1. 功能一致（命令、热备份、热还原、自动重连、KnotLink 通信）
2. 日志与错误处理策略一致
3. 后续同步到 NeoForge/Forge 时有可执行的映射基线

---

## 2. 当前已对齐的模块（结构层）

两边已统一为同名核心文件（共 9 个 Java + 4 个资源）：

- `MineBackup.java`
- `MineBackupClient.java`
- `Command.java`
- `Config.java`
- `restore/HotRestoreState.java`
- `compat/GcaCompat.java`
- `knotlink/OpenSocketQuerier.java`
- `knotlink/SignalSubscriber.java`
- `knotlink/TcpClient.java`
- `fabric.mod.json`
- `assets/minebackup/lang/zh_cn.json`
- `assets/minebackup/lang/en_us.json`
- `assets/minebackup/icon.png`

已移除无用模板/占位代码：

- `ExampleMixin`
- `minebackup.mixins.json`
- `HotRestorePackets`（未接入）
- `SignalSender`（未引用）
- `OpenSocketResponser`（未引用）

---

## 3. API 映射对照（核心差异）

> 维护建议：**逻辑保持一致，只替换 API 外壳**。

### 3.1 文本与消息

- Yarn：`Text`
- Mojang：`Component`

- Yarn 广播：`server.getPlayerManager().broadcast(...)`
- Mojang 广播：`server.getPlayerList().broadcastSystemMessage(...)`

### 3.2 命令源与权限

- Yarn：`ServerCommandSource`
- Mojang：`CommandSourceStack`

- Yarn 权限：`src.hasPermissionLevel(2)`
- Mojang 权限：`src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)`

### 3.3 世界与存档路径

- Yarn：`WorldSavePath.ROOT` + `server.getSavePath(...)`
- Mojang：`LevelResource.ROOT` + `server.getWorldPath(...)`

- Yarn 世界名：`server.getSaveProperties().getLevelName()`
- Mojang 世界名：`server.getWorldData().getLevelName()`

### 3.4 专用服判断

- Yarn：`isDedicated()`
- Mojang：`isDedicatedServer()`

### 3.5 客户端重连入口

- Yarn：`client.createIntegratedServerLoader().start(levelId, onCancel)`
- Mojang：`client.createWorldOpenFlows().openWorld(levelId, onCancel)`

---

## 4. 行为一致性检查清单（可直接勾选）

## 4.1 命令行为

- [ ] `/mb save`：仅本地保存，不触发远端备份
- [ ] `/mb quicksave`：先本地保存，再 `BACKUP_CURRENT`
- [ ] `/mb quicksave <comment>`：先本地保存，再 `BACKUP_CURRENT <comment>`
- [ ] `/mb auto`：写入本地配置并发送 `AUTO_BACKUP`
- [ ] `/mb stop`：清理本地配置并发送 `STOP_AUTO_BACKUP`
- [ ] `/minebackup` 旧入口仅提示迁移，不执行业务逻辑

## 4.2 通信与异常处理

- [ ] `queryBackend` 对 `null future` 和异常都能兜底
- [ ] 补全建议（`LIST_BACKUPS`）异常时返回空建议，不抛出
- [ ] `OpenSocketQuerier` 使用 UTF-8 显式输出流写包
- [ ] `TcpClient` 不使用 `System.out/err`，统一日志输出

## 4.3 热还原流程

- [ ] `pre_hot_restore` 时先保存、断开玩家，再发 `WORLD_SAVE_AND_EXIT_COMPLETE`
- [ ] `restore_finished success` 后设置客户端自动重连标记
- [ ] `rejoin_world` 事件可作为兜底触发重连
- [ ] 重连成功后发送 `REJOIN_RESULT success`
- [ ] 超时/失败发送 `REJOIN_RESULT failure ...`

## 4.4 GCA 兼容

- [ ] 热备份前执行 `saveFakePlayersIfNeeded`
- [ ] `fake_player.gca.json` 输出路径为当前世界根目录
- [ ] 反射异常不终止主流程，仅记录 warn

---

## 5. 同步 NeoForge/Forge 的建议策略

建议建立“适配层 + 业务层”两段式结构，避免三端重复维护：

1. **业务层（纯逻辑）**：命令语义、KnotLink 协议、热还原状态机
2. **平台适配层（加载器差异）**：
   - 文本/消息发送
   - 权限判断
   - 世界路径获取
   - 客户端重连入口
   - 玩家列表与踢出接口

推荐先抽象以下 5 组接口：

- `PlatformTextAdapter`
- `PlatformCommandAdapter`
- `PlatformWorldAdapter`
- `PlatformPlayerAdapter`
- `PlatformClientRejoinAdapter`

这样可让 Fabric/NeoForge/Forge 只实现壳层，核心流程代码保持一份语义模板。

---

## 6. 发布前最小回归集

每次跨版本同步后，至少回归以下指令：

1. `/mb save`
2. `/mb list_configs`
3. `/mb backup <config> <world>`
4. `/mb quicksave`
5. `/mb auto <config> <world> <sec>`
6. `/mb stop <config> <world>`
7. 热备份事件：`pre_hot_backup`
8. 热还原事件：`pre_hot_restore -> restore_finished -> rejoin_world`

若上述 8 项通过，可认为跨版本行为一致性达到可发布基线。
