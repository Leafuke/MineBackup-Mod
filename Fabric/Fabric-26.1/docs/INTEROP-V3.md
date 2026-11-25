# Fabric 26.1 联动候选 / Fabric 26.1 interoperability candidate

本目标使用 Minecraft 联动 v3。游戏内 `/mb status` 与标题/世界选择页的「联动状态」可重新检测本机后端。在游戏里有多个已核实配置时，执行 `/mb status select <候选键>`，或在联动状态页选定配置。游戏会记住所选域与世界的绑定。没有匹配的世界时，可使用已选后端的目录查询和显式目标命令。原配置离线不会把备份转交另一软件。

在备份软件中确认已启用联动，并为当前世界配置确切来源。FolderRewind 还需要提供 v3 能力的 MineRewind 插件。对旧路由的探测限于只读能力查询。保护备份以软件证实的精确备份和持久保护标记为准。

还原期间请保持目标世界关闭。完成变更后，游戏按当前步骤领取一次重开许可；若结果未确认，先在原软件查看事务，避免重新提交还原。专用服务器的脚本启动成功表示子进程已创建；管理器可能自行重启 JVM 的部署应先取消这类抢先启动行为。交接记录位于世界外的 `config/minebackup/restart/v3/`，原交接所有者或后端确认后更新，正常诊断无需编辑记录。

This Fabric 26.1 candidate discovers v3 backends on the local machine. Run `/mb status` or open Link Status on the title or world selection screen to check the selected product and configuration. When more than one configuration matches, select the intended one. If its endpoint is offline, the mod keeps that choice.

A backup may continue after the observation channel disconnects. Keep the world closed if a restore result is unknown. Check the original operation in the selected software before attempting another restore or starting a server. A sidecar reporting that its script started does not establish that the server is accepting players.

Wire fixtures are pinned to FolderRewind commit `877b31e103d60985b5e372781b41720b65582bee`, file `tests/Fixtures/MinecraftInteropV3/wire.json`, SHA-256 `0964d7491c11d6fe5c1405820a0a47d83013bc1574b62e0f3fc17cf2e215512d`. The target-local resource is used in offline tests. The seven other maintained targets continue on their current protocols.
