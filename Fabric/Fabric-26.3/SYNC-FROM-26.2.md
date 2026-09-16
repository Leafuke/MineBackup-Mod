# Fabric 26.2 → 26.3 同步与验收指南

本文档用于在 `Fabric/Fabric-26.2` 更新后，将共享功能安全同步到
`Fabric/Fabric-26.3`。Fabric 26.2 保留了 26.x 架构与 API 基线；Fabric
26.3 保留构建配置、模组元数据和 Minecraft 26.3 官方 API 的必要版本差异。

## 初始适配基线

- Fabric 26.2 源码基线：`Fabric/Fabric-26.2/src/main`。
- 两个版本的 MineBackup 模组版本均为 `3.3.2`。
- Fabric 26.3 不维护 `src/test`；每次同步至少执行完整 Gradle 构建和本文末尾的人工验收。

## 固定版本差异

| 项目 | Fabric 26.2 | Fabric 26.3 |
| --- | --- | --- |
| Minecraft | `26.2` | `26.3` |
| Fabric Loader | `0.19.3` | `0.19.5` |
| Fabric Loom | `1.17-SNAPSHOT` | `1.17-SNAPSHOT` |
| Fabric API | `0.155.2+26.2` | `0.160.5+26.3` |
| Gradle wrapper | `9.5.1` | `9.5.1` |
| Java | `25` | `25` |
| `fabric.mod.json` Minecraft 范围 | `~26.2` | `~26.3` |
| `fabric.mod.json` Loader 下限 | `>=0.19.3` | `>=0.19.5` |

Fabric 26.3 沿用 `rootProject.name = 'minebackup'`，`LICENSE` 使用仓库根目录的 MIT 许可证。

## Minecraft 26.3 官方 API 差异

同步以下客户端类时，不要直接用 26.2 文件覆盖 26.3 文件：

- `ClientRejoinController`
  - 原版移除了 `server.getPlayerList().isAllowCommandsForAllPlayers()`，在 26.3 中改用
    `server.getGuestCommandAccess()` 查询访客指令权限。
  - `server.publishServer(...)` 方法签名精简，移除了 `GameType` 参数，由
    `server.publishServer(MinecraftServer.MultiplayerScope.LAN, gameType, allowCommands, port)`
    变更为
    `server.publishServer(MinecraftServer.MultiplayerScope.LAN, allowCommands, port)`。
  - `GameType` 依赖已从 `ClientRejoinController` 中移除。

这些替换只适配 26.3 官方局域网发布签名与权限管理机制，不应改变恢复、重试、端口回退或提示的业务语义。

## 后续同步流程

1. 确认工作区没有与同步无关的未提交修改，并记录 26.2 提交范围。
2. 比较两个版本的主源码和资源：

   ```powershell
   git diff --no-index -- Fabric/Fabric-26.2/src/main Fabric/Fabric-26.3/src/main
   ```

3. 按业务模块同步 26.2 的改动。对 `ClientRejoinController` 手工合并，保留 26.3 API
   写法。不要复制 26.2 的构建文件或元数据。
4. 若新增 Minecraft/Fabric API 调用，先对照 26.3 官方源码或 Javadoc 确认签名。
5. 使用 JDK 25 和共享 Gradle 缓存执行完整构建：

   ```powershell
   & .agents/skills/build-verify-minebackup/scripts/invoke_gradle.ps1 -Project fabric-26.3 -Clean
   ```

6. 确认 `build/libs` 中只有一个非 sources、非 dev 的发布 JAR，并检查 JAR 内：
   - `fabric.mod.json` 的模组版本、`~26.3` 和 `>=0.19.5`
   - `LICENSE_minebackup`
7. 执行 `git diff --check`，再进行人工验收。

## 人工验收清单

### 客户端与通用功能

- Minecraft 26.3 客户端可以正常启动并加载 MineBackup。
- 更新检查提示可以写入聊天栏，点击和悬停事件正常。
- `/mb help`、查询、手动保存和快速备份可以正常执行。
- 单人世界恢复成功后能够重新进入原世界并显示成功消息。
- 恢复失败或取消后能够返回世界选择界面；异常时可回退到标题界面。

### 局域网恢复

- 房主恢复后以 `MultiplayerScope.LAN` 重新开放世界。
- 优先重新使用原端口；失败时按配置重试，并可回退到随机端口。
- 访客只在收到 MineBackup 恢复踢出消息时启动自动重连。
- 自动重连等待、重试间隔和总超时符合配置；普通断线不会误触发。

### 专用服务端

- 26.3 专用服务端可在没有客户端类加载错误的情况下启动。
- 备份、查询、自动备份启停和关闭流程正常。
- 复核 sidecar 交接、世界文件释放、FolderRewind 终态信号和重启脚本。

## 发布工作流注意事项

根目录的 `release-maintained-builds.yml` 已包含 Fabric 26.3，产物名为
`minebackup-fabric-26.3-<版本>.jar`。所有维护项目的 `mod_version` 必须完全一致。
