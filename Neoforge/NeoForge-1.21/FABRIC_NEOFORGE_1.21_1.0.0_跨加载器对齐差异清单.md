# Fabric ↔ NeoForge 1.21（1.0.0）跨加载器对齐差异清单

更新时间：2026-02-22
范围：`Fabric/Fabric-1.21` 对比 `Neoforge/NeoForge-1.21`

## 1) 已完成对齐（核心功能）

- KnotLink 新协议握手流程已对齐：`handshake` / `HANDSHAKE_RESPONSE` / 版本兼容检查。
- 热备份前完整保存流程已对齐：`pre_hot_backup` 后发送 `WORLD_SAVED`。
- 热还原退出确认流程已对齐：`pre_hot_restore` 后发送 `WORLD_SAVE_AND_EXIT_COMPLETE`。
- 单人世界自动重进流程已对齐：
  - levelId 解析与合法性校验
  - 延迟重进
  - 重连超时与最大重试
  - `REJOIN_RESULT` 结果回传
- 热还原状态集中管理已对齐：`restore/HotRestoreState.java`。
- 握手相关多语言文案键已补齐：`zh_cn.json` / `en_us.json`。

## 2) 有意保留的加载器差异（非问题）

- 入口与事件系统差异：
  - Fabric：`ModInitializer` + Fabric API 事件
  - NeoForge：`@Mod` + NeoForge Event Bus
- 服务端/客户端 API 命名差异：
  - Fabric `Text` / NeoForge `Component`
  - Fabric 世界加载 API 与 NeoForge 世界加载 API 不同
- 命令注册签名差异：
  - Fabric `CommandRegistrationCallback`
  - NeoForge `RegisterCommandsEvent`

## 3) 当前仍存在的“结构层”差异（建议后续收敛）

### A. KnotLink 子模块历史遗留类

- NeoForge 仍保留以下类，但当前主流程未依赖：
  - `knotlink/SignalSender.java`
  - `knotlink/OpenSocketResponser.java`
- Fabric 侧对应主流程中无这些类。
- 建议：
  - 若确认无运行时用途，可在 NeoForge 侧清理，减少维护面。
  - 若有备用用途，建议补注释说明调用入口和场景。

### B. Kotlin/Java 日志与异常处理风格（仅风格差异）

- Fabric 侧 `knotlink` 类日志更统一（`LOGGER` 为主）。
- NeoForge 侧 `knotlink` 历史实现中仍有 `System.out/err` 痕迹（非核心功能阻断）。
- 建议：统一为 `LOGGER`，便于线上排查。

### C. 命令实现细节差异（功能等价）

- 两侧命令行为整体一致，但返回文本拼接细节仍有小差异。
- 建议：后续抽象“共享命令响应构造器”模板，降低跨加载器漂移。

- 说明：NeoForge 端不包含 GCA 假人模组，因此该逻辑不应存在。
