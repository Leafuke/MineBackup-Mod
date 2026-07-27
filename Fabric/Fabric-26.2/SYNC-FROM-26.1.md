# Fabric 26.1 → 26.2 同步与验收指南

本文档用于在 `Fabric/Fabric-26.1` 更新后，将共享功能安全同步到
`Fabric/Fabric-26.2`。Fabric 26.1 是业务实现和通用文档的主源；Fabric
26.2 只保留构建配置、模组元数据和 Minecraft 客户端 API 的必要版本差异。

## 初始适配基线

- Fabric 26.1 源码基线：仓库提交 `702498c` 时的
  `Fabric/Fabric-26.1/src/main`。
- Fabric 26.2 初始适配提交：`a6e4eec`。
- 两个版本的 MineBackup 模组版本均为 `3.1.0`。
- Fabric 26.1 的 API、ADR、专用服务端恢复文档和 JUnit 测试继续作为主源，
  不复制到 Fabric 26.2。
- Fabric 26.2 不维护 `src/test`；每次同步至少执行完整 Gradle 构建和本文末尾的
  人工验收。

## 固定版本差异

| 项目 | Fabric 26.1 | Fabric 26.2 |
| --- | --- | --- |
| Minecraft | `26.1` | `26.2` |
| Fabric Loader | `0.18.4` | `0.19.3` |
| Fabric Loom | `1.15-SNAPSHOT` | `1.17-SNAPSHOT` |
| Fabric API | `0.144.0+26.1` | `0.155.2+26.2` |
| Gradle wrapper | `9.5.0` | `9.5.1` |
| Java | `25` | `25` |
| `fabric.mod.json` Minecraft 范围 | `~26.1` | `~26.2` |
| `fabric.mod.json` Loader 下限 | `>=0.18.4` | `>=0.19.3` |

Fabric 26.2 使用官方新模板的 `rootProject.name = 'minebackup'`，不需要复制
26.1 的 `archives_base_name` 配置。26.2 的 `LICENSE` 使用仓库根目录的 MIT
许可证；不要从当前 26.1 目录复制其 CC0 文件。

同步前通过 [Fabric Develop](https://fabricmc.net/develop/) 和
[Fabric 26.2 示例项目](https://github.com/FabricMC/fabric-example-mod/tree/26.2)
复核 Loader、Loom、Fabric API 和 Gradle wrapper。只有确认 26.2 推荐版本发生
变化时，才更新上表和对应工程属性。

## Minecraft 26.2 客户端 API 差异

同步以下三个客户端类时，不要直接用 26.1 文件覆盖 26.2 文件：

- `ClientRejoinController`
  - `client.setScreen(screen)` → `client.gui.setScreen(screen)`
  - `server.publishServer(gameType, allowCommands, port)` →
    `server.publishServer(MinecraftServer.MultiplayerScope.LAN, gameType, allowCommands, port)`
- `LanAutoReconnectController`
  - `client.screen` → `client.gui.screen()`
  - `ConnectScreen.startConnecting(...)` 的父界面参数使用
    `client.gui.screen()`
- `MineBackupClient`
  - `client.gui.getChat()` → `client.gui.hud.getChat()`

这些替换只适配 26.2 的界面所有权和局域网可见范围，不应改变恢复、重试、端口
回退、消息判断或更新提示的业务语义。

## 后续同步流程

1. 确认工作区没有与同步无关的未提交修改，并记录 26.1 新旧提交范围。
2. 比较两个版本的主源码和资源：

   ```powershell
   git diff --no-index -- Fabric/Fabric-26.1/src/main Fabric/Fabric-26.2/src/main
   ```

3. 按业务模块同步 26.1 的改动。对上述三个客户端类手工合并，并保留 26.2 API
   写法。不要复制 26.1 的构建文件、元数据、许可证、文档或测试目录。
4. 若新增 Minecraft/Fabric API 调用，先对照 26.2 官方源码或 Javadoc 确认签名，
   再通过编译错误补齐剩余差异。
5. 搜索不应出现在 26.2 中的旧客户端访问方式：

   ```powershell
   rg -n 'client\.screen|client\.setScreen|client\.gui\.getChat' `
     Fabric/Fabric-26.2/src/main
   ```

6. 使用 JDK 25 和共享 Gradle 缓存执行完整构建。当前开发机的命令为：

   ```powershell
   $env:JAVA_HOME='D:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
   $env:GRADLE_USER_HOME='D:\Programs\.gradle'
   Set-Location Fabric/Fabric-26.2
   .\gradlew.bat clean build --warning-mode all
   ```

   `gradlew.bat` 不硬编码本机路径；其他机器只需设置自己的 `JAVA_HOME` 和
   `GRADLE_USER_HOME`。

7. 确认 `build/libs` 中只有一个非 sources、非 dev 的发布 JAR，并检查 JAR 内：
   - `fabric.mod.json` 的模组版本、`~26.2` 和 `>=0.19.3`
   - `LICENSE_minebackup`
8. 执行 `git diff --check`，再进行下述人工验收。

## 人工验收清单

### 客户端与通用功能

- Minecraft 26.2 客户端可以正常启动并加载 MineBackup。
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

- 26.2 专用服务端可在没有客户端类加载错误的情况下启动。
- 备份、查询、自动备份启停和关闭流程正常。
- 按
  [`../Fabric-26.1/docs/MANUAL-DEDICATED-RESTORE-CHECKLIST.md`](../Fabric-26.1/docs/MANUAL-DEDICATED-RESTORE-CHECKLIST.md)
  复核 sidecar 交接、世界文件释放、FolderRewind 终态信号和重启脚本。

## 发布工作流注意事项

根目录的 `release-maintained-builds.yml` 已包含 Fabric 26.2，产物名为
`minebackup-fabric-26.2-<版本>.jar`。该工作流沿用仓库既有规则：所有维护项目
的 `mod_version` 必须完全一致。当前其他加载器仍为 2.1.x，而 Fabric 26.1/26.2
为 3.1.0，因此在其他维护项目完成版本同步前，统一发布预检会主动终止；这不是
Fabric 26.2 构建失败。
