![Forge Version](https://img.shields.io/badge/Forge-1.20%2B-blue?logo=minecraft)
![Fabric Version](https://img.shields.io/badge/Fabric-1.20%2B-blue?logo=minecraft)
![Neoforge Version](https://img.shields.io/badge/Neoforge-1.20%2B-blue?logo=minecraft)
![License](https://img.shields.io/badge/license-MIT-blue.svg)
[![中文说明](https://img.shields.io/badge/README-中文-blue)](README-zh.md)

---

### **⚠️ Important: This is a Companion Mod!**

Please note: This mod is a **companion component** for the **MineBackup desktop application** or **FolderRewind** and **cannot function independently**. You must first download and run the main application for this mod to work properly.

Additionally, for proper inter-process communication, the KnotLink server must be installed on your computer. https://github.com/hxh230802/KnotLink/releases

### **➡️ [Download the REQUIRED MineBackup Desktop Application Here](https://github.com/Leafuke/MineBackup/releases)**

or consider using **FolderRewind** with plugin [MineRewind](https://github.com/Leafuke/FolderRewind-Plugin-Minecraft/releases)

<a href="https://apps.microsoft.com/detail/9nwsdgxdqws4?referrer=appbadge&mode=direct">
	<img src="https://get.microsoft.com/images/en-us%20dark.svg" width="200"/>
</a>

---

## What is This?

This lightweight Forge mod serves as a bridge between the powerful **MineBackup desktop application** and **Minecraft itself**. It allows you to enjoy all the conveniences of MineBackup without ever leaving your game.

### ✨ This Mod Provides:

* **Rich In-Game Commands**: Use the `/mb` command series to manage your world saves directly from the chat. You can also use the hotkey `Alt + Ctrl + S` to back up your world!
* **Real-Time Backup Notifications**: Receive real-time messages from the main application in your game chat—whether a backup starts, succeeds, or fails.
* **Seamless Live Backup Support**: When the main application needs to perform a "live backup" (i.e., while the game is running), this mod automatically triggers a safe, complete world save (equivalent to `/save-all`) in the background, ensuring your backup always captures the latest progress.

## 🚀 Installation Guide

1.  **Download the Main Application**: Ensure you have downloaded the `MineBackup.exe` desktop application from the link above and that it runs correctly on your system.
2.  **Download This Mod**: Get the version-matched `minebackup-x.x.x.jar` file from the **[Releases](https://github.com/Leafuke/MineBackup/releases)** page or other mod distribution platforms.
3.  **Install the Mod**: Place the downloaded `.jar` file into your Minecraft client's `mods` folder.
4.  **Run Simultaneously**: Launch your Minecraft game or server. For the mod to function, you **must have the `MineBackup.exe` desktop application running in the background while playing**.

For step 1 to 2, you can also consider using **FolderRewind** with plugin **MineRewind** as an alternative. As follows:
1. Download and install **FolderRewind** from Microsoft Store.
2. Download the **MineRewind** plugin from [GitHub Releases](https://github.com/Leafuke/FolderRewind-Plugin-Minecraft/releases).
3. Add the **MineRewind** plugin to FolderRewind.
and then, the following steps are the same as above.

## 📖 Command Reference

All commands require operator (OP) permissions for multiplayer servers. But for single-player worlds, OP is not required.

| Command | Parameters | Description |
| :--- | :--- | :--- |
| **/mb save** | (none) | Manually performs a full world save in-game, equivalent to `/save-all`. |
| **/mb list_configs** | (none) | Lists all your configured backup profiles and their IDs from the MineBackup desktop application. |
| **/mb list_worlds** | `<config_id>` | Lists all worlds under the specified configuration profile along with their indices. |
| **/mb list_backups** | `<config_id> <world_index>` | Lists all available backup files for the specified world. |
| **/mb backup** | `<config_id> <world_index> [comment]` | Instructs the main application to create a backup for the specified world. An optional comment can be added. |
| **/mb restore** | `<config_id> <world_index> <filename>` | Instructs the main application to restore the world using the specified backup file. **This is a dangerous operation that will overwrite your current world!** |
| **/mb auto** | `<config_id> <world_index> <internal_time>` | Requests MineBackup to start an automatic backup task, backing up every `internal_time` minutes. |
| **/mb stop** | `<config_id> <world_index>` | Requests MineBackup to stop the automatic backup task. |
| **/mb quicksave** | (none) | Performs a backup for the current world. |

### **💡 Usage Example**

Let's say you want to create a backup for your server's main world:

1.  **Step 1: Find the Configuration and World**
    * Type `/mb list_configs` to see your configuration profiles.
        > Chat returns: `Available Configurations: - ID: 1, Name: Survival Server`
    * Type `/mb list_worlds 1` to see the worlds under the "Survival Server" profile.
        > Chat returns: `Worlds for Config 1: - Index: 0, Name: world`

2.  **Step 2: Perform the Backup**
    * Now you know the config ID is `1` and the world index is `0`.
    * Type `/mb backup 1 0 Preparing for Ender Dragon!`
        > Chat returns: `[MineBackup] Backup task for world 'world' started...`
        > (After a moment)
        > `[MineBackup] Backup successful! World 'world' saved as [Full][2025-08-11_12-33-00]world [Preparing for Ender Dragon!].7z`

3.  **(If Needed) Step 3: Perform a Restore**
    * First, use `/mb list_backups 1 0` to list all backup files.
    * Find the filename you want to restore, e.g., `[Full][2025-08-11_12-33-00]world [Preparing for Ender Dragon!].7z`.
    * Execute `/mb restore 1 0 "[Full][2025-08-11_12-33-00]world [Preparing for Ender Dragon!].7z"`. (**Tip**: If the filename contains spaces, enclose it in double quotes `""`).

## ❓ Frequently Asked Questions

* **Q: When I use a command, the chat says "Command failed", "No response", or a similar error.**
    * **A:** Please check and ensure the `MineBackup.exe` desktop application is running in the background on your computer. All features of this mod rely on network communication with the main application.

* **Q: Can this mod be used by itself?**
    * **A:** No. It is a "bridge" and cannot do anything without the main application.

## 📄 License

This project is licensed under the [MIT License](https://github.com/Leafuke/MineBackup/blob/main/LICENSE). For details, please visit the main project repository.
