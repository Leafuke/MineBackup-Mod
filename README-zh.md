![Forge Version](https://img.shields.io/badge/Forge-1.20%2B-blue?logo=minecraft)
![Fabric Version](https://img.shields.io/badge/Fabric-1.20%2B-blue?logo=minecraft)
![Neoforge Version](https://img.shields.io/badge/Neoforge-1.20%2B-blue?logo=minecraft)
![License](https://img.shields.io/badge/license-MIT-blue.svg)

---

### **⚠️ 重要提示：这是联动模组！**

请注意：本模组是 **MineBackup 主程序**的联动组件，**无法独立运行**。您必须先下载并运行主程序，本模组才能正常工作。
并且为了程序间正常的通信，电脑上需要存在 KnotLink 服务端。https://github.com/hxh230802/KnotLink/releases
**例外**：Linux/MacOS 用户无需安装 KnotLink 服务端。

### **⚠️ 重要提示：不适用于专用服务端 **

自 2.0.0 版本开始，模组仅适用于 Minecraft 客户端和集成服务器（单人世界）。它不再支持专用服务端环境。如果您需要在专用服务端上使用备份与还原功能，请使用 [MineBackup-Plugin 联动插件](https://modrinth.com/plugin/minebackupplugin)。

### **➡️ [点此下载必需的 MineBackup 主程序](https://github.com/Leafuke/MineBackup/releases)**

对于Windows端用户，推荐使用 [FolderRewind](https://apps.microsoft.com/detail/9nwsdgxdqws4) + [MineRewind](https://github.com/Leafuke/FolderRewind-Plugin-Minecraft/releases) 插件组合来实现同样的功能：

<a href="https://apps.microsoft.com/detail/9nwsdgxdqws4?referrer=appbadge&mode=direct">
	<img src="https://get.microsoft.com/images/en-us%20dark.svg" width="200"/>
</a>

---

## 这是什么？

这个轻量级的模组是连接功能强大的 **MineBackup 桌面应用**与 **Minecraft 游戏本身**的桥梁。它让你可以在不离开游戏的情况下，享受到 MineBackup 带来的所有便利。

### ✨ 本模组为您提供：

* **丰富的游戏内指令**: 使用 `/mb` 系列指令，直接在聊天框里管理你的存档备份。你也可以使用快捷键 `Alt + Ctrl + S` 来备份你的存档！
* **实时的备份通知**: 无论是备份开始、成功还是失败，你都会在游戏聊天框里收到来自主程序的实时消息。
* **无缝的热备份支持**: 当主程序需要进行“热备份”（即在游戏运行时备份）时，本模组会自动在后台执行一次安全的、完整的世界保存（等同于 `/save-all`），确保你备份的永远是最新的进度。

## 🚀 安装指南

1.  **下载主程序**: 确保你已经从上方的链接下载了 `MineBackup`/`FolderRewind` 主程序，并且它可以在你的电脑上正常运行。
2.  **下载 MineRewind 插件**：**如果你使用 FolderRewind**，那么你最好下载并安装 MineRewind 插件并创建 `Minecraft Saves` 类型的配置而不是 `Default`。你可以从 [GitHub Releases](https://github.com/Leafuke/FolderRewind-Plugin-Minecraft/releases) 下载该插件。
3.  **下载并安装 KnotLink 服务端**：为了实现程序间的通信，你需要在电脑上安装 KnotLink 服务端。你可以从 [GitHub Releases](https://github.com/hxh230802/KnotLink/releases) 下载。
4.  **在主程序内启动 KnotLink服务**：MineBackup 默认开启，FolderRewind 默认关闭，如果你使用**后者**，需要手动在设置中开启。
5.  **下载本模组**: 从 **[Releases](https://github.com/Leafuke/MineBackup/releases)** 或其他模组下载页面找到与主程序版本匹配的 `minebackup-x.x.x.jar` 文件。
6.  **安装模组**: 将下载的 `.jar` 文件放入你的 Minecraft 客户端的 `mods` 文件夹中。
7.  **同时运行**: 启动你的 Minecraft 游戏或服务器。为了让模组正常工作，请务必**在玩游戏的同时，让 `MineBackup`/`FolderRewind` 主程序在后台运行**。

## 📖 指令参考

所有指令都需要管理员（OP）权限。特别地，对于单人存档，这些指令不需要OP权限。

| 指令 | 参数 | 描述 |
| :--- | :--- | :--- |
| **/mb quickbackup** | `[注释]` | 为当前世界执行备份 |
| **/mb quickrestore** | `[文件名]` | 为当前世界执行热还原，不填写文件名则自动选择最新的备份文件 |
| **/mb save** | (无) | 在游戏内手动执行一次完整的世界保存，效果等同于 `/save-all`。 |
| **/mb list_configs** | (无) | 列出你在 MineBackup 主程序中设置的所有配置方案及其ID。 |
| **/mb list_worlds** | `<config_id>` | 列出指定配置下的所有世界及其索引号（index）。 |
| **/mb list_backups** | `<config_id> <world_index>` | 列出指定世界的所有可用备份文件。 |
| **/mb backup** | `<config_id> <world_index> [注释]` | 命令主程序为指定世界创建一次备份。可以附上一段可选的注释。 |
| **/mb restore** | `<config_id> <world_index> <文件名>` | 命令主程序用指定的备份文件来还原世界。如果你希望还原当前世界，务必使用 **/mb quickrestore** |
| **/mb auto** | `<config_id> <world_index> <internal_time>` | 请求 MineBackup 执行自动备份任务，间隔 internal_time 分钟进行自动备份 |
| **/mb stop** | `<config_id> <world_index>` | 请求 MineBackup 停止自动备份任务 |

### **💡 使用示例**

假设你想为你服务器的主世界创建一个备份：

1.  **第一步：找到配置和世界**
    * 输入 `/mb list_configs` 来查看你的配置方案。
        > 聊天框返回: `可用配置列表: - ID: 1, 名称: 生存服务器`
    * 输入 `/mb list_worlds 1` 来查看 "生存服务器" 配置下的世界。
        > 聊天框返回: `配置 1 的世界列表: - 索引: 0, 名称: world`

2.  **第二步：执行备份**
    * 现在你知道了配置ID是 `1`，世界索引是 `0`。
    * 输入 `/mb quickbackup 准备打末影龙！`
        > 聊天框返回: `[MineBackup] 世界 'world' 的备份任务已开始...`
        > (稍等片刻)
        > `[MineBackup] 备份成功! 世界 'world' 已保存为 [Full][2025-08-11_12-33-00]world [准备打末影龙！].7z`

3.  **(如果需要) 第三步：执行还原**
    * 执行 `/mb quickrestore "[Full][2025-08-11_12-33-00]world [准备打末影龙！].7z"`。（**提示**：文件名可自动补全）

## ❓ 常见问题

* **问题：我输入指令后，聊天框提示“指令失败”、“无响应”或类似的错误。**
    * **答案：** 请检查并确保 `MineBackup`/`FolderRewind` 主程序正在你的电脑后台运行。本模组的所有功能都依赖于和主程序的网络通信。

* **问题：这个模组可以单独使用吗？**
    * **答案：** 不可以。它是一个“桥梁”，没有主程序，它什么也做不了。

## 📄 许可证

本项目采用 [MIT License](https://github.com/Leafuke/MineBackup/blob/main/LICENSE) 开源。详情请访问主项目仓库。
