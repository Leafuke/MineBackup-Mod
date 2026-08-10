![Forge Version](https://img.shields.io/badge/Forge-1.20-red?logo=minecraft)
![Fabric Version](https://img.shields.io/badge/Fabric-1.21%20~%2026.2-blue?logo=minecraft)
![Neoforge Version](https://img.shields.io/badge/Neoforge-1.21%20~%2026.1-orange?logo=minecraft)
![License](https://img.shields.io/badge/license-MIT-blue.svg)
[![中文说明](https://img.shields.io/badge/README-中文-blue)](README-zh.md)

---

### **⚠️ Important: This is a Companion Mod!**

Please note: This mod is a **companion component** for the **MineBackup desktop application** or **FolderRewind** and **cannot function independently**. You must first download and run the main application for this mod to work properly.

Additionally, for proper inter-process communication, the KnotLink server must be installed on your computer. https://github.com/KnotLink-Protocol/KnotLinkService/releases

### **✅ Dedicated Servers Are Supported**

MineBackup now supports hot restore on dedicated servers, not just singleplayer/LAN. When you run `/mb restore` on a dedicated server, the mod safely saves the world, hands off to a bundled restart sidecar, and restarts the server through your own start script once the restore finishes. See [Dedicated Server Restore](#️-dedicated-server-restore) below for setup details.

If you run a Spigot/Paper server instead, use [MineBackupPlugin](https://modrinth.com/plugin/minebackupplugin), which provides the same restore flow for the Bukkit family.

### **➡️ [Download the REQUIRED MineBackup Desktop Application Here](https://github.com/Leafuke/MineBackup/releases)**

For Windows users, we recommend [FolderRewind](https://apps.microsoft.com/detail/9nwsdgxdqws4) with the [MineRewind](https://github.com/Leafuke/FolderRewind-Plugin-Minecraft/releases) plugin rather than the standalone MineBackup desktop app.

### **📚 FolderRewind Minecraft Documentation**

The [Minecraft integration overview](https://folderrewind.top/en/docs/guides/minecraft/overview) explains how this mod fits with [MineBackupPlugin](https://folderrewind.top/en/docs/guides/minecraft/minebackup-plugin), [Death Rewind](https://folderrewind.top/en/docs/guides/minecraft/death-rewind), and [Just Enough Accidents](https://folderrewind.top/en/docs/guides/minecraft/just-enough-accidents). The [MineBackup-Mod guide](https://folderrewind.top/en/docs/guides/minecraft/minebackup-mod) covers the current commands and dedicated-server Sidecar flow.

<a href="https://apps.microsoft.com/detail/9nwsdgxdqws4?referrer=appbadge&mode=direct">
	<img src="https://get.microsoft.com/images/en-us%20dark.svg" width="200"/>
</a>

---

## What is This?

This lightweight mod is a bridge between the **MineBackup desktop application** (or **FolderRewind**) and **Minecraft itself**. It lets you manage saves, trigger restores, and stay informed about backup status without ever leaving your game.

### ✨ This Mod Provides:

* **Rich In-Game Commands**: Use the `/mb` command series to manage world saves directly from chat — backup, restore, browse history, and schedule automatic backups.
* **Interactive Backup Browser**: `/mb list backups current` pages through your current world's backups right in chat, showing timestamps and comments (parsed automatically from standard backup file names) with a clickable **[Restore]** button next to each entry.
* **Restore Safety Countdown**: Before a restore actually runs, MineBackup can count down a configurable number of seconds. Use `/mb stop` to cancel or `/mb confirm` to skip ahead and restore immediately.
* **Full Dedicated Server Support**: Backup, restore, and browsing all work the same way on a dedicated server as they do in singleplayer, coordinated through a bundled restart sidecar (see below).
* **Real-Time Backup Notifications**: Receive real-time messages from the main application in your game chat — whether a backup starts, succeeds, or fails.
* **Seamless Live Backup Support**: When the main application needs to perform a "live backup" (i.e., while the game is running), this mod automatically triggers a safe, complete world save (equivalent to `/save-all`) in the background, ensuring your backup always captures the latest progress.
* **FolderRewind-Triggered Restore**: If you use FolderRewind's own UI to pick a backup, it can signal this mod directly to start the same hot-restore flow in-game, so both control surfaces stay in sync.

## 🖥️ Supported Versions

| Loader | Minecraft Versions | Mappings |
| :--- | :--- | :--- |
| Fabric | 1.21 – 1.21.8 | Yarn |
| Fabric | 1.21.9 – 1.21.10 | Mojang (official) |
| Fabric | 1.21.11 | Mojang (official) |
| Fabric | 26.1 – 26.1.2 | Mojang (unobfuscated) |
| Fabric | 26.2 | Mojang (unobfuscated) |
| NeoForge | 1.21 – 1.21.8 | Parchment |
| NeoForge | 26.1 – 26.1.2 | Mojang (unobfuscated) |
| Forge | 1.20 – 1.20.4 | Mojang (official) |

## 🚀 Installation Guide

1.  **Download the Main Application**: Ensure you have downloaded the `MineBackup`/`FolderRewind` desktop application from the link above and that it runs correctly on your system.
2.  **Download MineRewind Plugin**: **If you use FolderRewind**, it is strongly recommended to install the MineRewind plugin and create a `Minecraft Saves` type configuration instead of `Default`. You can download this plugin from [GitHub Releases](https://github.com/Leafuke/FolderRewind-Plugin-Minecraft/releases).
3.  **Download and Install KnotLink Server**: For inter-process communication, install the KnotLink server on your computer. You can download it from [GitHub Releases](https://github.com/hxh230802/KnotLink/releases).
4.  **Enable KnotLink Service in the Main Application**: It is enabled by default in MineBackup but disabled by default in FolderRewind. If you use **FolderRewind**, enable it manually in settings.
5.  **Download This Mod**: Get the version-matched `minebackup-x.x.x.jar` file for your loader and Minecraft version from the **[Releases](https://github.com/Leafuke/MineBackup/releases)** page or another mod distribution platform.
6.  **Install the Mod**: For single-player or LAN, place the downloaded `.jar` file in the Minecraft client's `mods` folder. For dedicated servers, install it in the server's `mods` folder; client installation is optional.
7.  **Run Simultaneously**: Launch your Minecraft game or server. For the mod to function, you **must have the `MineBackup`/`FolderRewind` desktop application running in the background while playing**.
8.  **(Dedicated servers only)** Make sure a start script is present so restores can restart the server automatically — see [Dedicated Server Restore](#️-dedicated-server-restore).

## 📖 Command Reference

On Fabric 26.1, commands use capability-specific native permission nodes, with OP level 2 as the compatibility fallback. The singleplayer/LAN world owner and the server console remain allowed. Help and completion show only permitted commands. See the [Fabric 26.1 configuration and permissions reference](Fabric/Fabric-26.1/docs/CONFIGURATION.md). Other maintained targets retain their existing permission behavior until ported. Most arguments support tab-completion (config IDs, folders, backup file names).

| Command | Parameters | Description |
| :--- | :--- | :--- |
| **/mb help** | `[command]` | Shows general help, or detailed help and an example for one command. |
| **/mb save** | (none) | Manually performs a full world save in-game, equivalent to `/save-all`. |
| **/mb backup** | `[comment]` | Backs up the current world. An optional comment is attached to the backup. |
| **/mb restore** | `[file]` | Restores the current world. Omit the file name to restore the latest backup. |
| **/mb confirm** | (none) | Immediately submits a restore that is currently counting down. |
| **/mb stop** | (none) | Cancels a restore that is currently counting down. |
| **/mb target backup** | `<config_id> <folder> [comment]` | Backs up a specific, non-current folder managed by the desktop application. |
| **/mb target restore** | `<config_id> <folder> <file>` | Restores a specific, non-current folder. **Unavailable on dedicated servers** — use `/mb restore` for the current world instead. |
| **/mb list configs** | (none) | Lists all configured backup profiles and their IDs. |
| **/mb list folders** | `<config_id>` | Lists the folders tracked under a configuration profile. |
| **/mb list backups current** | `[page]` | Interactively browses the current world's backups in chat, with a clickable restore button per entry. |
| **/mb list backups** | `<config_id> <folder>` | Lists backup files for a specific, non-current folder. |
| **/mb auto start** | `<minutes> [backup\|remind]` | Fabric 26.1: stores a world-bound automatic backup or reminder plan; mode defaults to `backup`. |
| **/mb auto status** | (none) | Fabric 26.1: shows the current world's mode, interval, and next trigger. |
| **/mb auto stop** | (none) | Fabric 26.1: stops only the current world's automation plan. |

Fabric 26.1 stores automation outside the save, so restoring a world cannot
restore an obsolete schedule. The `remind` mode is intended for players who
want to choose a safe moment around unload-sensitive redstone. Full defaults,
ranges, migration rules, and permission nodes are in the
[configuration reference](Fabric/Fabric-26.1/docs/CONFIGURATION.md).

### **💡 Usage Example**

Backing up and restoring the world you're currently playing:

1.  **Back up the current world**
    * Type `/mb backup Before the Ender Dragon fight`
        > Chat returns: `[MineBackup] Backup task started...`
        > (After a moment)
        > `[MineBackup] Backup successful! Saved as [Full][2026-07-30_12-33-00]world [Before the Ender Dragon fight].7z`

2.  **Browse and restore from that backup**
    * Type `/mb list backups current` to see a paginated, clickable list of recent backups — or type `/mb restore` directly to restore the latest one.
    * If a restore countdown is configured, use `/mb confirm` to restore right away or `/mb stop` to cancel.

Managing a different world (one not currently running) through the desktop app's configuration:

1.  **Find the configuration and folder**
    * Type `/mb list configs` to see your configuration profiles.
    * Type `/mb list folders <config_id>` to see the folders under that profile.
2.  **Back up or restore that folder**
    * `/mb target backup <config_id> <folder> [comment]`
    * `/mb target restore <config_id> <folder> "<file name>"` (quote file names that contain spaces; not available on dedicated servers)

## 🛠️ Dedicated Server Restore

Backups and browsing work identically on dedicated servers. Restoring, however, requires stopping and relaunching the Minecraft process — MineBackup automates this with a bundled **restart sidecar**:

1. On `/mb restore`, the server saves all worlds and hands ownership of the process to a small helper (the sidecar), then shuts down safely.
2. The sidecar waits for the main JVM to exit and the world files to be released, then lets FolderRewind/MineBackup perform the restore.
3. Once the desktop application reports a definite success, failure, or cancellation, the sidecar launches your configured start script exactly once and exits.

**You need a start script** in the server's working directory. MineBackup looks for one of `start.bat`, `start.cmd`, `run.bat`, `run.cmd` (Windows) or `start.sh`, `run.sh` (Linux/macOS); exactly one must exist, or set an explicit path with the `dedicatedRestore.restartScript` setting in `config/minebackup.properties`. Because the server process restarts, players are disconnected during the restore and need to reconnect manually once the new server instance is back online.

If you run a panel or wrapper that automatically relaunches the server the moment it exits, disable that behavior (or point it at a script MineBackup doesn't also manage) — an immediate auto-restart can race the restore and start the server before the world files are ready. Set `dedicatedRestore.mode=DISABLED` to turn off dedicated restore entirely.

## ❓ Frequently Asked Questions

* **Q: When I use a command, the chat says "Command failed", "No response", or a similar error.**
    * **A:** Please check that the `MineBackup`/`FolderRewind` desktop application is running in the background on your computer. All features of this mod rely on network communication with the main application.

* **Q: Can this mod be used by itself?**
    * **A:** No. It is a "bridge" and cannot do anything without the main application.

* **Q: I restored on my dedicated server and the server didn't come back automatically.**
    * **A:** Check that a start script exists in the server's working directory (see [Dedicated Server Restore](#️-dedicated-server-restore)). If the restart script itself failed, fix the script and start the server manually — MineBackup will not silently retry it.

## 📄 License

This project is licensed under the [MIT License](https://github.com/Leafuke/MineBackup/blob/main/LICENSE). For details, please visit the main project repository.
