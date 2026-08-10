![Forge Version](https://img.shields.io/badge/Forge-1.20-blue?logo=minecraft)
![Fabric Version](https://img.shields.io/badge/Fabric-1.21%20~%2026.2-blue?logo=minecraft)
![Neoforge Version](https://img.shields.io/badge/Neoforge-1.21%20~%2026.1-blue?logo=minecraft)
![License](https://img.shields.io/badge/license-MIT-blue.svg)
[![English README](https://img.shields.io/badge/README-English-blue)](README.md)

---

### **⚠️ 重要提示：这是联动模组！**

请注意：本模组是 **MineBackup 主程序** 或 **FolderRewind** 的联动组件，**无法独立运行**。您必须先下载并运行主程序，本模组才能正常工作。

并且为了程序间正常的通信，电脑上需要存在 KnotLink 服务端（本机 `127.0.0.1:6372/6376`）。https://github.com/KnotLink-Protocol/KnotLinkService/releases

### **✅ 专用服务端还原已支持**

从当前版本开始，本模组在专用服务端上**完整支持**热备份与热还原。还原时模组会启动内置的纯 JDK Sidecar 进程，等待世界文件安全释放后再交接给重启脚本，无需再额外安装 [MineBackupPlugin](https://modrinth.com/plugin/minebackupplugin)（该插件仍可用于 Spigot/Paper 服务端场景）。详见下方[专用服务端还原](#-专用服务端还原)一节。

### **➡️ [点此下载 MineBackup 主程序](https://github.com/Leafuke/MineBackup/releases)**

对于 Windows 端用户，推荐使用 [FolderRewind](https://apps.microsoft.com/detail/9nwsdgxdqws4) + [MineRewind 插件](https://github.com/Leafuke/FolderRewind-Plugin-Minecraft/releases) 组合来实现同样的功能，FolderRewind 是 MineBackup 桌面应用的精神续作。

### **📚 FolderRewind Minecraft 文档**

请先阅读 [Minecraft 联动生态总览](https://folderrewind.top/docs/guides/minecraft/overview)，其中介绍了本模组与 [MineBackupPlugin](https://folderrewind.top/docs/guides/minecraft/minebackup-plugin)、[Death Rewind](https://folderrewind.top/docs/guides/minecraft/death-rewind)、[Just Enough Accidents](https://folderrewind.top/docs/guides/minecraft/just-enough-accidents) 的关系；[MineBackup-Mod 说明](https://folderrewind.top/docs/guides/minecraft/minebackup-mod) 介绍当前指令和专用服务端 Sidecar 流程。

<a href="https://apps.microsoft.com/detail/9nwsdgxdqws4?referrer=appbadge&mode=direct">
	<img src="https://get.microsoft.com/images/en-us%20dark.svg" width="200"/>
</a>

---

## 这是什么？

这个轻量级的模组是连接功能强大的 **MineBackup / FolderRewind 桌面应用**与 **Minecraft 游戏本身**的桥梁。它让你可以在不离开游戏的情况下，享受到主程序带来的所有便利：游戏内指令、可点击的备份列表、实时通知，以及专用服务端上的安全热还原。

### ✨ 本模组为您提供：

* **丰富的游戏内指令**：使用 `/mb` 系列指令，直接在聊天框里管理你的存档备份与还原。
* **可交互的当前世界备份列表**：`/mb list backups` 会显示分页列表，附带时间戳、备份注释，以及可直接点击的 `[还原]` 按钮，无需手动输入文件名。
* **实时的操作通知**：无论是备份开始、成功还是失败，你都会在游戏聊天框里收到来自主程序的实时消息。
* **无缝的热备份支持**：当主程序需要进行“热备份”（即在游戏运行时备份）时，本模组会自动在后台执行一次安全的、完整的世界保存（等同于 `/save-all`），确保你备份的永远是最新的进度。
* **单机/局域网热还原**：直接在游戏内还原世界，随后自动重新加入，无需手动重启客户端。
* **专用服务端安全还原**：模组会协调保存所有玩家、启动内置 Sidecar、安全停止服务器，并在世界文件确认释放后再执行重启脚本，全程无需人工干预。
* **FolderRewind 远程触发还原**：FolderRewind 的用户界面可以直接广播还原请求，模组会走完全相同的倒计时、确认与安全校验流程。

## 🚀 安装指南

1.  **下载主程序**：确保你已经从上方的链接下载了 `MineBackup`/`FolderRewind` 主程序，并且它可以在你的电脑上正常运行。
2.  **下载 MineRewind 插件**：**如果你使用 FolderRewind**，建议下载并安装 MineRewind 插件，并创建 `Minecraft Saves` 类型的配置而不是 `Default`。可从 [GitHub Releases](https://github.com/Leafuke/FolderRewind-Plugin-Minecraft/releases) 下载。
3.  **下载并安装 KnotLink 服务端**：为了实现程序间的通信，你需要在电脑上安装 KnotLink 服务端。可从 [GitHub Releases](https://github.com/hxh230802/KnotLink/releases) 下载。（Linux/macOS 无需此步骤）
4.  **在主程序内启用 KnotLink 服务**：MineBackup 默认开启，FolderRewind 默认关闭，如果你使用**后者**，需要手动在设置中开启。
5.  **下载本模组**：从 **[Releases](https://github.com/Leafuke/MineBackup/releases)** 或其他模组下载页面找到与你的加载器、Minecraft 版本相匹配的 `minebackup-x.x.x.jar` 文件（参见下方版本对照表）。
6.  **安装模组**：单机或局域网场景下，将下载的 `.jar` 文件放入 Minecraft 客户端的 `mods` 文件夹。专用服务端场景下，将其安装到服务端的 `mods` 文件夹；客户端可选安装。
7.  **同时运行**：启动你的 Minecraft 游戏或服务器。为了让模组正常工作，请务必**在玩游戏的同时，让 `MineBackup`/`FolderRewind` 主程序在后台运行**。

## 🧩 版本对照表

| 加载器 | Minecraft 版本 | 映射方式 |
| :--- | :--- | :--- |
| Fabric | 1.21 ~ 1.21.8 | Yarn |
| Fabric | 1.21.9 ~ 1.21.10 | Mojang |
| Fabric | 1.21.11 | Mojang |
| Fabric | 26.1 ~ 26.1.2 | 官方（无混淆） |
| Fabric | 26.2 | 官方（无混淆） |
| NeoForge | 1.21 ~ 1.21.8 | Parchment |
| NeoForge | 26.1 ~ 26.1.2 | 官方（无混淆） |
| Forge | 1.20 ~ 1.20.4 | 官方 |

## 📖 指令参考

Fabric 26.1 按能力使用 Minecraft 原生权限节点，并以 OP 等级 2 作为兼容后备；单机/LAN 存档房主和服务端控制台保持直通，帮助与补全只显示有权使用的指令。详见 [Fabric 26.1 配置与权限参考](Fabric/Fabric-26.1/docs/CONFIGURATION-zh.md)。其他维护目标在完成移植前保持原有权限行为。

| 指令 | 参数 | 描述 |
| :--- | :--- | :--- |
| **/mb save** | (无) | 在游戏内手动执行一次完整的世界保存，效果等同于 `/save-all`。 |
| **/mb backup** | `[注释]` | 为当前世界执行备份，可附带一段可选注释。 |
| **/mb restore** | `[文件名]` | 为当前世界执行热还原；不填写文件名则自动选择最新备份。 |
| **/mb confirm** | (无) | 在倒计时结束前立即确认并提交正在等待的还原。 |
| **/mb stop** | (无) | 取消正在倒计时、尚未提交的还原。 |
| **/mb list backups** | `[current [页码]]` | 显示当前世界的可交互备份列表，附带时间戳、注释与可点击的还原按钮，支持分页。 |
| **/mb list configs** | (无) | 列出你在主程序中设置的所有配置方案及其 ID。 |
| **/mb list folders** | `<config_id>` | 列出指定配置下的所有文件夹。 |
| **/mb list backups** | `<config_id> <folder>` | 列出指定配置、指定文件夹下的所有备份文件。 |
| **/mb target backup** | `<config_id> <folder> [注释]` | 命令主程序为指定的非当前世界目标创建一次备份。 |
| **/mb target restore** | `<config_id> <folder> <文件名>` | 命令主程序还原指定的非当前世界目标。**仅限单机/局域网**；专用服务端会拒绝该指令。 |
| **/mb auto start** | `<分钟> [backup\|remind]` | Fabric 26.1：保存绑定当前世界的自动备份或提醒计划；默认模式为 `backup`。 |
| **/mb auto status** | (无) | Fabric 26.1：显示当前世界的模式、间隔和下次触发时间。 |
| **/mb auto stop** | (无) | Fabric 26.1：只停止当前世界的自动化计划。 |
| **/mb help** | `[指令]` | 显示指令帮助与用法示例。 |

Fabric 26.1 将自动化计划保存在存档外，因此还原世界不会同时还原过期计划。
`remind` 模式适合希望避开不抗卸载红石机器危险时刻的玩家。完整默认值、合法范围、
迁移规则和权限节点见[配置参考](Fabric/Fabric-26.1/docs/CONFIGURATION-zh.md)。

### **💡 使用示例**

1.  **备份当前世界**
    * 输入 `/mb backup 准备打末影龙`
        > 聊天框返回：`[MineBackup] 命令已发送: BACKUP`
        > （稍等片刻后）备份成功，文件命名为类似 `[Full][2026-07-30_11-29-54]world [准备打末影龙].7z` 的形式。

2.  **浏览并还原备份**
    * 输入 `/mb list backups`，聊天框会显示分页列表，每一条都附带时间戳、注释以及可点击的 `[还原]` 按钮。
    * 直接点击某条记录旁的 `[还原]`，或手动执行 `/mb restore "[Full][2026-07-30_11-29-54]world [准备打末影龙].7z"`（文件名可自动补全）。
    * 单机/局域网场景下，还原会经过倒计时，然后自动踢出并重新加入。专用服务端会走 Sidecar 安全还原流程（见下文）。

3.  **管理非当前世界的备份目标**
    * 输入 `/mb list configs` 查看配置方案，再用 `/mb list folders <config_id>` 查看该方案下的文件夹。
    * 执行 `/mb target backup <config_id> <folder> 注释内容` 备份该目标；单机/局域网下可用 `/mb target restore` 还原。

## 🖥️ 专用服务端还原

从当前版本开始，专用服务端上的 `/mb restore` 由内置的纯 JDK **Sidecar** 进程协调，全程自动化，无需额外安装插件：

1. 模组校验还原前置条件（重启脚本、会话目录、当前操作状态）后，向主程序提交还原请求。
2. 收到确认后，保存所有玩家与已加载世界，原子化写入交接状态文件，并启动 Sidecar 进程。
3. Sidecar 确认已成功订阅 KnotLink 后，服务器才会踢出玩家并安全停止（`server.halt(false)`）。
4. Sidecar 等待父进程完全退出，并连续三次确认世界文件已释放，才会向主程序确认释放。
5. 只有在收到主程序明确的成功、失败或取消终态后，Sidecar 才会启动一次配置好的重启脚本；未知结果会让服务器保持离线，绝不假设静默等于安全失败。

默认配置（写入 `config/minebackup.properties`）：

```properties
dedicatedRestore.mode=SIDECAR
dedicatedRestore.restartScript=
dedicatedRestore.sidecarStartTimeoutSeconds=5
dedicatedRestore.worldReleaseTimeoutSeconds=8
dedicatedRestore.operationTimeoutSeconds=3600
```

将 `mode` 设为 `DISABLED` 可在预检阶段直接拒绝专用服务端还原请求。`restartScript` 留空时，模组会在服务器工作目录中自动寻找唯一的启动脚本（Windows 下为 `start.bat`/`start.cmd`/`run.bat`/`run.cmd`，Linux/macOS 下为 `start.sh`/`run.sh`）；找到零个或多个候选都会在联系主程序、踢出玩家或停止服务器之前直接拒绝。

**请勿**同时使用会在进程退出后立即重启 JVM 的面板或包装脚本，这会和还原流程抢跑。详见 [`Fabric/Fabric-26.1/docs/DEDICATED-RESTORE.md`](Fabric/Fabric-26.1/docs/DEDICATED-RESTORE.md)。

如果你运行的是 Spigot/Paper 而非模组化服务端，请改用姊妹项目 [MineBackupPlugin](https://modrinth.com/plugin/minebackupplugin)，它提供同样基于 Sidecar 的还原能力。

## ❓ 常见问题

* **问题：我输入指令后，聊天框提示“通信失败”或类似的错误。**
    * **答案：** 请检查并确保 `MineBackup`/`FolderRewind` 主程序正在你的电脑后台运行，且 KnotLink 服务端（Windows）可用。本模组的所有功能都依赖于和主程序的网络通信。

* **问题：这个模组可以单独使用吗？**
    * **答案：** 不可以。它是一个“桥梁”，没有主程序，它什么也做不了。

* **问题：专用服务端还需要额外安装插件才能还原吗？**
    * **答案：** 不需要。本模组内置的 Sidecar 已能在纯模组服务端上完成安全还原。如果你运行的是 Spigot/Paper 服务端，请改用 MineBackupPlugin。

## 📄 许可证

本项目采用 [MIT License](LICENSE) 开源。详情请访问主项目仓库。
