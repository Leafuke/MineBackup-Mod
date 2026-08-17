# MineBackup-Mod 3.3.0

## 🎉 What's New

### Automation Enhancements
- **Welcome Message on World Join**: Shows automation status with clickable buttons when you join
  - `[Enable Auto Backup]` button when disabled
  - `[Disable]` and `[Reconfigure]` buttons when active, showing current mode, interval, and next run
- **Reminder Mode**: New `remind` mode that prompts you to backup instead of doing it automatically
  - `/mb auto start <minutes> remind` for reminder mode
  - `/mb auto start <minutes> backup` for auto-backup mode (default)
- **World-Bound Plans**: Automation schedules now bind to specific worlds; restoring won't resurrect old schedules
- **Status Command**: `/mb auto status` shows current mode, interval, and next trigger

### Permission System (Fabric 26.1)
- Fine-grained capability-based permissions: `save`, `backup`, `restore`, `browse`, `automation`
- OP level 2 compatibility fallback; world owner and console always allowed

### User Experience
- **Better Restore Failure UI**: Now shows world selection screen instead of a black screen when restore fails
- **Localized Error Messages**: User-friendly error messages for rejoin failures (invalid world ID, timeout, cancelled, max retries, missing session)

## 🐛 Bug Fixes
- Fixed cross-version API compatibility (ClickEvent/HoverEvent, client screen API, player messages, network handlers)
- Fixed Fabric-1.21 ABI compatibility with TextEvents

## 📦 Supported Versions
All maintained versions have been updated:
- Fabric 1.21 (Yarn) / 1.21.9–1.21.11 (Mojang) / 26.1–26.2 (Mojang)
- NeoForge 1.21 / 26.1
- Forge 1.20

---

**Full Changelog**: [v3.2.0...v3.3.0](https://github.com/Leafuke/MineBackup-Mod/compare/v3.2.0...v3.3.0)

**Installation**: Requires [MineBackup desktop app](https://github.com/Leafuke/MineBackup/releases) or [FolderRewind](https://apps.microsoft.com/detail/9nwsdgxdqws4) with [MineRewind plugin](https://github.com/Leafuke/FolderRewind-Plugin-Minecraft/releases), plus [KnotLink server](https://github.com/KnotLink-Protocol/KnotLinkService/releases).
