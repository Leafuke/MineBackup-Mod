# MineBackup-Mod 3.4.1

本版改进入服自动化状态通知，覆盖全部八个维护目标。

- 在 `config/minebackup.properties` 中设置 `automation.joinStatusNotification.mode`：`ALWAYS`（默认）、`ENABLED_ONLY`（只在自动备份或提醒计划启用时显示）、`NEVER`（不显示）。修改后重新打开存档或重启服务端。
- 普通玩家保留状态文字，管理按钮只向有自动化管理权限的玩家显示。
- 备份提醒模式明确说明不会自动创建备份；延迟通知会检查服务端和玩家会话。
- 此设置仅控制入服状态消息，不影响定时提醒、备份结果、失败反馈或还原安全提示。
- 旧配置升级保留现有展示习惯；API v2 与 KnotLink 协议保持兼容。

维护版本表已移除归档的 Fabric 1.21.9–1.21.10，并补齐 Fabric 26.3。
详见 [更新日志](CHANGELOG-3.4.1.md)。
