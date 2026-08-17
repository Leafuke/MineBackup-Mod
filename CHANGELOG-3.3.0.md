# MineBackup-Mod 3.3.0 更新日志

## 🎉 新功能

### 自动化备份增强
- **进入世界时显示自动备份状态**：玩家加入世界时会收到自动备份状态欢迎消息
  - 未启用时显示提示和可点击的 `[启用自动备份]` 按钮
  - 已启用时显示当前模式、间隔、下次运行时间，以及 `[关闭]` 和 `[重新配置]` 按钮
- **自动备份提醒模式**：新增 `remind` 模式，定时提醒玩家手动备份而不是自动执行
  - `/mb auto start <分钟> remind` - 启用提醒模式
  - `/mb auto start <分钟> backup` - 启用自动备份模式（默认）
- **按世界绑定的自动化计划**：自动化计划现在绑定到具体世界，还原世界不会恢复过时的计划
- **自动化状态查询**：`/mb auto status` 显示当前世界的模式、间隔和下次触发时间

### 权限系统改进
- **按能力划分的指令权限**（Fabric 26.1）：引入细粒度权限控制
  - `minebackup.command.save` - 世界保存权限
  - `minebackup.command.backup` - 备份权限
  - `minebackup.command.restore` - 还原权限
  - `minebackup.command.browse` - 浏览备份权限
  - `minebackup.command.automation` - 自动化管理权限
  - 兼容 OP 等级 2 作为后备，单人世界主和控制台始终允许

### 用户体验改进
- **还原失败界面优化**：修复还原失败后的界面显示问题
  - 当玩家不在游戏中时，自动跳转到世界选择界面而不是黑屏
  - 提供返回主菜单的能力，与正常还原流程保持一致
- **还原错误提示国际化**：为重进失败添加用户友好的错误消息
  - 支持的错误类型：无效世界 ID、超时、用户取消、超过最大重试次数、会话信息丢失
  - 中英文完整翻译

## 🐛 Bug 修复
- **跨版本 API 兼容性**：修正不同 Minecraft 版本间的 API 差异
  - 修复 ClickEvent/HoverEvent 构造方式（26.x 和 1.21.9+ 使用新 API）
  - 修复客户端界面 API 差异（26.1 vs 26.2）
  - 修复玩家消息方法差异（Yarn vs Mojang 映射）
  - 修复网络处理器类名差异
  - 修复 NeoForge 特定的服务器访问方式
- **Fabric-1.21 ABI 兼容性**：添加 TextEvents 兼容性类以支持文本事件处理

## 📝 文档更新
- 整理配置与权限参考文档（Fabric 26.1）
- 更新命令帮助和使用说明

## 🎯 适用版本

所有维护版本均已同步以上功能：
- Fabric 1.21 (Yarn)
- Fabric 1.21.9/1.21.11 (Mojang)
- Fabric 26.1/26.2 (Mojang)
- NeoForge 1.21/26.1
- Forge 1.20

---

**完整提交历史**: [v3.2.0...v3.3.0](https://github.com/Leafuke/MineBackup-Mod/compare/v3.2.0...v3.3.0)
